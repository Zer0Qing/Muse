/**
 * Rasterise public/icon.svg into the PNG sizes iOS/Android need for
 * add-to-home-screen. Pure Node (no dependencies): the shapes are redrawn with
 * 4x4 supersampling and encoded with a minimal RGBA PNG writer (node:zlib).
 *
 * Usage: npm --workspace @muse/client run icons
 * Output: public/icon-192.png, public/icon-512.png, public/apple-touch-icon.png
 *
 * Keep in sync with public/icon.svg if the mark changes.
 */
import { deflateSync } from "node:zlib";
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const PUBLIC_DIR = join(dirname(fileURLToPath(import.meta.url)), "..", "public");
const DESIGN = 192; // icon.svg viewBox
const ACCENT = [0x2a, 0x7a, 0x55];
const PAPER = [0xfa, 0xfa, 0xf8];
const SAMPLES = 4; // per axis

/* ── shapes (design space, matching public/icon.svg) ─────────────────── */

const BUBBLE = { x0: 48, y0: 44, x1: 144, y1: 135, radius: 18 };
const TAIL = [
  { x: 91, y: 135 },
  { x: 66, y: 155 },
  { x: 66, y: 135 },
];
const DOTS = [
  { x: 82, y: 89, radius: 8 },
  { x: 110, y: 89, radius: 8 },
];
const FACES = { minX: 74, maxX: 118, minY: 108, maxY: 122 };
const SMILE = (() => {
  const [p0, p1, p2, p3] = [
    { x: 77, y: 111 },
    { x: 88, y: 119 },
    { x: 104, y: 119 },
    { x: 115, y: 111 },
  ];
  const points = [];
  for (let index = 0; index <= 240; index += 1) {
    const t = index / 240;
    const u = 1 - t;
    points.push({
      x: u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
      y: u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y,
    });
  }
  return points;
})();

function insideRoundRect(x, y, rect, radius) {
  if (x < rect.x0 || x > rect.x1 || y < rect.y0 || y > rect.y1) return false;
  const cx = Math.min(Math.max(x, rect.x0 + radius), rect.x1 - radius);
  const cy = Math.min(Math.max(y, rect.y0 + radius), rect.y1 - radius);
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= radius * radius + 1e-9;
}

function edgeSign(px, py, a, b) {
  return (px - b.x) * (a.y - b.y) - (a.x - b.x) * (py - b.y);
}

function insideTriangle(px, py, triangle) {
  const [a, b, c] = triangle;
  const d1 = edgeSign(px, py, a, b);
  const d2 = edgeSign(px, py, b, c);
  const d3 = edgeSign(px, py, c, a);
  const negative = d1 < 0 || d2 < 0 || d3 < 0;
  const positive = d1 > 0 || d2 > 0 || d3 > 0;
  return !(negative && positive);
}

function onStroke(x, y, strokeWidth) {
  if (x < FACES.minX || x > FACES.maxX || y < FACES.minY || y > FACES.maxY) return false;
  const limit = (strokeWidth / 2) ** 2;
  for (const point of SMILE) {
    const dx = x - point.x;
    const dy = y - point.y;
    if (dx * dx + dy * dy <= limit) return true;
  }
  return false;
}

/** Premultiplied-free colour at a design-space point, or null when transparent. */
function colorAt(x, y, canvasRadius) {
  if (!insideRoundRect(x, y, { x0: 0, y0: 0, x1: DESIGN, y1: DESIGN }, canvasRadius)) return null;
  const inBubble = insideRoundRect(x, y, BUBBLE, BUBBLE.radius) || insideTriangle(x, y, TAIL);
  if (!inBubble) return ACCENT;
  for (const dot of DOTS) {
    const dx = x - dot.x;
    const dy = y - dot.y;
    if (dx * dx + dy * dy <= dot.radius * dot.radius) return ACCENT;
  }
  if (onStroke(x, y, 6)) return ACCENT;
  return PAPER;
}

/* ── PNG encoding ────────────────────────────────────────────────────── */

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let index = 0; index < 256; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
    table[index] = value >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([length, body, crc]);
}

function encodePng(size, rgba) {
  const stride = size * 4 + 1;
  const raw = Buffer.alloc(stride * size);
  for (let y = 0; y < size; y += 1) {
    raw[y * stride] = 0; // filter: none
    rgba.copy(raw, y * stride + 1, y * size * 4, (y + 1) * size * 4);
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bit depth
  header[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

function render(size, canvasRadius) {
  const rgba = Buffer.alloc(size * size * 4);
  const toDesign = DESIGN / size;
  const samples = SAMPLES * SAMPLES;
  for (let py = 0; py < size; py += 1) {
    for (let px = 0; px < size; px += 1) {
      let red = 0;
      let green = 0;
      let blue = 0;
      let covered = 0;
      for (let sy = 0; sy < SAMPLES; sy += 1) {
        for (let sx = 0; sx < SAMPLES; sx += 1) {
          const x = (px + (sx + 0.5) / SAMPLES) * toDesign;
          const y = (py + (sy + 0.5) / SAMPLES) * toDesign;
          const color = colorAt(x, y, canvasRadius);
          if (!color) continue;
          covered += 1;
          red += color[0];
          green += color[1];
          blue += color[2];
        }
      }
      const offset = (py * size + px) * 4;
      if (covered === 0) continue;
      rgba[offset] = Math.round(red / covered);
      rgba[offset + 1] = Math.round(green / covered);
      rgba[offset + 2] = Math.round(blue / covered);
      rgba[offset + 3] = Math.round((covered / samples) * 255);
    }
  }
  return encodePng(size, rgba);
}

const TARGETS = [
  // Brand corner radius, mirrors icon.svg.
  { file: "icon-192.png", size: 192, radius: 48 },
  // Full bleed: safe for `purpose: maskable` and for launcher/iOS masks.
  { file: "icon-512.png", size: 512, radius: 0 },
  { file: "apple-touch-icon.png", size: 180, radius: 0 },
];

for (const target of TARGETS) {
  const path = join(PUBLIC_DIR, target.file);
  writeFileSync(path, render(target.size, target.radius));
  console.log(`${target.file} (${target.size}x${target.size})`);
}
