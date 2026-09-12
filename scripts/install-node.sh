#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# VPN Node Bootstrap & Installer Script (Docker-based)
# Supported OS: Ubuntu 22.04 / 24.04 LTS, Debian 12
# ==============================================================================
# Runs the node agent + xray-core as a single Docker container (image built
# from agent/Dockerfile, published as romanew/vpn-node:latest by
# .github/workflows/release.yml's docker-node job) instead of installing
# Node.js/xray-core directly on the host — same monitoring story (docker ps/
# logs/stats) as the main server's docker-compose deployment.
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

SERVER_GRPC="${1:-}"
BOOTSTRAP_TOKEN="${2:-}"
# Optional: only for CDN-fronted nodes (registered with type=cdn). A direct
# Reality node needs none of this — Reality doesn't use a real certificate.
# Pass "" to skip this and still set trial_cap_mbps below.
CDN_HOSTNAME="${3:-}"
# Optional (Phase 10): shared egress bandwidth ceiling in Mbit/s for a node
# registered in the "trial" pool — see PLAN.md §4 ("тарификация без
# ограничения скорости"): trial and paid nodes differ by pool capacity, not
# a per-user limiter. fq_codel under the cap gives fair per-flow sharing so
# one heavy trial user doesn't starve the rest of that pool.
TRIAL_CAP_MBPS="${4:-}"
# Optional: override the published node-agent image (e.g. a specific
# version tag instead of latest, or a private mirror).
NODE_IMAGE="${VPN_NODE_IMAGE:-romanew/vpn-node:latest}"

if [ -z "$SERVER_GRPC" ] || [ -z "$BOOTSTRAP_TOKEN" ]; then
    echo "Usage: $0 <server_grpc_host:port> <bootstrap_token> [cdn_hostname] [trial_cap_mbps]"
    echo "Example (direct Reality node):     $0 vpn.example.com:9090 bst_abc12345"
    echo "Example (CDN node, Phase 9):       $0 vpn.example.com:9090 bst_abc12345 edge.example.com"
    echo "Example (trial node, Phase 10):    $0 vpn.example.com:9090 bst_abc12345 \"\" 50"
    echo "  cdn_hostname must already resolve (via the CDN) to this host's IP on port 80/443"
    echo "  before running this script, so certbot's HTTP-01 challenge can complete."
    exit 1
fi

echo "==> [1/6] Installing dependencies..."
apt-get update -qq
apt-get install -y -qq curl ca-certificates iptables iproute2

echo "==> [2/6] Optimizing sysctl (BBR, TCP buffer tuning)..."
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

echo "==> [3/6] Installing Docker..."
if ! command -v docker > /dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker
fi

echo "==> [4/6] Pulling node-agent image (${NODE_IMAGE})..."
docker pull "$NODE_IMAGE"

echo "==> [5/6] Setting up /opt/vpn-node-agent..."
mkdir -p /opt/vpn-node-agent/data /var/log/vpn-agent

cat <<EOF > /opt/vpn-node-agent/.env
CONTROL_PLANE_GRPC=${SERVER_GRPC}
BOOTSTRAP_TOKEN=${BOOTSTRAP_TOKEN}
AGENT_STATE_PATH=/opt/vpn-node-agent/data/.agent-state.json
XRAY_BIN_PATH=/usr/local/bin/xray
XRAY_CONFIG_PATH=/etc/xray/config.json
STATS_INTERVAL_SEC=15
HEARTBEAT_INTERVAL_SEC=15
EOF

chmod 600 /opt/vpn-node-agent/.env

echo "==> [6/6] Creating systemd service vpn-node-agent.service (runs the Docker container)..."
mkdir -p /etc/xray/certs
cat <<EOF > /etc/systemd/system/vpn-node-agent.service
[Unit]
Description=VPN Edge Node Agent (Dockerized Xray Controller)
After=network-online.target docker.service
Requires=docker.service
Wants=network-online.target

[Service]
Type=simple
Restart=always
RestartSec=5
ExecStartPre=-/usr/bin/docker rm -f vpn-node-agent
ExecStartPre=-/usr/bin/docker pull ${NODE_IMAGE}
ExecStart=/usr/bin/docker run --rm --name vpn-node-agent \\
  --network host \\
  --env-file /opt/vpn-node-agent/.env \\
  -v /opt/vpn-node-agent/data:/opt/vpn-node-agent/data \\
  -v /etc/xray/certs:/etc/xray/certs:ro \\
  ${NODE_IMAGE}
ExecStop=/usr/bin/docker stop -t 10 vpn-node-agent

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload

