import type { Library } from './types';

/**
 * 여러 권 확인의 집계.
 *
 * **확인하지 못한 책을 어느 쪽 집계에도 넣지 않는 것이 이 모듈의 전부입니다.**
 * 확인 불가를 미소장에 섞으면 실제로 있는 책을 없다고 답하게 되고, 소장에 섞으면
 * 없는 책을 있다고 답하게 됩니다. 둘 다 헛걸음을 만듭니다.
 *
 * 소장 조회가 도착하는 대로 다시 계산되므로, 순수 함수로 두고 화면에서 매번 부릅니다.
 */

/** 책 한 권의 확인 상태. `pending` 은 아직 조회가 도착하지 않은 것입니다. */
export type BookState = 'pending' | 'held' | 'none' | 'unknown';

export type BookRow = {
  /** 줄 식별자. 화면의 key 이자 집계의 단위입니다. */
  key: string;
  title: string;
  state: BookState;
  /** 소장이 확인된 도서관부호. `state` 가 `held` 일 때만 의미가 있습니다. */
  holdingLibCodes: string[];
};

export type LibraryRank = {
  libCode: string;
  name: string;
  /** 이 도서관에 있는 책 제목 */
  held: string[];
  /** 확인했는데 이 도서관에 없는 책 제목. **확인하지 못한 책은 들어가지 않습니다.** */
  missing: string[];
};

export type TripStep = {
  libCode: string;
  name: string;
  /** 이 도서관을 더해서 새로 채워지는 책 수 */
  added: number;
  /** 여기까지 들렀을 때 빌릴 수 있는 책 수 */
  cumulative: number;
};

/** 확인이 끝난 책만 셉니다. 진행 중이거나 확인하지 못한 책은 어느 쪽도 아닙니다. */
export function isChecked(row: BookRow): boolean {
  return row.state === 'held' || row.state === 'none';
}

export function countByState(rows: readonly BookRow[]): Record<BookState, number> {
  const counts: Record<BookState, number> = { pending: 0, held: 0, none: 0, unknown: 0 };
  for (const row of rows) counts[row.state] += 1;
  return counts;
}

/**
 * 도서관을 소장 권수 내림차순으로 세웁니다.
 *
 * 실제 행동은 "한 곳을 골라서 가는 것"이므로 이것이 기본 화면입니다.
 * 도서관 × 책 행렬은 20개 관에 30권이면 600칸이라 좁은 화면에서 읽을 수 없습니다.
 */
export function rankLibraries(
  rows: readonly BookRow[],
  selected: readonly Library[],
): LibraryRank[] {
  const checked = rows.filter(isChecked);

  return selected
    .map((library) => {
      const held: string[] = [];
      const missing: string[] = [];
      for (const row of checked) {
        if (row.state === 'held' && row.holdingLibCodes.includes(library.libCode)) {
          held.push(row.title);
        } else {
          missing.push(row.title);
        }
      }
      return { libCode: library.libCode, name: library.name, held, missing };
    })
    .filter((rank) => rank.held.length > 0)
    .sort((a, b) => b.held.length - a.held.length || a.name.localeCompare(b.name, 'ko'));
}

/**
 * 「한 곳에서 다 빌리기」. 적은 수의 도서관으로 가장 많은 책을 덮는 조합을 찾습니다.
 *
 * 집합 덮개 문제의 최적해는 계산이 어렵지만, 도서관 20곳 규모에서는 탐욕 방식으로
 * 충분하고 최적해와의 차이도 거의 없습니다.
 */
export function planTrip(
  rows: readonly BookRow[],
  selected: readonly Library[],
): TripStep[] {
  // 확인이 끝나고 어딘가에 있는 책만 대상입니다.
  const remaining = new Set(
    rows.filter((row) => row.state === 'held' && row.holdingLibCodes.length > 0).map((r) => r.key),
  );
  const byKey = new Map(rows.map((row) => [row.key, row]));
  const pool = selected.filter((library) =>
    rows.some((row) => row.state === 'held' && row.holdingLibCodes.includes(library.libCode)),
  );

  const steps: TripStep[] = [];
  const used = new Set<string>();
  let cumulative = 0;

  while (remaining.size > 0) {
    let best: Library | null = null;
    let bestCovered: string[] = [];

    for (const library of pool) {
      if (used.has(library.libCode)) continue;
      const covered = [...remaining].filter((key) =>
        byKey.get(key)!.holdingLibCodes.includes(library.libCode),
      );
      // 동점이면 이름 순으로 정해 결과가 실행마다 흔들리지 않게 합니다.
      if (
        covered.length > bestCovered.length ||
        (best !== null &&
          covered.length === bestCovered.length &&
          library.name.localeCompare(best.name, 'ko') < 0)
      ) {
        best = library;
        bestCovered = covered;
      }
    }

    if (best === null || bestCovered.length === 0) break;
    used.add(best.libCode);
    cumulative += bestCovered.length;
    steps.push({
      libCode: best.libCode,
      name: best.name,
      added: bestCovered.length,
      cumulative,
    });
    for (const key of bestCovered) remaining.delete(key);
  }

  return steps;
}

