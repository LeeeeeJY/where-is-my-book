import { describe, expect, it } from 'vitest';
import type { WorldcupStats } from '../../api';
import { PERCENT_FROM, rankValue, ranking, shareText, winnerLine } from '../stats';
import type { Theme } from '../themes';

const theme: Theme = {
  id: 't',
  title: '시험',
  description: '',
  books: ['a', 'b', 'c', 'd'].map((id) => ({
    id,
    title: id.toUpperCase(),
    author: '',
    publisher: '',
    isbn13: '',
    coverSource: '',
  })),
};

const stats = (completions: number, wins: Record<string, number>): WorldcupStats => ({
  themeId: 't',
  completions,
  wins,
});

describe('우승한 책 아래의 한 줄', () => {
  it('참여가 적을 때는 비율이 아니라 몇 번째 우승인지를 말한다', () => {
    expect(winnerLine(stats(3, { a: 1 }), 'a', { counted: true, own: true })).toBe(
      '이 책의 1번째 우승입니다.',
    );
    expect(winnerLine(stats(40, { a: 12 }), 'a', { counted: true, own: true })).toBe(
      '이 책의 12번째 우승입니다.',
    );
  });

  /**
   * 같은 브라우저에서 다시 한 완주는 집계에 더해지지 않습니다. 그때 「n번째 우승」이라고
   * 하면 더해지지 않은 것을 더해진 것처럼 말하게 됩니다.
   */
  it('이번 완주가 집계에 들어가지 않았으면 n번째라고 하지 않는다', () => {
    expect(winnerLine(stats(40, { a: 12 }), 'a', { counted: false, own: true })).toBe(
      '지금까지 12번 우승한 책입니다.',
    );
  });

  it('공유 링크로 들어온 사람에게는 몇 번 우승했는지를 말한다', () => {
    expect(winnerLine(stats(40, { a: 12 }), 'a', { counted: false, own: false })).toBe(
      '지금까지 12번 우승한 책입니다.',
    );
  });

  it('참여가 충분해지면 비율로 말한다', () => {
    expect(winnerLine(stats(PERCENT_FROM, { a: 25 }), 'a', { counted: true, own: true })).toBe(
      '이 책을 우승으로 고른 사람은 전체의 25%입니다.',
    );
  });

  it('집계에 없는 책은 말하지 않는다', () => {
    expect(winnerLine(stats(0, {}), 'a', { counted: false, own: false })).toBeNull();
    expect(winnerLine(stats(500, { b: 500 }), 'a', { counted: false, own: false })).toBeNull();
  });
});

describe('비율', () => {
  it('고른 사람이 있는데 0% 라고 하지 않는다', () => {
    expect(shareText(1, 1000)).toBe('1% 미만');
    expect(shareText(0, 1000)).toBe('0%');
    expect(shareText(126, 1000)).toBe('13%');
  });
});

describe('순위표', () => {
  it('우승 횟수 순이고, 한 번도 우승하지 못한 책은 넣지 않는다', () => {
    const rows = ranking(stats(10, { b: 5, a: 3, d: 2 }), theme);
    expect(rows.map((row) => row.book.id)).toEqual(['b', 'a', 'd']);
    expect(rows.map((row) => row.rank)).toEqual([1, 2, 3]);
  });

  it('횟수가 같으면 같은 순위이고, 다음 순위는 건너뛴다', () => {
    const rows = ranking(stats(10, { a: 4, b: 3, c: 3, d: 1 }), theme);
    expect(rows.map((row) => [row.book.id, row.rank])).toEqual([
      ['a', 1],
      ['b', 2],
      ['c', 2],
      ['d', 4],
    ]);
  });

  it('위에서 정한 수만큼만 보여 준다', () => {
    expect(ranking(stats(10, { a: 4, b: 3, c: 2, d: 1 }), theme, 2)).toHaveLength(2);
  });

  it('주제에 없는 책의 집계는 순위표에 나오지 않는다', () => {
    expect(ranking(stats(10, { ghost: 9, a: 1 }), theme).map((row) => row.book.id)).toEqual(['a']);
  });

  it('값은 위의 한 줄과 같은 기준으로 횟수와 비율을 가른다', () => {
    const few = stats(10, { a: 4 });
    const many = stats(200, { a: 50 });
    expect(rankValue(ranking(few, theme)[0], few)).toBe('4번');
    expect(rankValue(ranking(many, theme)[0], many)).toBe('25%');
  });
});
