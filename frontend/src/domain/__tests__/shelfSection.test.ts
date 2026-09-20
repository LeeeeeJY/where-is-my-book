import { describe, expect, it } from 'vitest';
import { sectionLabel } from '../shelfSection';

/**
 * 갈래 이름은 **정보나루가 준 글자**입니다. 떼는 것은 맨 앞의 대주제 한 칸뿐이고,
 * 그 대주제는 지금 보고 있는 서가 그 자체라 모든 줄에서 되풀이됩니다.
 */
describe('갈래 이름 줄이기', () => {
  it('맨 앞의 대주제만 뗀다', () => {
    expect(sectionLabel('문학 > 한국문학 > 소설')).toBe('한국문학 · 소설');
    expect(sectionLabel('문학 > 문학 > 전집, 총서')).toBe('문학 · 전집, 총서');
  });

  it('뗄 것이 없으면 그대로 둔다', () => {
    // 한 칸뿐인데 떼면 이름이 통째로 사라집니다.
    expect(sectionLabel('문학')).toBe('문학');
    expect(sectionLabel('')).toBe('');
  });

  it('구분 기호 주변의 공백이 제각각이어도 같은 이름이 된다', () => {
    // 도서관이 적어 넣은 값이라 무엇이 들어 있을지 모릅니다.
    expect(sectionLabel('문학>한국문학>소설')).toBe('한국문학 · 소설');
    expect(sectionLabel('문학 >  한국문학  > 소설 ')).toBe('한국문학 · 소설');
  });

  it('빈 칸이 섞여 있어도 가운뎃점만 남기지 않는다', () => {
    expect(sectionLabel('문학 > > 소설')).toBe('소설');
  });
});
