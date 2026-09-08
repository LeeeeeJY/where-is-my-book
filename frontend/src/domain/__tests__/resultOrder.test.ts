import { describe, expect, it } from 'vitest';
import { allChecked, checkedCount, holdingRank, orderByHolding } from '../resultOrder';
import type { HoldingFacts } from '../holdingState';

const HELD: HoldingFacts = { libCodes: ['111001'], complete: true, unreadable: false };
const NONE: HoldingFacts = { libCodes: [], complete: true, unreadable: false };
const UNKNOWN: HoldingFacts = { libCodes: [], complete: false, unreadable: true };

const works = [{ workId: 1 }, { workId: 2 }, { workId: 3 }, { workId: 4 }, { workId: 5 }];

describe('확인이 끝난 목록을 세운다', () => {
  it('소장 → 미소장 → 확인 불가 순이다', () => {
    const facts = new Map<number, HoldingFacts>([
      [1, UNKNOWN],
      [2, NONE],
      [3, HELD],
      [4, NONE],
      [5, HELD],
    ]);
    expect(orderByHolding(works, facts, 3).map((w) => w.workId)).toEqual([3, 5, 2, 4, 1]);
  });

  it('같은 등급 안에서는 원래 순서를 지킨다', () => {
    // 원래 순서는 검색어 일치도입니다. 소장 여부가 같은데 그 순서가 뒤집히면 위에 있던
    // 「제목이 그대로 맞는 책」이 아래로 밀립니다.
    const facts = new Map<number, HoldingFacts>(works.map((w) => [w.workId, HELD]));
    expect(orderByHolding(works, facts, 3).map((w) => w.workId)).toEqual([1, 2, 3, 4, 5]);
  });

  it('도서관을 고르지 않았으면 순서를 흔들지 않는다', () => {
    const facts = new Map<number, HoldingFacts>([[1, NONE], [2, HELD]]);
    expect(orderByHolding(works, facts, 0).map((w) => w.workId)).toEqual([1, 2, 3, 4, 5]);
  });

  it('확인 불가는 미소장보다 아래이지만 같은 등급은 아니다', () => {
    expect(holdingRank(UNKNOWN, 3)).toBeGreaterThan(holdingRank(NONE, 3));
    expect(holdingRank(NONE, 3)).toBeGreaterThan(holdingRank(HELD, 3));
  });

  it('원본 배열을 바꾸지 않는다', () => {
    const facts = new Map<number, HoldingFacts>([[1, NONE], [2, HELD]]);
    const input = [...works];
    orderByHolding(input, facts, 3);
    expect(input.map((w) => w.workId)).toEqual([1, 2, 3, 4, 5]);
  });
});

describe('묶음의 확인이 끝났는지 센다', () => {
  it('실패한 답도 도착한 것으로 친다', () => {
    const facts = new Map<number, HoldingFacts>([[1, UNKNOWN], [2, HELD]]);
    expect(allChecked([{ workId: 1 }, { workId: 2 }], facts)).toBe(true);
    expect(checkedCount([{ workId: 1 }, { workId: 2 }, { workId: 3 }], facts)).toBe(2);
  });

  it('아직 물어보지 않았으면 끝난 것이 아니다', () => {
    expect(allChecked([{ workId: 1 }], null)).toBe(false);
    expect(checkedCount([{ workId: 1 }], null)).toBe(0);
    expect(allChecked([], new Map())).toBe(true);
  });
});
