import type { WorldcupStats } from '../api';
import type { Book, Theme } from './themes';

/**
 * 비율로 말하기 시작하는 완주 횟수.
 *
 * <p><b>참여가 적을 때 비율은 거짓말에 가깝습니다.</b> 세 번째 완주에서 「전체의 33%」라고
 * 하면 숫자는 맞지만 읽는 사람은 수많은 사람 가운데 셋 중 하나로 받아들입니다. 그때까지는
 * 몇 번째 우승인지를 그대로 말합니다.
 */
export const PERCENT_FROM = 100;

export type RankRow = { book: Book; wins: number; rank: number };

/** 우승 횟수 기준 상위 순위표. 한 번도 우승하지 못한 책은 넣지 않습니다. */
export function ranking(stats: WorldcupStats, theme: Theme, limit = 10): RankRow[] {
  const rows = theme.books
    .map((book, index) => ({ book, wins: stats.wins[book.id] ?? 0, index }))
    .filter((row) => row.wins > 0)
    // 횟수가 같으면 주제 파일에 적힌 차례입니다. 새로 고칠 때마다 순서가 바뀌면
    // 같은 표가 다른 표로 보입니다.
    .sort((a, b) => b.wins - a.wins || a.index - b.index)
    .slice(0, limit);

  // 횟수가 같으면 같은 순위입니다. 「공동 2위」 둘 다음은 4위입니다.
  let rank = 0;
  return rows.map((row, position) => {
    if (position === 0 || rows[position - 1].wins !== row.wins) rank = position + 1;
    return { book: row.book, wins: row.wins, rank };
  });
}

/** 「12%」. 1% 에 못 미치면 0% 라고 하지 않습니다. 고른 사람이 있는데 없다고 읽힙니다. */
export function shareText(wins: number, completions: number): string {
  if (completions <= 0 || wins <= 0) return '0%';
  const percent = (wins / completions) * 100;
  if (percent < 1) return '1% 미만';
  return `${Math.round(percent)}%`;
}

/**
 * 우승한 책 아래에 적는 한 줄.
 *
 * @param counted 방금 이 완주가 집계에 더해졌는지. 더해졌을 때만 「n번째」라고 말합니다.
 *     같은 브라우저에서 다시 한 완주는 더해지지 않으므로 그렇게 말하면 틀립니다.
 * @param own 본인이 방금 고른 결과인지, 공유 링크로 들어온 것인지.
 */
export function winnerLine(
  stats: WorldcupStats,
  bookId: string,
  { counted, own }: { counted: boolean; own: boolean },
): string | null {
  const wins = stats.wins[bookId] ?? 0;
  if (stats.completions >= PERCENT_FROM) {
    if (wins === 0) return null;
    return `이 책을 우승으로 고른 사람은 전체의 ${shareText(wins, stats.completions)}입니다.`;
  }
  if (wins === 0) return null;
  if (own && counted) return `이 책의 ${wins}번째 우승입니다.`;
  return `지금까지 ${wins}번 우승한 책입니다.`;
}

/** 순위표 한 줄의 오른쪽 값. 위의 한 줄과 같은 기준으로 비율과 횟수를 가릅니다. */
export function rankValue(row: RankRow, stats: WorldcupStats): string {
  return stats.completions >= PERCENT_FROM
    ? shareText(row.wins, stats.completions)
    : `${row.wins}번`;
}
