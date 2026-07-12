# Mock soul-orchestrator — stands in for the Spring Boot service until it exists.
# Build context is soul-console/ (see docker-compose.yml).
FROM docker.io/library/node:20-alpine
WORKDIR /app
COPY mock/package.json mock/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY mock/server.mjs ./
ENV HOST=0.0.0.0 PORT=7788
EXPOSE 7788
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO /dev/null http://127.0.0.1:7788/actuator/health || exit 1
CMD ["node", "server.mjs"]
