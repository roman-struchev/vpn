import { app } from 'electron';
import { existsSync } from 'node:fs';
import path from 'node:path';

/**
 * Resolves the local xray binary path. Packaged builds get it from
 * extraResources (package.json "build.extraResources", populated from
 * resources/bin/<os>-<arch>/ at build time by scripts/fetch-xray-core.mjs).
 * In dev the same directory is read straight from the repo.
 */
export function getXrayBinaryPath(): string {
  const binaryName = process.platform === 'win32' ? 'xray.exe' : 'xray';

  const candidate = app.isPackaged
    ? path.join(process.resourcesPath, 'bin', binaryName)
    : path.join(app.getAppPath(), 'resources', 'bin', devTargetKey(), binaryName);

  if (!existsSync(candidate)) {
    throw new Error(
      `xray binary not found at ${candidate}. Run "npm run fetch:xray" first (see desktop/README.md).`
    );
  }
  return candidate;
}

export function getGeoDataDir(): string {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'bin')
    : path.join(app.getAppPath(), 'resources', 'bin', devTargetKey());
}

function devTargetKey(): string {
  const osMap: Partial<Record<NodeJS.Platform, string>> = { darwin: 'mac', win32: 'win', linux: 'linux' };
  const archMap: Partial<Record<string, string>> = { arm64: 'arm64', x64: 'x64' };
  const os = osMap[process.platform];
  const arch = archMap[process.arch];
  if (!os || !arch) {
    throw new Error(`Unsupported platform/arch: ${process.platform}/${process.arch}`);
  }
  return `${os}-${arch}`;
}
