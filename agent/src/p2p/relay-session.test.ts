import { describe, it, expect, afterEach } from 'vitest';
import net from 'net';
import nodeDataChannel, { type PeerConnection } from 'node-datachannel';
import { RelaySession, SignalEnvelope } from './relay-session.js';

// Exercises the FULL bridging pipeline (real WebRTC negotiation + a real TCP
// socket) end to end, on localhost — the one thing this cannot validate is
// genuine cross-NAT reachability (see docker-based validation done in the
// parent session; not reproducible inside a unit test). What it DOES prove:
// signaling/offer-answer/ICE trickle glue is correct, the DataChannel opens,
// and bytes flowing in both directions get bridged to/from the TCP socket
// with the byte counter incrementing correctly.
//
// The relay node's destination-ACL check (dest-acl.ts, tested exhaustively
// and separately in dest-acl.test.ts) would legitimately reject every
// locally-bindable test address (127.0.0.0/8 included) — DI seams on
// RelaySession (an injectable `lookup` and `connect`) let this test stub
// just the ACL's *verdict* while still exercising the real TCP<->DataChannel
// forwarding against a real local echo server, without weakening production
// behavior (both seams default to the real dns.lookup / net.createConnection
// when omitted).
describe('RelaySession (integration: real WebRTC + real TCP)', () => {
  let echoServer: net.Server;
  let session: RelaySession | null = null;
  let offerer: PeerConnection | null = null;

  afterEach(async () => {
    session?.close();
    offerer?.close();
    await new Promise<void>((resolve) => echoServer?.close(() => resolve()));
  });

  it('bridges bytes bidirectionally between the DataChannel and a TCP echo server', async () => {
    echoServer = net.createServer((socket) => socket.pipe(socket)); // echoes back whatever it receives
    const echoPort = await new Promise<number>((resolve) => {
      echoServer.listen(0, '127.0.0.1', () => resolve((echoServer.address() as net.AddressInfo).port));
    });

    const sentEnvelopes: SignalEnvelope[] = [];
    session = new RelaySession(
      'test-session-1',
      (envelope) => {
        sentEnvelopes.push(envelope);
        if (envelope.kind === 'answer') offerer!.setRemoteDescription(envelope.sdp!, 'answer');
        else if (envelope.kind === 'ice') offerer!.addRemoteCandidate(envelope.candidate!, envelope.sdpMid || '0');
      },
      () => {},
      undefined,
      // Stub ACL verdict only — the real check is covered in dest-acl.test.ts.
      async () => [{ address: '203.0.113.7', family: 4 }],
      // Stub the TCP leg's *destination* to the real local echo server,
      // regardless of the (fake, ACL-passing) targetIp/targetPort above.
      () => net.createConnection({ host: '127.0.0.1', port: echoPort })
    );

    offerer = new nodeDataChannel.PeerConnection('test-offerer', { iceServers: [] });
    // Handlers must be registered BEFORE createDataChannel() triggers
    // negotiation below — node-datachannel can emit the local description/
    // candidates before a callback attached afterwards would ever see them.
    offerer.onLocalDescription((sdp, type) => {
      if (type === 'offer') {
        session!.handleSignal({ kind: 'offer', sdp, targetHost: 'echo.example', targetPort: 12345 });
      }
    });
    offerer.onLocalCandidate((candidate, mid) => {
      session!.handleSignal({ kind: 'ice', candidate, sdpMid: mid });
    });

    const dc = offerer.createDataChannel('test');

    const received: Buffer[] = [];
    const gotEcho = new Promise<void>((resolve) => {
      dc.onMessage((msg) => {
        received.push(Buffer.isBuffer(msg) ? msg : Buffer.from(msg as string));
        resolve();
      });
    });

    await new Promise<void>((resolve) => {
      dc.onOpen(() => resolve());
      // Give the offer a moment to be generated (onLocalDescription above
      // fires asynchronously once createDataChannel triggers negotiation).
    });

    dc.sendMessageBinary(Buffer.from('hello p2p relay'));
    await Promise.race([
      gotEcho,
      new Promise((_, reject) => setTimeout(() => reject(new Error('timed out waiting for echo')), 5000)),
    ]);

    expect(Buffer.concat(received).toString()).toBe('hello p2p relay');
    expect(sentEnvelopes.some((e) => e.kind === 'answer')).toBe(true);
  }, 10000);

  it('closes the session without opening a TCP connection when the offer targets an ACL-blocked destination', async () => {
    let connectCalled = false;
    session = new RelaySession(
      'test-session-2',
      () => {},
      () => {},
      undefined,
      async () => [{ address: '192.168.1.1', family: 4 }], // private — must be rejected
      () => {
        connectCalled = true;
        return net.createConnection({ host: '127.0.0.1', port: 1 });
      }
    );

    await session.handleSignal({ kind: 'offer', sdp: 'v=0', targetHost: 'internal.example', targetPort: 80 });

    expect(connectCalled).toBe(false);
  });
});
