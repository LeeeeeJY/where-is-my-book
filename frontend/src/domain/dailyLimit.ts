/**
 * 정보나루의 하루 호출 한도를 다 쓴 날, 검색을 누른 사람을 안내 페이지로 보냅니다.
 *
 * <p>서버는 우리 쪽에서 먼저 멈추는 선을 두지 않습니다. 한도에 닿았다는 것은 정보나루가
 * 주는 오류로만 알고, 그 오류를 503 과 `code: "outOflimit"` 으로 화면까지 실어 보냅니다
 * (서버의 `ApiErrorAdvice`). 그 뒤 5분 동안은 서버가 정보나루를 부르지 않으므로 다시 눌러도
 * 같은 답이 옵니다. 오류 문구 한 줄로 두면 사람은 그것을 잠깐의 고장으로 읽고 계속 다시
 * 누르는데, 내일이 되기 전에는 낫지 않습니다. 그래서 따로 설명하는 페이지로 보냅니다.
 *
 * <p><b>보내는 자리는 검색을 시작하는 두 곳뿐입니다.</b> 한 권 검색(`BookSearch`)과 여러 권
 * 검색의 목록 확인(`MultiCheck`)입니다. 소장 확인과 대출 확인이 막힌 것은 지금처럼
 * 「확인 불가」로 둡니다. 그 화면에는 이미 받은 결과가 있고 캐시에 남은 소장은 계속 답할 수
 * 있는데, 페이지를 떠나면 그것을 잃습니다. 서가 탭은 정보나루를 부르지 않아 한도와 무관하게
 * 동작합니다. 도서관 목록을 못 받은 것도 보내지 않습니다. 첫 화면을 여는 것만으로 안내
 * 페이지에 갇히게 됩니다.
 *
 * <p><b>두 자리가 같은 함수를 씁니다.</b> 화면마다 따로 판정하면 언젠가 한쪽만 예전의 오류
 * 문구로 남습니다. `src/__tests__/limitPage.test.ts` 가 두 화면이 이 함수를 부르는지,
 * 코드의 철자가 서버와 같은지를 함께 봅니다.
 */

/** 안내 페이지의 주소. `public/limit.html` 이고 `vercel.json` 이 이 주소로 이어 줍니다. */
export const LIMIT_PAGE = '/limit';

/**
 * 정보나루가 하루 한도를 넘겼을 때 주는 오류 코드. 철자는 정보나루가 주는 그대로입니다
 * (`Of` 의 O 가 대문자, `limit` 의 l 이 소문자). 서버의 `Data4LibraryClient.DAILY_LIMIT_CODE`
 * 와 같아야 합니다.
 */
export const DAILY_LIMIT_CODE = 'outOflimit';

/**
 * 서버가 「정보나루의 오늘 몫을 다 썼다」고 답한 것인지.
 *
 * <p>주소별 호출 제한(429, `clientDailyLimit`)은 여기에 들지 않습니다. 그것은 이 주소 하나의
 * 몫을 다 쓴 것이고 문구가 이미 그렇게 말합니다. 안내 페이지의 「정보나루의 호출 수 제한」과는
 * 다른 이야기입니다.
 */
export function isDailyLimit(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  return (error as { code?: unknown }).code === DAILY_LIMIT_CODE;
}

/** 주소를 옮기는 데 필요한 것만 받습니다. 테스트에서 가짜를 끼우려고 밖으로 뺐습니다. */
export type Navigator = { assign(url: string): void };

/**
 * 한도 오류면 안내 페이지로 떠나고 참을 돌려줍니다. 아니면 아무 일도 하지 않습니다.
 *
 * <p><b>`replace` 가 아니라 `assign` 입니다.</b> 뒤로 가기를 누르면 검색하던 화면으로
 * 돌아갈 수 있어야 합니다. 고른 도서관은 주소와 이 기기에 남아 있어 그대로 돌아옵니다.
 */
export function leaveIfDailyLimit(
  error: unknown,
  navigator: Navigator | null = typeof window === 'undefined' ? null : window.location,
): boolean {
  if (!isDailyLimit(error)) return false;
  navigator?.assign(LIMIT_PAGE);
  return true;
}
