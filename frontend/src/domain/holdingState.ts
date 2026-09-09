import type { BookState } from './tripPlan';

/** 소장 조회 결과에서 화면 판정에 필요한 부분만. 한 권 검색과 여러 권 검색이 같은 모양을 씁니다. */
export type HoldingFacts = {
  libCodes: string[];
  /** 저작에 묶인 판본을 빠짐없이 확인했는지 */
  complete: boolean;
  /** 조회 자체가 실패했는지 */
  unreadable: boolean;
};

/**
 * 소장 결과를 화면의 네 상태 가운데 하나로 판정합니다.
 *
 * **이 함수가 유일한 판정 자리입니다.** 화면마다 따로 판정하면 언젠가 한쪽이
 * 확인 불가를 미소장으로 그리게 되고, 그때는 아무도 눈치채지 못합니다.
 *
 * | 반환 | 뜻 | 화면 문구 |
 * |---|---|---|
 * | `pending` | 아직 물어보지 않았거나 답을 기다리는 중 | "확인 중" |
 * | `unknown` | 물어보지 못했거나 답을 받지 못함 | "확인 불가" |
 * | `held` | 고른 도서관에 있음 | "n곳에 있습니다" |
 * | `none` | **빠짐없이 확인했는데** 없음 | "고른 도서관에는 없습니다" |
 *
 * **미소장이라고 말할 수 있는 것은 마지막 한 줄뿐입니다.**
 *
 * @param facts         소장 조회 결과. 아직 도착하지 않았으면 null
 * @param selectedCount 고른 도서관 수. 0이면 물어본 적이 없습니다
 */
export function holdingState(facts: HoldingFacts | null, selectedCount: number): BookState {
  // 고른 도서관이 없으면 물어볼 필요가 없었던 것입니다. 없다는 뜻이 아닙니다.
  if (selectedCount === 0) return 'pending';
  if (facts === null) return 'pending';
  if (facts.unreadable) return 'unknown';
  if (facts.libCodes.length > 0) return 'held';
  // 찾은 곳이 없는데 확인하지 못한 판본이 남아 있으면 미소장이라고 말할 수 없습니다.
  if (!facts.complete) return 'unknown';
  return 'none';
}
