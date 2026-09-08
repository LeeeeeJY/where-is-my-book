import { holdingState } from './holdingState';
import type { HoldingFacts } from './holdingState';

/**
 * 확인이 끝난 목록을 세우는 순서. 작을수록 위입니다.
 *
 * **확인 불가를 맨 아래에 둡니다.** 「있는 책 먼저」는 확실한 것부터 보겠다는 뜻이고,
 * 확인 불가는 그 축 위에 있지 않습니다. 값을 모르는 것을 아는 것들 사이에 끼워 넣으면
 * 목록을 위에서부터 읽어 내려가는 흐름이 끊깁니다.
 *
 * 다만 **미소장과 한 덩어리로 묶은 것은 아닙니다.** 둘은 다음에 할 일이 다릅니다.
 * 미소장은 서점으로 넘어가야 하는 책이고 확인 불가는 다시 확인하면 있을 수 있는 책이라,
 * 배지와 문구에서는 끝까지 갈라 놓습니다. 여기서 정하는 것은 순서뿐입니다.
 */
export function holdingRank(facts: HoldingFacts | null, selectedCount: number): number {
  switch (holdingState(facts, selectedCount)) {
    case 'held':
      return 0;
    case 'none':
      return 1;
    case 'pending':
      return 2;
    default:
      return 3; // 확인 불가
  }
}

/**
 * 소장 → 미소장 → 확인 불가 순으로 세웁니다. 같은 등급 안에서는 원래 순서(검색어 일치도)를
 * 그대로 둡니다.
 *
 * **확인이 끝난 뒤에 한 번만 부릅니다.** 답이 도착할 때마다 순서가 바뀌면 읽던 자리가
 * 사라집니다. 그래서 화면은 확인이 끝날 때까지 진행 막대를 보이고, 끝난 목록을 이 함수로
 * 세워 한 번에 그립니다.
 */
export function orderByHolding<T extends { workId: number }>(
  works: readonly T[],
  facts: ReadonlyMap<number, HoldingFacts>,
  selectedCount: number,
): T[] {
  return [...works].sort(
    (a, b) =>
      holdingRank(facts.get(a.workId) ?? null, selectedCount) -
      holdingRank(facts.get(b.workId) ?? null, selectedCount),
  );
}

/** 이 묶음의 소장 확인이 전부 도착했는지. 실패한 것도 답이 온 것으로 칩니다. */
export function allChecked<T extends { workId: number }>(
  works: readonly T[],
  facts: ReadonlyMap<number, HoldingFacts> | null,
): boolean {
  return facts !== null && works.every((work) => facts.has(work.workId));
}

/** 이 묶음에서 답이 도착한 수. 진행 막대의 분자입니다. */
export function checkedCount<T extends { workId: number }>(
  works: readonly T[],
  facts: ReadonlyMap<number, HoldingFacts> | null,
): number {
  if (facts === null) return 0;
  return works.filter((work) => facts.has(work.workId)).length;
}
