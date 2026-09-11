import net from 'node:net';

/**
 * Performs a TCP handshake ping to measure round-trip latency to a host and port.
 * Returns the round-trip time in milliseconds, or null if unreachable/timed out.
 */
export function pingTcp(host: string, port: number, timeoutMs = 2000): Promise<number | null> {
  return new Promise((resolve) => {
    const start = Date.now();
    const socket = new net.Socket();
    let settled = false;

    const cleanup = () => {
      if (!settled) {
        settled = true;
        socket.destroy();
      }
    };

    socket.setTimeout(timeoutMs);

    socket.once('connect', () => {
      const elapsed = Date.now() - start;
      cleanup();
      resolve(elapsed);
    });

    socket.once('timeout', () => {
      cleanup();
      resolve(null);
    });

    socket.once('error', () => {
      cleanup();
      resolve(null);
    });

    try {
      socket.connect(port, host);
    } catch {
      cleanup();
      resolve(null);
    }
  });
}
