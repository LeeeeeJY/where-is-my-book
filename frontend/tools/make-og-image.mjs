/**
 * 공유 링크 미리보기 그림(`public/og.png`)을 만듭니다.
 *
 *   node tools/make-og-image.mjs
 *
 * **왜 스크립트가 필요한가.** 헤드리스 크로미움의 `--window-size` 는 창 크기이지 그리는
 * 영역이 아닙니다. 실측으로 <b>요청한 높이보다 85px 적게 그리고</b>, 남는 아래쪽은 배경색
 * 으로 채운 채 요청한 크기의 파일을 내놓습니다. 그래서 1200×630 을 그대로 요청하면 아래
 * 85px 에 있는 것이 통째로 사라지는데, <b>배경색으로 채워져 나오기 때문에 잘렸다는 것이
 * 눈에 띄지 않습니다.</b> 실제로 아래쪽 띠 하나가 그렇게 사라졌고, CSS 를 세 번 고쳐 본
 * 뒤에야 렌더러 쪽 문제인 것을 알았습니다.
 *
 * 그래서 <b>85px 을 더 요청해서 그린 뒤 그만큼 잘라 냅니다.</b> 이 보정을 코드에 두지
 * 않으면 다음에 그림을 다시 만드는 사람이 같은 자리에서 같은 시간을 씁니다.
 *
 * 글꼴은 시스템에 있는 것을 씁니다. **한글 글꼴이 없는 기계에서 만들면 글자가 네모로
 * 나오므로 만든 뒤 반드시 눈으로 확인하세요.** 이 스크립트는 그것까지는 알지 못합니다.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, mkdtempSync, rmSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync, deflateSync } from 'node:zlib';

const WIDTH = 1200;
const HEIGHT = 630;
/** 크로미움이 요청한 창 높이보다 덜 그리는 만큼. 실측값입니다. */
const CHROME_CHROME_HEIGHT = 85;

/**
 * CRC 표. <b>최상위 실행 코드보다 위에 있어야 합니다.</b> 함수 선언과 달리 {@code const} 는
 * 끌어올려지지 않아서, 아래에 두면 그림을 쓰는 순간 「초기화 전에 접근」으로 죽습니다.
 */
const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

const here = dirname(fileURLToPath(import.meta.url));
const source = join(here, 'og-image.html');
const target = resolve(here, '..', 'public', 'og.png');

const chrome = findChrome();
if (!chrome) {
  console.error(
    '크로미움을 찾지 못했습니다. CHROME 환경 변수에 실행 파일 경로를 넣어 주세요.\n' +
      '  CHROME=/path/to/chrome node tools/make-og-image.mjs',
  );
  process.exit(1);
}

const work = mkdtempSync(join(tmpdir(), 'wimb-og-'));
try {
  const shot = join(work, 'shot.png');
  execFileSync(chrome, [
    '--headless',
    '--no-sandbox',
    '--disable-gpu',
    '--hide-scrollbars',
    `--screenshot=${shot}`,
    `--window-size=${WIDTH},${HEIGHT + CHROME_CHROME_HEIGHT}`,
    `--user-data-dir=${join(work, 'profile')}`,
    `file://${source}`,
  ], { stdio: 'ignore' });

  writeFileSync(target, cropTop(readFileSync(shot), WIDTH, HEIGHT));
  console.log(`${target} 를 ${WIDTH}×${HEIGHT} 로 만들었습니다.`);
} finally {
  rmSync(work, { recursive: true, force: true });
}

function findChrome() {
  if (process.env.CHROME && existsSync(process.env.CHROME)) return process.env.CHROME;
  const fixed = [
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/bin/google-chrome',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
  ];
  for (const path of fixed) if (existsSync(path)) return path;
  // Playwright 가 받아 둔 것이 있으면 그것도 씁니다.
  const pool = '/opt/pw-browsers';
  if (existsSync(pool)) {
    for (const entry of readdirSync(pool)) {
      const candidate = join(pool, entry, 'chrome-linux', 'chrome');
      if (entry.startsWith('chromium-') && existsSync(candidate)) return candidate;
    }
  }
  return null;
}

