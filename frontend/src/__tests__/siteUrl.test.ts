import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * **주소가 다섯 군데에 흩어져 있습니다.**
 *
 * <p>공유 미리보기와 사이트맵은 절대 경로여야 동작합니다. 상대 경로로는 미리보기를 만드는
 * 쪽이 그림을 찾지 못하고, 사이트맵의 `<loc>` 도 절대 경로가 규칙입니다. 그래서 도메인이
 * 코드 여러 곳에 글자로 박히는데, **도메인을 바꿀 때 한 곳을 빠뜨리면 아무것도 깨지지
 * 않은 채 조용히 예전 주소를 가리킵니다.** 미리보기에 없는 그림이 뜨고, 사이트맵이 남의
 * 주소를 제출하고, 그것이 눈에 띄지 않습니다.
 *
 * <p>이 테스트는 다섯 곳이 서로 어긋나면 실패합니다. 값이 무엇인지는 보지 않고 **서로 같은지**만
 * 봅니다. 도메인을 바꾸는 것은 정상적인 일이라 막을 이유가 없고, 막아야 하는 것은 그중
 * 하나만 바뀌는 것입니다.
 */
const read = (name: string) =>
  readFileSync(fileURLToPath(new URL(`../../${name}`, import.meta.url)), 'utf8');

const indexHtml = read('index.html');
const privacyHtml = read('public/privacy.html');
const sitemap = read('public/sitemap.xml');
const robots = read('public/robots.txt');

/** 절대 경로로 적힌 우리 주소를 전부 긁어옵니다. */
function origins(source: string, pattern: RegExp): string[] {
  return [...source.matchAll(pattern)].map((match) => new URL(match[1]).origin);
}

describe('사이트 주소', () => {
  const canonical = new URL(
    /<link rel="canonical" href="([^"]+)"/.exec(indexHtml)![1],
  ).origin;

  it('첫 화면의 미리보기 태그가 모두 같은 주소를 가리킨다', () => {
    // canonical, og:url, og:image, twitter:image 넷입니다. 도메인을 바꿀 때 가장 자주
    // 빠뜨리는 곳이고, 빠뜨려도 화면은 멀쩡해서 눈에 띄지 않습니다.
    const found = origins(indexHtml, /(?:href|content)="(https:\/\/[^"]+)"/g)
      // 바깥 서비스 주소는 우리 주소가 아닙니다.
      .filter((origin) => !origin.includes('data4library') && !origin.includes('github'));

    expect(found.length).toBeGreaterThanOrEqual(4);
    expect([...new Set(found)]).toEqual([canonical]);
  });

  it('개인정보처리방침의 정본 주소가 같은 곳을 가리킨다', () => {
    const found = /<link rel="canonical" href="([^"]+)"/.exec(privacyHtml);
    expect(found).not.toBeNull();
    expect(new URL(found![1]).origin).toBe(canonical);
  });

  it('사이트맵이 같은 주소만 담는다', () => {
    const locations = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
    expect(locations.length).toBeGreaterThan(0);
    for (const location of locations) expect(new URL(location).origin).toBe(canonical);
  });

  it('robots.txt 가 우리 사이트맵을 가리킨다', () => {
    const line = /^Sitemap:\s*(\S+)$/m.exec(robots);
    expect(line).not.toBeNull();
    expect(new URL(line![1]).origin).toBe(canonical);
    // 주소만 맞고 파일이 없으면 검색엔진은 404 를 받습니다.
    expect(new URL(line![1]).pathname).toBe('/sitemap.xml');
  });

  it('미리보기 그림이 실제로 있는 파일을 가리킨다', () => {
    // 그림 이름을 바꾸면 미리보기가 빈 카드가 되는데, 공유해 보기 전에는 알 수 없습니다.
    const image = /property="og:image" content="([^"]+)"/.exec(indexHtml)![1];
    const path = new URL(image).pathname;
    expect(existsSync(fileURLToPath(new URL(`../../public${path}`, import.meta.url)))).toBe(
      true,
    );
  });
});
