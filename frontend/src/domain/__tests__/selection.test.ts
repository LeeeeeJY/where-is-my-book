import { describe, expect, it } from 'vitest';
import { buildRegionTree, groupState, haversineKm, toggleGroup, toggleOne } from '../selection';
import type { Library } from '../types';

const lib = (libCode: string, shortId: number, sido: string, sigungu: string, name: string): Library => ({
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
