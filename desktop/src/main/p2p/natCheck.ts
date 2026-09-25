import dgram from 'node:dgram';
import { randomBytes } from 'node:crypto';

/**
 * Whether this network can take incoming P2P sessions at all.
 *
 * Relaying works by both sides learning their public address from a STUN
 * server and sending to each other's (docs §8.1: STUN only, no TURN). That
 * only works when the NAT keeps one public port per local socket whatever the
 * destination. A symmetric NAT — most mobile carriers — hands out a new port
 * per destination, so the address STUN reports is useless to the peer and
 * every session fails after the client has waited it out. Seen live: a phone
 * relaying over LTE was listed as a peer and nobody could connect through it.
 *
 * The test: one socket asks two different STUN servers what they see. Two
 * different answers mean symmetric; no answer at all means UDP is blocked.
 * One answer can't tell, and is let through rather than refused on a guess.
 */
export type NatVerdict = 'OK' | 'SYMMETRIC' | 'NO_UDP' | 'UNKNOWN';

export interface StunServer {
  host: string;
  port: number;
}

// Different operators on purpose: a symmetric NAT is told apart by
// destination, so the two must not be the same address.
export const DEFAULT_STUN_SERVERS: StunServer[] = [
  { host: 'stun.l.google.com', port: 19302 },
  { host: 'stun.cloudflare.com', port: 3478 },
];

const MAGIC_COOKIE = 0x2112a442;

export async function checkNat(servers: StunServer[] = DEFAULT_STUN_SERVERS, timeoutMs = 3000): Promise<NatVerdict> {
  const socket = dgram.createSocket('udp4');
  try {
    await new Promise<void>((resolve, reject) => {
      socket.once('error', reject);
      socket.bind(0, () => resolve());
    });
    const answers = await Promise.all(servers.map((s) => askMappedAddress(socket, s, timeoutMs)));
    const seen = answers.filter((a): a is string => a !== null);
    if (seen.length === 0) return 'NO_UDP';
    if (seen.length === 1) return 'UNKNOWN';
    return new Set(seen).size === 1 ? 'OK' : 'SYMMETRIC';
  } finally {
    socket.close();
  }
}

function askMappedAddress(socket: dgram.Socket, server: StunServer, timeoutMs: number): Promise<string | null> {
  const transactionId = randomBytes(12);
  const request = Buffer.alloc(20);
  request.writeUInt16BE(0x0001, 0); // Binding Request
  request.writeUInt16BE(0, 2);
  request.writeUInt32BE(MAGIC_COOKIE, 4);
  transactionId.copy(request, 8);

  return new Promise((resolve) => {
    const onMessage = (msg: Buffer) => {
      if (msg.length < 20 || !msg.subarray(8, 20).equals(transactionId)) return;
      done(parseMappedAddress(msg));
    };
    const timer = setTimeout(() => done(null), timeoutMs);
    const done = (value: string | null) => {
      clearTimeout(timer);
      socket.off('message', onMessage);
      resolve(value);
    };
    socket.on('message', onMessage);
    socket.send(request, server.port, server.host, (err) => {
      if (err) done(null);
    });
  });
}

/** "ip:port" from XOR-MAPPED-ADDRESS (or the legacy MAPPED-ADDRESS), IPv4 only. */
export function parseMappedAddress(msg: Buffer): string | null {
  let offset = 20;
  let legacy: string | null = null;
  while (offset + 4 <= msg.length) {
    const type = msg.readUInt16BE(offset);
    const length = msg.readUInt16BE(offset + 2);
    const value = msg.subarray(offset + 4, offset + 4 + length);
    if (value.length >= 8 && value[1] === 0x01) {
      if (type === 0x0020) {
        const port = value.readUInt16BE(2) ^ (MAGIC_COOKIE >>> 16);
        const ip = [0, 1, 2, 3].map((i) => value[4 + i] ^ ((MAGIC_COOKIE >>> (24 - 8 * i)) & 0xff)).join('.');
        return `${ip}:${port}`;
      }
      if (type === 0x0001) {
        legacy = `${[4, 5, 6, 7].map((i) => value[i]).join('.')}:${value.readUInt16BE(2)}`;
      }
    }
    offset += 4 + length + ((4 - (length % 4)) % 4);
  }
  return legacy;
}
