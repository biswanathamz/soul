# soul-console — production image
# Stage 1: build the static bundle
FROM docker.io/library/node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# Stage 2: serve with nginx on 7787, proxying /api + /ws to the orchestrator
FROM docker.io/library/nginx:1.27-alpine
COPY docker/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# Overridden by compose; point at the real services.
ENV ORCHESTRATOR_URL=http://soul-orchestrator:7788 \
    VOICE_URL=http://soul-voice:7789
EXPOSE 7787
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO /dev/null http://127.0.0.1:7787/ || exit 1
