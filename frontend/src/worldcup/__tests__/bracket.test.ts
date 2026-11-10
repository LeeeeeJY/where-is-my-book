import { describe, expect, it } from 'vitest';
import {
  fitsTheme,
  isBracketSize,
  newProgress,
  pick,
  roundLabel,
  shuffle,
  standing,
  type Progress,
} from '../bracket';

const ids = (count: number) => Array.from({ length: count }, (_, i) => `b${i + 1}`);

/** 늘 왼쪽 책을 고르며 끝까지 갑니다. */
function playLeft(progress: Progress): Progress {
  let now = progress;
  for (;;) {
    const at = standing(now);
    if (!at?.pair) return now;
    now = pick(now, at.pair[0]);
  }
}

describe('대진', () => {
  it('서른두 권이면 32강 16번, 16강 8번, 8강 4번, 4강 2번, 결승 1번이다', () => {
    let progress: Progress = { v: 1, order: ids(32), picks: [] };
    const seen: string[] = [];
    for (;;) {
      const at = standing(progress)!;
      if (!at.pair) break;
      seen.push(`${roundLabel(at.roundSize)} ${at.matchIndex + 1}/${at.matchCount}`);
      progress = pick(progress, at.pair[0]);
    }
    expect(seen).toHaveLength(31);
    expect(seen[0]).toBe('32강 1/16');
    expect(seen[2]).toBe('32강 3/16');
    expect(seen[16]).toBe('16강 1/8');
    expect(seen[24]).toBe('8강 1/4');
    expect(seen[28]).toBe('4강 1/2');
    expect(seen[30]).toBe('결승 1/1');
  });

  it('끝나면 우승, 준우승, 4강에서 떨어진 두 권이 나온다', () => {
    const done = standing(playLeft({ v: 1, order: ids(8), picks: [] }))!;
    // 왼쪽만 고르면 b1 이 우승하고, 결승 상대는 b5, 4강에서 떨어진 것은 b3 과 b7 입니다.
    expect(done.finish).toEqual({ winner: 'b1', runnerUp: 'b5', semifinalists: ['b3', 'b7'] });
    expect(done.matches).toHaveLength(7);
    expect(done.matches[0]).toEqual(['b1', 'b2']);
    expect(done.matches[6]).toEqual(['b1', 'b5']);
  });

  it('승패 기록에서 진 책은 다시 나오지 않고 우승한 책만 끝까지 지지 않는다', () => {
    const order = shuffle(ids(32), mulberry(7));
    let progress: Progress = { v: 1, order, picks: [] };
    const random = mulberry(11);
    for (;;) {
      const at = standing(progress)!;
      if (!at.pair) break;
      progress = pick(progress, at.pair[random() < 0.5 ? 0 : 1]);
    }
    const done = standing(progress)!;
    const lost = new Set<string>();
    for (const [won, fell] of done.matches) {
      expect(lost.has(won)).toBe(false);
      expect(lost.has(fell)).toBe(false);
      lost.add(fell);
    }
    expect(lost.size).toBe(31);
    expect(lost.has(done.finish!.winner)).toBe(false);
  });

  it('지금 견주는 두 권이 아닌 책은 고를 수 없다', () => {
    const progress: Progress = { v: 1, order: ids(4), picks: [] };
    expect(pick(progress, 'b3')).toBe(progress);
    expect(pick(progress, 'b2').picks).toEqual(['b2']);
  });

  it('끝난 대진에서는 더 고를 수 없다', () => {
    const done = playLeft({ v: 1, order: ids(4), picks: [] });
    expect(pick(done, 'b1')).toBe(done);
  });
});

describe('저장해 둔 기록', () => {
  it('대진과 맞지 않으면 쓰지 않는다', () => {
    // 고른 책이 그 대결의 두 권이 아닙니다.
    expect(standing({ v: 1, order: ids(4), picks: ['b3'] })).toBeNull();
    // 대진보다 깁니다.
    expect(standing({ v: 1, order: ids(4), picks: ['b1', 'b3', 'b1', 'b1'] })).toBeNull();
    // 2의 거듭제곱이 아닙니다.
    expect(standing({ v: 1, order: ids(6), picks: [] })).toBeNull();
    // 같은 책이 두 번 있습니다.
    expect(standing({ v: 1, order: ['a', 'a', 'b', 'c'], picks: [] })).toBeNull();
  });

  it('주제의 책이 바뀌었으면 처음부터 다시 한다', () => {
    const saved: Progress = { v: 1, order: ids(4), picks: ['b1'] };
    expect(fitsTheme(saved, ids(4))).toBe(true);
    expect(fitsTheme(saved, ['b1', 'b2', 'b3', 'x'])).toBe(false);
    expect(fitsTheme(saved, ids(8))).toBe(false);
  });
});

describe('섞기', () => {
  it('책을 잃거나 늘리지 않는다', () => {
    const shuffled = shuffle(ids(32), mulberry(3));
    expect([...shuffled].sort()).toEqual([...ids(32)].sort());
  });

  it('시작할 때마다 순서가 다르다', () => {
    const a = newProgress(ids(32), mulberry(1)).order;
    const b = newProgress(ids(32), mulberry(2)).order;
    expect(a).not.toEqual(b);
  });

  it('후보 수는 2의 거듭제곱만 받는다', () => {
    expect([2, 4, 8, 16, 32, 64].every(isBracketSize)).toBe(true);
    expect([0, 1, 3, 6, 30, 33].some(isBracketSize)).toBe(false);
  });
});

/** 씨앗을 주면 늘 같은 수열이 나오는 난수. 시험이 실행마다 달라지지 않게 합니다. */
function mulberry(seed: number): () => number {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
