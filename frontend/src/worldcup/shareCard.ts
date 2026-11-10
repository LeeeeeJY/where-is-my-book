/**
 * 결과 카드(1080×1920 PNG)를 그립니다. 인스타그램 스토리에 그대로 올라가는 크기입니다.
 *
 * <h2>화면을 찍지 않고 캔버스에 직접 그립니다</h2>
 *
 * <p>화면을 그림으로 바꿔 주는 라이브러리는 글꼴과 그림자를 브라우저마다 다르게 옮기고
 * 번들을 수백 KB 늘립니다. 카드에 올릴 것이 표지 몇 장과 글 몇 줄이라 직접 그리는 편이
 * 작고 결과가 일정합니다.
 *
 * <h2>표지는 우리 주소의 파일이어야 합니다</h2>
 *
 * <p>다른 주소의 그림을 캔버스에 올리면 그 캔버스는 <b>저장할 수 없는 상태</b>가 됩니다
 * (그림 서버가 허락 헤더를 주지 않는 한). 정보나루가 알려 주는 표지의 절반이 그런 서버에
 * 있어서 도구가 표지를 미리 받아 `public/worldcup/` 에 둡니다. 그래도 표지를 못 받으면
 * <b>제목을 적은 대체 표지로 그립니다.</b> 표지 한 장 때문에 카드 전체를 못 만들면 안 됩니다.
 *
 * <h2>글꼴과 그림이 준비된 뒤에 그립니다</h2>
 *
 * <p>캔버스는 그리는 순간의 글꼴로 한 번 찍고 끝이라, 글꼴이 아직 오지 않았으면 대체
 * 글꼴로 찍힌 그림이 그대로 저장됩니다. 지금은 기기 글꼴만 쓰지만, 웹 글꼴을 들이는 날
 * 조용히 틀어지지 않도록 쓰는 글꼴을 하나씩 기다립니다.
 */
export type CardBook = { title: string; author: string; cover: string };

export type CardInput = {
  /** 「세계문학 고전 32강」 */
  themeTitle: string;
  /** 「나의 인생 책」 */
  headline: string;
  winner: CardBook;
  runnerUp: CardBook | null;
  semifinalists: CardBook[];
  /** 아래에 적는 주소. `where-is-my-book.vercel.app/worldcup` */
  siteLabel: string;
};

export const CARD_WIDTH = 1080;
export const CARD_HEIGHT = 1920;

/**
 * 글을 폭에 맞춰 줄로 나눕니다. <b>띄어쓰기에서 먼저 끊고, 한 낱말이 폭을 넘을 때만 글자
 * 사이에서 끊습니다.</b> 한국어는 어디서든 끊을 수 있어서 그냥 두면 낱말 한가운데서
 * 갈립니다. 줄 수를 넘으면 마지막 줄 끝에 말줄임표를 답니다.
 */
export function wrapText(
  text: string,
  maxWidth: number,
  maxLines: number,
  measure: (s: string) => number,
): string[] {
  const lines: string[] = [];
  let line = '';
  const push = () => {
    if (line) lines.push(line);
    line = '';
  };
  for (const word of text.trim().split(/\s+/)) {
    const joined = line ? `${line} ${word}` : word;
    if (measure(joined) <= maxWidth) {
      line = joined;
      continue;
    }
    push();
    if (measure(word) <= maxWidth) {
      line = word;
      continue;
    }
    // 낱말 하나가 한 줄보다 깁니다. 글자 단위로 채웁니다.
    for (const char of word) {
      if (line && measure(line + char) > maxWidth) push();
      line += char;
    }
  }
  push();
  if (lines.length <= maxLines) return lines;

  const kept = lines.slice(0, maxLines);
  let last = kept[maxLines - 1];
  while (last.length > 1 && measure(`${last}…`) > maxWidth) last = last.slice(0, -1);
  kept[maxLines - 1] = `${last}…`;
  return kept;
}

type Palette = {
  bg: string;
  surface: string;
  text: string;
  muted: string;
  accent: string;
  brass: string;
  wood: string;
  woodLight: string;
  woodDark: string;
  display: string;
  body: string;
};

/**
 * 색과 글꼴을 <b>화면의 값에서 읽어 옵니다.</b> 여기에 색을 다시 적으면 팔레트를 바꾼 날
 * 카드만 예전 색으로 남습니다. 뒤의 값은 스타일을 읽지 못하는 환경(시험)에서만 쓰입니다.
 */
