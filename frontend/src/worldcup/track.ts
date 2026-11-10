import { track } from '@vercel/analytics';
import { countWorldcupEvent } from '../api';

/**
 * 월드컵의 진행 단계를 셉니다.
 *
 * <h2>두 곳에 보냅니다</h2>
 *
 * <p>Vercel 방문자 수 세기의 사용자 정의 이벤트(`track`)와 우리 서버의 횟수 세기입니다.
 * Vercel 쪽은 요금제에 따라 이 이벤트가 화면에 나오지 않을 수 있어서, <b>우리 서버가 날짜와
 * 단계별 횟수를 따로 들고 있습니다</b>(`GET /api/worldcup/{주제}/events`). 어느 쪽도 누가
 * 눌렀는지는 받지 않습니다.
 *
 * <h2>유입 표시(utm)는 처음 들어올 때 한 번 적어 둡니다</h2>
 *
 * <p>광고 링크에 붙은 값은 첫 주소에만 있고 화면을 넘기면 사라집니다. 그래서 탭이 열려
 * 있는 동안만 기억해 두고 단계마다 함께 보냅니다. <b>방문자 수 세기는 주소의 물음표 뒤를
 * 떼고 보내므로</b>(`main.tsx`) 여기서 따로 싣지 않으면 어느 광고에서 왔는지 알 수 없습니다.
 *
 * <p><b>보내는 것을 늘리면 `public/privacy.html` 도 함께 고칩니다.</b>
 */
export type WorldcupEvent =
  | 'start'
  | 'round_16'
  | 'round_8'
  | 'round_4'
  | 'final'
  | 'complete'
  | 'share'
  | 'save_image'
  | 'find_library';

const UTM_KEYS = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_content', 'utm_term'] as const;
const UTM_STORE = 'wimb.worldcup.utm.v1';
/** 서버가 받아 적는 길이와 같습니다(`WorldcupStats.MAX_UTM_LENGTH`). */
const UTM_MAX = 80;

/**
 * 주소의 유입 표시를 한 줄로 잇습니다. `출처/매체/캠페인/내용/검색어` 순서이고 없는 칸은
 * `-` 입니다. 하나도 없으면 빈 문자열입니다.
 *
 * <p><b>한 줄로 잇는 이유는 속성 수 때문입니다.</b> Vercel 은 이벤트에 붙일 수 있는 속성
 * 수를 요금제로 제한합니다. 다섯 칸을 따로 보내면 그것만으로 넘칩니다.
 */
export function utmFromSearch(search: string): string {
  const params = new URLSearchParams(search);
  const parts = UTM_KEYS.map((key) => (params.get(key) ?? '').replace(/[^A-Za-z0-9_.-]/g, ''));
  while (parts.length > 0 && parts[parts.length - 1] === '') parts.pop();
  if (parts.length === 0) return '';
  return parts.map((part) => part || '-').join('/').slice(0, UTM_MAX);
}

/** 들어온 주소에 유입 표시가 있으면 적어 둡니다. 이미 적어 둔 것은 덮지 않습니다. */
export function rememberUtm(search: string = window.location.search): void {
  const utm = utmFromSearch(search);
  if (!utm) return;
  try {
    if (!sessionStorage.getItem(UTM_STORE)) sessionStorage.setItem(UTM_STORE, utm);
  } catch {
    // 적어 두지 못하면 이번 화면의 주소에 있는 것만 씁니다.
  }
}

export function currentUtm(): string {
  try {
    const stored = sessionStorage.getItem(UTM_STORE);
    if (stored) return stored;
  } catch {
    // 아래에서 주소를 봅니다.
  }
  return utmFromSearch(window.location.search);
}

/** 단계에 닿았을 때 부릅니다. 세는 것이 실패해도 진행을 막지 않습니다. */
export function trackWorldcup(event: WorldcupEvent, themeId: string): void {
  const utm = currentUtm();
  try {
    track(`worldcup_${event}`, utm ? { theme: themeId, utm } : { theme: themeId });
  } catch {
    // 세는 도구가 막혀 있어도 월드컵은 그대로 진행됩니다.
  }
  void countWorldcupEvent(themeId, event, utm).catch(() => {
    // 서버가 답하지 않는 날에도 고르는 일은 계속됩니다.
  });
}

/** 라운드에 들어섰을 때 셀 이름. 32강은 「시작」이 이미 세므로 없습니다. */
export function roundEvent(roundSize: number): WorldcupEvent | null {
  switch (roundSize) {
    case 16:
      return 'round_16';
    case 8:
      return 'round_8';
    case 4:
      return 'round_4';
    case 2:
      return 'final';
    default:
      return null;
  }
}
