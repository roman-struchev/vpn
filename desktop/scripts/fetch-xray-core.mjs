#!/usr/bin/env node
// Downloads prebuilt XTLS/Xray-core binaries into resources/bin/<os>-<arch>/,
// matching electron-builder's ${os}-${arch} extraResources macro (see
// package.json "build.extraResources"). Not run automatically by npm install
// — the binaries are large (~20-35MB each) and are intentionally not
// committed to git (see .gitignore). Run this once before `npm run dev` or
// packaging.
//
// See docs/PLAN.md §5: "официальный бинарь xray из релизов, запускается
// дочерним процессом — так устроены v2rayN, NekoRay, Hiddify Desktop".

import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RESOURCES_BIN = path.join(__dirname, '..', 'resources', 'bin');
const VERSION = process.env.XRAY_CORE_VERSION || 'v26.3.27';
const REPO = 'XTLS/Xray-core';

// electron-builder ${os}-${arch} key -> Xray-core release asset name.
const TARGETS = {
  'mac-arm64': 'Xray-macos-arm64-v8a.zip',
  'mac-x64': 'Xray-macos-64.zip',
  'win-x64': 'Xray-windows-64.zip',
  'win-arm64': 'Xray-windows-arm64-v8a.zip',
  'linux-x64': 'Xray-linux-64.zip',
  'linux-arm64': 'Xray-linux-arm64-v8a.zip',
};

function currentTargetKey() {
  const os = { darwin: 'mac', win32: 'win', linux: 'linux' }[process.platform];
  const arch = { arm64: 'arm64', x64: 'x64' }[process.arch];
  if (!os || !arch) {
    throw new Error(`Unsupported platform/arch: ${process.platform}/${process.arch}`);
  }
  return `${os}-${arch}`;
}

const requested = process.argv[2]; // e.g. "mac-arm64", or "all"
const keys = requested === 'all' ? Object.keys(TARGETS) : [requested || currentTargetKey()];

for (const key of keys) {
  const asset = TARGETS[key];
  if (!asset) {
    console.error(`Unknown target "${key}". Valid: ${Object.keys(TARGETS).join(', ')}, or "all".`);
    process.exit(1);
  }
  fetchOne(key, asset);
}

function fetchOne(key, asset) {
  const destDir = path.join(RESOURCES_BIN, key);
  const binaryName = key.startsWith('win-') ? 'xray.exe' : 'xray';
  if (existsSync(path.join(destDir, binaryName))) {
    console.log(`[${key}] already present, skipping (delete resources/bin/${key} to refetch)`);
    return;
  }

  console.log(`[${key}] downloading ${asset} @ ${VERSION} ...`);
  mkdirSync(destDir, { recursive: true });
  const zipPath = path.join(destDir, asset);
  const url = `https://github.com/${REPO}/releases/download/${VERSION}/${asset}`;

  execFileSync('curl', ['-sL', '-o', zipPath, url], { stdio: 'inherit' });
  // `tar` extracts zip on macOS/Linux (bsdtar) and on Windows 10 1803+ (built-in bsdtar).
  execFileSync('tar', ['-xf', zipPath, '-C', destDir], { stdio: 'inherit' });
  rmSync(zipPath);

  const binaryPath = path.join(destDir, binaryName);
  if (process.platform !== 'win32' && existsSync(binaryPath)) {
    execFileSync('chmod', ['+x', binaryPath]);
  }
  console.log(`[${key}] ready: resources/bin/${key}/${binaryName}`);
}
