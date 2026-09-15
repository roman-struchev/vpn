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

# --p2p (docs/research/P2P_RELAY_FEASIBILITY.md §8): a p2p node never accepts
# a direct inbound connection (a connecting client reaches it over a WebRTC
# DataChannel signaled through the server instead — see agent/src/p2p/), so
# unlike a direct/cdn node it needs neither a public IP nor an open inbound
# port. This flag only changes what THIS SCRIPT requires/configures on the
# host; the node's actual type (and therefore whether the server will ever
# route it a p2p signal at all) comes from the bootstrap token itself
# (assignedType, set when the token was minted — see NodeManagementService).
P2P_MODE=false
if [ "${1:-}" = "--p2p" ]; then
    P2P_MODE=true
    shift
fi

SERVER_GRPC="${1:-}"
BOOTSTRAP_TOKEN="${2:-}"
if $P2P_MODE; then
    CDN_HOSTNAME="" # never applicable to a p2p node — Reality/TLS don't apply either way (see agent/src/p2p/relay-session.ts's header comment: no Xray-core at all for this node type).
    REGION_OVERRIDE="${3:-}"
    # Relay window (docs §8.5) this node should register/heartbeat with.
    # "always" needs nothing else to survive a host reboot — Docker's
    # --restart unless-stopped (below) already brings the container back up,
    # and the server (not this script or the container) is what actually
    # enforces the window on every real dispatch, so a stale RELAY_DURATION_HOURS
    # baked into an old .env just means this instance stops advertising itself
    # sooner than the operator maybe intended, never longer.
    RELAY_MODE="${4:-always}"
    RELAY_DURATION_HOURS="${5:-}"
else
    # Optional: only for CDN-fronted nodes (registered with type=cdn). A direct
    # Reality node needs none of this — Reality doesn't use a real certificate.
    CDN_HOSTNAME="${3:-}"
    # Optional: skips auto-detection below (step [3/5]) and uses this label as-is.
    # Useful when the geo-IP lookup gets a node's city wrong, or to force several
    # nodes onto one label the lookup wouldn't naturally agree on (region grouping
    # is an exact string match — see SubscriptionExportService#groupingBy).
    REGION_OVERRIDE="${4:-}"
fi
# Optional: override the published node-agent image (e.g. a specific
# version tag instead of latest, or a private mirror).
NODE_IMAGE="${VPN_NODE_IMAGE:-romanew/vpn-node:latest}"

if [ -z "$SERVER_GRPC" ] || [ -z "$BOOTSTRAP_TOKEN" ]; then
    echo "Usage: $0 <server_grpc_host:port> <bootstrap_token> [cdn_hostname] [region]"
    echo "       $0 --p2p <server_grpc_host:port> <bootstrap_token> [region] [relay_mode] [relay_duration_hours]"
    echo "Example (direct Reality node):     $0 vpn.example.com:9090 bst_abc12345"
    echo "Example (CDN node, Phase 9):       $0 vpn.example.com:9090 bst_abc12345 edge.example.com"
    echo "Example (force a region label):    $0 vpn.example.com:9090 bst_abc12345 \"\" \"Netherlands, Amsterdam\""
    echo "Example (p2p relay node, always on): $0 --p2p vpn.example.com:9090 bst_p2p_abc12345"
    echo "Example (p2p relay node, 8h window): $0 --p2p vpn.example.com:9090 bst_p2p_abc12345 \"\" timed 8"
    echo "  cdn_hostname must already resolve (via the CDN) to this host's IP on port 80/443"
    echo "  before running this script, so certbot's HTTP-01 challenge can complete."
    echo "  region is auto-detected from this host's public IP via geo-IP lookup if omitted"
    echo "  (falls back to \"default\" if that lookup fails — never fatal, unlike public IP)."
    echo "  format is \"Country\" or \"Country, City\" — full country name, city optional."
    echo "  --p2p needs no public IP or open inbound port (docs/research/P2P_RELAY_FEASIBILITY.md §8) —"
    echo "  relay_mode is \"always\" (default) or \"timed\" (needs relay_duration_hours too)."
    exit 1
fi

echo "==> [1/5] Optimizing sysctl (BBR, TCP buffer tuning)..."
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

