import { describe, expect, it } from 'vitest';
import { foldInstallProgress, macBundlePath, updateModeFor } from '../src/shared/updatePlan';

describe('updateModeFor', () => {
  it('macOS goes through the install script — Squirrel.Mac refuses an unsigned app', () => {
    expect(updateModeFor('darwin', { appImage: false, bundlePath: '/Applications/Aura VPN.app' })).toBe('script');
    expect(updateModeFor('darwin', { appImage: false, bundlePath: '/Users/x/Applications/Aura VPN.app' })).toBe('script');
  });

  it('an app still running from its disk image has nothing on disk to replace', () => {
    expect(updateModeFor('darwin', { appImage: false, bundlePath: '/Volumes/Aura VPN 0.1.24/Aura VPN.app' })).toBe('manual');
    expect(updateModeFor('darwin', { appImage: false, bundlePath: null })).toBe('manual');
  });

  it('Windows and AppImage install in place; other Linux installs are manual', () => {
    expect(updateModeFor('win32', { appImage: false, bundlePath: null })).toBe('install');
    expect(updateModeFor('linux', { appImage: true, bundlePath: null })).toBe('install');
    expect(updateModeFor('linux', { appImage: false, bundlePath: null })).toBe('manual');
  });
});

describe('macBundlePath', () => {
  it('finds the .app around the executable', () => {
    expect(macBundlePath('/Applications/Aura VPN.app/Contents/MacOS/Aura VPN')).toBe('/Applications/Aura VPN.app');
    expect(macBundlePath('/usr/local/bin/electron')).toBeNull();
  });
});

describe('foldInstallProgress', () => {
  it('follows the script from download to install', () => {
    let p = foldInstallProgress('==> [2/5] Downloading Aura-VPN-0.1.25-mac-arm64.dmg...\n', null);
    expect(p).toEqual({ phase: 'download', percent: 0 });

    // curl redraws its meter with \r; several redraws can share one chunk.
    p = foldInstallProgress('\r#####       12.5%\r##########      42.7%', p)!;
    expect(p).toEqual({ phase: 'download', percent: 43 });

    expect(foldInstallProgress('\r##########      42.9%', p)).toBeNull(); // same whole percent: nothing to send

    p = foldInstallProgress('==> [3/5] Mounting disk image...\n', p)!;
    expect(p).toEqual({ phase: 'install' });
    expect(foldInstallProgress('==> [4/5] Installing into /Applications...\n', p)).toBeNull();
  });

  it('ignores output before the download starts (the release lookup)', () => {
    expect(foldInstallProgress('==> [1/5] Looking up the latest release for macOS (arm64)...\n', null)).toBeNull();
  });
});
