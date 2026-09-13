import { describe, expect, it } from 'vitest';
import {
  buildRegionTree,
  chosenGroups,
  chosenLibraries,
  CHOSEN_NAMES_MAX,
  groupsWorthShowing,
  groupState,
  haversineKm,
  nearbyLibraries,
  NEARBY_RADIUS_KM,
  toggleGroup,
  toggleOne,
} from '../selection';
import type { Library } from '../types';

const lib = (
  libCode: string,
  shortId: number,
  sido: string | null,
  sigungu: string | null,
  name: string,
): Library => ({
  libCode, shortId, sido, sigungu, name, latitude: null, longitude: null, homepageUrl: null,
});

const SEOUL_A = lib('011001', 1, '서울특별시', '서초구', '국립중앙도서관');
const SEOUL_B = lib('011002', 2, '서울특별시', '서초구', '서초구립도서관');
const GYEONGGI = lib('141053', 3, '경기도', '성남시', '성남시중원도서관');
const ALL = [SEOUL_A, SEOUL_B, GYEONGGI];

describe('세 상태 체크박스', () => {
  it('하나도 안 고르면 미선택이다', () => {
    expect(groupState(new Set(), [SEOUL_A, SEOUL_B])).toBe('none');
  });

  it('일부만 고르면 부분 선택이다', () => {
    expect(groupState(new Set(['011001']), [SEOUL_A, SEOUL_B])).toBe('some');
  });

  it('하위를 모두 고르면 상위가 자동으로 전체 선택이 된다', () => {
    // 상위 상태를 따로 저장하지 않고 계산하므로 저절로 따라옵니다.
    expect(groupState(new Set(['011001', '011002']), [SEOUL_A, SEOUL_B])).toBe('all');
  });

  it('구성원이 없는 묶음은 미선택이다', () => {
    expect(groupState(new Set(['011001']), [])).toBe('none');
  });
});

describe('고른 도서관 목록', () => {
  it('고른 것만 이름순으로 늘어놓는다', () => {
    const chosen = chosenLibraries(ALL, new Set(['141053', '011001']));
    expect(chosen.map((c) => c.label)).toEqual(['국립중앙도서관', '성남시중원도서관']);
  });

  it('고르지 않으면 빈 목록이다', () => {
    expect(chosenLibraries(ALL, new Set())).toEqual([]);
  });

  it('목록에 없는 도서관부호는 이름을 알 수 없으므로 나오지 않는다', () => {
    expect(chosenLibraries(ALL, new Set(['999999']))).toEqual([]);
  });

  it('이름이 겹치는 곳에만 지역을 덧붙인다', () => {
    // 「고양시립」 두 곳처럼 같은 이름이 여러 지역에 있으면 이름만으로는 어느 것을
    // 지워야 하는지 알 수 없습니다. 겹치지 않는 이름까지 달면 줄만 길어집니다.
    const goyang = lib('141234', 4, '경기도', '고양시', '시립도서관');
    const suwon = lib('141235', 5, '경기도', '수원시', '시립도서관');
    const chosen = chosenLibraries([...ALL, goyang, suwon], new Set(['141234', '141235', '011001']));
    expect(chosen.map((c) => c.label)).toEqual([
      '국립중앙도서관',
      '시립도서관 (고양시)',
      '시립도서관 (수원시)',
    ]);
  });

  it('지역을 모르는 도서관은 이름만 쓴다', () => {
    // 주소를 해석하지 못한 도서관입니다. 덧붙일 것이 없다고 목록에서 빼면
    // 고른 개수와 목록 길이가 어긋나 사용자가 나머지 한 곳을 찾지 못합니다.
    const unknown = lib('141236', 6, null, null, '시립도서관');
    const suwon = lib('141235', 5, '경기도', '수원시', '시립도서관');
    const chosen = chosenLibraries([unknown, suwon], new Set(['141236', '141235']));
    expect(chosen.map((c) => c.label)).toEqual(['시립도서관', '시립도서관 (수원시)']);
  });

  it('고른 도서관과 짝이 그대로 따라온다', () => {
    // 화면이 이 부호로 해제하므로 라벨과 도서관이 어긋나면 엉뚱한 곳이 지워집니다.
    const chosen = chosenLibraries(ALL, new Set(['011002']));
    expect(chosen[0].library.libCode).toBe('011002');
  });
});

