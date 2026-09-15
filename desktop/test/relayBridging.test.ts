import net from 'node:net';
import { PeerConnection } from 'node-datachannel';
import { afterEach, describe, expect, it } from 'vitest';

/**
 * Integration test for the actual DataChannel<->TCP bridging mechanism
 * RelayAgent implements (desktop/src/main/p2p/relayAgent.ts) — two real
 * node-datachannel PeerConnections on localhost (no STUN/NAT involved, since
 * genuine cross-network reachability cannot be verified in this environment
 * — see the class's own doc comment on the accepted STUN-only design), one
 * playing the "connecting client" role and one playing the exact "relay
 * node" role RelayAgent plays: on DataChannel open, bridge bytes to/from a
 * TCP socket. Confirms the mechanism this session already validated with
 * node-datachannel in a two-isolated-Docker-networks + public-STUN-server
 * setup (real DataChannel established, real bytes exchanged) also holds once
 * a TCP socket is spliced onto one end of it, which is the part actually
 * exercised here that the earlier Docker check didn't cover.
 */
describe('relay DataChannel <-> TCP bridging', () => {
  let echoServer: net.Server;
  let echoPort: number;
  const peers: PeerConnection[] = [];
  // net.Server (unlike http.Server) has no closeAllConnections() — its
  // close()'s callback otherwise waits for every still-open connection to
  // end on its own, and the bridged sockets here are intentionally never
  // explicitly closed by the bridging code itself (same as RelayAgent, which
  // relies on the DataChannel/socket 'close'/'error' events for that), so
  // every socket this test opens (both ends) is tracked here and destroyed
  // directly in afterEach instead of leaking a hung hook every run.
  const openSockets: net.Socket[] = [];

  afterEach(async () => {
    for (const p of peers) p.close();
    peers.length = 0;
    for (const s of openSockets) s.destroy();
    openSockets.length = 0;
    await new Promise<void>((resolve) => (echoServer ? echoServer.close(() => resolve()) : resolve()));
  });

  async function startEchoServer(): Promise<number> {
    return new Promise((resolve) => {
      echoServer = net.createServer((socket) => {
        openSockets.push(socket);
        socket.pipe(socket);
      });
      echoServer.listen(0, '127.0.0.1', () => resolve((echoServer.address() as net.AddressInfo).port));
    });
  }

  it('relays bytes from a DataChannel, through a TCP echo server, and back', async () => {
    echoPort = await startEchoServer();

    const offerer = new PeerConnection('client', { iceServers: [] });
    const answerer = new PeerConnection('relay-node', { iceServers: [] });
    peers.push(offerer, answerer);

    // Wire signaling directly between the two (in production this goes
    // through the server's gRPC p2p_signal passthrough — see
    // AgentStreamServiceImpl.sendSignalToNodeAndAwaitReply on the server and
    // RelayAgent#sendSignal/#handleIncomingSignal here; skipped for this test
    // since only the DataChannel<->TCP splice, not the signaling transport
    // itself, is what this test targets).
    offerer.onLocalDescription((sdp, type) => answerer.setRemoteDescription(sdp, type));
    offerer.onLocalCandidate((candidate, mid) => answerer.addRemoteCandidate(candidate, mid));
    answerer.onLocalDescription((sdp, type) => offerer.setRemoteDescription(sdp, type));
    answerer.onLocalCandidate((candidate, mid) => offerer.addRemoteCandidate(candidate, mid));

    // This is the exact pattern RelayAgent#wireDataChannelHandlers implements:
    // on receiving a DataChannel, open a TCP socket to the (ACL-checked, in
    // the real class) target and bridge bytes both ways.
    const bridgeReady = new Promise<void>((resolve) => {
      answerer.onDataChannel((dc) => {
        const socket = net.createConnection({ host: '127.0.0.1', port: echoPort }, () => {
          openSockets.push(socket);
          socket.on('data', (chunk) => dc.sendMessageBinary(chunk));
          dc.onMessage((msg) => socket.write(Buffer.isBuffer(msg) ? msg : Buffer.from(msg)));
          resolve();
        });
      });
    });

    const dc = offerer.createDataChannel('test');
    const echoedBack = new Promise<Buffer>((resolve) => {
      dc.onMessage((msg) => resolve(Buffer.isBuffer(msg) ? msg : Buffer.from(msg)));
    });
    const opened = new Promise<void>((resolve) => dc.onOpen(resolve));

    await opened;
    await bridgeReady;
    dc.sendMessageBinary(Buffer.from('hello through the relay'));

    const echoed = await echoedBack;
    expect(echoed.toString('utf-8')).toBe('hello through the relay');
  }, 15000);
});
