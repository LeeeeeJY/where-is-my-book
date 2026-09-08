import { describe, expect, it } from 'vitest';
import { LOAN_DISCLAIMER, loanPhrase, loanTallyPhrase } from '../loanStatus';

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
    const phrase = loanPhrase({ hasBook: false, loanAvailable: false, asOf: YESTERDAY });
    expect(phrase.caution).toBe(true);
    expect(loanPhrase({ hasBook: true, loanAvailable: true, asOf: YESTERDAY }).caution).toBe(false);
  });

  it('어긋났을 때 「없습니다」라고 단정하지 않는다', () => {
    // 여기까지 왔다는 것은 묶인 판본을 전부 물어봤는데도 없다고 나왔다는 뜻입니다.
    // 소장 조회와 대출 조회의 답이 어긋난 것이고, 어느 쪽이 맞는지 우리는 모릅니다.
    // 단정하면 실제로 있는 책을 없다고 답하게 되고, 그게 이 도구가 가장 피해야 할 답입니다.
    const text = loanPhrase({ hasBook: false, loanAvailable: false, asOf: YESTERDAY }).text;
    expect(text).toContain('소장 목록에는 있는데');
    expect(text).not.toContain('없습니다');
  });

  it('단서 문구가 실시간이 아님을 말한다', () => {
    expect(LOAN_DISCLAIMER).toContain('어제');
    expect(LOAN_DISCLAIMER).toContain('다를 수 있');
  });
});

describe('여러 권을 모아 말하는 문구', () => {
  it('날짜를 항상 붙인다', () => {
    expect(loanTallyPhrase(3, 5, 0, YESTERDAY).text).toBe(`5권 중 3권 대출 가능 (${YESTERDAY} 기준)`);
  });

  it('못 물어본 권수를 「빌릴 수 없다」와 섞지 않는다', () => {
    const phrase = loanTallyPhrase(2, 5, 1, YESTERDAY);
    expect(phrase.text).toContain('1권은 확인 못 함');
    expect(phrase.caution).toBe(true);
  });

  it('한 권도 답을 못 받았으면 확인하지 못했다고 말한다', () => {
    const phrase = loanTallyPhrase(0, 5, 5, null);
    expect(phrase.text).toContain('확인하지 못했습니다');
    expect(phrase.caution).toBe(true);
  });
});

