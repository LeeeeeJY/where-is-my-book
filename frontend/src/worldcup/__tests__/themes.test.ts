import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { isBracketSize } from '../bracket';
import { resultMeta } from '../meta';
import { THEMES, coverPath } from '../themes';

/**
 * **주제 파일과 그것에서 만들어 낸 것들이 서로 맞는지 봅니다.**
 *
 * <p>주제를 더할 때 코드는 고치지 않지만 `node tools/make-worldcup-assets.mjs` 는 돌려야
 * 합니다. 돌리지 않아도 화면은 멀쩡히 뜹니다. 표지는 제목으로 그린 대체 표지가 나가고,
 * 공유 미리보기는 그림 없는 카드가 되고, 서버는 그 주제의 완주를 조용히 거절합니다.
 * **어느 것도 오류로 드러나지 않아서** 여기서 붙잡습니다.
 */
const fromFrontend = (path: string) => fileURLToPath(new URL(`../../../${path}`, import.meta.url));

const ID = /^[a-z0-9][a-z0-9-]{0,39}$/;

/** ISBN-13 의 체크디지트. 옮겨 적다 한 자리가 틀리면 여기서 걸립니다. */
function validIsbn13(isbn: string): boolean {
  if (!/^\d{13}$/.test(isbn)) return false;
  const sum = [...isbn].reduce((acc, digit, i) => acc + Number(digit) * (i % 2 === 0 ? 1 : 3), 0);
  return sum % 10 === 0;
}

describe('월드컵 주제', () => {
  it('주제가 하나 이상 있다', () => {
    expect(THEMES.length).toBeGreaterThan(0);
  });

  it.each(THEMES)('$id: 이름표가 주소에 쓸 수 있는 모양이고 겹치지 않는다', (theme) => {
    expect(theme.id).toMatch(ID);
    const ids = theme.books.map((book) => book.id);
    for (const id of ids) expect(id).toMatch(ID);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it.each(THEMES)('$id: 책 수가 둘씩 짝이 끝까지 맞는 수다', (theme) => {
    expect(isBracketSize(theme.books.length)).toBe(true);
  });

  it.each(THEMES)('$id: 책마다 제목·저자·출판사와 맞는 ISBN 이 있다', (theme) => {
    for (const book of theme.books) {
      expect(book.title.trim(), book.id).not.toBe('');
      expect(book.author.trim(), book.id).not.toBe('');
      expect(book.publisher.trim(), book.id).not.toBe('');
      expect(validIsbn13(book.isbn13), `${book.id} ${book.isbn13}`).toBe(true);
    }
    // 같은 판을 두 번 넣으면 같은 책끼리 겨루게 됩니다.
    expect(new Set(theme.books.map((b) => b.isbn13)).size).toBe(theme.books.length);
  });

  it.each(THEMES)('$id: 표지와 공유 미리보기 그림이 책마다 있다', (theme) => {
    const missing: string[] = [];
    for (const book of theme.books) {
      const cover = `public${coverPath(theme.id, book.id)}`;
      const preview = `public${resultMeta(theme.id, theme, book).image}`;
      if (!existsSync(fromFrontend(cover))) missing.push(cover);
      if (!existsSync(fromFrontend(preview))) missing.push(preview);
    }
    // 비어 있지 않으면 `node tools/make-worldcup-assets.mjs` 를 돌려야 합니다.
    expect(missing).toEqual([]);
  });

  /**
   * 서버는 명단에 있는 책의 완주만 셉니다. 명단이 주제 파일과 어긋나면 화면에는 아무
   * 이상이 없는데 그 책이 낀 완주가 전부 거절되고, 화면은 통계 자리만 비웁니다.
   */
  it('서버의 책 명단이 주제 파일과 같다', () => {
    const roster = readFileSync(
      fromFrontend('../backend/src/main/resources/worldcup/rosters.tsv'),
      'utf8',
    )
      .split('\n')
      .filter((line) => line.trim() && !line.startsWith('#'));
    const expected = THEMES.flatMap((theme) => theme.books.map((book) => `${theme.id}\t${book.id}`));
    // 다르면 `node tools/make-worldcup-assets.mjs` 를 돌려야 합니다.
    expect(roster).toEqual(expected);
  });
});
