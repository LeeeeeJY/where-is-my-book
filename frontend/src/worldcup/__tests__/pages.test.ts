import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { homeMeta, resultMeta, SITE_TITLE } from '../meta';
import { allPages, renderPage } from '../pages';
import { THEMES } from '../themes';
import { wrapText } from '../shareCard';
import { roundEvent, utmFromSearch } from '../track';

const read = (name: string) =>
  readFileSync(fileURLToPath(new URL(`../../../${name}`, import.meta.url)), 'utf8');
const indexHtml = read('index.html');
const canonical = new URL(/<link rel="canonical" href="([^"]+)"/.exec(indexHtml)![1]).origin;

const themeFiles = Object.fromEntries(THEMES.map((theme) => [theme.id, theme]));

describe('경로마다 내보내는 HTML', () => {
  it('시작 화면, 주제마다 진행 화면, 책마다 결과 화면이 한 장씩이다', () => {
    const pages = allPages(themeFiles);
    const books = THEMES.reduce((sum, theme) => sum + theme.books.length, 0);
    expect(pages).toHaveLength(1 + THEMES.length + books);
    expect(new Set(pages.map((page) => page.path)).size).toBe(pages.length);
  });

  /**
   * 미리보기를 만드는 쪽은 자바스크립트를 돌리지 않습니다. 결과 주소로 내보내는 HTML 에
   * 그 책의 제목과 그림이 적혀 있어야 공유했을 때 우승한 책이 보입니다.
   */
  it('결과 화면의 미리보기에 우승한 책의 제목과 그림이 들어간다', () => {
    const theme = THEMES[0];
    const book = theme.books[0];
    const page = resultMeta(theme.id, theme, book);
    const html = renderPage(indexHtml, page);

    expect(html).toContain(`<title>《${book.title}》`);
    expect(html).toContain(`property="og:title" content="《${book.title}》`);
    expect(html).toContain(
      `property="og:image" content="${canonical}/worldcup/${theme.id}/og/${book.id}.jpg"`,
    );
    expect(html).toContain(
      `name="twitter:image" content="${canonical}/worldcup/${theme.id}/og/${book.id}.jpg"`,
    );
    expect(html).toContain(
      `property="og:url" content="${canonical}/worldcup/${theme.id}/result/${book.id}"`,
    );
    expect(html).toContain(
      `<link rel="canonical" href="${canonical}/worldcup/${theme.id}/result/${book.id}"`,
    );
    // 첫 화면의 문구가 한 군데도 남으면 안 됩니다.
    expect(html).not.toContain('og.png');
    expect(html).not.toContain('어느 도서관에 있는지 한 번에');
  });

  it('도메인을 따로 적지 않고 첫 화면의 정본 주소에서 가져온다', () => {
    const html = renderPage(indexHtml, homeMeta());
    const found = [...html.matchAll(/(?:href|content)="(https:\/\/[^"]+)"/g)].map(
      (match) => new URL(match[1]).origin,
    );
    expect(found.length).toBeGreaterThanOrEqual(4);
    expect([...new Set(found)]).toEqual([canonical]);
  });

  it('따옴표가 든 문구가 태그를 깨지 않는다', () => {
    const html = renderPage(indexHtml, { ...homeMeta(), description: '그가 "네"라고 & 말했다' });
    expect(html).toContain('content="그가 &quot;네&quot;라고 &amp; 말했다"');
  });

  /**
   * 조용히 넘어가면 그날부터 모든 결과가 첫 화면의 미리보기로 공유되는데, 화면에는 아무
   * 이상이 없어서 공유해 보기 전에는 알 수 없습니다.
   */
  it('바꿀 태그를 찾지 못하면 빌드를 멈춘다', () => {
    const broken = indexHtml.replace('property="og:image"', 'property="og:picture"');
    expect(() => renderPage(broken, homeMeta())).toThrow(/og:image/);
  });

  it('첫 화면으로 돌아갈 때 되돌려 놓는 제목이 index.html 의 것과 같다', () => {
    expect(/<title>([^<]*)<\/title>/.exec(indexHtml)![1]).toBe(SITE_TITLE);
  });
});

describe('유입 표시', () => {
  it('출처·매체·캠페인 순으로 한 줄로 잇는다', () => {
    expect(utmFromSearch('?utm_source=instagram&utm_medium=paid&utm_campaign=launch')).toBe(
      'instagram/paid/launch',
    );
  });

  it('가운데 칸이 비면 자리를 지키고, 뒤의 빈 칸은 뗀다', () => {
    expect(utmFromSearch('?utm_source=instagram&utm_campaign=launch')).toBe('instagram/-/launch');
    expect(utmFromSearch('?utm_source=instagram')).toBe('instagram');
  });

  it('유입 표시가 없으면 빈 문자열이고, 다른 값은 싣지 않는다', () => {
    expect(utmFromSearch('?libs=1,2,3&q=데미안')).toBe('');
    expect(utmFromSearch('')).toBe('');
  });

  it('받아 적을 수 없는 글자를 떼고 길이를 자른다', () => {
    expect(utmFromSearch('?utm_source=인스타 <b>gram')).toBe('bgram');
    expect(utmFromSearch(`?utm_source=${'x'.repeat(300)}`)).toHaveLength(80);
  });

  it('16강부터 결승까지 들어설 때마다 셀 이름이 있다', () => {
    expect([32, 16, 8, 4, 2].map(roundEvent)).toEqual([
      null,
      'round_16',
      'round_8',
      'round_4',
      'final',
    ]);
  });
});

describe('카드의 글줄', () => {
  // 글자 하나가 폭 10 인 글꼴이라고 칩니다.
  const measure = (text: string) => text.length * 10;

  it('띄어쓰기에서 먼저 끊는다', () => {
    expect(wrapText('젊은 베르테르의 슬픔', 80, 3, measure)).toEqual(['젊은 베르테르의', '슬픔']);
  });

  it('한 낱말이 폭을 넘을 때만 글자 사이에서 끊는다', () => {
    expect(wrapText('카라마조프가의형제들', 60, 3, measure)).toEqual(['카라마조프가', '의형제들']);
  });

  it('줄 수를 넘으면 마지막 줄 끝에 말줄임표를 단다', () => {
    const lines = wrapText('하나 둘 셋 넷 다섯 여섯', 40, 2, measure);
    expect(lines).toHaveLength(2);
    expect(lines[1].endsWith('…')).toBe(true);
    expect(measure(lines[1])).toBeLessThanOrEqual(40);
  });
});
