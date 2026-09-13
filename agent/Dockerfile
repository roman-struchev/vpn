FROM node:22-bookworm-slim AS builder

WORKDIR /app
COPY package*.json tsconfig.json ./
RUN npm ci
COPY src ./src
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
    && curl -L -o /tmp/xray.zip "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip" \
    && unzip -q /tmp/xray.zip xray -d /usr/local/bin/ \
    && chmod +x /usr/local/bin/xray \
    && rm -rf /tmp/xray.zip /var/lib/apt/lists/*

COPY package*.json ./
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