describe('고른 도서관을 지역으로 묶기', () => {
  const seoulGangnam = lib('011003', 7, '서울특별시', '강남구', '강남구립도서관');

  it('여러 시도에 걸쳐 있으면 시도로 묶는다', () => {
    const groups = chosenGroups(chosenLibraries(ALL, new Set(['011001', '011002', '141053'])));
    expect(groups.map((g) => [g.label, g.libraries.length])).toEqual([
      ['경기도', 1],
      ['서울특별시', 2],
    ]);
  });

  it('한 시도 안이면 시군구로 한 단계 내려간다', () => {
    // 시도로 묶으면 묶음이 하나뿐이라 아무것도 줄여 주지 못합니다. 서울 359곳이 이렇습니다.
    const all = [...ALL, seoulGangnam];
    const groups = chosenGroups(chosenLibraries(all, new Set(['011001', '011002', '011003'])));
    expect(groups.map((g) => [g.label, g.libraries.length])).toEqual([
      ['강남구', 1],
      ['서초구', 2],
    ]);
  });

  it('주소를 모르는 곳은 지역들 사이에 끼우지 않고 맨 뒤에 둔다', () => {
    const unknown = lib('141236', 8, null, null, '고양시립백석도서관');
    const groups = chosenGroups(chosenLibraries([...ALL, unknown], new Set(['011001', '141053', '141236'])));
    expect(groups.map((g) => g.label)).toEqual(['경기도', '서울특별시', '주소를 모르는 곳']);
  });
});

describe('이름으로 보여 줄지 지역으로 묶을지', () => {
  it('이름으로 감당되는 동안에는 묶지 않는다', () => {
    expect(groupsWorthShowing(CHOSEN_NAMES_MAX, 5)).toBe(false);
  });

  it('묶어도 거의 줄지 않으면 이름을 그대로 둔다', () => {
    // 서른 곳이 열다섯 지역에 흩어져 있으면 묶음도 열다섯 줄입니다. 줄지 않는데 이름만
    // 한 번 더 눌러야 보이므로 손해입니다.
    expect(groupsWorthShowing(30, 15)).toBe(false);
  });

  it('많고 실제로 줄어들면 묶는다', () => {
    // 서울 전체 359곳이 스물다섯 구로 묶이는 경우입니다.
    expect(groupsWorthShowing(359, 25)).toBe(true);
  });

  it('묶음이 하나면 묶지 않는다', () => {
    // 시군구 하나를 통째로 고른 경우라, 묶어도 그 묶음 하나가 전부입니다.
    expect(groupsWorthShowing(31, 1)).toBe(false);
  });
});

describe('묶음 토글', () => {
  it('부분 선택에서 누르면 전체 선택이 된다', () => {
    // 해제로 가면 이미 고른 것이 사라져 사용자가 놀랍니다.
    const next = toggleGroup(new Set(['011001']), [SEOUL_A, SEOUL_B]);
    expect([...next].sort()).toEqual(['011001', '011002']);
  });

  it('전체 선택에서 누르면 해제된다', () => {
    const next = toggleGroup(new Set(['011001', '011002']), [SEOUL_A, SEOUL_B]);
    expect([...next]).toEqual([]);
  });

  it('묶음을 해제해도 다른 지역의 선택은 남는다', () => {
    const next = toggleGroup(new Set(['011001', '011002', '141053']), [SEOUL_A, SEOUL_B]);
    expect([...next]).toEqual(['141053']);
  });

  it('개별 토글은 뒤집는다', () => {
    expect([...toggleOne(new Set(), '011001')]).toEqual(['011001']);
    expect([...toggleOne(new Set(['011001']), '011001')]).toEqual([]);
  });
});

describe('지역 계층', () => {
  it('시도와 시군구로 묶고 이름순으로 정렬한다', () => {
    const tree = buildRegionTree(ALL);
    expect(tree.map((node) => node.sido)).toEqual(['경기도', '서울특별시']);
    expect(tree[1].libraryCount).toBe(2);
    expect(tree[1].sigungus[0].libraries.map((l) => l.name))
      .toEqual(['국립중앙도서관', '서초구립도서관']);
  });
});

describe('거리 계산', () => {
  it('위경도가 없으면 거리를 재지 않는다', () => {
    // 표준데이터 대조에 실패한 도서관은 위경도가 비어 있습니다.
    expect(haversineKm(37.5, 127.0, SEOUL_A)).toBeNull();
  });

  it('가까운 곳이 더 작은 값을 갖는다', () => {
    const near = { ...SEOUL_A, latitude: 37.5, longitude: 127.0 };
    const far = { ...GYEONGGI, latitude: 35.1, longitude: 129.0 };
    expect(haversineKm(37.5, 127.0, near)!).toBeLessThan(1);
    expect(haversineKm(37.5, 127.0, far)!).toBeGreaterThan(300);
  });
});

