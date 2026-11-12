import { describe, expect, it } from 'vitest';
import { ApiUnavailable, ApiUnreadable } from '../../api';
import { DAILY_LIMIT_CODE, LIMIT_PAGE, isDailyLimit, leaveIfDailyLimit } from '../dailyLimit';

/** 어디로 보냈는지 적어 두는 가짜 주소창입니다. */
function recorder() {
  const went: string[] = [];
  return { went, assign: (url: string) => void went.push(url) };
}

describe('정보나루의 하루 한도를 다 쓴 날', () => {
  it('서버가 outOflimit 을 실어 보내면 한도 안내 페이지로 떠난다', () => {
    const location = recorder();
    const error = new ApiUnreadable(503, 'outOflimit', '오늘 확인할 수 있는 횟수를 다 썼습니다.');

    expect(leaveIfDailyLimit(error, location)).toBe(true);
    expect(location.went).toEqual([LIMIT_PAGE]);
  });

  it('그 밖의 실패에는 떠나지 않는다', () => {
    // 떠나면 보고 있던 화면을 잃습니다. 잠깐 흔들린 것까지 「내일 다시 오세요」로 보내면
    // 다시 누르면 될 것을 하루 미루게 만듭니다.
    const others: unknown[] = [
      new ApiUnreadable(503, 'authErr', '연결하지 못하고 있습니다.'),
      new ApiUnreadable(503, undefined, '지금은 확인할 수 없습니다.'),
      new ApiUnreadable(503),
      // 이 주소 하나의 몫을 다 쓴 것입니다. 정보나루의 한도와는 다른 이야기입니다.
      new ApiUnreadable(429, 'clientDailyLimit', '오늘은 더 확인할 수 없습니다.'),
      new ApiUnreadable(429, 'clientRateLimit', '잠시 뒤 다시 해 주세요.'),
      new ApiUnavailable('API 서버에 연결하지 못했습니다.'),
      new Error('outOflimit'),
      null,
      undefined,
      'outOflimit',
    ];
    for (const error of others) {
      const location = recorder();
      expect(leaveIfDailyLimit(error, location)).toBe(false);
      expect(location.went).toEqual([]);
      expect(isDailyLimit(error)).toBe(false);
    }
  });

  it('철자를 글자 그대로 견준다', () => {
    // 정보나루가 주는 코드는 Of 의 O 만 대문자입니다. 대소문자를 고쳐 적으면 한 번도 맞지
    // 않고, 화면은 조용히 예전의 오류 문구로 돌아갑니다.
    expect(DAILY_LIMIT_CODE).toBe('outOflimit');
    expect(isDailyLimit(new ApiUnreadable(503, 'outOfLimit'))).toBe(false);
    expect(isDailyLimit(new ApiUnreadable(503, 'OUTOFLIMIT'))).toBe(false);
  });

  it('주소창이 없는 곳(테스트, 서버 렌더링)에서도 죽지 않는다', () => {
    expect(leaveIfDailyLimit(new ApiUnreadable(503, 'outOflimit'), null)).toBe(true);
  });
});
