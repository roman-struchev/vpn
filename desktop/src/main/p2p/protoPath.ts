import { app } from 'electron';
import path from 'node:path';

/**
 * Resolves the bundled copy of proto/vpn/agent/v1/agent.proto. Packaged
 * builds get it from extraResources (package.json "build.extraResources",
 * populated from resources/proto/ by scripts/sync-proto.mjs at build time —
 * same pattern as xray/binaryManager.ts's getXrayBinaryPath for the xray-core
 * binary). In dev the same file is read straight from the repo root.
 */
export function getAgentProtoPath(): string {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'proto', 'agent.proto');
  }
  return path.join(app.getAppPath(), '..', 'proto', 'vpn', 'agent', 'v1', 'agent.proto');
}
