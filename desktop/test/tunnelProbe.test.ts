import net from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { probeThroughHttpProxy } from '../src/main/vpn/tunnelProbe';

/** A stand-in for xray's local HTTP inbound, answering however the test says. */
function fakeProxy(answer: (socket: net.Socket, request: string) => void): Promise<{ port: number; server: net.Server }> {
  return new Promise((resolve) => {
    const server = net.createServer((socket) => {
      socket.on('error', () => undefined); // the probe hangs up as soon as it has its answer
      socket.once('data', (chunk) => answer(socket, chunk.toString()));
    });
    server.listen(0, '127.0.0.1', () => resolve({ port: (server.address() as net.AddressInfo).port, server }));
  });
}

describe('probeThroughHttpProxy', () => {
  let server: net.Server | null = null;
  afterEach(() => server?.close());

  it('passes only on an answer from the far end', async () => {
    let seen = '';
    const proxy = await fakeProxy((socket, request) => {
      seen = request;
      socket.end('HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nfl=1\nh=1.1.1.1\nip=203.0.113.5\nts=1\n');
    });
    server = proxy.server;
    expect(await probeThroughHttpProxy(proxy.port, 2000)).toBe(true);
    expect(seen).toMatch(/^GET http:\/\/1\.1\.1\.1\/cdn-cgi\/trace HTTP\/1\.1/);
  });

  it("passes on Cloudflare's redirect, which is what plain HTTP to 1.1.1.1 really gets", async () => {
    const proxy = await fakeProxy((socket) =>
      socket.end(
        'HTTP/1.1 301 Moved Permanently\r\nServer: cloudflare\r\nLocation: https://1.1.1.1/cdn-cgi/trace\r\n' +
          'CF-RAY: a3f42da238fc55ad-BEG\r\n\r\n<html></html>'
      )
    );
    server = proxy.server;
    expect(await probeThroughHttpProxy(proxy.port, 2000)).toBe(true);
  });

  it('fails when the proxy accepts but the tunnel behind it goes nowhere', async () => {
    const proxy = await fakeProxy((socket) => socket.end());
    server = proxy.server;
    expect(await probeThroughHttpProxy(proxy.port, 2000)).toBe(false);
  });

  it('fails on an error page the proxy produced itself', async () => {
    const proxy = await fakeProxy((socket) => socket.end('HTTP/1.1 503 Service Unavailable\r\n\r\n'));
    server = proxy.server;
    expect(await probeThroughHttpProxy(proxy.port, 2000)).toBe(false);
  });

  it('fails on silence, within the timeout', async () => {
    const proxy = await fakeProxy(() => undefined);
    server = proxy.server;
    const started = Date.now();
    expect(await probeThroughHttpProxy(proxy.port, 300)).toBe(false);
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it('fails when nothing listens at all', async () => {
    expect(await probeThroughHttpProxy(1, 1000)).toBe(false);
  });
});
