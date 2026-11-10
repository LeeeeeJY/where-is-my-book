import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { editionLine } from '../editionLabels';

describe('이름표 한 줄', () => {
  it('받은 이름표를 그대로 이어 적는다', () => {
    // 서버가 「큰글자책」을 받은 글자 그대로 줍니다. 화면이 다듬지 않습니다.
    expect(editionLine(['큰글자책'])).toBe('판본 큰글자책');
    expect(editionLine(['큰글자책', '양장 (2026)'])).toBe('판본 큰글자책 · 양장 (2026)');
  });

  it('이름표가 없으면 줄을 만들지 않는다', () => {
    // 빈 줄이 남으면 「판본」이라는 말만 덩그러니 나갑니다.
    expect(editionLine([])).toBeNull();
    expect(editionLine(undefined)).toBeNull();
    expect(editionLine(null)).toBeNull();
    expect(editionLine(['  '])).toBeNull();
  });
});

describe('이름표를 그리는 자리', () => {
  const read = (name: string) =>
    readFileSync(fileURLToPath(new URL(`../../components/${name}`, import.meta.url)), 'utf8');

  it('한 권 검색과 여러 권 검색이 같은 함수로 그린다', () => {
    // 여러 권 검색의 후보에는 이름표가 없어서, 큰글자책 판과 보통 판이 표제·저자·출판사가
    // 똑같은 후보 둘로 나왔습니다. 한쪽에서만 그리면 같은 일이 되풀이됩니다.
    for (const file of ['BookSearch.tsx', 'MultiCheck.tsx']) {
      expect(read(file), `${file} 가 이름표를 그리지 않습니다`).toContain('editionLine(');
    }
  });

  it('여러 권 검색은 후보와 고른 책 양쪽에 그린다', () => {
    // 후보에만 그리면 고르고 난 카드에서 어느 판을 골랐는지가 다시 사라집니다.
    const uses = read('MultiCheck.tsx').match(/editionLine\(/g) ?? [];
    expect(uses.length).toBeGreaterThanOrEqual(2);
  });
});
