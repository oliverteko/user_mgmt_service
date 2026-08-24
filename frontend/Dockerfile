# =============================================================================
# Stage 1: Install dependencies (cached separately from source changes)
# =============================================================================
FROM node:22-alpine AS deps

WORKDIR /app

RUN corepack enable

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

# =============================================================================
# Stage 2: Build the Next.js app
# =============================================================================
FROM node:22-alpine AS builder

WORKDIR /app

RUN corepack enable

COPY --from=deps /app/node_modules ./node_modules
COPY . .

RUN pnpm build

# =============================================================================
# Stage 3: Minimal runtime using the Next.js standalone output
# =============================================================================
FROM node:22-alpine AS runtime

WORKDIR /app

RUN addgroup -S nodejs && \
    adduser -S -G nodejs -H -D nextjs

COPY --chown=nextjs:nodejs --from=builder /app/public ./public
COPY --chown=nextjs:nodejs --from=builder /app/.next/standalone ./
COPY --chown=nextjs:nodejs --from=builder /app/.next/static ./.next/static

ENV NODE_ENV=production \
    PORT=3000 \
    HOSTNAME=0.0.0.0

USER nextjs

EXPOSE 3000

CMD ["node", "server.js"]
