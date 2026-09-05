import type { Library } from './domain/types';

const BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export type WorkResult = {
  workId: number;
  title: string;
  author: string | null;
  publisher: string | null;
  coverUrl: string | null;
  isbn13List: string[];
  editionLabels: string[];
  holdingLibCodes: string[];
  /** 모든 판본을 빠짐없이 확인했는지. false 면 화면에 밝혀야 합니다. */
  holdingsComplete: boolean;
  /** 조회 자체가 실패했는지. true 면 미소장이 아니라 확인 불가입니다. */
  holdingsUnreadable: boolean;
};

export type SearchResponse = {
  works: WorkResult[];
  allHoldingsChecked: boolean;
  asOf: string | null;
};

type LibraryDto = {
  libCode: string;
  shortId: number;
  name: string;
  sido: string;
  sigungu: string;
  latitude: number | null;
  longitude: number | null;
  homepageUrl: string | null;
};

export class ApiUnavailable extends Error {}

async function get<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`);
  } catch {
    // 서버가 안 떠 있는 경우입니다. 화면이 죽지 않도록 구분해 던집니다.
    throw new ApiUnavailable('API 서버에 연결하지 못했습니다.');
  }
  if (!response.ok) {
    throw new Error(`API 오류 ${response.status}`);
  }
  return (await response.json()) as T;
}

export async function fetchLibraries(): Promise<Library[]> {
  const rows = await get<LibraryDto[]>('/api/libraries');
  return rows.map((row) => ({
    libCode: row.libCode,
    shortId: row.shortId,
    name: row.name,
    sido: row.sido,
    sigungu: row.sigungu,
    latitude: row.latitude,
    longitude: row.longitude,
    homepageUrl: row.homepageUrl,
  }));
}

export async function searchBooks(query: string, libCodes: string[]): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  for (const code of libCodes) params.append('libs', code);
  return get<SearchResponse>(`/api/search?${params}`);
}

export function libraryLink(libCode: string): string {
  return `${BASE}/api/go/${encodeURIComponent(libCode)}`;
}

// ── 여러 권 동시 확인 ────────────────────────────────────────────────
//
// 두 단계로 나뉘어 있는 것이 중요합니다. 줄 해석과 책 확정을 먼저 받아 화면을 그리고,
// 소장은 책마다 따로 물어 도착하는 대로 채웁니다. 캐시가 빈 상태로 30권을 확인하면
// 수십 초가 걸리는데, 그동안 빈 화면을 보여 주면 사용자는 이 도구를 다시 쓰지 않습니다.

export type LineStatus = 'CONFIRMED' | 'AMBIGUOUS' | 'NOT_FOUND' | 'UNREADABLE';

export type LineResult = {
  lineNo: number;
  raw: string;
  status: LineStatus;
  /** 어떻게 해석했는지. 사용자가 왜 이 결과가 나왔는지 알아야 스스로 고칠 수 있습니다. */
  explanation: string | null;
  /** 같은 책으로 보고 합친 다른 줄 번호들 */
  mergedFrom: number[];
  candidates: WorkResult[];
};

export type ResolveResponse = {
  lines: LineResult[];
  /** 50줄을 넘어 잘라 냈는지. 잘라 낸 사실을 숨기지 않습니다. */
  truncated: boolean;
};

export type HoldingsResponse = {
  libCodes: string[];
  complete: boolean;
  unreadable: boolean;
  asOf: string;
};

async function post<T>(path: string, body: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiUnavailable('API 서버에 연결하지 못했습니다.');
  }
  if (!response.ok) throw new Error(`API 오류 ${response.status}`);
  return (await response.json()) as T;
}

export async function resolveLines(lines: string[]): Promise<ResolveResponse> {
  return post<ResolveResponse>('/api/check/resolve', { lines });
}

export async function fetchHoldings(
  isbn13List: string[],
  libCodes: string[],
): Promise<HoldingsResponse> {
  return post<HoldingsResponse>('/api/holdings', { isbn13List, libs: libCodes });
}
