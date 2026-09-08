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
    // **여기까지 왔다는 것은 묶인 판본을 전부 물어봤다는 뜻입니다.** 그런데도 없다고 하면
    // 소장 조회(libSrchByBook)와 대출 조회(bookExist)의 답이 서로 어긋난 것입니다.
    // 어느 쪽이 맞는지 우리는 알 수 없으므로 「없습니다」라고 단정하지 않습니다.
    // 단정하면 실제로 있는 책을 없다고 답하는 것이고, 그게 이 도구가 가장 피해야 할 답입니다.
    return {
      text: `소장 목록에는 있는데 대출 정보에는 안 잡힙니다 (${status.asOf} 기준)`,
      caution: true,
    };
  }
  if (status.loanAvailable) {
    return { text: `대출 가능 (${status.asOf} 기준)`, caution: false };
  }
  return { text: `대출 중 (${status.asOf} 기준)`, caution: false };
}

/** 값 옆에 항상 붙는 단서. 「어제 기준」을 한 번 더 풀어서 말합니다. */
export const LOAN_DISCLAIMER =
  '대출 상태는 어제 기준이라 지금과 다를 수 있습니다. 확실한 것은 도서관 페이지에서 확인해 주세요.';
