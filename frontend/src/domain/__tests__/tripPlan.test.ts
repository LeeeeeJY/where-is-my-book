import { describe, expect, it } from 'vitest';
import { countByState, fullCoverage, planTrip, rankLibraries } from '../tripPlan';
import type { BookRow } from '../tripPlan';
import type { Library } from '../types';

function library(libCode: string, name: string): Library {
  return {
    libCode,
    shortId: Number(libCode),
    name,
    sido: '경기',
    sigungu: '성남시',
    latitude: null,
    longitude: null,
    homepageUrl: null,
  };
}

const 중원 = library('141053', '성남시중원도서관');
const 분당 = library('141054', '분당도서관');
const 판교 = library('141055', '판교도서관');
const LIBS = [중원, 분당, 판교];

function held(key: string, title: string, ...libCodes: string[]): BookRow {
  return { key, title, state: 'held', holdingLibCodes: libCodes };
}

function none(key: string, title: string): BookRow {
  return { key, title, state: 'none', holdingLibCodes: [] };
}

function unknown(key: string, title: string): BookRow {
  return { key, title, state: 'unknown', holdingLibCodes: [] };
}

describe('확인하지 못한 책을 집계에 넣지 않는다', () => {
  it('확인 불가는 소장에도 미소장에도 들어가지 않는다', () => {
    const rows = [held('a', '코스모스', 중원.libCode), unknown('b', '사피엔스')];
    const ranks = rankLibraries(rows, LIBS);

    const 중원순위 = ranks.find((r) => r.libCode === 중원.libCode)!;
    expect(중원순위.held).toEqual(['코스모스']);
    // 사피엔스는 확인하지 못했으므로 "이 도서관에 없는 책"이 아닙니다.
    expect(중원순위.missing).toEqual([]);
  });

  it('조회가 도착하지 않은 책도 마찬가지다', () => {
    const rows: BookRow[] = [
      held('a', '코스모스', 중원.libCode),
      { key: 'b', title: '총 균 쇠', state: 'pending', holdingLibCodes: [] },
    ];
    const 중원순위 = rankLibraries(rows, LIBS).find((r) => r.libCode === 중원.libCode)!;
    expect(중원순위.missing).toEqual([]);
  });

  it('확인이 끝나고 없는 책만 미소장으로 센다', () => {
    const rows = [held('a', '코스모스', 중원.libCode), none('b', '사피엔스')];
    const 중원순위 = rankLibraries(rows, LIBS).find((r) => r.libCode === 중원.libCode)!;
    expect(중원순위.missing).toEqual(['사피엔스']);
  });

  it('상태별 권수를 센다', () => {
    const rows = [held('a', 'A', 중원.libCode), none('b', 'B'), unknown('c', 'C')];
    expect(countByState(rows)).toEqual({ pending: 0, held: 1, none: 1, unknown: 1 });
  });
});

describe('도서관 순위', () => {
  it('소장 권수 내림차순으로 세운다', () => {
    const rows = [
      held('a', '코스모스', 중원.libCode, 분당.libCode),
      held('b', '사피엔스', 중원.libCode),
      held('c', '총 균 쇠', 중원.libCode, 판교.libCode),
    ];
    const ranks = rankLibraries(rows, LIBS);
    expect(ranks.map((r) => r.name)).toEqual(['성남시중원도서관', '분당도서관', '판교도서관']);
    expect(ranks[0].held).toHaveLength(3);
  });

  it('한 권도 없는 도서관은 순위에 넣지 않는다', () => {
    const ranks = rankLibraries([held('a', '코스모스', 중원.libCode)], LIBS);
    expect(ranks.map((r) => r.libCode)).toEqual([중원.libCode]);
  });
});

describe('한 곳에서 다 빌리기', () => {
  it('가장 많이 덮는 도서관부터 고른다', () => {
    const rows = [
      held('a', 'A', 중원.libCode, 분당.libCode),
      held('b', 'B', 중원.libCode),
      held('c', 'C', 중원.libCode),
      held('d', 'D', 분당.libCode),
      held('e', 'E', 판교.libCode),
    ];
    const plan = planTrip(rows, LIBS);

    expect(plan[0].name).toBe('성남시중원도서관');
    expect(plan[0].added).toBe(3);
    expect(plan[0].cumulative).toBe(3);
    // 그다음은 남은 D 와 E 를 각각 덮는 두 곳입니다.
    expect(plan).toHaveLength(3);
    expect(plan[plan.length - 1].cumulative).toBe(5);
  });

  it('한 곳으로 전부 덮이면 한 곳만 제안한다', () => {
    const rows = [held('a', 'A', 중원.libCode), held('b', 'B', 중원.libCode)];
    const plan = planTrip(rows, LIBS);
    expect(plan).toHaveLength(1);
    expect(plan[0].cumulative).toBe(2);
  });

  it('확인하지 못한 책은 계획에 넣지 않는다', () => {
    const rows = [held('a', 'A', 중원.libCode), unknown('b', 'B')];
    const plan = planTrip(rows, LIBS);
    expect(plan[0].cumulative).toBe(1);
  });

  it('빌릴 수 있는 책이 없으면 빈 계획이다', () => {
    expect(planTrip([none('a', 'A'), unknown('b', 'B')], LIBS)).toEqual([]);
  });

  it('동점이면 이름 순으로 정해 결과가 흔들리지 않는다', () => {
    const rows = [held('a', 'A', 분당.libCode, 판교.libCode)];
    expect(planTrip(rows, LIBS)[0].name).toBe('분당도서관');
    // 도서관 순서를 바꿔도 같은 답이 나와야 합니다.
    expect(planTrip(rows, [판교, 분당, 중원])[0].name).toBe('분당도서관');
  });
});

