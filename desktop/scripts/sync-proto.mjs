#!/usr/bin/env node
// Copies proto/vpn/agent/v1/agent.proto into resources/proto/ so it can ship
// as an electron-builder extraResource (see package.json "build.extraResources")
// the same way resources/bin/ ships the xray-core binary. Unlike the xray
// binary, this file is tiny checked-in text, not a fetched artifact — this
// script just keeps the bundled copy in sync with the real source of truth
// at proto/vpn/agent/v1/agent.proto (repo root) whenever that file changes.
// Run manually after editing the proto, or as part of `npm run build`.

import { copyFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SOURCE = path.join(__dirname, '..', '..', 'proto', 'vpn', 'agent', 'v1', 'agent.proto');
const DEST_DIR = path.join(__dirname, '..', 'resources', 'proto');
const DEST = path.join(DEST_DIR, 'agent.proto');

mkdirSync(DEST_DIR, { recursive: true });
copyFileSync(SOURCE, DEST);
console.log(`Synced ${SOURCE} -> ${DEST}`);
