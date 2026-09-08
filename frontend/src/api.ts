import type { Library, LinkKind } from './domain/types';

const BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export type WorkResult = {
  workId: number;
  title: string;
  author: string | null;
  publisher: string | null;
  coverUrl: string | null;
  /** 정보나루의 그 책 상세 페이지. 책마다 한 번만 답니다. */
  detailUrl: string | null;
  isbn13List: string[];
  editionLabels: string[];
};

/**
 * <b>소장 항목이 없는 것이 의도적입니다.</b> 소장은 {@link fetchHoldings} 한 곳에서만
 * 답합니다. 검색 응답에도 실으면 「물어본 적 없음」과 「물어봤는데 없음」이 같은 빈 목록으로
 * 와서, 화면이 그것을 「고른 도서관에는 없습니다」로 그리게 됩니다. 실제로 있는 책을
 * 없다고 답하는 것이라 이 도구의 전제가 무너집니다.
 */
export type SearchResponse = {
  works: WorkResult[];
  asOf: string | null;
};

type LibraryDto = {
  libCode: string;
  shortId: number;
  name: string;
  sido: string | null;
  sigungu: string | null;
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
/**
 * 그 도서관에 그 책이 지금 있는지.
 *
 * <p><b>목록에 미리 부르지 마세요.</b> 이 호출은 (도서관 × 책)이라 목록에 달면 한 번에
 * 수백~수천 회가 나갑니다. 사용자가 그 도서관을 눌렀을 때만 부릅니다.
 */
export type LoanStatus = {
  hasBook: boolean;
  loanAvailable: boolean;
  /** 이 상태가 언제 기준인지. **어제 날짜입니다.** 화면에서 지우지 마세요. */
  asOf: string;
};

/**
 * 대출 상태를 물어봅니다.
 *
 * <p><b>저작에 묶인 판본을 전부 보냅니다.</b> 대표 판본 하나만 물으면, 도서관이 2판을
 * 가지고 있을 때 1판을 물어보고 「이 도서관에는 없다」는 답을 받습니다. 소장한다고
 * 표시해 놓고 누르면 없다고 하는 셈이라 소장 정보 자체를 믿지 못하게 됩니다.
 */
export async function fetchLoanStatus(
  libCode: string,
  isbn13List: string[],
): Promise<LoanStatus> {
  const params = new URLSearchParams({ lib: libCode });
  for (const isbn13 of isbn13List) params.append('isbn', isbn13);
  return get<LoanStatus>(`/api/loan?${params}`);
}

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
