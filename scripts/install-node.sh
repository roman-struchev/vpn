#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# VPN Node Bootstrap & Installer Script (Docker-based)
# Requires: Docker already installed and running (https://get.docker.com).
# ==============================================================================
# Everything besides host-level networking (sysctl, certbot's port-80
# challenge — neither of which a container can do to the host on its own)
# lives inside the romanew/vpn-node:latest image (built from agent/Dockerfile,
# published by .github/workflows/gradlew-publish-and-deploy.yml's publish-node
# job): the node agent, xray-core, and all their dependencies.
#
# There used to be an optional per-node egress bandwidth cap (tc qdisc on the
# host's primary interface) for nodes in the "trial" pool. Removed: with
# --network host the container shares the host's real NIC, so `tc` there has
# no way to shape just this container's traffic — it throttles the whole
# host, every other service included. Reliably scoping it to one container
# needs cgroup net_cls/net_prio classification (finicky across cgroup v1/v2
# and Docker's cgroup driver), which isn't worth the fragility here. If a
# trial pool ever needs a real cap again, dedicate a whole host to it instead.
# No systemd unit either — `docker run --restart unless-stopped` already
# survives crashes and reboots (as long as the Docker daemon itself is
# enabled, which it is by default once installed); monitor/control it with
# plain `docker ps` / `docker logs -f vpn-node-agent` / `docker stats` /
# `docker restart|stop vpn-node-agent`.
#
# --network host: xray-core's listener ports are driven by server-pushed
# TransportPolicy, not fixed at install time, so there's no static port list
# to map with `-p`; host networking also preserves real client source IPs
# (needed for the anti-enumeration IP tracking — see README.md §2 "Защита от
# перечисления нод"), which Docker's default bridge/NAT networking would mask.

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: This script must be run as root." >&2
    exit 1
fi

if ! command -v docker > /dev/null 2>&1; then
    echo "ERROR: Docker is required but not installed on this host." >&2
    echo "        Install it first: curl -fsSL https://get.docker.com | sh" >&2
    exit 1
fi

SERVER_GRPC="${1:-}"
BOOTSTRAP_TOKEN="${2:-}"
# Optional: only for CDN-fronted nodes (registered with type=cdn). A direct
# Reality node needs none of this — Reality doesn't use a real certificate.
CDN_HOSTNAME="${3:-}"
# Optional: override the published node-agent image (e.g. a specific
# version tag instead of latest, or a private mirror).
NODE_IMAGE="${VPN_NODE_IMAGE:-romanew/vpn-node:latest}"

if [ -z "$SERVER_GRPC" ] || [ -z "$BOOTSTRAP_TOKEN" ]; then
    echo "Usage: $0 <server_grpc_host:port> <bootstrap_token> [cdn_hostname]"
    echo "Example (direct Reality node):     $0 vpn.example.com:9090 bst_abc12345"
    echo "Example (CDN node, Phase 9):       $0 vpn.example.com:9090 bst_abc12345 edge.example.com"
    echo "  cdn_hostname must already resolve (via the CDN) to this host's IP on port 80/443"
    echo "  before running this script, so certbot's HTTP-01 challenge can complete."
    exit 1
fi

echo "==> [1/3] Optimizing sysctl (BBR, TCP buffer tuning)..."
cat <<EOF > /etc/sysctl.d/99-vpn-tuning.conf
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.tcp_fastopen = 3
net.ipv4.tcp_rmem = 8192 262144 536870912
net.ipv4.tcp_wmem = 4096 16384 536870912
net.core.rmem_max = 67108864
net.core.wmem_max = 67108864
fs.file-max = 1048576
EOF
sysctl --system > /dev/null 2>&1 || true

echo "==> [2/3] Pulling node-agent image (${NODE_IMAGE})..."
docker pull "$NODE_IMAGE"

echo "==> [3/3] Starting vpn-node-agent container..."
mkdir -p /opt/vpn-node-agent/data /etc/xray/certs

