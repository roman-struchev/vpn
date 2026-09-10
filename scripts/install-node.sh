#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# VPN Node Bootstrap & Installer Script
# Supported OS: Ubuntu 22.04 / 24.04 LTS, Debian 12
# ==============================================================================

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: This script must be run as root." >&2
    exit 1
fi

SERVER_GRPC="${1:-}"
BOOTSTRAP_TOKEN="${2:-}"

if [ -z "$SERVER_GRPC" ] || [ -z "$BOOTSTRAP_TOKEN" ]; then
    echo "Usage: $0 <server_grpc_host:port> <bootstrap_token>"
    echo "Example: $0 vpn.example.com:9090 bst_abc12345"
    exit 1
fi

echo "==> [1/6] Installing dependencies..."
apt-get update -qq
apt-get install -y -qq curl wget jq tar unzip ca-certificates iptables

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

echo "==> [3/6] Installing Xray-core..."
XRAY_VERSION="v24.11.30"
mkdir -p /usr/local/bin /etc/xray
TMP_DIR=$(mktemp -d)
wget -q -O "${TMP_DIR}/xray.zip" "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip" || {
    echo "Warning: Direct download failed, trying official Xray install script..."
    bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
}

if [ -f "${TMP_DIR}/xray.zip" ]; then
    unzip -q -o "${TMP_DIR}/xray.zip" xray -d /usr/local/bin/
    chmod +x /usr/local/bin/xray
fi
rm -rf "$TMP_DIR"

echo "==> [4/6] Installing Node.js runtime..."
if ! command -v node > /dev/null 2>&1; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - > /dev/null 2>&1
    apt-get install -y -qq nodejs
fi

echo "==> [5/6] Setting up vpn-node-agent in /opt/vpn-node-agent..."
mkdir -p /opt/vpn-node-agent /var/log/vpn-agent
chown -R root:root /opt/vpn-node-agent

cat <<EOF > /opt/vpn-node-agent/.env
CONTROL_PLANE_GRPC=${SERVER_GRPC}
BOOTSTRAP_TOKEN=${BOOTSTRAP_TOKEN}
NODE_DATA_DIR=/opt/vpn-node-agent/data
XRAY_BIN_PATH=/usr/local/bin/xray
XRAY_CONFIG_PATH=/etc/xray/config.json
STATS_INTERVAL_SEC=15
HEARTBEAT_INTERVAL_SEC=15
EOF

chmod 600 /opt/vpn-node-agent/.env

echo "==> [6/6] Creating systemd service vpn-node-agent.service..."
cat <<EOF > /etc/systemd/system/vpn-node-agent.service
[Unit]
Description=VPN Edge Node Agent (Xray Controller)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/vpn-node-agent
EnvironmentFile=/opt/vpn-node-agent/.env
ExecStart=/usr/bin/node /opt/vpn-node-agent/dist/index.js
Restart=always
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
echo "==> Installation complete!"
echo "To start the agent: systemctl enable --now vpn-node-agent"
