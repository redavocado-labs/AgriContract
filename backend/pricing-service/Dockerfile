FROM node:22-alpine AS builder
WORKDIR /app/pricing-service
COPY pricing-service/package.json pricing-service/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY pricing-service/ ./
RUN npm run build && npm prune --omit=dev --no-audit --no-fund

FROM node:22-alpine AS runtime
ENV NODE_ENV=production
WORKDIR /app/pricing-service
COPY --from=builder /app/pricing-service/package.json ./package.json
COPY --from=builder /app/pricing-service/node_modules ./node_modules
COPY --from=builder /app/pricing-service/dist ./dist
# Read by dist/infrastructure/persistence/migrate.js (pricing-migrate in docker-compose).
COPY --from=builder /app/pricing-service/migrations ./migrations
USER node
EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:' + (process.env.SERVER_PORT || 8091) + '/health').then((r) => process.exit(r.status === 200 ? 0 : 1)).catch(() => process.exit(1))"]
CMD ["node", "dist/main.js"]