# If this host was already registered under a *different* bootstrap token,
# treat this run as an intentional re-registration: the agent only ever calls
# RegisterNode when it has no persisted nodeId/nodeToken (see
# agent/src/client/grpc-client.ts), so as long as .agent-state.json survives,
# a fresh bootstrap token passed here would otherwise be silently ignored and
# the container would just reconnect as the old node identity. The server
# dedupes by hostname (NodeManagementService#registerNode), so this doesn't
# create a duplicate node — it updates the same row with whatever pool/type
# the new token grants. Re-running with the *same* token (e.g. redeploying a
# newer image) leaves the persisted state alone, so it reconnects as before.
PREV_TOKEN=""
[ -f /opt/vpn-node-agent/.env ] && PREV_TOKEN=$(grep -m1 '^BOOTSTRAP_TOKEN=' /opt/vpn-node-agent/.env | cut -d= -f2-)
if [ -n "$PREV_TOKEN" ] && [ "$PREV_TOKEN" != "$BOOTSTRAP_TOKEN" ]; then
    echo "==> New bootstrap token — discarding this host's previous node identity so it re-registers."
    rm -f /opt/vpn-node-agent/data/.agent-state.json
fi

cat <<EOF > /opt/vpn-node-agent/.env
SERVER_GRPC_URL=${SERVER_GRPC}
BOOTSTRAP_TOKEN=${BOOTSTRAP_TOKEN}
AGENT_STATE_PATH=/opt/vpn-node-agent/data/.agent-state.json
STATS_INTERVAL_MS=15000
HEARTBEAT_INTERVAL_MS=15000
EOF
chmod 600 /opt/vpn-node-agent/.env

docker rm -f vpn-node-agent > /dev/null 2>&1 || true
docker run -d --name vpn-node-agent \
  --network host \
  --restart unless-stopped \
  --env-file /opt/vpn-node-agent/.env \
  -v /opt/vpn-node-agent/data:/opt/vpn-node-agent/data \
  -v /etc/xray/certs:/etc/xray/certs:ro \
  "$NODE_IMAGE"

if [ -n "$CDN_HOSTNAME" ]; then
    echo "==> Provisioning Let's Encrypt certificate for CDN node ($CDN_HOSTNAME)..."
    # CDN nodes use real TLS instead of Reality (a CDN terminates TLS itself,
    # which breaks Reality's cert-stealing handshake — see docs/PLAN.md §6 and
    # docs/ROADMAP_PROGRESS.md Phase 9). NodeManagementService expects the
    # cert at /etc/xray/certs/<hostname>/{fullchain,privkey}.pem by default
    # (see vpn.cdn.cert-dir); that host path is bind-mounted read-only into
    # the container above, so certbot's renewals (running on the host) are
    # picked up without rebuilding/restarting the container's own filesystem.
    command -v certbot > /dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq certbot)
    if certbot certonly --standalone --non-interactive --agree-tos \
        --register-unsafely-without-email -d "$CDN_HOSTNAME" \
        --deploy-hook "mkdir -p /etc/xray/certs/$CDN_HOSTNAME && cp /etc/letsencrypt/live/$CDN_HOSTNAME/fullchain.pem /etc/xray/certs/$CDN_HOSTNAME/fullchain.pem && cp /etc/letsencrypt/live/$CDN_HOSTNAME/privkey.pem /etc/xray/certs/$CDN_HOSTNAME/privkey.pem && docker restart vpn-node-agent || true"; then
        mkdir -p "/etc/xray/certs/$CDN_HOSTNAME"
        cp "/etc/letsencrypt/live/$CDN_HOSTNAME/fullchain.pem" "/etc/xray/certs/$CDN_HOSTNAME/fullchain.pem"
        cp "/etc/letsencrypt/live/$CDN_HOSTNAME/privkey.pem" "/etc/xray/certs/$CDN_HOSTNAME/privkey.pem"
        docker restart vpn-node-agent
        echo "==> Certificate installed at /etc/xray/certs/$CDN_HOSTNAME/"
    else
        echo "WARNING: certbot failed — this node needs a cert at /etc/xray/certs/$CDN_HOSTNAME/{fullchain,privkey}.pem"
        echo "         before it's registered with type=cdn, or Xray will fail to start."
    fi
fi

echo "==> Installation complete!"
echo "Monitor:      docker ps / docker logs -f vpn-node-agent / docker stats vpn-node-agent"
echo "Restart/stop: docker restart vpn-node-agent / docker stop vpn-node-agent"
if [ -n "$CDN_HOSTNAME" ]; then
    echo "This node was provisioned as a CDN edge for $CDN_HOSTNAME — register it via the admin API"
    echo "with type=cdn (see docs/ROADMAP_PROGRESS.md Phase 9 / AdminController's bootstrap-token endpoint)."
fi
