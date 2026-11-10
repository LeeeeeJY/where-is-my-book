/**
 * 화면의 탭. **첫 화면과 월드컵 화면이 같은 목록을 씁니다.** 따로 적으면 탭을 하나 더할 때
 * 한쪽에만 들어가고, 그 화면은 다른 쪽에서 갈 수 없는 곳이 됩니다.
 */
export const MODES = [
  ['single', '한 권 검색'],
  ['multi', '여러 권 검색'],
  ['shelf', '서가'],
] as const;

export type Mode = (typeof MODES)[number][0];

/**
 * 넷째 탭. **앞의 셋과 달리 주소가 따로 있는 화면입니다.** 광고와 공유 링크가 이 화면으로
 * 곧바로 들어와야 하고, 도서관 목록을 받지 못한 날에도 열려야 하기 때문입니다. 그래서
 * `MODES` 에 넣지 않고 탭 줄에서만 나란히 둡니다.
 */
export const WORLDCUP_TAB_LABEL = '월드컵';

export function isMode(value: unknown): value is Mode {
  return MODES.some(([key]) => key === value);
}
