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
