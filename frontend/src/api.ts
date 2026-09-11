import type { Library, LinkKind } from './domain/types';
import { withVisibilityRetry, type VisibilityDoc } from './domain/visibilityRetry';

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
  /**
   * 묶인 판본 가운데 가장 큰 대출건수. 서버가 같은 등급 안의 순서를 정하는 데 쓴 값이고,
   * 화면은 참고만 합니다. 예전 서버는 주지 않으므로 없을 수 있습니다.
   */
  loanCount?: number;
};

/**
 * <b>소장 항목이 없는 것이 의도적입니다.</b> 소장은 {@link fetchHoldings} 한 곳에서만
 * 답합니다. 검색 응답에도 실으면 「물어본 적 없음」과 「물어봤는데 없음」이 같은 빈 목록으로
 * 와서, 화면이 그것을 「고른 도서관에는 없습니다」로 그리게 됩니다. 실제로 있는 책을
 * 없다고 답하는 것이라 이 도구의 전제가 무너집니다.
 */
/** ISBN 을 판별하지 못해 소장을 확인할 수 없는 자료. */
export type DroppedBook = {
  title: string | null;
  author: string | null;
  publisher: string | null;
  rawIsbn13: string | null;
};

export type SearchResponse = {
  works: WorkResult[];
  /** 자르기 전의 전체 저작 수. 지금 보는 것이 전부인지 잘린 것인지 알려면 필요합니다. */
  totalWorks: number;
  /**
   * ISBN 을 판별할 수 없어 결과에서 뺀 자료 수.
   *
   * <p>소장 조회가 ISBN 으로만 되기 때문에 빼는 것 자체는 맞지만, **조용히 빼면 사용자는
   * 그것을 「그런 책이 없다」로 읽습니다.** 찾던 책이 하필 그 자료였을 때 아무 단서도 없이
   * 사라지므로 화면이 이것을 밝힙니다.
   */
  /**
   * 정보나루가 돌려준 서지 건수. **우리가 거르기 전의 숫자입니다.**
   *
   * <p>찾는 책이 안 나올 때 이 숫자 하나로 어디를 봐야 하는지 갈립니다. 0이면 정보나루가
   * 못 찾은 것이고, 0이 아닌데 저작이 0이면 우리가 버린 것입니다.
   */
  foundBooks: number;
  droppedNoIsbn: number;
  /**
   * ISBN 을 판별하지 못해 뺀 자료가 **무엇인지.** 전체 건수는 `droppedNoIsbn` 입니다.
   *
   * <p>건수만으로는 사용자가 할 수 있는 일이 없습니다. 찾던 책이 하필 그 자료였는지
   * 알려 주지 않으므로 결국 아무 단서 없이 사라진 것과 같습니다. 표제를 보여 주면
   * 적어도 「이 책이구나」 하고 도서관에서 직접 찾아볼 수 있습니다.
   */
  droppedBooks: DroppedBook[];
  /**
   * 넣은 제목 말고 **함께 찾아본 다른 띄어쓰기 표기**.
   *
   * <p>정보나루는 어절 단위로 그대로 찾으므로 「레미제라블」과 「레 미제라블」이 서로 다른
   * 검색입니다. 서버가 두 표기를 모두 찾아 합치므로 어느 쪽으로 넣어도 같은 목록이 나오는데,
   * 그 사실을 화면이 밝혀야 넣은 것과 다른 표기의 책을 보고 어리둥절하지 않습니다.
   */
  alsoSearchedTitles: string[];
  /**
   * 넣은 저자 말고 **함께 찾아 실제로 책을 데려온 다른 띄어쓰기 표기**.
   *
   * <p>「칼 세이건」과 「칼세이건」도 서로 다른 검색이라, 서버가 띄어쓰기를 뺀 표기와
   * 자리를 옮긴 표기로도 함께 찾습니다. 제목 쪽과 달리 **책을 데려온 표기만** 옵니다.
   * 자리를 옮겨 본 표기는 이름 길이만큼 있어서 전부 늘어놓으면 아무것도 못 찾은 표기까지
   * 화면에 나가기 때문입니다.
   */
  alsoSearchedAuthors: string[];
  /**
   * 제목으로는 걸리지 않던 판을 **저자로 되찾아 더했는지.**
   *
   * <p>정보나루의 제목 매칭은 어절의 앞에서부터 맞습니다. 「미제라블」은 「레 미제라블」을
   * 찾아내지만 「레미제라블」은 어느 어절과도 맞지 않아 낱권을 한 권도 못 찾습니다.
   * 그럴 때 서버가 찾은 책의 저자로 한 번 더 찾아 표제가 맞는 것을 더합니다.
   *
   * <p>**화면이 이 사실을 밝혀야 합니다.** 사용자가 넣은 제목과 다른 표기의 책이 목록에
   * 섞여 있는 것이라, 말하지 않으면 검색이 엉뚱한 것을 가져왔다고 읽힙니다.
   */
  recoveredByAuthor: boolean;
  asOf: string | null;
};

