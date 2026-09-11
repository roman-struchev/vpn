// Generates the menu-bar/system-tray icon PNGs used by src/main/tray.ts.
//
// There is no existing app icon/branding asset anywhere in desktop/ to derive
// tray icons from (checked resources/ and build/ — neither has one), so this
// draws a small padlock glyph procedurally (rounded-rect body + ring
// "shackle" + keyhole cutout, box-filtered supersampling for anti-aliasing)
// and rasterizes it straight to PNG with a hand-rolled encoder (no image
// library is a dependency of this project, and pulling one in just for a
// handful of static icons isn't worth it).
//
// One shape, four colors — same convention the renderer already uses for
// connection-state color-coding (design-tokens/tokens.mjs `state.*`):
//   disconnected -> template (macOS/Windows tint it automatically)
//   connecting/reconnecting -> amber (#f59e0b)
//   connected -> green (#22c55e)
//   error/operator-blocked -> red (#ef4444)
//
// Run with: node scripts/generate-tray-icons.mjs
// Output: resources/tray/tray-<state>.png + tray-<state>@2x.png (16x16 / 32x32).
// Re-run this after changing the shape or palette below; the PNGs are
// checked in like resources/bin/**, not generated at build time.

import { createWriteStream, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(__dirname, '..', 'resources', 'tray');
mkdirSync(OUT_DIR, { recursive: true });

// Design grid: shape is authored in a 32x32 logical unit space (== the @2x
// pixel grid at 1 unit/px); the @1x (16x16) render just uses 2 logical
// units/px. Supersampled at SS x SS per output pixel for anti-aliased edges.
const SS = 8;

function roundedRectContains(x, y, x0, y0, x1, y1, r) {
  if (x < x0 || x > x1 || y < y0 || y > y1) return false;
  const inCornerX = x < x0 + r ? -1 : x > x1 - r ? 1 : 0;
  const inCornerY = y < y0 + r ? -1 : y > y1 - r ? 1 : 0;
  if (inCornerX === 0 || inCornerY === 0) return true;
  const cx = inCornerX < 0 ? x0 + r : x1 - r;
  const cy = inCornerY < 0 ? y0 + r : y1 - r;
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
}

/** Padlock glyph: rounded body + a ring "shackle" arch on top, keyhole cut out of the body. */
function insideLock(x, y) {
  const body = roundedRectContains(x, y, 8, 15, 24, 27, 2.5);

  const dx = x - 16;
  const dy = y - 15;
  const dist = Math.sqrt(dx * dx + dy * dy);
  const shackle = dist >= 4.5 && dist <= 7.5 && y <= 15.2;

  const keyholeCircle = (x - 16) ** 2 + (y - 19.5) ** 2 <= 1.3 * 1.3;
  const keyholeSlit = x >= 15.3 && x <= 16.7 && y >= 19.5 && y <= 23;
  const keyhole = keyholeCircle || keyholeSlit;

  return (body || shackle) && !keyhole;
}

function renderAlpha(sizePx) {
  const unitsPerPx = 32 / sizePx;
  const alpha = new Uint8Array(sizePx * sizePx);
  for (let py = 0; py < sizePx; py++) {
    for (let px = 0; px < sizePx; px++) {
      let hits = 0;
      for (let sy = 0; sy < SS; sy++) {
        const ly = (py + (sy + 0.5) / SS) * unitsPerPx;
        for (let sx = 0; sx < SS; sx++) {
          const lx = (px + (sx + 0.5) / SS) * unitsPerPx;
          if (insideLock(lx, ly)) hits++;
        }
      }
      alpha[py * sizePx + px] = Math.round((hits / (SS * SS)) * 255);
    }
  }
  return alpha;
}

function crc32(buf) {
  return zlib.crc32(buf);
}

function chunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const lenBuf = Buffer.alloc(4);
  lenBuf.writeUInt32BE(data.length, 0);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])) >>> 0, 0);
  return Buffer.concat([lenBuf, typeBuf, data, crcBuf]);
}

/** Minimal PNG encoder: 8-bit RGBA, filter type 0 (none) per scanline. */
function encodePng(width, height, rgba) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8; // bit depth
  ihdrData[9] = 6; // color type: RGBA
  ihdrData[10] = 0;
  ihdrData[11] = 0;
  ihdrData[12] = 0;
  const ihdr = chunk('IHDR', ihdrData);

  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, y * stride + stride);
  }
  const idat = chunk('IDAT', zlib.deflateSync(raw));

  const iend = chunk('IEND', Buffer.alloc(0));

  return Buffer.concat([sig, ihdr, idat, iend]);
}

/** color = null means "template" (macOS auto-tint): black RGB, real alpha channel. */
function buildRgba(sizePx, color) {
  const alpha = renderAlpha(sizePx);
  const [r, g, b] = color ?? [0, 0, 0];
  const rgba = Buffer.alloc(sizePx * sizePx * 4);
  for (let i = 0; i < sizePx * sizePx; i++) {
    rgba[i * 4] = r;
    rgba[i * 4 + 1] = g;
    rgba[i * 4 + 2] = b;
    rgba[i * 4 + 3] = alpha[i];
  }
  return rgba;
}

function hex(h) {
  const n = parseInt(h.replace('#', ''), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// Keep in sync with design-tokens/tokens.mjs `state.*` — same colors the
// renderer uses for connection-state text (ConnectPage.tsx STATE_COLOR).
const VARIANTS = {
  disconnected: null, // template image: macOS/Windows-dark-mode tint it automatically
  connecting: hex('#f59e0b'),
  reconnecting: hex('#f59e0b'), // same glyph/color as connecting; distinct name for clarity at call sites
  connected: hex('#22c55e'),
  error: hex('#ef4444'),
  blocked: hex('#ef4444'), // OPERATOR_BLOCKED shares the "error" red — both mean "not protected, needs attention"
};

for (const [name, color] of Object.entries(VARIANTS)) {
  for (const [suffix, size] of [['', 16], ['@2x', 32]]) {
    const rgba = buildRgba(size, color);
    const png = encodePng(size, size, rgba);
    const file = path.join(OUT_DIR, `tray-${name}${suffix}.png`);
    createWriteStream(file).end(png);
    console.log(`wrote ${file} (${size}x${size}, ${color ? 'color' : 'template'})`);
  }
}
