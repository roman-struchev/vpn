import net from 'node:net';

/**
 * Whether traffic actually gets through the tunnel: one plain-HTTP request
 * sent through xray's local HTTP proxy inbound to a well-known endpoint.
 *
 * "xray is listening on its local port" — all this app used to check — is
 * true the moment the process starts, whether or not the node is reachable,
 * its key valid, or the plan still active. The app then showed "Protected"
 * with the system proxy pointed at a tunnel that went nowhere, i.e. the
 * whole machine offline. Only an answer that could have come from the far
 * end counts: Cloudflare's trace page, addressed by IP so a broken DNS path
 * cannot fail the probe on its own.
 */
export const PROBE_HOST = '1.1.1.1';
const PROBE_PATH = '/cdn-cgi/trace';

export function probeThroughHttpProxy(proxyPort: number, timeoutMs = 8000, proxyHost = '127.0.0.1'): Promise<boolean> {
  return new Promise((resolve) => {
    let received = '';
    let settled = false;
    const socket = net.connect({ port: proxyPort, host: proxyHost });
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(timeoutMs, () => finish(false));
    socket.on('error', () => finish(false));
    socket.on('connect', () => {
      socket.write(
        `GET http://${PROBE_HOST}${PROBE_PATH} HTTP/1.1\r\nHost: ${PROBE_HOST}\r\nConnection: close\r\nUser-Agent: Mozilla/5.0\r\n\r\n`
      );
    });
    socket.on('data', (chunk) => {
      received += chunk.toString('latin1');
      // The trace body is key=value lines, one of them "ip=<our exit IP>" —
      // something a local proxy failing on its own would never produce.
      if (/\nip=/.test(received)) finish(true);
      if (received.length > 64 * 1024) finish(false);
    });
    socket.on('end', () => finish(/\nip=/.test(received)));
  });
}
