import type { Library } from './types';

/**
 * 선택 상태의 URL 표현.
 *
 * 도서관부호를 그대로 나열하지 않습니다. 20곳만 골라도 부호가 6자리씩이라 URL 이 길어집니다.
 * 대신 `short_id` 를 36진수로 바꿔 잇고, 많이 고르면 비트맵으로 전환합니다.
 *
 * **`short_id` 는 절대 바뀌지 않는다는 전제 위에 서 있습니다.** 값이 바뀌면 공유된 링크가
 * 전부 조용히 다른 도서관을 가리키게 됩니다. 백엔드에서 트리거로 막고 있습니다.
 */

/** 이 수를 넘으면 목록보다 비트맵이 짧아집니다. */
const BITMAP_THRESHOLD = 100;

export const LIST_PARAM = 'libs';
export const BITMAP_PARAM = 'libsb';

export type SelectionParams = { key: typeof LIST_PARAM | typeof BITMAP_PARAM; value: string } | null;

export function encodeSelection(shortIds: readonly number[]): SelectionParams {
  if (shortIds.length === 0) return null;
  const sorted = [...new Set(shortIds)].sort((a, b) => a - b);

  if (sorted.length <= BITMAP_THRESHOLD) {
    return { key: LIST_PARAM, value: sorted.map((id) => id.toString(36)).join(',') };
  }
  return { key: BITMAP_PARAM, value: toBase64Url(toBitmap(sorted)) };
}

export function decodeSelection(params: URLSearchParams): number[] {
  const list = params.get(LIST_PARAM);
  if (list) {
    return list
      .split(',')
      // parseInt 는 잘못된 문자를 만나면 거기까지만 읽고 멈춥니다.
      // 그대로 두면 'zzz!' 가 46655 라는 멀쩡해 보이는 번호가 되어 엉뚱한 도서관이 선택됩니다.
      .filter((token) => /^[0-9a-z]+$/.test(token))
      .map((token) => parseInt(token, 36))
      .filter((id) => Number.isSafeInteger(id) && id >= 0);
  }
  const bitmap = params.get(BITMAP_PARAM);
  if (bitmap) {
    try {
      return fromBitmap(fromBase64Url(bitmap));
    } catch {
      // 손상된 링크로 화면 전체가 죽지 않게 합니다. 선택이 비어 있을 뿐입니다.
      return [];
    }
  }
  return [];
}

/** 선택된 도서관부호 집합을 URL 에 넣을 수 있는 형태로 바꿉니다. */
export function selectionToParams(
  selected: ReadonlySet<string>,
  libraries: readonly Library[],
): SelectionParams {
  const shortIds = libraries.filter((l) => selected.has(l.libCode)).map((l) => l.shortId);
  return encodeSelection(shortIds);
}

/**
 * URL 에서 읽은 번호를 도서관부호로 되돌립니다.
 * 카탈로그에 없는 번호는 조용히 버립니다. 도서관이 목록에서 빠졌을 수 있습니다.
 */
export function paramsToSelection(
  params: URLSearchParams,
  libraries: readonly Library[],
): Set<string> {
  const wanted = new Set(decodeSelection(params));
  const out = new Set<string>();
  for (const library of libraries) {
    if (wanted.has(library.shortId)) out.add(library.libCode);
  }
  return out;
}

function toBitmap(sortedIds: readonly number[]): Uint8Array {
  const max = sortedIds[sortedIds.length - 1];
  const bytes = new Uint8Array(Math.floor(max / 8) + 1);
  for (const id of sortedIds) bytes[id >> 3] |= 1 << (id & 7);
  return bytes;
}

function fromBitmap(bytes: Uint8Array): number[] {
  const out: number[] = [];
  for (let byte = 0; byte < bytes.length; byte++) {
    for (let bit = 0; bit < 8; bit++) {
      if (bytes[byte] & (1 << bit)) out.push(byte * 8 + bit);
    }
  }
  return out;
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
