const STORAGE_KEY = 'wimb.selectedLibraries.v1';

/**
 * 선택 결과를 로그인 없이 브라우저에 보관합니다.
 * 매번 다시 고르게 하면 쓰지 않게 되기 때문입니다.
 *
 * 사생활 보호 모드나 저장 차단 설정에서는 읽기와 쓰기 자체가 예외를 던집니다.
 * 저장이 안 되는 것은 불편할 뿐이지만, 그것 때문에 화면이 죽으면 안 됩니다.
 */
export function loadSelection(): string[] | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return null;
    return parsed.filter((item): item is string => typeof item === 'string');
  } catch {
    return null;
  }
}

export function saveSelection(libCodes: readonly string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...libCodes]));
  } catch {
    // 저장하지 못해도 이번 세션의 선택은 그대로 동작합니다.
  }
}
