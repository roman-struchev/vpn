#!/usr/bin/env bash
# Boots a REAL local agent+node (same pattern as e2e/tests/agentHelpers.ts's
# startLocalAgent, but PUBLIC_IP=10.0.2.2 so the AOSP emulator can actually
# reach it — 10.0.2.2 is the emulator's alias for the host loopback), points
# the node at the gRPC+Reality fallback transport (so it doesn't need root to
# bind the primary XHTTP port), flips the server's global TransportPolicy to
# GRPC so the Android app dials that transport on its very first attempt
# instead of waiting through 2-3 real XHTTP failures first, then runs the
# TunnelFlowTest instrumented test against it.
#
# NOT safe to run at the same time as e2e/tests/tunnel.spec.ts (or another
# invocation of this script): both bind the single, server-wide
# vpn.grpc-fallback.port (8443, see NodeManagementService/DynamicRoutingService
# in server/src/main/resources/application.yml) and this script mutates the
# *global* TransportPolicy row, which every other client/session on the same
# shared dev server would also pick up while this test runs. Run it alone.
#
# Usage (from repo root, with a real server already up on :8080/:9090 and the
# Small_Phone_API_34_2 emulator already booted and visible to `adb devices`):
#   JAVA_HOME=$(/usr/libexec/java_home -v 21) android/scripts/real-tunnel-e2e.sh
#
# Requires: curl, jq, docker (for promoting a fresh user to ADMIN in Postgres,
# same technique as e2e/tests/adminHelpers.ts#promoteToAdmin), and a real
# xray-core binary at desktop/resources/bin/<platform>/xray (see
# e2e/tests/agentHelpers.ts#resolveXrayBinaryPath / `npm run fetch:xray` in
# desktop/), or set XRAY_BIN_PATH to one.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_BASE="${API_BASE:-http://localhost:8080}"
GRPC_URL="${SERVER_GRPC_URL:-localhost:9090}"
EMULATOR_SERIAL="${EMULATOR_SERIAL:-}"
WORKDIR="$(mktemp -d /tmp/real-tunnel-e2e.XXXXXX 2>/dev/null || mktemp -d "${TMPDIR:-/tmp}/real-tunnel-e2e.XXXXXX")"
AGENT_LOG="$WORKDIR/agent.log"
STATE_FILE="$WORKDIR/agent-state.json"
XRAY_CFG="$WORKDIR/xray-config.json"
HOSTNAME_TAG="e2e-android-tunnel-$(date +%s)-$RANDOM"
POLICY_RESTORE_FILE="$WORKDIR/policy-restore.json"
AGENT_PID=""

log() { echo "[real-tunnel-e2e] $*" >&2; }

cleanup() {
  local exit_code=$?
  log "cleaning up..."
  if [ -n "$AGENT_PID" ] && kill -0 "$AGENT_PID" 2>/dev/null; then
    kill -TERM "$AGENT_PID" 2>/dev/null || true
    sleep 1
  fi
  # Belt-and-suspenders, same reasoning as agentHelpers.ts#stopLocalAgent: npx
  # tsx's child xray-core process can outlive a plain SIGTERM to the npx pid.
  pkill -f "$XRAY_CFG" 2>/dev/null || true

  # Take this run's node out of rotation too: the server keeps a node whose
  # agent just died ONLINE until its heartbeat goes stale, and every run's
  # node shares 10.0.2.2:8443 — see retire_previous_test_nodes.
  if [ -n "${NODE_ID:-}" ] && [ -n "${ADMIN_TOKEN:-}" ]; then
    curl -s -X POST "$API_BASE/api/v1/admin/nodes/$NODE_ID/status?status=OFFLINE" \
      -H "Authorization: Bearer $ADMIN_TOKEN" >/dev/null || true
  fi

  if [ -s "$POLICY_RESTORE_FILE" ] && [ -n "${ADMIN_TOKEN:-}" ]; then
    log "restoring global TransportPolicy to its previous value"
    curl -s -X POST "$API_BASE/api/v1/admin/policies" \
      -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      -d @"$POLICY_RESTORE_FILE" >/dev/null || log "WARN: failed to restore TransportPolicy"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

find_xray_bin() {
  if [ -n "${XRAY_BIN_PATH:-}" ] && [ -x "$XRAY_BIN_PATH" ]; then
    echo "$XRAY_BIN_PATH"; return 0
  fi
  local plat=""
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) plat="mac-arm64" ;;
    Darwin-x86_64) plat="mac-x64" ;;
    Linux-x86_64) plat="linux-x64" ;;
    Linux-aarch64) plat="linux-arm64" ;;
  esac
  local candidate="$REPO_ROOT/desktop/resources/bin/$plat/xray"
  if [ -n "$plat" ] && [ -x "$candidate" ]; then
    echo "$candidate"; return 0
  fi
  return 1
}

XRAY_BIN="$(find_xray_bin)" || { log "ERROR: no real xray-core binary found (set XRAY_BIN_PATH or run 'npm run fetch:xray' in desktop/)"; exit 1; }
log "using xray-core binary: $XRAY_BIN"

