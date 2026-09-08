import type { Library, LinkKind } from './domain/types';

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
  linkKind: LinkKind;
};

/** 서버에 닿지도 못한 경우. 서버가 안 떠 있거나 주소가 틀린 것입니다. */
export class ApiUnavailable extends Error {}

/**
 * 서버는 답했지만 우리가 원하는 것을 주지 못한 경우(503 등).
 *
 * <p><b>{@link ApiUnavailable} 과 섞지 마세요.</b> 앞은 "서버가 없다", 이것은 "서버는 있는데
 * 정보나루에 물어보지 못했다"입니다. 사람이 할 일이 서로 다릅니다. 앞은 서버를 띄워야 하고,
 * 뒤는 인증키를 확인해야 합니다. 화면이 둘을 같은 문구로 말하면 멀쩡한 서버를 다시 띄우게
 * 됩니다.
 */
export class ApiUnreadable extends Error {
  constructor(readonly status: number) {
    super(`API 오류 ${status}`);
  }
}

async function get<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`);
  } catch {
    // 서버가 안 떠 있는 경우입니다. 화면이 죽지 않도록 구분해 던집니다.
    throw new ApiUnavailable('API 서버에 연결하지 못했습니다.');
  }
  if (!response.ok) {
    throw new ApiUnreadable(response.status);
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
    linkKind: row.linkKind,
  }));
}

export async function searchBooks(query: string, libCodes: string[]): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  for (const code of libCodes) params.append('libs', code);
  return get<SearchResponse>(`/api/search?${params}`);
}

/**
 * 도서관으로 넘기는 링크.
 *
 * <p>책을 함께 넘기면 서버가 그 도서관의 주소 규칙을 보고 **그 책의 페이지나 검색 결과**로
 * 보냅니다. 규칙이 없는 도서관은 홈페이지로 내려앉는데, 그 사실은 `linkLabel` 이 화면에
 * 밝힙니다. 책 없이 부르면(도서관 순위처럼 한 권을 가리키지 않을 때) 홈페이지로 갑니다.
 */
export function libraryLink(libCode: string, isbn13?: string, title?: string): string {
  const params = new URLSearchParams();
  if (isbn13) params.set('isbn', isbn13);
  if (title) params.set('title', title);
  const query = params.toString();
  return `${BASE}/api/go/${encodeURIComponent(libCode)}${query ? `?${query}` : ''}`;
}

// ── 여러 권 동시 확인 ────────────────────────────────────────────────
//
// 두 단계로 나뉘어 있는 것이 중요합니다. 줄 해석과 책 확정을 먼저 받아 화면을 그리고,
// 소장은 책마다 따로 물어 도착하는 대로 채웁니다. 캐시가 빈 상태로 30권을 확인하면
// 수십 초가 걸리는데, 그동안 빈 화면을 보여 주면 사용자는 이 도구를 다시 쓰지 않습니다.

/**
 * `NOT_FOUND` 는 "물어봤는데 그런 책이 없다", `LOOKUP_FAILED` 는 "물어보지 못했다"입니다.
 * **절대 섞으면 안 됩니다.** 뒤를 결과 없음으로 그리면 멀쩡히 있는 책을 없다고 답하게 됩니다.
 */
export type LineStatus =
  | 'CONFIRMED'
  | 'AMBIGUOUS'
  | 'NOT_FOUND'
  | 'LOOKUP_FAILED'
  | 'UNREADABLE';

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
  if (!response.ok) throw new ApiUnreadable(response.status);
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