function palette(): Palette {
  const root = getComputedStyle(document.documentElement);
  const read = (name: string, fallback: string) => root.getPropertyValue(name).trim() || fallback;
  return {
    bg: read('--bg', '#f3eee2'),
    surface: read('--surface', '#fdfaf3'),
    text: read('--text', '#241d14'),
    muted: read('--muted', '#6e6354'),
    accent: read('--accent', '#2a5741'),
    brass: read('--brass', '#a4813f'),
    wood: read('--wood', '#9c6d42'),
    woodLight: read('--wood-light', '#c69b6b'),
    woodDark: read('--wood-dark', '#6b452a'),
    display: read('--display', 'serif'),
    body: getComputedStyle(document.body).fontFamily || 'sans-serif',
  };
}

/** 그림 한 장을 받아 그릴 수 있을 때까지 기다립니다. 못 받으면 null 입니다. */
async function loadImage(src: string): Promise<HTMLImageElement | null> {
  const image = new Image();
  image.decoding = 'async';
  image.src = src;
  try {
    await image.decode();
    return image.naturalWidth > 0 ? image : null;
  } catch {
    return null;
  }
}

async function fontsReady(specs: string[], sample: string): Promise<void> {
  if (!('fonts' in document)) return;
  try {
    await Promise.all(specs.map((spec) => document.fonts.load(spec, sample)));
    await document.fonts.ready;
  } catch {
    // 글꼴을 기다리다 실패해도 기기 글꼴로 그립니다.
  }
}

function roundedRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * 표지 한 장을 <b>아래 선에 맞춰</b> 그리고 그린 자리의 윗선을 돌려줍니다. 서가에서
 * 책이 선반 바닥에 닿아 서 있는 것과 같은 방식입니다.
 */
function drawCover(
  ctx: CanvasRenderingContext2D,
  p: Palette,
  book: CardBook,
  image: HTMLImageElement | null,
  centerX: number,
  bottom: number,
  width: number,
  maxHeight: number,
): number {
  let w = width;
  let h = Math.round(width * 1.45);
  if (image) h = Math.round((width * image.naturalHeight) / image.naturalWidth);
  if (h > maxHeight) {
    w = Math.round((w * maxHeight) / h);
    h = maxHeight;
  }
  const x = Math.round(centerX - w / 2);
  const y = bottom - h;
  const radius = Math.max(4, Math.round(w * 0.012));

  ctx.save();
  ctx.shadowColor = 'rgba(43, 27, 13, 0.38)';
  ctx.shadowBlur = Math.round(w * 0.09);
  ctx.shadowOffsetY = Math.round(w * 0.035);
  roundedRect(ctx, x, y, w, h, radius);
  ctx.fillStyle = p.accent;
  ctx.fill();
  ctx.restore();

  ctx.save();
  roundedRect(ctx, x, y, w, h, radius);
  ctx.clip();
  if (image) {
    ctx.drawImage(image, x, y, w, h);
  } else {
    // 대체 표지. 가죽 장정의 녹색 위에 제목과 저자를 적습니다.
    const pad = Math.round(w * 0.1);
    ctx.fillStyle = '#f6f1e6';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    const titleSize = Math.max(18, Math.round(w * 0.12));
    ctx.font = `600 ${titleSize}px ${p.display}`;
    const lines = wrapText(book.title, w - pad * 2, 5, (s) => ctx.measureText(s).width);
    lines.forEach((line, i) => ctx.fillText(line, x + pad, y + pad + i * titleSize * 1.3));
    const authorSize = Math.max(14, Math.round(w * 0.08));
    ctx.font = `400 ${authorSize}px ${p.body}`;
    ctx.globalAlpha = 0.8;
    ctx.textBaseline = 'bottom';
    ctx.fillText(
      wrapText(book.author, w - pad * 2, 1, (s) => ctx.measureText(s).width)[0] ?? '',
      x + pad,
      y + h - pad,
    );
  }
  ctx.restore();
  return y;
}

/** 선반 판. 위는 밝고 아래는 어둡습니다. 화면의 `.shelf__plank` 와 같은 차례입니다. */
function drawPlank(ctx: CanvasRenderingContext2D, p: Palette, x: number, y: number, w: number, h: number) {
  const wood = ctx.createLinearGradient(0, y, 0, y + h);
  wood.addColorStop(0, p.woodLight);
  wood.addColorStop(0.18, p.woodLight);
  wood.addColorStop(0.2, p.wood);
  wood.addColorStop(0.62, p.wood);
  wood.addColorStop(0.64, p.woodDark);
  wood.addColorStop(1, p.woodDark);
  ctx.save();
  ctx.shadowColor = 'rgba(43, 27, 13, 0.34)';
  ctx.shadowBlur = 18;
  ctx.shadowOffsetY = 10;
  ctx.fillStyle = wood;
  roundedRect(ctx, x, y, w, h, 3);
  ctx.fill();
  ctx.restore();
}

