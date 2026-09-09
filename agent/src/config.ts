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
}

const stateFilePath = process.env.AGENT_STATE_PATH || path.resolve(process.cwd(), '.agent-state.json');

function loadPersistedState(): { nodeId?: number; nodeToken?: string } {
  try {
    if (fs.existsSync(stateFilePath)) {
      const data = JSON.parse(fs.readFileSync(stateFilePath, 'utf-8'));
      return {
        nodeId: data.nodeId,
        nodeToken: data.nodeToken,
      };
    }
  } catch (err) {
    console.warn('Failed to read agent state file:', err);
  }
  return {};
}

export function savePersistedState(nodeId: number, nodeToken: string): void {
  try {
    fs.writeFileSync(stateFilePath, JSON.stringify({ nodeId, nodeToken }, null, 2), 'utf-8');
  } catch (err) {
    console.error('Failed to save agent state:', err);
  }
}

const savedState = loadPersistedState();

export const config: AgentConfig = {
  serverGrpcUrl: process.env.SERVER_GRPC_URL || 'localhost:9090',
  bootstrapToken: process.env.BOOTSTRAP_TOKEN || '',
  nodeId: process.env.NODE_ID ? parseInt(process.env.NODE_ID, 10) : savedState.nodeId,
  nodeToken: process.env.NODE_TOKEN || savedState.nodeToken,
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
};