describe('지역 트리에 자리가 없는 도서관', () => {
  // 주소를 해석하지 못한 도서관입니다. 예전에는 「기타」라는 묶음에 몰아넣었는데,
  // 지역으로 훑는 사람에게 「기타」는 아무것도 알려 주지 않아 열어 볼 이유가 없습니다.
  const NO_ADDRESS = lib('999999', 99, null, null, '주소없는도서관');

  it('트리에서는 빠진다', () => {
    const tree = buildRegionTree([...ALL, NO_ADDRESS]);

    expect(tree.map((node) => node.sido)).toEqual(['경기도', '서울특별시']);
    expect(tree.reduce((sum, node) => sum + node.libraryCount, 0)).toBe(ALL.length);
  });

  it('그래도 목록 자체에서 사라지지는 않는다', () => {
    // 목록에서 빼 버리면 이름으로 검색해도 안 나오고, 사용자는 그것을
    // 「그런 도서관이 없다」로 읽습니다. 트리에서 빠지는 것과는 전혀 다른 이야기입니다.
    const all = [...ALL, NO_ADDRESS];
    expect(all.filter((l) => l.name.includes('주소없는'))).toHaveLength(1);
  });

  it('시군구만 없어도 트리에서 빠진다', () => {
    const sidoOnly = lib('999998', 98, '서울특별시', null, '시군구없는도서관');
    const tree = buildRegionTree([SEOUL_A, sidoOnly]);

    expect(tree).toHaveLength(1);
    expect(tree[0].libraryCount).toBe(1);
  });
});

describe('내 주변', () => {
  // 서울시청 근처를 기준점으로 삼고, 거리에 맞춰 북쪽으로 좌표를 흩뜨립니다.
  // 위도 1도는 2πR/360 = 111.195km 이고, haversineKm 이 쓰는 R 과 같은 값이라
  // 아래 km 가 그대로 haversineKm 의 결과가 됩니다.
  const KM_PER_DEGREE = (2 * Math.PI * 6371) / 360;
  const HERE = { lat: 37.5665, lon: 126.978 };
  const at = (km: number, name: string): Library => ({
    ...lib(`x${km}${name}`, 900 + Math.round(km * 10), '서울특별시', '중구', name),
    latitude: HERE.lat + km / KM_PER_DEGREE,
    longitude: HERE.lon,
  });

  it('반경 안이 넉넉하면 그것만 보여 주고 넓혔다고 하지 않는다', () => {
    const result = nearbyLibraries(
      [at(1, '가'), at(2, '나'), at(3, '다'), at(30, '먼곳')],
      HERE.lat,
      HERE.lon,
    );
    expect(result.libraries.map((l) => l.name)).toEqual(['가', '나', '다']);
    expect(result.widened).toBe(false);
    expect(result.withinRadius).toBe(3);
  });

  it('가까운 순으로 나온다', () => {
    const result = nearbyLibraries([at(3, '다'), at(1, '가'), at(2, '나')], HERE.lat, HERE.lon);
    expect(result.libraries.map((l) => l.name)).toEqual(['가', '나', '다']);
  });

  it('반경 안이 모자라면 넓히되 넓혔다고 알린다', () => {
    // 빈 목록을 주면 사용자는 「우리 동네에 도서관이 없다」가 아니라
    // 「이 도구가 고장났다」로 읽습니다.
    const result = nearbyLibraries([at(1, '하나'), at(20, '멀리'), at(40, '더멀리')], HERE.lat, HERE.lon);
    expect(result.widened).toBe(true);
    expect(result.withinRadius).toBe(1);
    expect(result.libraries.map((l) => l.name)).toEqual(['하나', '멀리', '더멀리']);
  });

  it('반경 안에 하나도 없어도 빈 목록을 주지 않는다', () => {
    const result = nearbyLibraries([at(30, '멀리')], HERE.lat, HERE.lon);
    expect(result.widened).toBe(true);
    expect(result.withinRadius).toBe(0);
    expect(result.libraries).toHaveLength(1);
  });

  it('위경도가 없는 도서관은 거리를 잴 수 없어 빠진다', () => {
    // 목록에서 사라지는 것이 아니라 이 탭에만 안 나옵니다.
    // 이름 검색과 지역 계층에서는 그대로 찾힙니다.
    const result = nearbyLibraries([SEOUL_A, at(1, '가'), at(2, '나'), at(3, '다')], HERE.lat, HERE.lon);
    expect(result.libraries.map((l) => l.name)).toEqual(['가', '나', '다']);
  });

  it('반경 바로 안쪽은 넣고 바로 바깥쪽은 뺀다', () => {
    const result = nearbyLibraries(
      [at(NEARBY_RADIUS_KM - 0.1, '안쪽'), at(NEARBY_RADIUS_KM + 0.1, '바깥쪽'), at(1, '가'), at(2, '나')],
      HERE.lat,
      HERE.lon,
    );
    expect(result.withinRadius).toBe(3);
    expect(result.widened).toBe(false);
    expect(result.libraries.map((l) => l.name)).toEqual(['가', '나', '안쪽']);
  });

  it('아무것도 없으면 넓혔다고 하지 않는다', () => {
    const result = nearbyLibraries([], HERE.lat, HERE.lon);
    expect(result.libraries).toEqual([]);
    expect(result.widened).toBe(false);
  });
});
