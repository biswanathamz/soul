# SOUL — lifecycle Makefile
#
# Container engine defaults to podman-compose (what works on this machine).
# For Docker instead:   make up COMPOSE="docker compose"
#
# Only the orchestrator + console run in containers. Ollama runs host-native on
# the GPU (`make ollama-serve`); the orchestrator container reaches it via
# host.containers.internal. Models are managed host-side (`make models-sync`).

COMPOSE ?= podman-compose
PYTHON  ?= python3

CONSOLE_DIR := soul-console
MANAGE      := $(PYTHON) soul-scripts/ollama/manage.py
STACK       := soul-orchestrator soul-voice soul-console
BUILDABLE   := soul-orchestrator soul-voice soul-console
OLLAMA_URL  := http://localhost:11434

.DEFAULT_GOAL := help

# ---------------------------------------------------------------------------
##@ Setup — one-time host bootstrap
# ---------------------------------------------------------------------------

.PHONY: setup
setup: models-deps ollama-install models-sync ## One-time: install Python deps + host Ollama (GPU) + pull the model
	@echo ""
	@echo "Host setup complete. Start the app with:  make up"

# ---------------------------------------------------------------------------
##@ Stack — Manager + UI containers (Ollama is host-native)
# ---------------------------------------------------------------------------

.PHONY: up
up: ## Build + start the Manager + UI containers (→ http://localhost:7787). Run 'make setup' once first
	$(COMPOSE) up -d --build $(STACK)
	@echo "SOUL console → http://localhost:7787  (first time? run 'make setup' to install host Ollama + pull the model)"

.PHONY: down
down: ## Stop and remove all SOUL containers
	$(COMPOSE) down

.PHONY: restart
restart: down up ## Restart the stack

.PHONY: build
build: ## Build the stack images (Manager + UI)
	$(COMPOSE) build $(BUILDABLE)

.PHONY: rebuild
rebuild: ## Rebuild images with no cache, then start
	$(COMPOSE) build --no-cache $(BUILDABLE)
	@$(MAKE) up

.PHONY: ps
ps: ## Show running SOUL containers
	@podman ps --filter name=soul --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null || $(COMPOSE) ps

.PHONY: logs
logs: ## Tail logs from the stack
	$(COMPOSE) logs -f $(STACK)

# ---------------------------------------------------------------------------
##@ Ollama + models
# ---------------------------------------------------------------------------

.PHONY: ollama-install
ollama-install: ## Install host-native Ollama + run it as a GPU service on 0.0.0.0:11434 (uses sudo)
	@if command -v ollama >/dev/null 2>&1; then \
	  echo "ollama already installed: $$(command -v ollama)"; \
	else \
	  echo "installing Ollama (official script — will prompt for sudo) …"; \
	  curl -fsSL https://ollama.com/install.sh | sh; \
	fi
	@echo "configuring the ollama systemd service to listen on 0.0.0.0:11434 …"
	sudo mkdir -p /etc/systemd/system/ollama.service.d
	printf '[Service]\nEnvironment="OLLAMA_HOST=0.0.0.0:11434"\n' | \
	  sudo tee /etc/systemd/system/ollama.service.d/override.conf >/dev/null
	sudo systemctl daemon-reload
	sudo systemctl enable ollama
	sudo systemctl restart ollama   # restart (not just start) so the 0.0.0.0 drop-in applies to a running service
	@echo "Ollama serving on 0.0.0.0:11434 (systemd, GPU). Next: make models-sync"

.PHONY: ollama-serve
ollama-serve: ## Run Ollama in the foreground on 0.0.0.0:11434 (alternative to the systemd service; stop the service first)
	@echo "starting host Ollama on 0.0.0.0:11434 (Ctrl-C to stop) …"
	OLLAMA_HOST=0.0.0.0:11434 ollama serve

.PHONY: ollama-wait
ollama-wait: ## Block until host Ollama answers (used by models-sync)
	@echo "waiting for Ollama at $(OLLAMA_URL) …"; \
	for i in $$(seq 1 30); do \
	  curl -sf $(OLLAMA_URL)/api/version >/dev/null 2>&1 && { echo "  ready"; exit 0; }; \
	  sleep 1; \
	done; \
	echo "  Ollama not ready after 30s — start it with 'make ollama-serve'" >&2; exit 1