if $P2P_MODE; then
    # A p2p node's publicIp is never embedded in a client-facing link (see
    # NodeManagementService's isP2p branches and SubscriptionExportService's
    # p2p exclusion) — any placeholder is fine, so skip the whole detection
    # dance (and its hard failure) that exists solely to protect direct/cdn
    # nodes from shipping a broken VLESS link.
    echo "==> [2/5] Skipping public IP detection (--p2p: not needed, never used)."
    PUBLIC_IP="0.0.0.0"
else
    echo "==> [2/5] Detecting public IP..."
    # Without this, PUBLIC_IP is never set and the agent defaults to '127.0.0.1'
    # (see agent/src/config.ts) — which the server then embeds verbatim into every
    # VLESS link it generates for this node, making it unreachable for real
    # clients. Try the primary interface's own address first (many VPS providers
    # assign the public IP directly to eth0, no external call needed); only fall
    # back to an external echo service if that's missing or looks private/NAT'd.
    IFACE=$(ip route get 8.8.8.8 2>/dev/null | awk '{for(i=1;i<=NF;i++) if ($i=="dev") print $(i+1)}' | head -1)
    LOCAL_IP=""
    [ -n "$IFACE" ] && LOCAL_IP=$(ip -4 addr show dev "$IFACE" 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -1)
    is_private_ip() {
        case "$1" in
            10.*|192.168.*|127.*) return 0 ;;
            172.1[6-9].*|172.2[0-9].*|172.3[0-1].*) return 0 ;;
            *) return 1 ;;
        esac
    }
    if [ -n "$LOCAL_IP" ] && ! is_private_ip "$LOCAL_IP"; then
        PUBLIC_IP="$LOCAL_IP"
    else
        PUBLIC_IP=$(curl -4 -fsS --max-time 5 https://ifconfig.me 2>/dev/null || true)
        [ -z "$PUBLIC_IP" ] && PUBLIC_IP=$(curl -4 -fsS --max-time 5 https://icanhazip.com 2>/dev/null | tr -d '[:space:]' || true)
        [ -z "$PUBLIC_IP" ] && PUBLIC_IP=$(curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)
    fi
    if ! echo "$PUBLIC_IP" | grep -qE '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
        echo "ERROR: could not determine this host's public IPv4 address (got: '${PUBLIC_IP:-<empty>}')." >&2
        echo "        Every VLESS link the server generates for this node embeds this IP verbatim —" >&2
        echo "        refusing to fall back to a default and silently ship broken client configs." >&2
        echo "        Fix network/DNS connectivity, or set PUBLIC_IP yourself in /opt/vpn-node-agent/.env" >&2
        echo "        and 'docker restart vpn-node-agent' afterwards." >&2
        exit 1
    fi
    echo "==> Public IP: ${PUBLIC_IP}"
fi

echo "==> [3/5] Detecting region..."
# Shown in the admin panel and used to group nodes for the "auto (best
# available)" client selector (SubscriptionExportService groups nodes by
# this exact string — see docs/ARCHITECTURE.md §4.3/§5) — unlike PUBLIC_IP
# above, getting this wrong doesn't break the node, so a failed lookup falls
# back to "default" instead of aborting the install.
#
# Clients show this string verbatim as the region's display name, so it must
# read as a country, not a 2-letter ISO code — country_name_for_code() below
# expands the code both geo-IP providers return; the city (if known) is
# appended after the country, not before, and dropped entirely if unknown.
country_name_for_code() {
    case "$1" in
        AD) echo "Andorra" ;; AE) echo "United Arab Emirates" ;; AF) echo "Afghanistan" ;;
        AG) echo "Antigua and Barbuda" ;; AI) echo "Anguilla" ;; AL) echo "Albania" ;;
        AM) echo "Armenia" ;; AO) echo "Angola" ;; AQ) echo "Antarctica" ;; AR) echo "Argentina" ;;
        AS) echo "American Samoa" ;; AT) echo "Austria" ;; AU) echo "Australia" ;; AW) echo "Aruba" ;;
        AX) echo "Aland Islands" ;; AZ) echo "Azerbaijan" ;; BA) echo "Bosnia and Herzegovina" ;;
        BB) echo "Barbados" ;; BD) echo "Bangladesh" ;; BE) echo "Belgium" ;; BF) echo "Burkina Faso" ;;
        BG) echo "Bulgaria" ;; BH) echo "Bahrain" ;; BI) echo "Burundi" ;; BJ) echo "Benin" ;;
        BL) echo "Saint Barthelemy" ;; BM) echo "Bermuda" ;; BN) echo "Brunei" ;; BO) echo "Bolivia" ;;
        BQ) echo "Bonaire, Sint Eustatius and Saba" ;; BR) echo "Brazil" ;; BS) echo "Bahamas" ;;
        BT) echo "Bhutan" ;; BV) echo "Bouvet Island" ;; BW) echo "Botswana" ;; BY) echo "Belarus" ;;
        BZ) echo "Belize" ;; CA) echo "Canada" ;; CC) echo "Cocos Islands" ;;
        CD) echo "DR Congo" ;; CF) echo "Central African Republic" ;; CG) echo "Congo" ;;
        CH) echo "Switzerland" ;; CI) echo "Ivory Coast" ;; CK) echo "Cook Islands" ;; CL) echo "Chile" ;;
        CM) echo "Cameroon" ;; CN) echo "China" ;; CO) echo "Colombia" ;; CR) echo "Costa Rica" ;;
        CU) echo "Cuba" ;; CV) echo "Cabo Verde" ;; CW) echo "Curacao" ;; CX) echo "Christmas Island" ;;
        CY) echo "Cyprus" ;; CZ) echo "Czechia" ;; DE) echo "Germany" ;; DJ) echo "Djibouti" ;;
        DK) echo "Denmark" ;; DM) echo "Dominica" ;; DO) echo "Dominican Republic" ;; DZ) echo "Algeria" ;;
        EC) echo "Ecuador" ;; EE) echo "Estonia" ;; EG) echo "Egypt" ;; EH) echo "Western Sahara" ;;
        ER) echo "Eritrea" ;; ES) echo "Spain" ;; ET) echo "Ethiopia" ;; FI) echo "Finland" ;;
        FJ) echo "Fiji" ;; FK) echo "Falkland Islands" ;; FM) echo "Micronesia" ;; FO) echo "Faroe Islands" ;;
        FR) echo "France" ;; GA) echo "Gabon" ;; GB) echo "United Kingdom" ;; GD) echo "Grenada" ;;
        GE) echo "Georgia" ;; GF) echo "French Guiana" ;; GG) echo "Guernsey" ;; GH) echo "Ghana" ;;
        GI) echo "Gibraltar" ;; GL) echo "Greenland" ;; GM) echo "Gambia" ;; GN) echo "Guinea" ;;
        GP) echo "Guadeloupe" ;; GQ) echo "Equatorial Guinea" ;; GR) echo "Greece" ;;
        GS) echo "South Georgia" ;; GT) echo "Guatemala" ;; GU) echo "Guam" ;; GW) echo "Guinea-Bissau" ;;
        GY) echo "Guyana" ;; HK) echo "Hong Kong" ;; HM) echo "Heard Island" ;; HN) echo "Honduras" ;;
        HR) echo "Croatia" ;; HT) echo "Haiti" ;; HU) echo "Hungary" ;; ID) echo "Indonesia" ;;
        IE) echo "Ireland" ;; IL) echo "Israel" ;; IM) echo "Isle of Man" ;; IN) echo "India" ;;
        IO) echo "British Indian Ocean Territory" ;; IQ) echo "Iraq" ;; IR) echo "Iran" ;;
        IS) echo "Iceland" ;; IT) echo "Italy" ;; JE) echo "Jersey" ;; JM) echo "Jamaica" ;;
        JO) echo "Jordan" ;; JP) echo "Japan" ;; KE) echo "Kenya" ;; KG) echo "Kyrgyzstan" ;;
        KH) echo "Cambodia" ;; KI) echo "Kiribati" ;; KM) echo "Comoros" ;; KN) echo "Saint Kitts and Nevis" ;;
        KP) echo "North Korea" ;; KR) echo "South Korea" ;; KW) echo "Kuwait" ;; KY) echo "Cayman Islands" ;;
        KZ) echo "Kazakhstan" ;; LA) echo "Laos" ;; LB) echo "Lebanon" ;; LC) echo "Saint Lucia" ;;
        LI) echo "Liechtenstein" ;; LK) echo "Sri Lanka" ;; LR) echo "Liberia" ;; LS) echo "Lesotho" ;;
        LT) echo "Lithuania" ;; LU) echo "Luxembourg" ;; LV) echo "Latvia" ;; LY) echo "Libya" ;;
        MA) echo "Morocco" ;; MC) echo "Monaco" ;; MD) echo "Moldova" ;; ME) echo "Montenegro" ;;
        MF) echo "Saint Martin" ;; MG) echo "Madagascar" ;; MH) echo "Marshall Islands" ;;
        MK) echo "North Macedonia" ;; ML) echo "Mali" ;; MM) echo "Myanmar" ;; MN) echo "Mongolia" ;;
        MO) echo "Macao" ;; MP) echo "Northern Mariana Islands" ;; MQ) echo "Martinique" ;;
        MR) echo "Mauritania" ;; MS) echo "Montserrat" ;; MT) echo "Malta" ;; MU) echo "Mauritius" ;;
        MV) echo "Maldives" ;; MW) echo "Malawi" ;; MX) echo "Mexico" ;; MY) echo "Malaysia" ;;
        MZ) echo "Mozambique" ;; NA) echo "Namibia" ;; NC) echo "New Caledonia" ;; NE) echo "Niger" ;;
        NF) echo "Norfolk Island" ;; NG) echo "Nigeria" ;; NI) echo "Nicaragua" ;; NL) echo "Netherlands" ;;
        "NO") echo "Norway" ;; NP) echo "Nepal" ;; NR) echo "Nauru" ;; NU) echo "Niue" ;;
        NZ) echo "New Zealand" ;; OM) echo "Oman" ;; PA) echo "Panama" ;; PE) echo "Peru" ;;
        PF) echo "French Polynesia" ;; PG) echo "Papua New Guinea" ;; PH) echo "Philippines" ;;
        PK) echo "Pakistan" ;; PL) echo "Poland" ;; PM) echo "Saint Pierre and Miquelon" ;;
        PN) echo "Pitcairn" ;; PR) echo "Puerto Rico" ;; PS) echo "Palestine" ;; PT) echo "Portugal" ;;
        PW) echo "Palau" ;; PY) echo "Paraguay" ;; QA) echo "Qatar" ;; RE) echo "Reunion" ;;
        RO) echo "Romania" ;; RS) echo "Serbia" ;; RU) echo "Russia" ;; RW) echo "Rwanda" ;;
        SA) echo "Saudi Arabia" ;; SB) echo "Solomon Islands" ;; SC) echo "Seychelles" ;; SD) echo "Sudan" ;;
        SE) echo "Sweden" ;; SG) echo "Singapore" ;; SH) echo "Saint Helena" ;; SI) echo "Slovenia" ;;
        SJ) echo "Svalbard and Jan Mayen" ;; SK) echo "Slovakia" ;; SL) echo "Sierra Leone" ;;
        SM) echo "San Marino" ;; SN) echo "Senegal" ;; SO) echo "Somalia" ;; SR) echo "Suriname" ;;
        SS) echo "South Sudan" ;; ST) echo "Sao Tome and Principe" ;; SV) echo "El Salvador" ;;
        SX) echo "Sint Maarten" ;; SY) echo "Syria" ;; SZ) echo "Eswatini" ;; TC) echo "Turks and Caicos Islands" ;;
        TD) echo "Chad" ;; TF) echo "French Southern Territories" ;; TG) echo "Togo" ;; TH) echo "Thailand" ;;
        TJ) echo "Tajikistan" ;; TK) echo "Tokelau" ;; TL) echo "Timor-Leste" ;; TM) echo "Turkmenistan" ;;
        TN) echo "Tunisia" ;; TO) echo "Tonga" ;; TR) echo "Turkey" ;; TT) echo "Trinidad and Tobago" ;;
        TV) echo "Tuvalu" ;; TW) echo "Taiwan" ;; TZ) echo "Tanzania" ;; UA) echo "Ukraine" ;;
        UG) echo "Uganda" ;; UM) echo "United States Minor Outlying Islands" ;; US) echo "United States" ;;
        UY) echo "Uruguay" ;; UZ) echo "Uzbekistan" ;; VA) echo "Vatican City" ;;
        VC) echo "Saint Vincent and the Grenadines" ;; VE) echo "Venezuela" ;; VG) echo "British Virgin Islands" ;;
        VI) echo "United States Virgin Islands" ;; VN) echo "Vietnam" ;; VU) echo "Vanuatu" ;;
        WF) echo "Wallis and Futuna" ;; WS) echo "Samoa" ;; YE) echo "Yemen" ;; YT) echo "Mayotte" ;;
        ZA) echo "South Africa" ;; ZM) echo "Zambia" ;; ZW) echo "Zimbabwe" ;;
        *) echo "" ;;
    esac
}
if [ -n "$REGION_OVERRIDE" ]; then
    REGION="$REGION_OVERRIDE"