describe('한 곳에서 다 빌릴 수 있는 도서관을 고른다', () => {
  it('어딘가에 있는 책 전부를 가진 도서관만 남긴다', () => {
    const rows = [
      held('a', '코스모스', 중원.libCode, 분당.libCode),
      held('b', '사피엔스', 중원.libCode, 판교.libCode),
    ];
    expect(fullCoverage(rows, LIBS).map((l) => l.libCode)).toEqual([중원.libCode]);
  });

  it('여럿이면 고른 순서 그대로 전부 돌려준다', () => {
    const rows = [held('a', '코스모스', 중원.libCode, 분당.libCode), held('b', '사피엔스', 분당.libCode, 중원.libCode)];
    expect(fullCoverage(rows, LIBS).map((l) => l.libCode)).toEqual([중원.libCode, 분당.libCode]);
  });

  it('확인하지 못한 책과 없는 책은 세지 않는다', () => {
    // 확인 불가를 「그 도서관에 없다」로 세면 다 빌릴 수 있는 곳이 사라지고,
    // 「있다」로 세면 없는 책을 있다고 답하게 됩니다. 어느 쪽에도 넣지 않습니다.
    const rows = [held('a', '코스모스', 중원.libCode), unknown('b', '사피엔스'), none('c', '데미안')];
    expect(fullCoverage(rows, LIBS).map((l) => l.libCode)).toEqual([중원.libCode]);
  });

  it('어딘가에 있는 책이 없으면 비어 있다', () => {
    expect(fullCoverage([none('a', '코스모스'), unknown('b', '사피엔스')], LIBS)).toEqual([]);
    expect(fullCoverage([], LIBS)).toEqual([]);
  });
});


describe('도서관 순위의 거리와 「전부 있음」', () => {
  const rows = [
    held('a', '코스모스', 중원.libCode, 분당.libCode, 판교.libCode),
    held('b', '사피엔스', 중원.libCode, 분당.libCode),
    none('c', '데미안'),
  ];

  it('같은 권수 안에서는 가까운 곳이 먼저이고 거리를 모르는 곳은 뒤다', () => {
    const km = new Map([[중원.libCode, 5], [분당.libCode, 1.2]]);
    const ranks = rankLibraries(rows, LIBS, (library) => km.get(library.libCode) ?? null);
    // 중원과 분당은 두 권씩이라 동률인데, 분당이 더 가깝습니다. 판교는 한 권이라 맨 뒤입니다.
    expect(ranks.map((r) => r.name)).toEqual(['분당도서관', '성남시중원도서관', '판교도서관']);
    expect(ranks[0].km).toBe(1.2);
    expect(ranks[2].km).toBeNull();
  });

  it('거리를 모르는 도서관끼리는 이름 순이고, 아는 곳이 앞이다', () => {
    const km = new Map([[중원.libCode, 3]]);
    const both = [held('a', 'A', 분당.libCode, 판교.libCode, 중원.libCode)];
    const ranks = rankLibraries(both, [판교, 분당, 중원], (l) => km.get(l.libCode) ?? null);
    expect(ranks.map((r) => r.name)).toEqual(['성남시중원도서관', '분당도서관', '판교도서관']);
  });

  it('거리를 넘기지 않으면 예전과 같이 권수와 이름으로만 세운다', () => {
    const ranks = rankLibraries(rows, LIBS);
    expect(ranks.map((r) => r.name)).toEqual(['분당도서관', '성남시중원도서관', '판교도서관']);
    expect(ranks.every((r) => r.km === null)).toBe(true);
  });

  it('어딘가에 있는 책 전부를 가진 도서관만 full 이다', () => {
    const ranks = rankLibraries(rows, LIBS);
    expect(ranks.find((r) => r.libCode === 중원.libCode)!.full).toBe(true);
    expect(ranks.find((r) => r.libCode === 분당.libCode)!.full).toBe(true);
    expect(ranks.find((r) => r.libCode === 판교.libCode)!.full).toBe(false);
    // fullCoverage 와 같은 답이어야 합니다. 두 판정이 갈리면 화면의 표시와 문장이 어긋납니다.
    expect(ranks.filter((r) => r.full).map((r) => r.libCode).sort())
      .toEqual(fullCoverage(rows, LIBS).map((l) => l.libCode).sort());
  });

  it('확인하지 못한 책은 full 판정에서도 세지 않는다', () => {
    const withUnknown = [held('a', 'A', 중원.libCode), unknown('b', 'B')];
    expect(rankLibraries(withUnknown, LIBS)[0].full).toBe(true);
  });

  it('찾은 책이 없으면 full 인 도서관도 없다', () => {
    expect(rankLibraries([none('a', 'A')], LIBS)).toEqual([]);
  });
});
