FROM node:22-bookworm-slim AS builder

# Build context is the repo ROOT (not agent/): this image needs two separate
# proto trees that don't share a common parent inside agent/ — agent/proto/
# (xray-stats.proto, for talking to xray-core's own local Stats API — see
# agent/src/xray/stats-collector.ts) and the repo-root proto/ (vpn/agent/v1/
# agent.proto, the control-plane gRPC contract — see agent/src/config.ts and
# agent/src/client/grpc-client.ts). Both COPYs below land in the same ./proto
# dir (they don't overlap: xray-stats.proto vs. the vpn/ subtree), matching
# how each file resolves its own proto path at runtime.
WORKDIR /app
COPY agent/package*.json agent/tsconfig.json ./
RUN npm ci
COPY agent/src ./src
COPY agent/proto ./proto
COPY proto ./proto
RUN npm run build

FROM node:22-bookworm-slim AS runner

WORKDIR /opt/vpn-node-agent

# Install Xray-core
ARG XRAY_VERSION=v24.11.30
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    unzip \
    && curl -fL -o /tmp/xray.zip "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip" \
    && unzip -q /tmp/xray.zip xray -d /usr/local/bin/ \
    && chmod +x /usr/local/bin/xray \
    && rm -rf /tmp/xray.zip /var/lib/apt/lists/*

COPY agent/package*.json ./
RUN npm ci --only=production

COPY --from=builder /app/dist ./dist
COPY --from=builder /app/proto ./proto

ENV NODE_ENV=production
ENV XRAY_BIN_PATH=/usr/local/bin/xray
ENV XRAY_CONFIG_PATH=/etc/xray/config.json
# config.ts's default (`path.resolve(process.cwd(), '../proto/...')`) assumes
# proto/ sits next to the agent/ checkout, which is true for local dev but not
# in this image: proto/ lands as a sibling of dist/ under this same WORKDIR.
ENV PROTO_PATH=/opt/vpn-node-agent/proto/vpn/agent/v1/agent.proto

CMD ["node", "dist/index.js"]
