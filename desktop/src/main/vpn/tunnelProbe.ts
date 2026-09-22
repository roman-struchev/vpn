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
 * end counts — one from Cloudflare itself, addressed by IP so a broken DNS
 * path cannot fail the probe on its own.
 *
 * Over plain HTTP 1.1.1.1 answers with a 301 to HTTPS rather than the trace
 * body, so the body cannot be required: Cloudflare's CF-RAY header is what
 * proves the answer came from the far end. (The first version waited for the
 * body's "ip=" line and never passed against the real endpoint — caught by
 * test/realTunnel.integration.test.ts.)
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
      if (answeredByFarEnd(received)) finish(true);
      if (received.length > 64 * 1024) finish(false);
    });
    socket.on('end', () => finish(answeredByFarEnd(received)));
  });
}

/**
 * A response only Cloudflare could have produced: its CF-RAY header (on the
 * redirect and on the trace page alike), or the trace body's "ip=" line. A
 * local proxy failing on its own produces neither.
 */
function answeredByFarEnd(received: string): boolean {
  if (!received.startsWith('HTTP/1.')) return false;
  return /\r\ncf-ray:/i.test(received) || /\nip=/.test(received);
}