.PHONY: models-sync
models-sync: ollama-wait ## Pull + warm every model in the manifest (host Ollama)
	$(MANAGE) sync

.PHONY: models-status
models-status: ## Show manifest vs installed models
	$(MANAGE) status

.PHONY: models-verify
models-verify: ## Validate the manifest (CI parity — no Ollama needed)
	$(MANAGE) verify

.PHONY: models-warm
models-warm: ## Load warm:true models into RAM
	$(MANAGE) warm

.PHONY: models-prune
models-prune: ## Remove installed models not in the manifest
	$(MANAGE) prune

.PHONY: models-deps
models-deps: ## Ensure manage.py's dependency (PyYAML) is available (apt-first; PEP 668-safe)
	@if $(PYTHON) -c "import yaml" >/dev/null 2>&1; then \
	  echo "PyYAML already available — nothing to install"; \
	elif command -v apt-get >/dev/null 2>&1; then \
	  echo "installing python3-yaml via apt (externally-managed Python) …"; \
	  sudo apt-get install -y python3-yaml; \
	else \
	  echo "installing PyYAML via pip …"; \
	  $(PYTHON) -m pip install -r soul-scripts/ollama/requirements.txt; \
	fi

# ---------------------------------------------------------------------------
##@ Console dev (host, no containers)
# ---------------------------------------------------------------------------

.PHONY: install
install: ## npm install in soul-console
	cd $(CONSOLE_DIR) && npm install

.PHONY: dev
dev: ## Run the Vite dev server (proxies to the orchestrator on :7788 — start it with `make up` or the jar)
	cd $(CONSOLE_DIR) && npm run dev

.PHONY: test
test: ## Run console unit/component tests
	cd $(CONSOLE_DIR) && npm test

.PHONY: console-build
console-build: ## Typecheck + production build of the console
	cd $(CONSOLE_DIR) && npm run build

.PHONY: pools-verify
pools-verify: ## Validate + smoke-test skillpool/ and hookspool/ (no model needed)
	$(PYTHON) soul-scripts/pooltest.py

.PHONY: orchestrator-test
orchestrator-test: ## Run the orchestrator's JUnit tests (Spring Boot)
	cd soul-orchestrator && ./gradlew test

.PHONY: orchestrator-build
orchestrator-build: ## Build the orchestrator jar
	cd soul-orchestrator && ./gradlew build

.PHONY: voice-test
voice-test: ## Run soul-voice contract tests (auto-creates its venv; Piper is stubbed)
	@test -x soul-voice/.venv/bin/pytest || { \
	  echo "setting up soul-voice/.venv …"; \
	  $(PYTHON) -m venv soul-voice/.venv && \
	  soul-voice/.venv/bin/pip install -q -r soul-voice/requirements-dev.txt; }
	cd soul-voice && .venv/bin/python -m pytest -q

.PHONY: verify
verify: models-verify pools-verify orchestrator-test voice-test test ## Run all checks (manifest + pools + orchestrator + voice + console)

# ---------------------------------------------------------------------------
##@ Cleanup
# ---------------------------------------------------------------------------

.PHONY: clean
clean: ## Stop containers and remove the network (host Ollama + models untouched)
	$(COMPOSE) down

.PHONY: clean-models
clean-models: ## Remove installed models NOT in the manifest (host Ollama; alias of models-prune)
	@echo "Host Ollama stores models in ~/.ollama. Removing models not in the manifest;"
	@echo "delete a specific one with 'ollama rm <model>'."
	$(MANAGE) prune

# ---------------------------------------------------------------------------
##@ Help
# ---------------------------------------------------------------------------

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nSOUL — make targets\n\nUsage: make <target>\n"} \
		/^##@/ {printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next} \
		/^[a-zA-Z0-9_-]+:.*?##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@printf "\nEngine: COMPOSE=\"%s\"  (override with e.g. COMPOSE=\"docker compose\")\n\n" "$(COMPOSE)"