/** PNG 의 위쪽 `height` 줄만 남깁니다. 크로미움이 아래에 덧붙인 빈 자리를 떼어 냅니다. */
function cropTop(png, width, height) {
  const { ihdr, pixels, bytesPerPixel, stride } = decode(png);
  if (ihdr.width !== width) {
    throw new Error(`가로가 ${ihdr.width} 입니다. ${width} 이어야 합니다.`);
  }
  if (ihdr.height < height) {
    throw new Error(`세로가 ${ihdr.height} 뿐이라 ${height} 로 자를 수 없습니다.`);
  }

  // 필터는 전부 0(없음)으로 다시 씁니다. 바탕이 넓은 그림이라 압축률 차이가 크지 않고,
  // 필터를 다시 고르는 코드를 두지 않는 편이 읽기 쉽습니다.
  const out = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    out[y * (stride + 1)] = 0;
    pixels.copy(out, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  return encode(ihdr, height, deflateSync(out, { level: 9 }), bytesPerPixel);
}

function decode(png) {
  if (png.readUInt32BE(0) !== 0x89504e47) throw new Error('PNG 가 아닙니다.');
  let pos = 8;
  let ihdr = null;
  const idat = [];
  while (pos < png.length) {
    const length = png.readUInt32BE(pos);
    const type = png.toString('ascii', pos + 4, pos + 8);
    const body = png.subarray(pos + 8, pos + 8 + length);
    if (type === 'IHDR') {
      ihdr = {
        width: body.readUInt32BE(0),
        height: body.readUInt32BE(4),
        depth: body[8],
        colorType: body[9],
      };
      if (ihdr.depth !== 8) throw new Error('8비트 채널만 다룹니다.');
      if (body[12] !== 0) throw new Error('인터레이스된 PNG 는 다루지 않습니다.');
    } else if (type === 'IDAT') {
      idat.push(body);
    }
    pos += 12 + length;
  }
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr.colorType];
  if (!channels) throw new Error(`모르는 색 형식입니다: ${ihdr.colorType}`);
  const stride = ihdr.width * channels;
  return {
    ihdr,
    bytesPerPixel: channels,
    stride,
    pixels: unfilter(inflateSync(Buffer.concat(idat)), ihdr.height, stride, channels),
  };
}

/** PNG 의 줄 단위 필터를 풀어 날 픽셀로 되돌립니다. */
function unfilter(raw, height, stride, bpp) {
  const out = Buffer.alloc(height * stride);
  for (let y = 0; y < height; y++) {
    const type = raw[y * (stride + 1)];
    const from = y * (stride + 1) + 1;
    const to = y * stride;
    for (let i = 0; i < stride; i++) {
      const value = raw[from + i];
      const a = i >= bpp ? out[to + i - bpp] : 0;
      const b = y > 0 ? out[to - stride + i] : 0;
      const c = y > 0 && i >= bpp ? out[to - stride + i - bpp] : 0;
      let add = 0;
      if (type === 1) add = a;
      else if (type === 2) add = b;
      else if (type === 3) add = (a + b) >> 1;
      else if (type === 4) add = paeth(a, b, c);
      else if (type !== 0) throw new Error(`모르는 필터입니다: ${type}`);
      out[to + i] = (value + add) & 0xff;
    }
  }
  return out;
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

function encode(ihdr, height, data, channels) {
  const head = Buffer.alloc(13);
  head.writeUInt32BE(ihdr.width, 0);
  head.writeUInt32BE(height, 4);
  head[8] = 8;
  head[9] = { 1: 0, 2: 4, 3: 2, 4: 6 }[channels];
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', head),
    chunk('IDAT', data),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function chunk(type, body) {
  const out = Buffer.alloc(12 + body.length);
  out.writeUInt32BE(body.length, 0);
  out.write(type, 4, 'ascii');
  body.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + body.length)), 8 + body.length);
  return out;
}


function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