curl -sf "$API_BASE/actuator/health" >/dev/null || { log "ERROR: server not reachable at $API_BASE"; exit 1; }

if [ -n "$EMULATOR_SERIAL" ]; then
  adb -s "$EMULATOR_SERIAL" get-state >/dev/null 2>&1 || { log "ERROR: emulator $EMULATOR_SERIAL not visible to adb"; exit 1; }
else
  adb devices | grep -q "device$" || { log "ERROR: no emulator/device visible to adb"; exit 1; }
fi

# --- 1. A throwaway admin account (same technique as e2e/tests/adminHelpers.ts) ---
ADMIN_EMAIL="e2e-android-tunnel-admin-$(date +%s)@example.com"
ADMIN_PASSWORD="Test-Passw0rd!"
log "registering admin bootstrap user $ADMIN_EMAIL"
curl -sf -X POST "$API_BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" >/dev/null

log "promoting to ADMIN in Postgres"
docker exec vpn-postgres psql -U vpn_user -d vpn_db -c \
  "UPDATE users SET role='ADMIN' WHERE email='$ADMIN_EMAIL';" >/dev/null

ADMIN_TOKEN="$(curl -sf -X POST "$API_BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .token)"
[ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_TOKEN" != "null" ] || { log "ERROR: failed to obtain admin JWT"; exit 1; }

# --- 1b. Retire nodes left over from earlier runs ------------------------------
# Every run registers a new node at the same 10.0.2.2:8443, and the server keeps
# a node whose agent is gone ONLINE until its heartbeat goes stale. Those stale
# rows come first in the app's link list, carry REALITY keys the process now on
# :8443 does not know ("REALITY: received real certificate"), and cost the app
# a full round of failures before it reaches this run's node — long enough to
# time the test out.
log "retiring leftover e2e-android nodes"
curl -sf "$API_BASE/api/v1/admin/nodes" -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq -r '.[] | select((.hostname // "") | startswith("e2e-android-tunnel-")) | select(.status=="ONLINE") | .id' \
  | while read -r stale_id; do
      curl -s -X POST "$API_BASE/api/v1/admin/nodes/$stale_id/status?status=OFFLINE" \
        -H "Authorization: Bearer $ADMIN_TOKEN" >/dev/null || true
    done

# --- 2. Flip the global TransportPolicy to GRPC ------------------------------
# Without this the Android app starts on XHTTP (server default) against the
# node's *real* primary-inbound port, which — same as tunnel.spec.ts — this
# script cannot bind without root, so it would need 2-3 real, ~15-20s-each
# XHTTP failures on this single node before TransportFallbackPolicy switches
# to GRPC. Worse: since XrayInvoker.runXray() only fails if libXray itself
# throws (it does not probe reachability), a wrong-port XHTTP attempt would
# actually settle into a false "CONNECTED" state that never moves real
# traffic — so this is not just a speed optimization, it's required for the
# test to mean anything. (Since 22.09.2026 the app verifies the tunnel end to
# end before CONNECTED, so a wrong port no longer fakes success — it only
# costs the failures and minutes described above.) maxRetriesBeforeNodeSwitch
# is set to its minimum, 2, so TunnelFlowTest's revoked-key recovery takes one
# retry instead of two. Save the existing global policy first so it can be
# restored on exit.
log "reading current global TransportPolicy"
CURRENT_POLICY="$(curl -sf "$API_BASE/api/v1/admin/policies" -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq '[.[] | select(.scope=="global" and .scopeValue=="*")] | first')"
if [ "$CURRENT_POLICY" != "null" ] && [ -n "$CURRENT_POLICY" ]; then
  echo "$CURRENT_POLICY" > "$POLICY_RESTORE_FILE"
else
  # No global row exists yet (server creates one lazily) — restoring means
  # putting the documented default (XHTTP) back explicitly.
  jq -n '{scope:"global", scopeValue:"*", primaryTransport:"XHTTP", fallbackTransport:"GRPC", fingerprint:"firefox", backoffInitialSec:15, maxRetriesBeforeNodeSwitch:3, isActive:true}' > "$POLICY_RESTORE_FILE"
fi

log "setting global TransportPolicy.primaryTransport=GRPC"
NEW_POLICY="$(echo "$CURRENT_POLICY" | jq 'if . == null then {scope:"global", scopeValue:"*", fallbackTransport:"GRPC", fingerprint:"firefox", backoffInitialSec:15, maxRetriesBeforeNodeSwitch:3, isActive:true} else . end | .primaryTransport = "GRPC" | .maxRetriesBeforeNodeSwitch = 2')"
curl -sf -X POST "$API_BASE/api/v1/admin/policies" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "$NEW_POLICY" >/dev/null

# --- 3. Bootstrap token + real local agent/node -------------------------------
log "creating bootstrap token (pool=paid type=direct)"
BOOTSTRAP_TOKEN="$(curl -sf -X POST "$API_BASE/api/v1/admin/nodes/bootstrap-token?pool=paid&type=direct&validHours=1" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r .token)"
[ -n "$BOOTSTRAP_TOKEN" ] && [ "$BOOTSTRAP_TOKEN" != "null" ] || { log "ERROR: failed to create bootstrap token"; exit 1; }

log "starting real local agent (hostname=$HOSTNAME_TAG, PUBLIC_IP=10.0.2.2, real xray-core)"
(
  cd "$REPO_ROOT/agent"
  BOOTSTRAP_TOKEN="$BOOTSTRAP_TOKEN" \
  SERVER_GRPC_URL="$GRPC_URL" \
  PUBLIC_IP="10.0.2.2" \
  REGION="e2e-android" \
  NODE_HOSTNAME="$HOSTNAME_TAG" \
  AGENT_STATE_PATH="$STATE_FILE" \
  XRAY_CONFIG_PATH="$XRAY_CFG" \
  XRAY_BIN_PATH="$XRAY_BIN" \
  AGENT_PRIMARY_INBOUND_PORT_OVERRIDE="18443" \
  AGENT_XRAY_LOGLEVEL="debug" \
  npx tsx src/index.ts >"$AGENT_LOG" 2>&1 &
  echo $! > "$WORKDIR/agent.pid"
)
sleep 1
AGENT_PID="$(cat "$WORKDIR/agent.pid")"
log "agent pid=$AGENT_PID, log=$AGENT_LOG"

log "waiting for node $HOSTNAME_TAG to report ONLINE..."
NODE_ID=""
for i in $(seq 1 45); do
  NODES_JSON="$(curl -sf "$API_BASE/api/v1/admin/nodes" -H "Authorization: Bearer $ADMIN_TOKEN" || echo '[]')"
  STATUS="$(echo "$NODES_JSON" | jq -r --arg h "$HOSTNAME_TAG" '.[] | select(.hostname==$h) | .status' 2>/dev/null || true)"
  if [ "$STATUS" = "ONLINE" ]; then
    NODE_ID="$(echo "$NODES_JSON" | jq -r --arg h "$HOSTNAME_TAG" '.[] | select(.hostname==$h) | .id')"
    break
  fi
  if ! kill -0 "$AGENT_PID" 2>/dev/null; then
    log "ERROR: agent process died. Log:"; cat "$AGENT_LOG" >&2; exit 1
  fi
  sleep 1
done
[ -n "$NODE_ID" ] || { log "ERROR: node never reported ONLINE. Log:"; cat "$AGENT_LOG" >&2; exit 1; }
log "node $NODE_ID ($HOSTNAME_TAG) is ONLINE"

log "waiting for the real xray-core gRPC+Reality inbound on 127.0.0.1:8443..."
for i in $(seq 1 30); do
  if nc -z 127.0.0.1 8443 2>/dev/null; then break; fi
  sleep 1
done
nc -z 127.0.0.1 8443 2>/dev/null || { log "ERROR: nothing listening on :8443. Agent log:"; cat "$AGENT_LOG" >&2; exit 1; }
log "gRPC+Reality fallback inbound is up"

# --- 4. Run the real instrumented test ---------------------------------------
log "running :app:connectedDebugAndroidTest (TunnelFlowTest)"
cd "$REPO_ROOT/android"
adb logcat -c || true
set +e
./gradlew :app:connectedDebugAndroidTest \
  -PapiBaseUrl=http://10.0.2.2:8080/ \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vpn.android.TunnelFlowTest &
GRADLE_PID=$!

# TunnelFlowTest asks for its device to be revoked from outside, the way it
# happens for real (another device, the web dashboard) — see the test. Watch
# for its request and act on it as that user.
REVOKED=""
while kill -0 "$GRADLE_PID" 2>/dev/null; do
  if [ -z "$REVOKED" ]; then
    MARKER="$(adb logcat -d -s TunnelFlowTest:I 2>/dev/null | grep -o 'REVOKE_DEVICES_NOW .*' | tail -1 || true)"
    if [ -n "$MARKER" ]; then
      read -r _ T_EMAIL T_PASSWORD <<< "$MARKER"
      log "revoking every device of $T_EMAIL (requested by the test)"
      T_TOKEN="$(curl -sf -X POST "$API_BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$T_EMAIL\",\"password\":\"$T_PASSWORD\"}" | jq -r .token)"
      for DEVICE_ID in $(curl -sf "$API_BASE/api/v1/user/devices" -H "Authorization: Bearer $T_TOKEN" | jq -r '.[].id'); do
        curl -sf -X DELETE "$API_BASE/api/v1/user/devices/$DEVICE_ID" -H "Authorization: Bearer $T_TOKEN" >/dev/null \
          && log "revoked device $DEVICE_ID"
      done
      REVOKED=1
    fi
  fi
  sleep 2
done
wait "$GRADLE_PID"
GRADLE_EXIT=$?
set -e

log "--- agent log (tail) ---"
tail -n 80 "$AGENT_LOG" >&2 || true

exit $GRADLE_EXIT
