/**
 * Why a connection attempt ended in ERROR, so the UI can say something more
 * useful than "Error" and offer the one action that helps. Pure — same
 * classification as the Android client's FailureReason.
 */
export type FailureReason = 'NO_SUBSCRIPTION' | 'SESSION_EXPIRED' | 'NO_SERVERS' | 'NETWORK' | 'UNKNOWN';

export function classifyFailure(error: unknown): FailureReason {
  const httpCode =
    error && typeof error === 'object' && 'httpCode' in error ? (error as { httpCode: unknown }).httpCode : undefined;
  const message = error instanceof Error ? error.message.toLowerCase() : '';
  if (typeof httpCode === 'number') {
    if (httpCode === 401) return 'SESSION_EXPIRED';
    if (message.includes('subscription')) return 'NO_SUBSCRIPTION';
    return 'UNKNOWN';
  }
  if (message.includes('no subscription links') || message.includes('no usable subscription links')) {
    return 'NO_SERVERS';
  }
  // Node's fetch reports every connectivity failure as a TypeError ("fetch failed").
  if (error instanceof TypeError || message.includes('fetch failed') || message.includes('network')) {
    return 'NETWORK';
  }
  return 'UNKNOWN';
}
