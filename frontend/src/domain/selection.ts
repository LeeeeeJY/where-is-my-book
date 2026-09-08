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

/**
 * 「내 주변」의 반경(km).
 *
 * <p>도서관은 멀리 다니지 않으므로 좁게 잡습니다. 다만 3km 로 내리면 서울 도심 말고는
 * 대부분 0~1곳이 나오고, 그러면 아래의 「더 먼 곳까지 보여 줍니다」 안내가 기본 상태가
 * 됩니다. 매번 보이는 안내는 아무도 읽지 않게 되어, 정작 넓혔을 때 그 사실이 묻힙니다.
 */
export const NEARBY_RADIUS_KM = 5;

/** 반경 안에 이만큼도 없으면 반경을 무시하고 가까운 곳들을 보여 줍니다. */
const NEARBY_MIN = 3;
/** 반경을 무시할 때 보여 줄 개수. */
const NEARBY_WIDENED = 5;
/** 반경 안이 아무리 많아도 여기까지만 보여 줍니다. */
const NEARBY_MAX = 20;

export type NearbyResult = {
  libraries: Library[];
  /** 반경 안이 너무 적어서 더 먼 곳까지 보여 주고 있는지. 화면에 반드시 밝힙니다. */
  widened: boolean;
  /** 반경 안에 실제로 몇 곳이 있었는지. 0곳과 1곳은 사용자에게 다른 뜻입니다. */
  withinRadius: number;
};

/**
 * 위치에서 가까운 도서관을 고릅니다.
 *
 * <p>빈 목록을 돌려주지 않는 것이 중요합니다. 사용자는 빈 화면을 「우리 동네에 도서관이
 * 없다」가 아니라 「이 도구가 고장났다」로 읽습니다. 그래서 반경 안이 비면 넓히되,
 * 넓혔다는 사실을 함께 돌려줍니다.
 */
export function nearbyLibraries(libraries: Library[], lat: number, lon: number): NearbyResult {
  const sorted = libraries
    .map((library) => ({ library, km: haversineKm(lat, lon, library) }))
    .filter((entry): entry is { library: Library; km: number } => entry.km !== null)
    .sort((a, b) => a.km - b.km);

  const withinRadius = sorted.filter((entry) => entry.km <= NEARBY_RADIUS_KM).length;
  if (withinRadius >= NEARBY_MIN) {
    return {
      libraries: sorted.slice(0, Math.min(withinRadius, NEARBY_MAX)).map((e) => e.library),
      widened: false,
      withinRadius,
    };
  }
  return {
    libraries: sorted.slice(0, NEARBY_WIDENED).map((e) => e.library),
    widened: sorted.length > 0,
    withinRadius,
  };
}
