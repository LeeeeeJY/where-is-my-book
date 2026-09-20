import { describe, expect, it } from 'vitest';
import { sectionLabel, sectionsWorthShowing } from '../shelfSection';

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

/**
 * 실측으로 부천 어느 자료실(7,072권)에 갈래가 **예순세 가지**였고 그중 **서른네 가지가
 * 한 자릿수 권수**였습니다. 목록의 첫 줄이 「중국문학 · 르포르타주 및 기타 (2권)」이라,
 * 그것을 고르려고 목록을 여는 사람은 없습니다.
 */
describe('목록에 올릴 갈래 고르기', () => {
  const 서가 = [
    { name: '문학 > 문학 > 문학', count: 1 },
    { name: '문학 > 문학 > 전집, 총서', count: 817 },
    { name: '문학 > 중국문학 > 희곡', count: 1 },
    { name: '문학 > 한국문학 > 소설', count: 1964 },
  ];

  it('걸어갈 만한 구역만 남긴다', () => {
    expect(sectionsWorthShowing(서가, '').map((one) => one.count)).toEqual([817, 1964]);
  });

  /**
   * **지금 서 있는 갈래는 작아도 남깁니다.** 목록이 보여 주는 값이 곧 「지금 어느 갈래
   * 앞인가」라, 잘려 나가면 목록이 빈칸이 됩니다. 스크롤하다 작은 갈래에 들어설 때마다
   * 이름이 사라졌다 나타나면 고장으로 읽힙니다.
   */
  it('지금 서 있는 갈래는 한 권짜리여도 남긴다', () => {
    const 보이는것 = sectionsWorthShowing(서가, '문학 > 중국문학 > 희곡');

    expect(보이는것.map((one) => one.name)).toContain('문학 > 중국문학 > 희곡');
    expect(보이는것).toHaveLength(3);
  });

  it('서가에 선 차례를 흐트러뜨리지 않는다', () => {
    const 보이는것 = sectionsWorthShowing(서가, '문학 > 중국문학 > 희곡');

    expect(보이는것.map((one) => 서가.indexOf(one))).toEqual([1, 2, 3]);
  });

  it('갈래가 없으면 빈 목록이다', () => {
    expect(sectionsWorthShowing([], '문학 > 한국문학 > 소설')).toEqual([]);
  });
});
