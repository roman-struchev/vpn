import net from 'node:net';

/** Polls a local TCP port until something accepts a connection, or times out. */
export function waitForPortOpen(port: number, host = '127.0.0.1', timeoutMs = 3000, intervalMs = 100): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;

  return new Promise((resolve) => {
    const attempt = () => {
      const socket = net.connect({ port, host }, () => {
        socket.end();
        resolve(true);
      });
      socket.on('error', () => {
        socket.destroy();
        if (Date.now() >= deadline) {
          resolve(false);
        } else {
          setTimeout(attempt, intervalMs);
        }
      });
    };
    attempt();
  });
}
