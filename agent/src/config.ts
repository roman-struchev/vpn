import os from 'os';
import path from 'path';
import fs from 'fs';
import dotenv from 'dotenv';

dotenv.config();

export interface AgentConfig {
  serverGrpcUrl: string;
  bootstrapToken: string;
  nodeId?: number;
  nodeToken?: string;
  // Known once RegisterNode's response comes back (assignedType), or from
  // persisted state / NODE_TYPE on a subsequent start that skips
  // registration entirely (NODE_ID+NODE_TOKEN already set) — see
  // grpc-client.ts's registerNode(). Drives whether index.ts wires up
  // XraySupervisor at all (docs/research/P2P_RELAY_FEASIBILITY.md §8: a p2p
  // node never runs Xray-core — see relay-session.ts's header comment).
  nodeType?: string;
  hostname: string;
  publicIp: string;
  region: string;
  asn: string;
  protoPath: string;
  xrayConfigPath: string;
  xrayBinaryPath: string;
  xrayStatsApiUrl: string;
  heartbeatIntervalMs: number;
  statsIntervalMs: number;
  stateFilePath: string;
  // P2P relay window this instance should register/heartbeat with (docs
  // §8.5) — meaningless unless nodeType ends up being p2p. The SERVER is the
  // real enforcement point (Node#isEligibleForRelay,
  // AgentStreamServiceImpl#sendSignalToNodeAndAwaitReply); relayMode/
  // relayExpiresAtEpochMs here only ever *declare* this instance's intent.
  relayMode: 'OFF' | 'TIMED' | 'ALWAYS';
  relayExpiresAtEpochMs?: number;
}

const stateFilePath = process.env.AGENT_STATE_PATH || path.resolve(process.cwd(), '.agent-state.json');

function loadPersistedState(): { nodeId?: number; nodeToken?: string; nodeType?: string } {
  try {
    if (fs.existsSync(stateFilePath)) {
      const data = JSON.parse(fs.readFileSync(stateFilePath, 'utf-8'));
      return {
        nodeId: data.nodeId,
        nodeToken: data.nodeToken,
        nodeType: data.nodeType,
      };
    }
  } catch (err) {
    console.warn('Failed to read agent state file:', err);
  }
  return {};
}

export function savePersistedState(nodeId: number, nodeToken: string, nodeType?: string): void {
  try {
    fs.writeFileSync(stateFilePath, JSON.stringify({ nodeId, nodeToken, nodeType }, null, 2), 'utf-8');
  } catch (err) {
    console.error('Failed to save agent state:', err);
  }
}

const savedState = loadPersistedState();

// RELAY_MODE/RELAY_DURATION_HOURS (docs §8.5) are read once at process start,
// same as every other env var here — changing them requires a restart (or,
// for a future phase, a runtime API this agent doesn't have yet), consistent
// with this file's existing pattern for every other setting.
const relayModeEnv = (process.env.RELAY_MODE || 'OFF').toUpperCase();
const relayMode: AgentConfig['relayMode'] = relayModeEnv === 'TIMED' || relayModeEnv === 'ALWAYS' ? relayModeEnv : 'OFF';
const relayDurationHours = process.env.RELAY_DURATION_HOURS ? parseFloat(process.env.RELAY_DURATION_HOURS) : undefined;
// Computed once at startup, not re-derived on every heartbeat — a TIMED
// window's deadline is fixed at the moment relay mode was turned on, not a
// rolling "N hours from now" that would never actually expire.
const relayExpiresAtEpochMs =
  relayMode === 'TIMED' && relayDurationHours ? Date.now() + relayDurationHours * 60 * 60 * 1000 : undefined;

export const config: AgentConfig = {
  serverGrpcUrl: process.env.SERVER_GRPC_URL || 'localhost:9090',
  bootstrapToken: process.env.BOOTSTRAP_TOKEN || '',
  nodeId: process.env.NODE_ID ? parseInt(process.env.NODE_ID, 10) : savedState.nodeId,
  nodeToken: process.env.NODE_TOKEN || savedState.nodeToken,
  nodeType: process.env.NODE_TYPE || savedState.nodeType,
  hostname: process.env.NODE_HOSTNAME || os.hostname(),
  publicIp: process.env.PUBLIC_IP || '127.0.0.1',
  region: process.env.REGION || 'default',
  asn: process.env.ASN || 'AS0',
  protoPath: process.env.PROTO_PATH || path.resolve(process.cwd(), '../proto/vpn/agent/v1/agent.proto'),
  xrayConfigPath: process.env.XRAY_CONFIG_PATH || path.resolve(process.cwd(), 'xray-config.json'),
  xrayBinaryPath: process.env.XRAY_BIN_PATH || 'xray',
  xrayStatsApiUrl: process.env.XRAY_STATS_API_URL || '127.0.0.1:10085',
  heartbeatIntervalMs: parseInt(process.env.HEARTBEAT_INTERVAL_MS || '30000', 10),
  statsIntervalMs: parseInt(process.env.STATS_INTERVAL_MS || '30000', 10),
  stateFilePath,
  relayMode,
  relayExpiresAtEpochMs,
};
