import type { CheckState, Library } from './types';

/**
 * 선택 상태는 **선택된 도서관부호의 집합 하나만을 유일한 원본**으로 둡니다.
 * 시도와 시군구의 상태는 그 집합에서 매번 계산합니다.
 *
 * 상위 계층의 상태를 따로 저장하면 하위와 어긋나는 순간 복구가 어려워집니다.
 * 하위가 모두 선택되면 상위가 자동으로 체크되는 동작도 이 계산 방식에서 저절로 따라옵니다.
 */
export function groupState(selected: ReadonlySet<string>, members: readonly Library[]): CheckState {
  if (members.length === 0) return 'none';
  let hit = 0;
  for (const member of members) {
    if (selected.has(member.libCode)) hit++;
  }
  if (hit === 0) return 'none';
  return hit === members.length ? 'all' : 'some';
}

/**
 * 상위 계층을 눌렀을 때의 동작.
 * 일부만 선택된 상태에서 누르면 전체 선택이 됩니다. 부분 선택에서 해제로 가면
 * 이미 고른 것이 사라져 사용자가 놀라기 때문입니다.
 */
export function toggleGroup(
  selected: ReadonlySet<string>,
  members: readonly Library[],
): Set<string> {
  const next = new Set(selected);
  const state = groupState(selected, members);
  for (const member of members) {
    if (state === 'all') next.delete(member.libCode);
    else next.add(member.libCode);
  }
  return next;
}

export function toggleOne(selected: ReadonlySet<string>, libCode: string): Set<string> {
  const next = new Set(selected);
  if (!next.delete(libCode)) next.add(libCode);
  return next;
}

/** 시도 > 시군구 > 도서관 3단계 목록을 만듭니다. */
export type RegionTree = {
  sido: string;
  libraryCount: number;
  sigungus: { sigungu: string; libraries: Library[] }[];
}[];

export function buildRegionTree(libraries: readonly Library[]): RegionTree {
  const bySido = new Map<string, Map<string, Library[]>>();
  for (const library of libraries) {
    // 주소를 해석하지 못한 도서관은 지역 트리에 자리가 없습니다. 「기타」라는 묶음을 만들면
    // 지역으로 훑는 사람에게 아무것도 알려 주지 못합니다. 목록에서 사라지는 것은 아니고
    // 이름 검색에서는 그대로 찾힙니다.
    if (library.sido === null || library.sigungu === null) continue;
    let sigungus = bySido.get(library.sido);
    if (!sigungus) bySido.set(library.sido, (sigungus = new Map()));
    const list = sigungus.get(library.sigungu);
    if (list) list.push(library);
    else sigungus.set(library.sigungu, [library]);
  }

  return [...bySido.entries()]
    .map(([sido, sigungus]) => ({
      sido,
      libraryCount: [...sigungus.values()].reduce((sum, list) => sum + list.length, 0),
      sigungus: [...sigungus.entries()]
        .map(([sigungu, libs]) => ({
          sigungu,
          libraries: [...libs].sort((a, b) => a.name.localeCompare(b.name, 'ko')),
        }))
        .sort((a, b) => a.sigungu.localeCompare(b.sigungu, 'ko')),
    }))
    .sort((a, b) => a.sido.localeCompare(b.sido, 'ko'));
}

/** 위경도가 없는 도서관은 거리를 잴 수 없으므로 목록 끝으로 보냅니다. */
export function byDistanceFrom(lat: number, lon: number) {
  return (a: Library, b: Library) => {
    const da = haversineKm(lat, lon, a);
    const db = haversineKm(lat, lon, b);
    if (da === null && db === null) return a.name.localeCompare(b.name, 'ko');
    if (da === null) return 1;
    if (db === null) return -1;
    return da - db;
  };
}

export function haversineKm(lat: number, lon: number, library: Library): number | null {
  if (library.latitude === null || library.longitude === null) return null;
  const R = 6371;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(library.latitude - lat);
  const dLon = toRad(library.longitude - lon);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat)) * Math.cos(toRad(library.latitude)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}
