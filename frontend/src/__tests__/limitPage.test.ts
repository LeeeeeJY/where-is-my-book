import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { DAILY_LIMIT_CODE, LIMIT_PAGE } from '../domain/dailyLimit';

/**
 * **한도 안내 페이지로 가는 길이 여러 파일에 걸쳐 있습니다.**
 *
 * <p>서버가 오류 코드를 실어 보내고, 화면 두 곳이 그 코드를 보고 떠나고, Vercel 이 그 주소를
 * 정적 페이지로 이어 줍니다. 한 곳만 어긋나도 **오류가 나지 않습니다.** 코드의 철자가
 * 다르면 화면은 조용히 예전의 오류 문구로 돌아가고, 주소 규칙이 빠지면 안내 페이지 대신
 * 앱의 첫 화면이 다시 열립니다. 한도를 넘긴 날에만 드러나는 일이라 평소에는 볼 방법이
 * 없습니다.
 *
 * <p>`siteUrl.test.ts` 와 같은 종류입니다. 값이 옳은지가 아니라 **서로 어긋나는지**를 봅니다.
 */
const read = (name: string) =>
  readFileSync(fileURLToPath(new URL(`../../${name}`, import.meta.url)), 'utf8');

const vercel = JSON.parse(read('vercel.json')) as {
  rewrites: { source: string; destination: string }[];
};
const limitHtml = read('public/limit.html');

describe('한도 안내 페이지', () => {
  it('Vercel 이 그 주소를 정적 페이지로 잇고, 그 규칙이 모든 주소를 받는 규칙보다 앞에 있다', () => {
    const sources = vercel.rewrites.map((rule) => rule.source);
    const at = sources.indexOf(LIMIT_PAGE);
    const catchAll = sources.indexOf('/(.*)');

    expect(at).toBeGreaterThanOrEqual(0);
    expect(vercel.rewrites[at].destination).toBe(`${LIMIT_PAGE}.html`);
    // 뒤에 있으면 앞의 규칙이 먼저 받아 앱의 첫 화면이 열립니다.
    expect(at).toBeLessThan(catchAll);
  });

  it('무엇 때문에 막혔는지와 언제 다시 되는지를 말한다', () => {
    expect(limitHtml).toMatch(/정보나루/);
    expect(limitHtml).toMatch(/호출 수/);
    expect(limitHtml).toMatch(/내일 0시/);
  });

  it('「책이 없다」로 읽히지 않게 말한다', () => {
    // 조회에 실패한 화면과 소장한 곳이 없는 화면은 똑같이 「안 나온다」로 보입니다.
    expect(limitHtml).toMatch(/책이 없다는 뜻이 아닙니다/);
  });

  it('돌아갈 길이 있다', () => {
    expect(limitHtml).toMatch(/<a [^>]*href="\/"/);
  });

  it('검색 결과에 나오지 않는다', () => {
    expect(limitHtml).toMatch(/<meta name="robots" content="noindex"/);
    expect(read('public/sitemap.xml')).not.toContain(LIMIT_PAGE);
  });

  it('자바스크립트 없이 열린다', () => {
    // API 서버가 답하지 않는 날에 열리는 페이지입니다. 앱과 서버에 기대면 안 됩니다.
    expect(limitHtml).not.toMatch(/<script/);
  });
});

describe('한도 안내 페이지로 보내는 자리', () => {
  it('검색을 시작하는 두 화면이 같은 함수를 부른다', () => {
    for (const screen of ['src/components/BookSearch.tsx', 'src/components/MultiCheck.tsx']) {
      const source = read(screen);
      expect(source, screen).toMatch(/import \{ leaveIfDailyLimit \} from '\.\.\/domain\/dailyLimit'/);
      expect(source, screen).toMatch(/if \(leaveIfDailyLimit\(error\)\) return;/);
    }
  });

  it('주소를 글자로 적어 직접 옮기는 화면이 없다', () => {
    // 화면마다 따로 판정하면 언젠가 한쪽만 예전 동작으로 남습니다.
    for (const screen of ['BookSearch', 'MultiCheck', 'LoanCheck', 'Shelf', 'LibraryPicker']) {
      expect(read(`src/components/${screen}.tsx`), screen).not.toContain(`'${LIMIT_PAGE}'`);
    }
    expect(read('src/api.ts')).not.toContain(`'${LIMIT_PAGE}'`);
  });

  it('오류 코드의 철자가 서버와 같다', () => {
    const client = read('../backend/src/main/java/kr/wimb/data4library/Data4LibraryClient.java');
    const found = /DAILY_LIMIT_CODE = "([^"]+)"/.exec(client);

    expect(found?.[1]).toBe(DAILY_LIMIT_CODE);
  });
});