if [ -n "$CDN_HOSTNAME" ]; then
    echo "==> [7/8] Provisioning Let's Encrypt certificate for CDN node ($CDN_HOSTNAME)..."
    # CDN nodes use real TLS instead of Reality (a CDN terminates TLS itself,
    # which breaks Reality's cert-stealing handshake — see docs/PLAN.md §6 and
    # docs/ROADMAP_PROGRESS.md Phase 9). NodeManagementService expects the
    # cert at /etc/xray/certs/<hostname>/{fullchain,privkey}.pem by default
    # (see vpn.cdn.cert-dir); that host path is bind-mounted read-only into
    # the container above, so certbot's renewals (running on the host) are
    # picked up without rebuilding/restarting the container's own filesystem.
    apt-get install -y -qq certbot
    if certbot certonly --standalone --non-interactive --agree-tos \
        --register-unsafely-without-email -d "$CDN_HOSTNAME" \
        --deploy-hook "mkdir -p /etc/xray/certs/$CDN_HOSTNAME && cp /etc/letsencrypt/live/$CDN_HOSTNAME/fullchain.pem /etc/xray/certs/$CDN_HOSTNAME/fullchain.pem && cp /etc/letsencrypt/live/$CDN_HOSTNAME/privkey.pem /etc/xray/certs/$CDN_HOSTNAME/privkey.pem && systemctl restart vpn-node-agent || true"; then
        mkdir -p "/etc/xray/certs/$CDN_HOSTNAME"
        cp "/etc/letsencrypt/live/$CDN_HOSTNAME/fullchain.pem" "/etc/xray/certs/$CDN_HOSTNAME/fullchain.pem"
        cp "/etc/letsencrypt/live/$CDN_HOSTNAME/privkey.pem" "/etc/xray/certs/$CDN_HOSTNAME/privkey.pem"
        echo "==> Certificate installed at /etc/xray/certs/$CDN_HOSTNAME/"
    else
        echo "WARNING: certbot failed — this node needs a cert at /etc/xray/certs/$CDN_HOSTNAME/{fullchain,privkey}.pem"
        echo "         before it's registered with type=cdn, or Xray will fail to start."
    fi
fi

if [ -n "$TRIAL_CAP_MBPS" ]; then
    echo "==> [8/8] Capping egress bandwidth at ${TRIAL_CAP_MBPS}mbit (trial pool, fq_codel underneath)..."
    # tc shapes the host's real NIC, so this applies regardless of the agent
    # running in a container — --network host means container traffic still
    # traverses this same interface.
    IFACE=$(ip route get 8.8.8.8 2>/dev/null | awk '{for(i=1;i<=NF;i++) if ($i=="dev") print $(i+1)}' | head -1)
    if [ -z "$IFACE" ]; then
        echo "WARNING: could not auto-detect the primary network interface; skipping tc setup."
        echo "         Run scripts/apply-trial-cap.sh <iface> ${TRIAL_CAP_MBPS} manually once you know it."
    else
        cat <<EOF > /usr/local/bin/vpn-apply-trial-cap.sh
#!/usr/bin/env bash
set -euo pipefail
IFACE="\$1"
CAP_MBPS="\$2"
tc qdisc del dev "\$IFACE" root 2>/dev/null || true
tc qdisc add dev "\$IFACE" root handle 1: htb default 10
tc class add dev "\$IFACE" parent 1: classid 1:10 htb rate "\${CAP_MBPS}mbit" ceil "\${CAP_MBPS}mbit"
tc qdisc add dev "\$IFACE" parent 1:10 handle 10: fq_codel
EOF
        chmod +x /usr/local/bin/vpn-apply-trial-cap.sh

        cat <<EOF > /etc/systemd/system/vpn-trial-cap.service
[Unit]
Description=Apply trial-pool egress bandwidth cap (tc/fq_codel)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/vpn-apply-trial-cap.sh ${IFACE} ${TRIAL_CAP_MBPS}
RemainAfterExit=true

[Install]
WantedBy=multi-user.target
EOF
        systemctl daemon-reload
        systemctl enable --now vpn-trial-cap.service
        echo "==> Applied: interface ${IFACE} capped at ${TRIAL_CAP_MBPS}mbit, persisted via vpn-trial-cap.service"
    fi
fi

echo "==> Installation complete!"
echo "To start the agent: systemctl enable --now vpn-node-agent"
echo "To monitor it: docker ps / docker logs -f vpn-node-agent / docker stats vpn-node-agent"
if [ -n "$CDN_HOSTNAME" ]; then
    echo "This node was provisioned as a CDN edge for $CDN_HOSTNAME — register it via the admin API"
    echo "with type=cdn (see docs/ROADMAP_PROGRESS.md Phase 9 / AdminController's bootstrap-token endpoint)."
fi
if [ -n "$TRIAL_CAP_MBPS" ]; then
    echo "Register this node in the 'trial' pool via the admin API to match the bandwidth cap applied above."
fi
