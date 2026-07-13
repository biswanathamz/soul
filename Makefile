# SOUL — lifecycle Makefile
#
# Container engine defaults to podman-compose (what works on this machine).
# For Docker instead:   make up COMPOSE="docker compose"
#
# Services are always named explicitly because podman-compose ignores compose
# `profiles:` — so a bare `up` would otherwise also start Ollama's big pull.

COMPOSE ?= podman-compose
PYTHON  ?= python3

CONSOLE_DIR := soul-console
MANAGE      := $(PYTHON) soul-scripts/ollama/manage.py
UI_SERVICES := soul-orchestrator soul-console
OLLAMA_URL  := http://localhost:11434

.DEFAULT_GOAL := help

# ---------------------------------------------------------------------------
##@ Stack — UI + mock orchestrator
# ---------------------------------------------------------------------------

.PHONY: up
up: ## Start the UI stack in the background (→ http://localhost:7787)
	$(COMPOSE) up -d $(UI_SERVICES)
	@echo "SOUL console → http://localhost:7787"

.PHONY: down
down: ## Stop and remove all SOUL containers
	$(COMPOSE) down

.PHONY: restart
restart: down up ## Restart the UI stack

.PHONY: build
build: ## Build the UI stack images
	$(COMPOSE) build $(UI_SERVICES)

.PHONY: rebuild
rebuild: ## Rebuild images with no cache, then start
	$(COMPOSE) build --no-cache $(UI_SERVICES)
	@$(MAKE) up

.PHONY: ps
ps: ## Show running SOUL containers
	@podman ps --filter name=soul --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null || $(COMPOSE) ps

.PHONY: logs
logs: ## Tail logs from the UI stack
	$(COMPOSE) logs -f $(UI_SERVICES)

# ---------------------------------------------------------------------------
##@ Ollama + models
# ---------------------------------------------------------------------------

.PHONY: ollama-up
ollama-up: ## Start the Ollama service (localhost:11434)
	$(COMPOSE) up -d soul-ollama
	@echo "Ollama → $(OLLAMA_URL)"

.PHONY: ollama-down
ollama-down: ## Stop the Ollama service
	$(COMPOSE) stop soul-ollama

.PHONY: ollama-wait
ollama-wait: ## Block until Ollama answers (used by models-sync)
	@echo "waiting for Ollama at $(OLLAMA_URL) …"; \
	for i in $$(seq 1 30); do \
	  curl -sf $(OLLAMA_URL)/api/version >/dev/null 2>&1 && { echo "  ready"; exit 0; }; \
	  sleep 1; \
	done; \
	echo "  Ollama not ready after 30s" >&2; exit 1

.PHONY: models-sync
models-sync: ollama-up ollama-wait ## Pull + warm every model in the manifest (~20 GB first run)
	$(COMPOSE) build soul-model-init
	$(COMPOSE) run --rm soul-model-init sync

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
models-deps: ## Install manage.py's Python dependency (PyYAML)
	$(PYTHON) -m pip install -r soul-scripts/ollama/requirements.txt

.PHONY: ollama-gpu
ollama-gpu: ## Start Ollama with NVIDIA GPU — Docker only, needs a working driver + toolkit
	docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d soul-ollama

# ---------------------------------------------------------------------------
##@ Console dev (host, no containers)
# ---------------------------------------------------------------------------

.PHONY: install
install: ## npm install in soul-console
	cd $(CONSOLE_DIR) && npm install

.PHONY: dev
dev: ## Run the Vite dev server (needs `make mock` in another terminal)
	cd $(CONSOLE_DIR) && npm run dev

.PHONY: mock
mock: ## Run the mock orchestrator (REST + WS on :7788)
	cd $(CONSOLE_DIR) && npm run dev:mock

.PHONY: test
test: ## Run console unit/component tests
	cd $(CONSOLE_DIR) && npm test

.PHONY: console-build
console-build: ## Typecheck + production build of the console
	cd $(CONSOLE_DIR) && npm run build

.PHONY: verify
verify: models-verify test ## Run all checks (manifest + console tests)

# ---------------------------------------------------------------------------
##@ Cleanup
# ---------------------------------------------------------------------------

.PHONY: clean
clean: ## Stop containers and remove the network (keeps model volume)
	$(COMPOSE) down

.PHONY: clean-models
clean-models: ## Delete the downloaded-models volume (frees ~20 GB)
	@echo "Removing the Ollama model volume — this deletes all downloaded models."
	-podman volume rm soul_soul-ollama-models 2>/dev/null || \
	 podman volume rm soul-ollama-models 2>/dev/null || \
	 echo "  (volume not found — nothing to remove)"

# ---------------------------------------------------------------------------
##@ Help
# ---------------------------------------------------------------------------

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nSOUL — make targets\n\nUsage: make <target>\n"} \
		/^##@/ {printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next} \
		/^[a-zA-Z0-9_-]+:.*?##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@printf "\nEngine: COMPOSE=\"%s\"  (override with e.g. COMPOSE=\"docker compose\")\n\n" "$(COMPOSE)"