function centered(
  ctx: CanvasRenderingContext2D,
  lines: string[],
  centerX: number,
  top: number,
  lineHeight: number,
): number {
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  lines.forEach((line, i) => ctx.fillText(line, centerX, top + i * lineHeight));
  return top + lines.length * lineHeight;
}

export async function renderShareCard(input: CardInput): Promise<Blob> {
  const p = palette();
  const others = [
    ...(input.runnerUp ? [{ label: '준우승', book: input.runnerUp }] : []),
    ...input.semifinalists.map((book) => ({ label: '4강', book })),
  ];

  const sample = [input.headline, input.themeTitle, input.winner.title, input.winner.author].join(' ');
  const [winnerImage, otherImages] = await Promise.all([
    loadImage(input.winner.cover),
    Promise.all(others.map((one) => loadImage(one.book.cover))),
    fontsReady([`700 84px ${p.display}`, `600 40px ${p.body}`, `400 36px ${p.body}`], sample),
  ]);

  const canvas = document.createElement('canvas');
  canvas.width = CARD_WIDTH;
  canvas.height = CARD_HEIGHT;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('캔버스를 쓸 수 없습니다.');
  const measure = (s: string) => ctx.measureText(s).width;
  const mid = CARD_WIDTH / 2;

  // 종이. 위에서 드는 빛 한 겹만 얹습니다.
  ctx.fillStyle = p.bg;
  ctx.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);
  const light = ctx.createRadialGradient(mid, -160, 0, mid, -160, 1500);
  light.addColorStop(0, 'rgba(255, 253, 247, 0.9)');
  light.addColorStop(1, 'rgba(255, 253, 247, 0)');
  ctx.fillStyle = light;
  ctx.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);

  // 머리. 인스타그램 스토리는 위아래 250px 안팎을 제 단추로 덮으므로 그 안쪽에서 시작합니다.
  ctx.fillStyle = p.accent;
  ctx.font = `700 46px ${p.display}`;
  centered(ctx, ['WIMB'], mid, 150, 56);
  ctx.fillStyle = p.muted;
  ctx.font = `600 38px ${p.body}`;
  centered(ctx, [`인생 책 월드컵 · ${input.themeTitle}`], mid, 222, 50);

  ctx.fillStyle = p.text;
  ctx.font = `700 84px ${p.display}`;
  centered(ctx, [input.headline], mid, 300, 100);

  // 우승한 책. 선반 위에 세웁니다.
  const plankY = 1140;
  drawCover(ctx, p, input.winner, winnerImage, mid, plankY, 460, 700);
  drawPlank(ctx, p, 150, plankY, CARD_WIDTH - 300, 34);

  ctx.fillStyle = p.text;
  ctx.font = `700 68px ${p.display}`;
  const titleEnd = centered(
    ctx,
    wrapText(`《${input.winner.title}》`, 920, 2, measure),
    mid,
    plankY + 78,
    86,
  );
  ctx.fillStyle = p.muted;
  ctx.font = `400 38px ${p.body}`;
  centered(ctx, wrapText(input.winner.author, 900, 1, measure), mid, titleEnd + 8, 50);

  // 준우승과 4강. 작은 선반 하나에 나란히 세웁니다.
  if (others.length > 0) {
    const shelfY = 1650;
    const step = 250;
    const first = mid - ((others.length - 1) * step) / 2;
    others.forEach((one, i) => {
      const x = first + i * step;
      drawCover(ctx, p, one.book, otherImages[i], x, shelfY, 130, 200);
    });
    drawPlank(ctx, p, first - 150, shelfY, (others.length - 1) * step + 300, 20);
    others.forEach((one, i) => {
      const x = first + i * step;
      ctx.fillStyle = p.brass;
      ctx.font = `600 26px ${p.body}`;
      centered(ctx, [one.label], x, shelfY + 36, 32);
      ctx.fillStyle = p.text;
      ctx.font = `600 28px ${p.body}`;
      centered(ctx, wrapText(one.book.title, step - 24, 2, measure), x, shelfY + 70, 36);
    });
  }

  // 놋쇠 선 한 줄과 주소.
  ctx.fillStyle = p.brass;
  ctx.fillRect(mid - 60, 1806, 120, 3);
  ctx.fillStyle = p.accent;
  ctx.font = `600 32px ${p.body}`;
  centered(ctx, [input.siteLabel], mid, 1826, 40);

  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('그림을 만들지 못했습니다.'))),
      'image/png',
    );
  });
}
