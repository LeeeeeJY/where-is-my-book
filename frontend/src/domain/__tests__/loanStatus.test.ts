import { describe, expect, it } from 'vitest';
import { LOAN_DISCLAIMER, loanPhrase } from '../loanStatus';

const YESTERDAY = '2026-09-07';

describe('대출 상태 문구', () => {
  it('언제 기준인지를 값에서 떼어 놓지 않는다', () => {
    // 「대출 가능」 네 글자만 남으면 사용자는 그것을 지금 상태로 읽습니다.
    // 정보나루가 주는 값은 조회일 기준 전날의 것입니다.
    expect(loanPhrase({ hasBook: true, loanAvailable: true, asOf: YESTERDAY }).text)
      .toContain(YESTERDAY);
    expect(loanPhrase({ hasBook: true, loanAvailable: false, asOf: YESTERDAY }).text)
      .toContain(YESTERDAY);
    expect(loanPhrase({ hasBook: false, loanAvailable: false, asOf: YESTERDAY }).text)
      .toContain(YESTERDAY);
  });

  it('빌릴 수 있는지를 구분한다', () => {
    expect(loanPhrase({ hasBook: true, loanAvailable: true, asOf: YESTERDAY }).text)
      .toContain('대출 가능');
    expect(loanPhrase({ hasBook: true, loanAvailable: false, asOf: YESTERDAY }).text)
      .toContain('대출 중');
  });

  it('소장 조회와 어긋나면 주의로 표시한다', () => {
    // 소장한다고 나왔는데 여기서 없다고 하면 판본이 다르거나 자료가 빠진 것입니다.
    const phrase = loanPhrase({ hasBook: false, loanAvailable: false, asOf: YESTERDAY });
    expect(phrase.caution).toBe(true);
    expect(loanPhrase({ hasBook: true, loanAvailable: true, asOf: YESTERDAY }).caution).toBe(false);
  });

  it('단서 문구가 실시간이 아님을 말한다', () => {
    expect(LOAN_DISCLAIMER).toContain('어제');
    expect(LOAN_DISCLAIMER).toContain('다를 수 있');
  });
});
