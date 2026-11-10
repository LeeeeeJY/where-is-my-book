import { useSyncExternalStore } from 'react';

/**
 * 주소에서 화면을 고릅니다.
 *
 * <p><b>라우터 라이브러리를 쓰지 않습니다.</b> 주소가 있는 화면이 월드컵 하나뿐이고 경로도
 * 셋이라, 의존성 하나를 들이는 값보다 이 파일 하나가 쌉니다. 기존 화면(검색·서가)은
 * 여전히 `/` 하나이고 탭은 상태로 바뀝니다.
 *
 * <p><b>모르는 경로는 첫 화면입니다.</b> `vercel.json` 이 모든 경로를 첫 화면으로 돌려보내던
 * 그대로입니다. 여기서 「없는 페이지」를 그리기 시작하면 공유된 예전 주소가 깨집니다.
 */
export type Route =
  | { kind: 'app' }
  | { kind: 'worldcup-home' }
  | { kind: 'worldcup-play'; themeId: string }
  | { kind: 'worldcup-result'; themeId: string; bookId: string };

/** 주소에 들어가는 이름표. 소문자·숫자·하이픈만 씁니다. 서버의 명단도 같은 모양만 받습니다. */
const ID = /^[a-z0-9][a-z0-9-]{0,39}$/;

export function parseRoute(pathname: string): Route {
  // 끝의 빗금은 있어도 없어도 같은 화면입니다. 정적 파일로 내보낸 경로는 호스팅에 따라
  // 빗금이 붙어 오기도 합니다.
  const parts = pathname.split('/').filter(Boolean);
  if (parts[0] !== 'worldcup') return { kind: 'app' };
  if (parts.length === 1) return { kind: 'worldcup-home' };
  const themeId = parts[1];
  if (!ID.test(themeId)) return { kind: 'worldcup-home' };
  if (parts.length === 2) return { kind: 'worldcup-play', themeId };
  if (parts.length === 4 && parts[2] === 'result' && ID.test(parts[3])) {
    return { kind: 'worldcup-result', themeId, bookId: parts[3] };
  }
  return { kind: 'worldcup-home' };
}

export const paths = {
  worldcup: () => '/worldcup',
  play: (themeId: string) => `/worldcup/${themeId}`,
  result: (themeId: string, bookId: string) => `/worldcup/${themeId}/result/${bookId}`,
  /** 책 제목을 채운 채 한 권 검색으로 갑니다. `App` 이 한 번 읽고 주소에서 지웁니다. */
  search: (title: string) => `/?q=${encodeURIComponent(title)}`,
};

const CHANGED = 'wimb:navigate';

/**
 * 페이지를 새로 받지 않고 주소를 바꿉니다.
 *
 * <p><b>옮긴 뒤에는 맨 위로 올립니다.</b> 브라우저가 페이지를 새로 받을 때 해 주던 일인데,
 * 주소만 바꾸면 스크롤이 앞 화면의 자리에 그대로 남아 새 화면의 중간에서 시작합니다.
 */
export function navigate(to: string, options: { replace?: boolean; state?: unknown } = {}): void {
  const state = options.state ?? null;
  if (options.replace) window.history.replaceState(state, '', to);
  else window.history.pushState(state, '', to);
  window.scrollTo(0, 0);
  window.dispatchEvent(new Event(CHANGED));
}

function subscribe(notify: () => void): () => void {
  window.addEventListener('popstate', notify);
  window.addEventListener(CHANGED, notify);
  return () => {
    window.removeEventListener('popstate', notify);
    window.removeEventListener(CHANGED, notify);
  };
}

/** 지금 주소의 경로. 물음표 뒤는 화면을 고르는 데 쓰지 않습니다. */
export function usePathname(): string {
  return useSyncExternalStore(subscribe, () => window.location.pathname);
}
