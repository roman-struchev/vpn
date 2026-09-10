import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { HTTP_PORT, SOCKS_PORT } from '../../shared/xrayConfigFactory';

const execFileAsync = promisify(execFile);

/**
 * System-proxy MVP (docs/PLAN.md §5): points the OS's HTTP/HTTPS/SOCKS proxy
 * settings at the local xray-core listeners. No TUN driver, no admin
 * rights, nothing that would complicate the unsigned electron-updater flow.
 * Limitation: only apps that honor the OS/desktop-environment proxy setting
 * are covered.
 */
export interface SystemProxyManager {
  enable(): Promise<void>;
  disable(): Promise<void>;
}

export function createSystemProxyManager(): SystemProxyManager {
  switch (process.platform) {
    case 'darwin':
      return new MacSystemProxy();
    case 'win32':
      return new WindowsSystemProxy();
    case 'linux':
      return new LinuxGnomeSystemProxy();
    default:
      return new UnsupportedSystemProxy();
  }
}

class MacSystemProxy implements SystemProxyManager {
  async enable(): Promise<void> {
    for (const service of await this.enabledServices()) {
      await this.run(['-setwebproxy', service, '127.0.0.1', String(HTTP_PORT)]);
      await this.run(['-setsecurewebproxy', service, '127.0.0.1', String(HTTP_PORT)]);
      await this.run(['-setsocksfirewallproxy', service, '127.0.0.1', String(SOCKS_PORT)]);
      await this.run(['-setwebproxystate', service, 'on']);
      await this.run(['-setsecurewebproxystate', service, 'on']);
      await this.run(['-setsocksfirewallproxystate', service, 'on']);
    }
  }

  async disable(): Promise<void> {
    for (const service of await this.enabledServices()) {
      await this.run(['-setwebproxystate', service, 'off']);
      await this.run(['-setsecurewebproxystate', service, 'off']);
      await this.run(['-setsocksfirewallproxystate', service, 'off']);
    }
  }

  private async enabledServices(): Promise<string[]> {
    const { stdout } = await execFileAsync('networksetup', ['-listallnetworkservices']);
    return stdout
      .split('\n')
      .slice(1) // first line is a hint string, not a service
      .map((line) => line.trim())
      .filter((line) => line.length > 0 && !line.startsWith('*')); // '*' prefix = disabled service
  }

  private run(args: string[]): Promise<unknown> {
    return execFileAsync('networksetup', args);
  }
}

class WindowsSystemProxy implements SystemProxyManager {
  private static readonly KEY = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings';

  async enable(): Promise<void> {
    // xray's HTTP inbound (sniffing on) also proxies HTTPS CONNECT, so one
    // ProxyServer entry covers both; ProxyOverride keeps local traffic direct.
    await execFileAsync('reg', ['add', WindowsSystemProxy.KEY, '/v', 'ProxyServer', '/t', 'REG_SZ', '/d', `127.0.0.1:${HTTP_PORT}`, '/f']);
    await execFileAsync('reg', ['add', WindowsSystemProxy.KEY, '/v', 'ProxyOverride', '/t', 'REG_SZ', '/d', '<local>', '/f']);
    await execFileAsync('reg', ['add', WindowsSystemProxy.KEY, '/v', 'ProxyEnable', '/t', 'REG_DWORD', '/d', '1', '/f']);
    await this.broadcastSettingsChange();
  }

  async disable(): Promise<void> {
    await execFileAsync('reg', ['add', WindowsSystemProxy.KEY, '/v', 'ProxyEnable', '/t', 'REG_DWORD', '/d', '0', '/f']);
    await this.broadcastSettingsChange();
  }

  private async broadcastSettingsChange(): Promise<void> {
    // Registry edits alone don't propagate to already-running apps (browsers
    // included) until something re-reads WinINet settings; this well-known
    // rundll32 call forces that re-read without a logoff/restart.
    await execFileAsync('rundll32.exe', ['user32.dll,UpdatePerUserSystemParameters', ',1', ',True']);
  }
}

class LinuxGnomeSystemProxy implements SystemProxyManager {
  async enable(): Promise<void> {
    await this.set(['org.gnome.system.proxy', 'mode', 'manual']);
    await this.set(['org.gnome.system.proxy.http', 'host', '127.0.0.1']);
    await this.set(['org.gnome.system.proxy.http', 'port', String(HTTP_PORT)]);
    await this.set(['org.gnome.system.proxy.https', 'host', '127.0.0.1']);
    await this.set(['org.gnome.system.proxy.https', 'port', String(HTTP_PORT)]);
    await this.set(['org.gnome.system.proxy.socks', 'host', '127.0.0.1']);
    await this.set(['org.gnome.system.proxy.socks', 'port', String(SOCKS_PORT)]);
  }

  async disable(): Promise<void> {
    await this.set(['org.gnome.system.proxy', 'mode', 'none']);
  }

  private async set(args: string[]): Promise<void> {
    try {
      await execFileAsync('gsettings', ['set', ...args]);
    } catch {
      // Non-GNOME desktop (KDE, sway, ...): nothing standard to fall back to
      // here. The local proxy still runs at 127.0.0.1; apps that can be
      // pointed at a proxy manually (browsers, curl, etc.) still work.
    }
  }
}

class UnsupportedSystemProxy implements SystemProxyManager {
  async enable(): Promise<void> {
    console.warn(`System proxy auto-configuration is not implemented for ${process.platform}.`);
  }
  async disable(): Promise<void> {
    /* no-op */
  }
}
