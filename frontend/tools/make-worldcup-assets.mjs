/**
 * 「인생 책 월드컵」 주제 파일에서 **표지, 공유 미리보기 그림, 서버의 책 명단**을 만듭니다.
 *
 *   node tools/make-worldcup-assets.mjs            없는 것만 만듭니다
 *   node tools/make-worldcup-assets.mjs --force    전부 다시 만듭니다
 *
 * 주제를 더하는 차례는 이렇습니다. `src/worldcup/themes/<이름>.json` 을 더하고, 이 도구를
 * 한 번 돌리고, 생긴 파일을 함께 커밋합니다. 코드를 고칠 곳은 없습니다.
 *
 * ## 만드는 것 셋
 *
 * 1. **표지** `public/worldcup/<주제>/covers/<책>.jpg`
 *    정보나루가 알려 준 주소(`coverSource`)에서 받아 폭 480px 의 JPEG 로 다시 적습니다.
 *    우리 주소에 두는 이유는 저장용 카드 때문입니다. 다른 주소의 그림을 캔버스에 올리면
 *    그 캔버스는 저장할 수 없게 되는데, 정보나루가 알려 주는 표지의 절반(네이버 그림
 *    서버)이 그것을 허락하지 않습니다(2026-11-10 확인. 알라딘 쪽은 허락합니다).
 *
 *    **정보나루가 주는 주소는 작은 그림입니다.** 네이버는 `?type=m1` 이 붙어 73×122 이고
 *    알라딘은 `/cover/` 가 200×341 입니다. 검색 목록의 56px 칸에는 충분하지만 대결 화면과
 *    1080px 카드에는 모자랍니다. 그래서 같은 그림의 큰 판을 먼저 받아 봅니다. 네이버는
 *    물음표 뒤를 떼면 원본이고, 알라딘은 `/cover500/` 입니다. 받지 못하면 정보나루가
 *    준 주소 그대로 받습니다.
 *
 * 2. **공유 미리보기** `public/worldcup/og.jpg`, `public/worldcup/<주제>/og/<책>.jpg`
 *    결과 주소의 미리보기에 우승한 책의 표지와 제목이 보여야 합니다. 책마다 한 장입니다.
 *    색과 글꼴은 `src/styles.css` 의 `:root` 에서 읽어 옵니다. 여기에 다시 적지 않습니다.
 *
 * 3. **서버의 책 명단** `backend/src/main/resources/worldcup/rosters.tsv`
 *    서버는 이 명단에 있는 책의 완주만 셉니다. 이미지가 `backend/` 만 가지고 만들어져
 *    화면 쪽 주제 파일을 읽을 수 없어서, 이름표만 그쪽에 한 벌 둡니다. 둘이 어긋나면
 *    `src/worldcup/__tests__/themes.test.ts` 가 실패합니다.
 *
 * ## 필요한 것
 *
 * - **Playwright 와 크로미움.** 그림을 다시 적고 미리보기를 찍는 데 씁니다. 없으면
 *   `npm i --no-save playwright && npx playwright install chromium` 으로 받습니다.
 *   프로젝트의 의존성에는 넣지 않았습니다. 주제를 더할 때만 쓰는 도구입니다.
 * - **curl.** 표지를 받는 데 씁니다. 프록시 설정을 그대로 따르기 때문에 고른 것입니다.
 * - **한글 글꼴.** 없는 기계에서 만들면 글자가 네모로 나옵니다. **만든 뒤 반드시 눈으로
 *   확인하세요.** 이 도구는 그것까지는 알지 못합니다. 그리고 기계마다 글꼴이 달라 같은
 *   그림이 다르게 나오므로, 이미 있는 그림은 `--force` 없이는 다시 만들지 않습니다.
 *
 * ## 표지가 마음에 들지 않으면
 *
 * 파일을 직접 바꿔 넣어도 됩니다. 이름(`<책>.jpg`)만 같으면 화면과 카드가 그것을 씁니다.
 * 미리보기에도 반영하려면 그 책의 `og/<책>.jpg` 를 지우고 이 도구를 다시 돌립니다.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const frontend = resolve(here, '..');
const themesDir = join(frontend, 'src/worldcup/themes');
const publicDir = join(frontend, 'public/worldcup');
const rostersFile = resolve(frontend, '../backend/src/main/resources/worldcup/rosters.tsv');
const force = process.argv.includes('--force');

const COVER_WIDTH = 480;
const OG = { width: 1200, height: 630 };

const themes = readdirSync(themesDir)
  .filter((name) => name.endsWith('.json'))
  .sort()
  .map((name) => ({
    id: name.replace(/\.json$/, ''),
    ...JSON.parse(readFileSync(join(themesDir, name), 'utf8')),
  }));
if (themes.length === 0) {
  console.error(`${themesDir} 에 주제 파일이 없습니다.`);
  process.exit(1);
}

writeRosters();

const playwright = loadPlaywright();
const browser = await playwright.chromium.launch();
const work = mkdtempSync(join(tmpdir(), 'wimb-worldcup-'));
let failed = 0;
try {
  const page = await browser.newPage({ viewport: OG, deviceScaleFactor: 1 });
  const tokens = readTokens();

  for (const theme of themes) {
    const coverDir = join(publicDir, theme.id, 'covers');
    const ogDir = join(publicDir, theme.id, 'og');
    mkdirSync(coverDir, { recursive: true });
    mkdirSync(ogDir, { recursive: true });

    for (const book of theme.books) {
      const coverFile = join(coverDir, `${book.id}.jpg`);
      if (force || !existsSync(coverFile)) {
        const size = await makeCover(page, book, coverFile);
        if (size) console.log(`표지   ${theme.id}/${book.id}  ${size}`);
        else {
          failed++;
          console.error(`표지를 받지 못했습니다: ${theme.id}/${book.id}  ${book.coverSource}`);
          continue;
        }
      }
      const ogFile = join(ogDir, `${book.id}.jpg`);
      if (force || !existsSync(ogFile)) {
        await shoot(page, resultCard(tokens, theme, book, dataUri(coverFile)), ogFile);
        console.log(`미리보기 ${theme.id}/${book.id}`);
      }
    }
  }

  // 첫 화면의 미리보기. 맨 앞 주제의 표지 몇 장을 선반에 세웁니다.
  const homeFile = join(publicDir, 'og.jpg');
  if (force || !existsSync(homeFile)) {
    const first = themes[0];
    const covers = first.books
      // 일곱 장입니다. 더 세우면 선반 판보다 넓어져 양 끝의 책이 잘립니다.
      .slice(0, 7)
      .map((book) => join(publicDir, first.id, 'covers', `${book.id}.jpg`))
      .filter((file) => existsSync(file))
      .map(dataUri);
    await shoot(page, homeCard(tokens, first, covers), homeFile);
    console.log('미리보기 worldcup/og.jpg');
  }
} finally {
  await browser.close();
  rmSync(work, { recursive: true, force: true });
}

if (failed > 0) {
  console.error(`\n표지 ${failed}장을 받지 못했습니다. 그 책은 화면에서 제목으로 그린 대체 표지가 나갑니다.`);
  process.exit(1);
}
console.log('\n끝났습니다. 한글이 네모로 나오지 않았는지 그림을 열어 확인해 주세요.');

// ── 서버의 책 명단 ─────────────────────────────────────────────────────

function writeRosters() {
  const lines = [
    '# 월드컵 주제마다 서버가 받는 책의 이름표입니다. 손으로 고치지 마세요.',
    '# frontend/tools/make-worldcup-assets.mjs 가 frontend/src/worldcup/themes/*.json 에서 만듭니다.',
  ];
  for (const theme of themes) for (const book of theme.books) lines.push(`${theme.id}\t${book.id}`);
  const next = lines.join('\n') + '\n';
  const before = existsSync(rostersFile) ? readFileSync(rostersFile, 'utf8') : '';
  if (before !== next) {
    mkdirSync(dirname(rostersFile), { recursive: true });
    writeFileSync(rostersFile, next);
    console.log(`명단   ${rostersFile}`);
  }
}

// ── 표지 ───────────────────────────────────────────────────────────────

/** 같은 그림의 큰 판을 먼저, 정보나루가 준 주소를 마지막에 둡니다. */
function candidates(source) {
  const url = new URL(source);
  if (url.host === 'image.aladin.co.kr') {
    return [source.replace(/\/cover(?:sum|150|200)?\//, '/cover500/'), source];
  }
  if (url.host.endsWith('.pstatic.net') && url.search) {
    const original = new URL(source);
    original.search = '';
    return [original.toString(), source];
  }
  return [source];
}

async function makeCover(page, book, target) {
  for (const url of [...new Set(candidates(book.coverSource))]) {
    const raw = join(work, 'cover.bin');
    try {
      execFileSync('curl', ['-fsSL', '-m', '40', '-o', raw, url], { stdio: 'ignore' });
    } catch {
      continue;
    }
    const bytes = readFileSync(raw);
    if (bytes.length < 500) continue;
    // 브라우저가 그림으로 읽을 수 있는지까지 봅니다. 오류 페이지가 200 으로 오는 서버가 있습니다.
    const made = await page.evaluate(
      async ({ base64, width }) => {
        const image = new Image();
        image.src = `data:application/octet-stream;base64,${base64}`;
        try {
          await image.decode();
        } catch {
          return null;
        }
        if (image.naturalWidth < 60) return null;
        // 키우지는 않습니다. 작은 그림을 늘려 적어 봐야 흐려지기만 하고 파일만 커집니다.
        const w = Math.min(width, image.naturalWidth);
        const h = Math.round((w * image.naturalHeight) / image.naturalWidth);
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(image, 0, 0, w, h);
        return { data: canvas.toDataURL('image/jpeg', 0.86), w, h };
      },
      { base64: bytes.toString('base64'), width: COVER_WIDTH },
    );
    if (!made) continue;
    writeFileSync(target, Buffer.from(made.data.split(',')[1], 'base64'));
    return `${made.w}×${made.h}`;
  }
  return null;
}

function dataUri(file) {
  return `data:image/jpeg;base64,${readFileSync(file).toString('base64')}`;
}

// ── 미리보기 그림 ───────────────────────────────────────────────────────

/** `src/styles.css` 의 `:root` 에서 색과 글꼴을 읽습니다. 여기에 다시 적지 않습니다. */
function readTokens() {
  const css = readFileSync(join(frontend, 'src/styles.css'), 'utf8');
  const root = /:root\s*\{([\s\S]*?)\n\}/.exec(css)?.[1] ?? '';
  const read = (name) => {
    const found = new RegExp(`--${name}:\\s*([^;]+);`).exec(root);
    if (!found) throw new Error(`styles.css 에서 --${name} 을 찾지 못했습니다.`);
    return found[1].replace(/\s+/g, ' ').trim();
  };
  return {
    bg: read('bg'),
    text: read('text'),
    muted: read('muted'),
    accent: read('accent'),
    brass: read('brass'),
    wood: read('wood'),
    woodLight: read('wood-light'),
    woodDark: read('wood-dark'),
    // 글꼴 이름을 띄어 적는 기계와 붙여 적는 기계가 있어 둘 다 둡니다.
    display: `${read('display').replace(/,\s*serif$/, '')}, 'NanumMyeongjo', serif`,
    body: `-apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Pretendard', 'Noto Sans KR', 'Malgun Gothic', 'NanumBarunGothic', 'NanumGothic', sans-serif`,
  };
}

/**
 * **함수 선언으로 둡니다.** 화살표 함수를 `const` 에 담으면 끌어올려지지 않아서, 위의 실행
 * 코드가 이것을 부르는 순간 「초기화 전에 접근」으로 죽습니다. 실제로 그렇게 죽었습니다.
 */
function escapeHtml(text) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function baseStyle(t) {
  return `
    * { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; }
    body {
      width: ${OG.width}px; height: ${OG.height}px; overflow: hidden;
      color: ${t.text}; font-family: ${t.body};
      background-color: ${t.bg};
      background-image: radial-gradient(130% 90% at 50% -10%,
        rgba(255, 253, 247, 0.9), rgba(255, 253, 247, 0) 62%);
      /* 아래쪽 띠 하나로 강조색을 넣습니다. 큰 면을 칠하면 카드에서 글자가 묻힙니다. */
      border-bottom: 14px solid ${t.accent};
    }
    .plank {
      height: 20px; border-radius: 2px;
      background: linear-gradient(180deg, ${t.woodLight} 0 4px, ${t.wood} 4px 12px, ${t.woodDark} 12px);
      box-shadow: 0 10px 14px -8px rgba(43, 27, 13, 0.4);
    }
    .cover { display: block; border-radius: 3px;
      box-shadow: 0 2px 4px rgba(43, 27, 13, 0.3), 0 14px 26px -10px rgba(43, 27, 13, 0.45); }
    .kicker { margin: 0; font-size: 30px; font-weight: 600; color: ${t.muted}; }
    .display { font-family: ${t.display}; }
    .brand { color: ${t.accent}; }
  `;
}

/** 제목이 길수록 글자를 줄입니다. 두 줄을 넘기면 표지 옆에서 넘칩니다. */
function titleSize(title) {
  if (title.length <= 6) return 96;
  if (title.length <= 9) return 80;
  if (title.length <= 12) return 68;
  return 58;
}

function resultCard(t, theme, book, cover) {
  return `<!doctype html><html lang="ko"><head><meta charset="UTF-8" /><style>
    ${baseStyle(t)}
    body { display: flex; align-items: center; gap: 70px; padding: 0 84px; }
    .stand { flex: none; width: 330px; }
    .stand .cover { max-width: 290px; max-height: 440px; margin: 0 auto; }
    .text { min-width: 0; }
    .label { margin: 26px 0 0; font-size: 36px; font-weight: 700; color: ${t.accent}; }
    h1 { margin: 10px 0 0; font-size: ${titleSize(book.title)}px; line-height: 1.22; font-weight: 700;
         word-break: keep-all; }
    .author { margin: 18px 0 0; font-size: 34px; color: ${t.muted}; word-break: keep-all; }
    .foot { margin: 34px 0 0; font-size: 28px; font-weight: 600; }
    .foot span { color: ${t.brass}; }
  </style></head><body>
    <div class="stand"><img class="cover" src="${cover}" /><div class="plank"></div></div>
    <div class="text">
      <p class="kicker">인생 책 월드컵 · ${escapeHtml(theme.title)}</p>
      <p class="label">이 월드컵의 우승 책</p>
      <h1 class="display">《${escapeHtml(book.title)}》</h1>
      <p class="author">${escapeHtml(book.author)}</p>
      <p class="foot"><span class="brand display">WIMB</span> · 나의 인생 책도 골라 보세요</p>
    </div>
  </body></html>`;
}

function homeCard(t, theme, covers) {
  return `<!doctype html><html lang="ko"><head><meta charset="UTF-8" /><style>
    ${baseStyle(t)}
    body { padding: 62px 84px 0; display: flex; flex-direction: column; }
    h1 { margin: 10px 0 0; font-size: 100px; line-height: 1.15; font-weight: 700; }
    .sub { margin: 14px 0 0; font-size: 36px; font-weight: 500; word-break: keep-all; }
    .sub b { color: ${t.accent}; }
    .shelf { margin-top: auto; padding-bottom: 26px; }
    .books { display: flex; gap: 18px; align-items: flex-end; justify-content: center; padding: 0 20px; }
    .books .cover { height: 200px; width: auto; }
  </style></head><body>
    <p class="kicker"><span class="brand display">WIMB</span> · ${escapeHtml(theme.title)}</p>
    <h1 class="display">인생 책 월드컵</h1>
    <p class="sub">둘 중 더 마음이 가는 책을 고르면, <b>마지막에 한 권</b>이 남습니다.</p>
    <div class="shelf">
      <div class="books">${covers.map((src) => `<img class="cover" src="${src}" />`).join('')}</div>
      <div class="plank"></div>
    </div>
  </body></html>`;
}

async function shoot(page, html, target) {
  await page.setViewportSize(OG);
  await page.setContent(html, { waitUntil: 'load' });
  // 글꼴과 그림이 다 온 뒤에 찍습니다. 먼저 찍으면 대체 글꼴이나 빈 표지가 그대로 남습니다.
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all([...document.images].map((image) => image.decode().catch(() => {})));
  });
  writeFileSync(target, await page.screenshot({ type: 'jpeg', quality: 88 }));
}

function loadPlaywright() {
  try {
    return createRequire(import.meta.url)('playwright');
  } catch {
    console.error(
      'Playwright 를 찾지 못했습니다. 아래 두 줄로 받은 뒤 다시 돌려 주세요.\n' +
        '  npm i --no-save playwright\n' +
        '  npx playwright install chromium',
    );
    process.exit(1);
  }
}
