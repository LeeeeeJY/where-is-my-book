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

/** 고른 도서관 한 곳. `label` 은 목록 안에서 서로 구별되게 다듬은 이름입니다. */
export type ChosenLibrary = { library: Library; label: string };

/**
 * 고른 도서관을 화면에 늘어놓기 좋게 이름순으로 정리합니다.
 *
 * <p>**이름이 겹치는 곳에는 지역을 덧붙입니다.** 목록에 이름만 늘어놓으면 같은 이름이
 * 둘일 때 어느 것을 지워야 하는지 알 수 없습니다. 실제 목록 1,619곳에 아홉 곳이 그렇습니다
 * (「아름드리작은도서관」 세 곳, 「무지개도서관」·「삼성도서관」·「햇살작은도서관」이 각
 * 두 곳, 2026-09-13). 겹치지 않는 이름까지 지역을 달면 줄이 길어지기만 하므로 겹칠 때만
 * 답니다.
 *
 * <p>목록에 없는 도서관부호는 이름을 알 수 없으므로 여기에 나오지 않습니다. 주소로 받은
 * 선택은 `App` 이 목록과 대조해 걸러 두므로 실제로는 어긋나지 않습니다.
 */
export function chosenLibraries(
  libraries: readonly Library[],
  selected: ReadonlySet<string>,
): ChosenLibrary[] {
  const chosen = libraries.filter((library) => selected.has(library.libCode));
  const sameName = new Map<string, number>();
  for (const library of chosen) {
    sameName.set(library.name, (sameName.get(library.name) ?? 0) + 1);
  }
  return chosen
    .map((library) => {
      const where = library.sigungu ?? library.sido;
      const ambiguous = (sameName.get(library.name) ?? 0) > 1;
      return {
        library,
        label: ambiguous && where !== null ? `${library.name} (${where})` : library.name,
      };
    })
    .sort(
      (a, b) =>
        a.library.name.localeCompare(b.library.name, 'ko') ||
        a.label.localeCompare(b.label, 'ko'),
    );
}

/** 고른 도서관을 묶은 한 덩어리. `label` 은 시도 이름이거나 시군구 이름입니다. */
export type ChosenGroup = { label: string; libraries: ChosenLibrary[] };

/** 주소를 읽지 못한 도서관이 들어갈 자리. 「고양시립」 두 곳이 실제로 그렇습니다. */
export const UNKNOWN_REGION = '주소를 모르는 곳';

/**
 * 고른 도서관이 많을 때 **이름 대신 보여 줄 묶음**을 만듭니다.
 *
 * <p>많이 고르는 행동은 대개 지역 단위 선택입니다. 지역 계층의 「서울특별시」를 한 번
 * 누르면 **359곳**이 한꺼번에 들어오는데, 그때 이름을 359개 늘어놓아 봐야 사용자가 알고
 * 싶은 답은 「서울 전체」라는 한마디입니다. 그래서 **갈라지는 가장 위 단위로 묶습니다.**
 * 여러 시도에 걸쳐 있으면 시도로, 한 시도 안이면 시군구로 묶고, 시군구까지 하나면 묶을
 * 것이 없으므로 묶음 하나를 그대로 돌려줍니다. 부르는 쪽은 그때 이름을 늘어놓습니다.
 *
 * <p>묶음 순서는 지역 계층과 같은 이름순입니다. **개수 순으로 세우지 마세요.** 사용자가
 * 방금 트리에서 본 순서와 어긋나면 같은 지역을 두 곳에서 다르게 찾게 됩니다.
 */
export function chosenGroups(chosen: readonly ChosenLibrary[]): ChosenGroup[] {
  const bySido = groupChosen(chosen, (c) => c.library.sido);
  if (bySido.length > 1) return bySido;
  // 시도가 하나뿐이면 한 단계 내려갑니다. 서울 359곳은 시도로 묶어도 묶음이 하나라
  // 아무것도 줄여 주지 못하지만, 시군구로 묶으면 스물다섯 줄이 됩니다.
  return groupChosen(chosen, (c) => c.library.sigungu ?? c.library.sido);
}

function groupChosen(
  chosen: readonly ChosenLibrary[],
  keyOf: (c: ChosenLibrary) => string | null,
): ChosenGroup[] {
  const groups = new Map<string, ChosenLibrary[]>();
  for (const entry of chosen) {
    const key = keyOf(entry) ?? UNKNOWN_REGION;
    const list = groups.get(key);
    if (list) list.push(entry);
    else groups.set(key, [entry]);
  }
  return [...groups.entries()]
    .map(([label, libraries]) => ({ label, libraries }))
    .sort((a, b) => {
      // 주소를 모르는 곳은 지역 이름이 아니므로 지역들 사이에 끼워 넣지 않고 맨 뒤에 둡니다.
      if (a.label === UNKNOWN_REGION) return 1;
      if (b.label === UNKNOWN_REGION) return -1;
      return a.label.localeCompare(b.label, 'ko');
    });
}

/**
 * 이름을 그대로 늘어놓는 최대 개수.
 *
 * <p>선택 칸 폭에서 여덟 곳 남짓이 한 화면이라 스무 곳이면 두세 번 내려야 하지만, **이름은
 * 사용자가 실제로 찾는 답이므로 감당되는 한 이름을 보여 줍니다.** 지역 묶음은 이름을 다
 * 보여 줄 수 없을 때의 대안입니다.
 */
export const CHOSEN_NAMES_MAX = 20;

/** 묶음 하나가 이만큼은 담아야 묶는 값이 있습니다. */
const MIN_PER_GROUP = 3;

/**
 * 이름 대신 지역 묶음으로 보여 줄 때인지.
 *
 * <p>**개수만 보고 정하면 안 됩니다.** 열세 곳을 여섯 구에서 골랐을 때 묶어 보니 「1곳」
 * 짜리 묶음이 넷이었습니다. 줄어드는 것은 거의 없는데 이름은 한 번 더 눌러야 보이므로
 * 손해입니다. 그래서 **묶으면 실제로 줄어드는지**를 함께 봅니다.
 */
export function groupsWorthShowing(count: number, groupCount: number): boolean {
  if (count <= CHOSEN_NAMES_MAX) return false;
  // 묶음이 하나면 묶어도 그대로입니다. 시군구 하나를 통째로 고른 경우가 여기입니다.
  if (groupCount <= 1) return false;
  return groupCount * MIN_PER_GROUP <= count;
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
