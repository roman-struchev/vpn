import { evaluateCensorship, type CensorshipResult } from '../../shared/censorshipVerdict';

const DEFAULT_WHITELIST_URL = 'https://www.gosuslugi.ru/';
const DEFAULT_FOREIGN_TEST_URL = 'https://www.google.com/generate_204';
const PROBE_TIMEOUT_MS = 4000;

/**
 * Runs the two probes behind evaluateCensorship() directly on the system
 * network path (the local xray listeners aren't used here — the point is to
 * tell "operator blocks this" apart from "our node/tunnel is down").
 */
export async function probeCensorship(
  whitelistUrl = DEFAULT_WHITELIST_URL,
  foreignTestUrl = DEFAULT_FOREIGN_TEST_URL
): Promise<CensorshipResult> {
  const [whitelistReachable, foreignReachable] = await Promise.all([
    isReachable(whitelistUrl),
    isReachable(foreignTestUrl),
  ]);
  return evaluateCensorship(whitelistReachable, foreignReachable);
}

async function isReachable(url: string): Promise<boolean> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const response = await fetch(url, { method: 'HEAD', signal: controller.signal });
    return response.ok || (response.status >= 300 && response.status < 400);
  } catch {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}
