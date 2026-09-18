import { config } from './config.js';
import { logger } from './utils/logger.js';
import { XraySupervisor } from './xray/xray-supervisor.js';
import { StatsCollector } from './xray/stats-collector.js';
import { AgentGrpcClient } from './client/grpc-client.js';
import { installProcessHandlers, reportError } from './utils/diagnostics.js';

async function main() {
  // Before anything else: an uncaught exception or rejection during startup
  // is exactly the failure nobody is watching for on an unattended node.
  installProcessHandlers();

  logger.info('Starting VPN Node Agent...');
  logger.info(`Server: ${config.serverGrpcUrl}, Region: ${config.region}, Hostname: ${config.hostname}`);

  const xraySupervisor = new XraySupervisor(config.xrayConfigPath, config.xrayBinaryPath);
  const statsCollector = new StatsCollector(config.xrayStatsApiUrl);
  const client = new AgentGrpcClient(config, xraySupervisor, statsCollector);

  const shutdown = async (signal: string) => {
    logger.info(`Received ${signal}, initiating graceful shutdown...`);
    await client.shutdown();
    process.exit(0);
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  try {
    await client.init();
    logger.info('VPN Node Agent is running.');
  } catch (err) {
    reportError('startup', 'STARTUP_FAILED', 'Fatal agent error during startup', err);
    process.exit(1);
  }
}

main().catch(err => {
  console.error('Unhandled fatal exception:', err);
  process.exit(1);
});
