import type { LoanStatus } from '../api';

/**
 * 대출 상태 문구. **판정은 여기 한 곳에서만 합니다.**
 *
 * <p>이 프로젝트는 원래 대출 상태를 아예 보여 주지 않기로 했었습니다. 정보나루가 주는 값이
 * <b>조회일 기준 전날의 상태</b>라(매뉴얼 11절) 실시간이 아니고, 부정확한 상태를 믿고
 * 헛걸음하는 것이 이 도구를 못 쓰게 만드는 가장 큰 요인이기 때문입니다.
 *
 * <p>보여 주기로 방향을 바꾼 대신, <b>언제 기준인지를 값과 떼어 놓지 않습니다.</b> 「대출
 * 가능」이라는 네 글자만 남으면 사용자는 그것을 지금 상태로 읽습니다. 그래서 문구를 만드는
 * 곳을 하나로 두고, 여기서 항상 기준 날짜를 함께 돌려줍니다.
 */
export type LoanPhrase = { text: string; caution: boolean };

export function loanPhrase(status: LoanStatus): LoanPhrase {
  if (!status.hasBook) {
    // 소장 조회는 있다고 했는데 여기서 없다고 하면, 판본이 다르거나 자료가 빠진 것입니다.
    return { text: `이 도서관에는 없다고 나옵니다 (${status.asOf} 기준)`, caution: true };
  }
  if (status.loanAvailable) {
    return { text: `대출 가능 (${status.asOf} 기준)`, caution: false };
  }
  return { text: `대출 중 (${status.asOf} 기준)`, caution: false };
}

/** 값 옆에 항상 붙는 단서. 「어제 기준」을 한 번 더 풀어서 말합니다. */
export const LOAN_DISCLAIMER =
  '대출 상태는 어제 기준이라 지금과 다를 수 있습니다. 확실한 것은 도서관 페이지에서 확인해 주세요.';
