import type { Library } from './types';

/**
 * 선택 상태의 URL 표현.
 *
 * 도서관부호를 그대로 나열하지 않습니다. 20곳만 골라도 부호가 6자리씩이라 URL 이 길어집니다.
 * 대신 `short_id` 를 36진수로 바꿔 잇고, **비트맵이 실제로 더 짧을 때만** 비트맵으로
 * 바꿉니다. 어느 쪽이 짧은지는 고른 개수가 아니라 번호가 얼마나 흩어져 있는지로
 * 정해지므로, 개수로 가르면 안 됩니다(`encodeSelection` 참고).
 *
 * **`short_id` 는 절대 바뀌지 않는다는 전제 위에 서 있습니다.** 값이 바뀌면 공유된 링크가
 * 전부 조용히 다른 도서관을 가리키게 됩니다. 백엔드에서 트리거로 막고 있습니다.
 */

export const LIST_PARAM = 'libs';
export const BITMAP_PARAM = 'libsb';

export type SelectionParams = { key: typeof LIST_PARAM | typeof BITMAP_PARAM; value: string } | null;

export function encodeSelection(shortIds: readonly number[]): SelectionParams {
  if (shortIds.length === 0) return null;
  const sorted = [...new Set(shortIds)].sort((a, b) => a - b);

  // **둘 중 실제로 짧은 쪽을 씁니다. 고른 개수로 가르면 안 됩니다.**
  // 예전에는 100곳을 넘으면 비트맵으로 바꿨는데, 그것은 `shortId` 가 1부터 촘촘히
  // 붙는다는 전제 위에 서 있었습니다. **실제로 오는 값은 도서관부호 그대로라
  // 50,001 부터 14,703,813 까지 흩어져 있습니다.** 비트맵은 가장 큰 번호만큼 자리를
  // 잡으므로, 서울 359곳을 고르면 1.4MB 가 되고 base64 로 바꾼 1.9MB 짜리 주소를
  // `replaceState` 가 체크 한 번마다 받아 갔습니다. **그동안 화면이 0.6초씩 멈췄고,
  // 그 주소로는 공유도 되지 않습니다.** 비트맵 크기는 만들어 보지 않아도 가장 큰
  // 번호에서 나오므로, 견준 뒤에 만들면 그 할당 자체를 치르지 않습니다.
  if (bitmapLength(sorted[sorted.length - 1]) < listLength(sorted)) {
    return { key: BITMAP_PARAM, value: toBase64Url(toBitmap(sorted)) };
  }
  return { key: LIST_PARAM, value: sorted.map((id) => id.toString(36)).join(',') };
}

/** 36진수를 쉼표로 이었을 때의 길이. */
function listLength(sorted: readonly number[]): number {
  let length = sorted.length - 1;
  for (const id of sorted) length += id.toString(36).length;
  return length;
}

/** 비트맵을 base64 로 바꿨을 때의 길이. 가장 큰 번호만으로 정해집니다. */
function bitmapLength(max: number): number {
  return Math.ceil((Math.floor(max / 8) + 1) / 3) * 4;
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
    const value = bytes[byte];
    // **빈 바이트는 건너뜁니다.** 위의 전제가 어긋나 있던 동안 만들어진 주소가 남아
    // 있는데, 그런 주소는 1.8MB 가 넘으면서 실제로 켜진 자리는 수백 개뿐입니다.
    // 한 자리씩 세면 그 주소를 여는 것만으로 또 멈춥니다.
    if (value === 0) continue;
    for (let bit = 0; bit < 8; bit++) {
      if (value & (1 << bit)) out.push(byte * 8 + bit);
    }
  }
  return out;
}

function toBase64Url(bytes: Uint8Array): string {
  // **한 글자씩 이어 붙이지 마세요.** 길이에 제곱으로 늘어나고 쓰레기 수집이 그만큼
  // 따라옵니다. 한 번에 넘길 수 있는 만큼씩 끊어 붙입니다. 끊지 않고 통째로 펼치면
  // 인수가 너무 많아 스택이 넘칩니다.
  const CHUNK = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
