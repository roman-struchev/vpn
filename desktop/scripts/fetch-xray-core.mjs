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
// Must stay wire-compatible with the Xray-core installed on server nodes
// (agent/Dockerfile, v26.x — which still accepts this older client; the
// reverse, a newer client against an older node, is what breaks REALITY). Newer
// Xray-core releases (e.g. v26.x) made "password" mandatory on outbound
// REALITY stream settings for XHTTP/gRPC, which nothing in this repo's
// config/URL/proto pipeline populates, so bumping this ahead of the node
// version breaks every connection with "empty \"password\"".
const VERSION = process.env.XRAY_CORE_VERSION || 'v24.11.30';
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

  // --fail: error out on a non-2xx response instead of silently writing the
  // error page's body to zipPath (which then surfaces as a confusing
  // "tar: This does not look like a tar archive" far from the real cause).
  execFileSync(
    'curl',
    ['-sL', '--fail', '--retry', '3', '--retry-delay', '2', '-o', zipPath, url],
    { stdio: 'inherit' },
  );
  if (process.platform === 'linux') {
    // GNU tar (the default `tar` on Ubuntu/most Linux distros, incl. GitHub-
    // hosted runners) cannot extract the PKZIP format at all — it tries to
    // read the zip's local file header as a tar header and fails with
    // "This does not look like a tar archive". `unzip` handles it correctly
    // and ships preinstalled on ubuntu-latest.
    execFileSync('unzip', ['-oq', zipPath, '-d', destDir], { stdio: 'inherit' });
  } else {
    // macOS's `tar` and Windows 10 1803+'s built-in `tar.exe` are both
    // bsdtar, which extracts zip directly.
    execFileSync('tar', ['-xf', zipPath, '-C', destDir], { stdio: 'inherit' });
  }
  rmSync(zipPath);

  const binaryPath = path.join(destDir, binaryName);
  if (process.platform !== 'win32' && existsSync(binaryPath)) {
    execFileSync('chmod', ['+x', binaryPath]);
  }
  console.log(`[${key}] ready: resources/bin/${key}/${binaryName}`);
}
