import { homeMeta, playMeta, resultMeta, type PageMeta } from './meta';

/**
 * 월드컵 경로마다 따로 내보내는 HTML 을 만듭니다. 빌드가 씁니다(`vite.config.ts`).
 *
 * <h2>왜 경로마다 파일이 필요한가</h2>
 *
 * <p>이 사이트는 화면을 자바스크립트로 그리고, 어느 주소로 들어오든 같은 `index.html` 을
 * 줍니다. 그런데 <b>공유 미리보기를 만드는 쪽(카카오톡, 인스타그램, 슬랙)은 자바스크립트를
 * 돌리지 않습니다.</b> HTML 에 적힌 태그만 읽으므로, 그대로 두면 어느 결과를 공유해도
 * 미리보기는 첫 화면의 것이 나옵니다. 결과 주소의 미리보기에 우승한 책이 보이려면 그
 * 주소로 내보내는 HTML 자체가 달라야 합니다.
 *
 * <p>후보가 고정이라 결과 주소도 유한합니다(주제 하나에 서른두 개). 그래서 서버에서 그때그때
 * 만들지 않고 빌드할 때 전부 만들어 정적 파일로 둡니다. 서버 함수도 환경 변수도 필요 없습니다.
 *
 * <h2>도메인을 여기에 적지 않습니다</h2>
 *
 * <p>절대 주소가 필요하지만 `index.html` 의 정본 주소에서 읽어 옵니다. 여기에 한 번 더
 * 적으면 도메인이 여섯 군데가 되고, 바꿀 때 하나를 빠뜨려도 아무것도 깨지지 않은 채 예전
 * 주소를 가리킵니다(`src/__tests__/siteUrl.test.ts`).
 */
type ThemeFile = {
  title: string;
  description: string;
  books: { id: string; title: string; author: string }[];
};

/** 주제 파일들에서 내보낼 경로를 전부 셉니다. */
export function allPages(themes: Record<string, ThemeFile>): PageMeta[] {
  const pages: PageMeta[] = [homeMeta()];
  for (const [themeId, theme] of Object.entries(themes)) {
    pages.push(playMeta(themeId, theme));
    for (const book of theme.books) pages.push(resultMeta(themeId, theme, book));
  }
  return pages;
}

function escapeAttr(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

/**
 * 태그 하나의 값을 갈아 끼웁니다. <b>찾지 못하면 빌드를 멈춥니다.</b> 조용히 넘어가면
 * `index.html` 의 태그 모양이 바뀐 날부터 모든 결과가 첫 화면의 미리보기로 공유되는데,
 * 화면에는 아무 이상이 없어서 공유해 보기 전에는 알 수 없습니다.
 */
function swap(html: string, pattern: RegExp, value: string, what: string): string {
  if (!pattern.test(html)) throw new Error(`index.html 에서 ${what} 태그를 찾지 못했습니다.`);
  return html.replace(pattern, (_all, head: string, tail: string) => head + escapeAttr(value) + tail);
}

const meta = (attr: 'name' | 'property', key: string) =>
  new RegExp(`(<meta\\s+${attr}="${key}"\\s+content=")[^"]*(")`);

export function renderPage(indexHtml: string, page: PageMeta): string {
  const canonical = /<link rel="canonical" href="([^"]+)"/.exec(indexHtml);
  if (!canonical) throw new Error('index.html 에서 정본 주소를 찾지 못했습니다.');
  const origin = new URL(canonical[1]).origin;
  const url = origin + page.path;
  const image = origin + page.image;

  let html = indexHtml;
  html = swap(html, /(<title>)[^<]*(<\/title>)/, page.title, '제목');
  html = swap(html, /(<link rel="canonical" href=")[^"]*(")/, url, '정본 주소');
  html = swap(html, meta('name', 'description'), page.description, '설명');
  html = swap(html, meta('property', 'og:url'), url, 'og:url');
  html = swap(html, meta('property', 'og:title'), page.title, 'og:title');
  html = swap(html, meta('property', 'og:description'), page.description, 'og:description');
  html = swap(html, meta('property', 'og:image'), image, 'og:image');
  html = swap(html, meta('property', 'og:image:alt'), page.imageAlt, 'og:image:alt');
  html = swap(html, meta('name', 'twitter:title'), page.title, 'twitter:title');
  html = swap(html, meta('name', 'twitter:description'), page.description, 'twitter:description');
  html = swap(html, meta('name', 'twitter:image'), image, 'twitter:image');
  return html;
}