else
    GEO_CITY=""
    GEO_COUNTRY=""
    # ipinfo.io's free tier already returns a 2-letter country code over HTTPS.
    GEO_JSON=$(curl -fsS --max-time 5 "https://ipinfo.io/${PUBLIC_IP}/json" 2>/dev/null || true)
    GEO_CITY=$(echo "$GEO_JSON" | grep -o '"city"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
    GEO_COUNTRY=$(echo "$GEO_JSON" | grep -o '"country"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
    if [ -z "$GEO_CITY" ] || [ -z "$GEO_COUNTRY" ]; then
        # Fallback: ip-api.com's free tier is HTTP-only (HTTPS needs a paid plan).
        GEO_JSON=$(curl -fsS --max-time 5 "http://ip-api.com/json/${PUBLIC_IP}" 2>/dev/null || true)
        GEO_CITY=$(echo "$GEO_JSON" | grep -o '"city"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
        GEO_COUNTRY=$(echo "$GEO_JSON" | grep -o '"countryCode"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
    fi
    if [ -n "$GEO_COUNTRY" ]; then
        COUNTRY_NAME=$(country_name_for_code "$GEO_COUNTRY")
        [ -z "$COUNTRY_NAME" ] && COUNTRY_NAME="$GEO_COUNTRY"
        if [ -n "$GEO_CITY" ]; then
            REGION="${COUNTRY_NAME}, ${GEO_CITY}"
        else
            REGION="${COUNTRY_NAME}"
        fi
    else
        echo "WARNING: geo-IP lookup failed — using \"default\" as this node's region."
        echo "         Re-run with a 4th argument to set one explicitly, e.g. \"Netherlands, Amsterdam\"."
        REGION="default"
    fi
fi
echo "==> Region: ${REGION}"

echo "==> [4/5] Pulling node-agent image (${NODE_IMAGE})..."
docker pull "$NODE_IMAGE"

echo "==> [5/5] Starting vpn-node-agent container..."
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
PUBLIC_IP=${PUBLIC_IP}
REGION=${REGION}
AGENT_STATE_PATH=/opt/vpn-node-agent/data/.agent-state.json
STATS_INTERVAL_MS=15000
HEARTBEAT_INTERVAL_MS=15000
EOF
if $P2P_MODE; then
    {
        echo "RELAY_MODE=${RELAY_MODE}"
        [ -n "$RELAY_DURATION_HOURS" ] && echo "RELAY_DURATION_HOURS=${RELAY_DURATION_HOURS}"
    } >> /opt/vpn-node-agent/.env
fi
chmod 600 /opt/vpn-node-agent/.env

docker rm -f vpn-node-agent > /dev/null 2>&1 || true
if $P2P_MODE; then
    # Default bridge networking, not --network host: a p2p node accepts no
    # inbound connection at all (see the P2P_MODE comment near the top of
    # this script) — running it with the host's full network namespace would
    # be needless exposure for a container that never needs to bind a public
    # port. It only needs outbound reachability (to the gRPC server, and to a
    # public STUN server for its own WebRTC candidate gathering), which the
    # default bridge's NAT'd egress already provides.
    docker run -d --name vpn-node-agent \
      --restart unless-stopped \
      --env-file /opt/vpn-node-agent/.env \
      -v /opt/vpn-node-agent/data:/opt/vpn-node-agent/data \
      "$NODE_IMAGE"
else
    docker run -d --name vpn-node-agent \
      --network host \
      --restart unless-stopped \
      --env-file /opt/vpn-node-agent/.env \
      -v /opt/vpn-node-agent/data:/opt/vpn-node-agent/data \
      -v /etc/xray/certs:/etc/xray/certs:ro \
      "$NODE_IMAGE"
fi

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
if $P2P_MODE; then
    echo "This node registered as a p2p relay (relay_mode=${RELAY_MODE}) — no public IP or inbound port needed."
    echo "It earns relay credit for whichever user's own bootstrap token it was registered with (see"
    echo "docs/research/P2P_RELAY_FEASIBILITY.md §8.4/§8.6); watch 'docker logs -f vpn-node-agent' for"
    echo "'P2P session ...' lines once real traffic starts flowing through it."
fi