/** 검색 조건. **모두 비면 부르지 않습니다.** */
export type SearchCriteria = {
  title: string;
  author: string;
  publisher: string;
  isbn: string;
};

export function hasCriteria(criteria: SearchCriteria): boolean {
  return Object.values(criteria).some((v) => v.trim().length > 0);
}

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
  constructor(
    readonly status: number,
    /** 정보나루의 오류 코드(`outOflimit`, `authErr` 등). 서버가 알려 주면 담깁니다. */
    readonly code?: string,
    reason?: string,
  ) {
    super(reason ?? `API 오류 ${status}`);
  }
}

/**
 * <b>모든 호출이 지나는 한 곳입니다. GET 과 POST 가 같은 함수를 씁니다.</b> 한쪽만 고치면
 * 언젠가 다시 갈립니다.
 *
 * <p><b>화면을 비운 사이 끊긴 요청은 돌아왔을 때 한 번 다시 보냅니다</b>
 * ({@link withVisibilityRetry}). 검색 한 번이 수 초에서 수십 초라 기다리다 창을 최소화하거나
 * 다른 앱으로 넘어가는 일이 흔한데, 그때 브라우저가 나가 있던 연결을 끊습니다. 그것을
 * 그대로 실패로 두면 **멀쩡한 서버를 죽었다고 말하게 됩니다.** 여기 없으면 화면마다 따로
 * 다시 부르는 장치를 달게 되고, 그러면 언젠가 한 화면이 빠집니다.
 *
 * <p><b>다시 보내는 것은 연결이 끊긴 경우뿐입니다.</b> 서버가 답을 준 것({@link ApiUnreadable})은
 * 끊긴 것이 아니므로 다시 보내지 않습니다. 429 를 다시 두드려 봐야 남은 몫만 깎이고
 * 답은 같습니다.
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  try {
    return await withVisibilityRetry(
      async () => {
        const response = await fetch(`${BASE}${path}`, init);
        // **서버가 알려 준 이유를 반드시 꺼내 씁니다.** 정보나루는 무엇이 잘못됐는지
        // 한국어로 또박또박 알려 주는데, 그것을 버리고 「API 오류 503」만 보여 주면
        // 사용자도 우리도 원인을 코드에서 찾게 됩니다. 실제로 그렇게 하루를 추측으로
        // 보냈습니다. 주소별 호출 제한(429)이 붙은 뒤로는 특히 그렇습니다. 「잠시 뒤
        // 다시 해 주세요」와 「오늘 몫을 다 썼습니다」는 사람이 할 일이 다릅니다.
        if (!response.ok) throw await unreadable(response);
        return (await response.json()) as T;
      },
      visibilityDoc(),
      (error) => !(error instanceof ApiUnreadable),
    );
  } catch (error) {
    if (error instanceof ApiUnreadable) throw error;
    // 서버가 안 떠 있거나, 본문을 받는 도중에 연결이 끊긴 경우입니다. 화면이 죽지 않도록
    // 구분해 던집니다.
    throw new ApiUnavailable('API 서버에 연결하지 못했습니다.');
  }
}

/** 브라우저 밖(테스트 등)에서는 가시성을 볼 수 없으므로 그대로 한 번만 보냅니다. */
function visibilityDoc(): VisibilityDoc | null {
  return typeof document === 'undefined' ? null : document;
}

