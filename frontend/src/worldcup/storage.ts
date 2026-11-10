import type { Progress } from './bracket';

/**
 * 월드컵이 이 기기에 남기는 것 둘.
 *
 * <ul>
 *   <li><b>진행 기록</b>은 `sessionStorage` 에 둡니다. 새로 고쳐도 이어지되 탭을 닫으면
 *       사라집니다. 다음에 왔을 때 지난번 대진이 그대로 남아 있으면 「처음부터」가 아니라
 *       「하다 만 것」으로 시작하게 되는데, 광고로 처음 들어온 사람에게는 그것이 고장으로
 *       읽힙니다.</li>
 *   <li><b>집계에 낸 적이 있는지</b>는 `localStorage` 에 둡니다. 같은 브라우저에서 여러 번
 *       완주해도 집계에는 한 번만 들어가게 하려는 것입니다. 막는 장치가 아니라 「다시
 *       하기」를 누르는 보통의 사용이 순위표를 흔들지 않게 하는 정도입니다.</li>
 * </ul>
 *
 * <p><b>여기 항목을 늘리거나 이름을 바꾸면 `public/privacy.html` 도 함께 고칩니다.</b>
 *
 * <p>사생활 보호 모드에서는 읽기와 쓰기가 예외를 던집니다. 저장이 안 되는 것은 불편할
 * 뿐이지만 그것 때문에 화면이 죽으면 안 됩니다.
 */
const PROGRESS_KEY = 'wimb.worldcup.progress.v1.';
const SUBMITTED_KEY = 'wimb.worldcup.submitted.v1';

export function loadProgress(themeId: string): Progress | null {
  try {
    const raw = sessionStorage.getItem(PROGRESS_KEY + themeId);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<Progress> | null;
    if (
      !parsed ||
      parsed.v !== 1 ||
      !Array.isArray(parsed.order) ||
      !Array.isArray(parsed.picks) ||
      !parsed.order.every((id) => typeof id === 'string') ||
      !parsed.picks.every((id) => typeof id === 'string')
    ) {
      return null;
    }
    return {
      v: 1,
      order: parsed.order,
      picks: parsed.picks,
      reported: parsed.reported === true,
    };
  } catch {
    return null;
  }
}

export function saveProgress(themeId: string, progress: Progress): void {
  try {
    sessionStorage.setItem(PROGRESS_KEY + themeId, JSON.stringify(progress));
  } catch {
    // 저장하지 못해도 이번 화면에서는 그대로 진행됩니다. 새로 고치면 처음부터입니다.
  }
}

export function clearProgress(themeId: string): void {
  try {
    sessionStorage.removeItem(PROGRESS_KEY + themeId);
  } catch {
    // 지우지 못했으면 다음에 읽을 때 대진과 맞지 않아 버려집니다.
  }
}

function submittedThemes(): string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(SUBMITTED_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

export function hasSubmitted(themeId: string): boolean {
  return submittedThemes().includes(themeId);
}

export function markSubmitted(themeId: string): void {
  try {
    const next = new Set(submittedThemes());
    next.add(themeId);
    localStorage.setItem(SUBMITTED_KEY, JSON.stringify([...next]));
  } catch {
    // 표시를 남기지 못하면 다음 완주가 한 번 더 집계됩니다. 그 정도는 받아들입니다.
  }
}
