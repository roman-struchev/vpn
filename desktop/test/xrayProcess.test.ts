import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let dir: string;
vi.mock('electron', () => ({ app: { getPath: () => dir, isPackaged: false } }));
// A stand-in xray: a shell script that ignores its arguments and just runs.
vi.mock('../src/main/xray/binaryManager', () => ({
  getXrayBinaryPath: () => path.join(dir, 'fake-xray.sh'),
  getGeoDataDir: () => dir,
}));

describe.skipIf(process.platform === 'win32')('XrayProcess', () => {
  beforeEach(() => {
    dir = mkdtempSync(path.join(os.tmpdir(), 'xray-process-test-'));
    const script = path.join(dir, 'fake-xray.sh');
    writeFileSync(script, '#!/bin/sh\nexec sleep 30\n');
    chmodSync(script, 0o755);
  });
  afterEach(() => {
    rmSync(dir, { recursive: true, force: true });
    vi.resetModules();
  });

  it('stop() resolves only once the process has exited', async () => {
    const { XrayProcess } = await import('../src/main/xray/xrayProcess');
    const proc = new XrayProcess();
    proc.start({}, () => undefined);
    expect(proc.isRunning()).toBe(true);
    await proc.stop();
    expect(proc.isRunning()).toBe(false);
    // A replacement can start straight away.
    proc.start({}, () => undefined);
    expect(proc.isRunning()).toBe(true);
    await proc.stop();
  });

  it("a stopped process's late exit neither orphans nor fails its replacement", async () => {
    const { XrayProcess } = await import('../src/main/xray/xrayProcess');
    const proc = new XrayProcess();
    const firstExit = vi.fn();
    const secondExit = vi.fn();
    proc.start({}, firstExit);
    const stopping = proc.stop(); // not awaited: the old process is still exiting
    proc.start({}, secondExit);
    await stopping;
    await new Promise((r) => setTimeout(r, 50));

    expect(proc.isRunning()).toBe(true);
    expect(firstExit).not.toHaveBeenCalled();
    expect(secondExit).not.toHaveBeenCalled();
    await proc.stop();
  });

  it('reports a crash of the current process', async () => {
    const { XrayProcess } = await import('../src/main/xray/xrayProcess');
    writeFileSync(path.join(dir, 'fake-xray.sh'), '#!/bin/sh\nexit 3\n');
    const proc = new XrayProcess();
    const onExit = vi.fn();
    proc.start({}, onExit);
    await vi.waitFor(() => expect(onExit).toHaveBeenCalledWith(3, null), { timeout: 5000 });
    expect(proc.isRunning()).toBe(false);
  });
});