async function get<T>(path: string): Promise<T> {
  return request<T>(path);
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

/**
 * 책 검색. **제목·저자·출판사를 따로 보냅니다.**
 *
 * <p>한 칸에 다 넣고 우리가 쪼개는 것보다 정보나루에 그대로 넘기는 편이 정확합니다.
 * 정보나루는 이 셋을 각각 받고 둘 이상 주면 AND 로 겁니다.
 */
export async function searchBooks(
  criteria: SearchCriteria,
  libCodes: string[],
): Promise<SearchResponse> {
  const params = new URLSearchParams();
  if (criteria.title.trim()) params.set('q', criteria.title.trim());
  if (criteria.author.trim()) params.set('author', criteria.author.trim());
  if (criteria.publisher.trim()) params.set('publisher', criteria.publisher.trim());
  if (criteria.isbn.trim()) params.set('isbn', criteria.isbn.trim());
  for (const code of libCodes) params.append('libs', code);
  const response = await get<SearchResponse>(`/api/search?${params}`);
  // 프론트와 서버가 따로 배포되므로 예전 서버가 이 항목을 안 줄 수 있습니다. 그때도 화면이
  // 죽지 않게 빈 목록으로 채웁니다.
  return {
    ...response,
    alsoSearchedTitles: response.alsoSearchedTitles ?? [],
    alsoSearchedAuthors: response.alsoSearchedAuthors ?? [],
  };
}

/**
 * 둘러보기 화면이 쓰는 것. **오늘의 이야기 한 권과 그 도서관의 인기대출 목록**입니다.
 *
 * <p>둘 다 서버가 도서관마다 하루에 한 번만 정보나루에 물어 두고 그날 내내 같은 답을
 * 돌려줍니다. 그래서 화면이 자주 불러도 호출이 늘지 않습니다.
 */
export type BrowseStory = {
  title: string;
  authors: string | null;
  publisher: string | null;
  publicationYear: string | null;
  isbn13: string;
  imageUrl: string | null;
  /**
   * 청구기호. **없을 수 있습니다.** 정보나루 매뉴얼만으로는 이 값의 모양을 확정할 수
   * 없어 서버가 확실히 읽히는 경우에만 채웁니다. 없는 것보다 틀린 청구기호가 나쁩니다.
   */
  callNumber: string | null;
  /** 정보나루 책 정보. 못 받으면 null 이고, 그때는 링크를 그리지 않습니다. */
  detailUrl: string | null;
  classNm: string | null;
  /** 그 도서관 문학 장서가 몇 건인지. 어디서 뽑았는지를 화면이 밝힙니다. */
  poolSize: number;
  /** 이 이야기가 어느 날 것인지(한국 날짜). **새로 고쳐도 바뀌지 않는다는 표시입니다.** */
  date: string;
};

export type BrowsePopularBook = {
  /** 그 도서관에서의 대출 순위. **15절은 대출건수를 주지 않아 순위만 옵니다.** */
  rank: number;
  title: string;
  authors: string | null;
  publisher: string | null;
  publicationYear: string | null;
  isbn13: string;
  imageUrl: string | null;
  detailUrl: string | null;
};

/** @param label 화면에 그대로 나가는 이름. **빈 묶음은 서버가 아예 담지 않습니다.** */
export type BrowsePopularGroup = { key: string; label: string; books: BrowsePopularBook[] };

/** @param story 없을 수 있습니다. **화면이 그것을 「없다」로 그리면 안 됩니다.** */
export type BrowseResponse = { story: BrowseStory | null; popular: BrowsePopularGroup[] };

export async function fetchBrowse(libCode: string): Promise<BrowseResponse> {
  const response = await get<BrowseResponse>(`/api/browse?lib=${encodeURIComponent(libCode)}`);
  return { story: response.story ?? null, popular: response.popular ?? [] };
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

/**
 * 도서관으로 넘기는 주소. **ISBN 은 그 도서관이 실제로 가진 판부터 여러 개를 보냅니다.**
 *
 * <p>순서는 `isbnsForLink` 가 정합니다. 서버는 앞의 것으로 상세를 찾고, 그 판에 링크가 없으면
 * 다음 것으로 한 번 더 찾습니다. 첫 ISBN 하나만 보내던 때는 그 판이 없는 도서관에서 검색이
 * 0건이 되어 「소장한다더니 그 책이 없네」로 보였습니다.
 */
export function libraryLink(libCode: string, isbn13List?: readonly string[], title?: string): string {
  const params = new URLSearchParams();
  for (const isbn13 of isbn13List ?? []) if (isbn13) params.append('isbn', isbn13);
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
  /**
   * 도서관마다 **그 도서관이 가진 것으로 확인된** 판본의 ISBN. 도서관 링크가 저작의 첫 ISBN 이
   * 아니라 이것을 먼저 넣어야 그 도서관 OPAC 에서 0건이 나오지 않습니다.
   */
  heldIsbns?: Record<string, string[]>;
  complete: boolean;
  unreadable: boolean;
  /**
   * 이 소장 정보를 **정보나루에서 받은** 날짜.
   *
   * <p>캐시에서 나온 값이면 오늘이 아니라 **그때 받은 날짜**입니다. 서버가 오늘 날짜를
   * 찍어 주지 않으므로 화면이 이 값을 그대로 보여 주어야 합니다. 사용자가 "미소장"을
   * "확실히 없다"로 읽을지 "한 달 전 기준이다"로 읽을지가 이 표시 하나에 달려 있습니다.
   *
   * <p>물어보지 못했으면 `null` 입니다. **그때 「방금」이라고 쓰면 안 됩니다.**
   */
  asOf: string | null;
};


/**
 * <b>GET 과 같은 통로를 씁니다.</b> 예전에는 여기에 똑같은 코드가 한 벌 더 있었고, 그래서
 * 오류 이유를 꺼내는 것도 두 곳에 따로 있었습니다. 소장 조회와 줄 확정은 전부 이쪽으로
 * 나가므로, 한쪽만 고치면 **가장 오래 걸리는 요청이 고쳐지지 않은 채 남습니다.**
 */
async function post<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** 서버가 실어 보낸 이유와 오류 코드를 꺼냅니다. 본문이 없으면 상태 코드만으로 답합니다. */
async function unreadable(response: Response): Promise<ApiUnreadable> {
  try {
    const body = (await response.json()) as { code?: string; reason?: string };
    return new ApiUnreadable(response.status, body.code, body.reason);
  } catch {
    return new ApiUnreadable(response.status);
  }
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
