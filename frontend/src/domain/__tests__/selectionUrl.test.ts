import { describe, expect, it } from 'vitest';
import {
  BITMAP_PARAM, LIST_PARAM, decodeSelection, encodeSelection, paramsToSelection,
} from '../selectionUrl';
import type { Library } from '../types';

const catalog: Library[] = [1, 2, 3, 42, 1603].map((shortId) => ({
  libCode: `lib${shortId}`, shortId, name: `도서관${shortId}`,
  sido: '서울특별시', sigungu: '서초구', latitude: null, longitude: null, homepageUrl: null,
}));

const roundTrip = (ids: number[]) => {
  const encoded = encodeSelection(ids)!;
  return decodeSelection(new URLSearchParams(`${encoded.key}=${encoded.value}`)).sort((a, b) => a - b);
};

describe('선택 상태의 URL 표현', () => {
  it('적게 고르면 36진수 목록으로 짧게 만든다', () => {
    const encoded = encodeSelection([1, 42, 1603])!;
    expect(encoded.key).toBe(LIST_PARAM);
    expect(encoded.value).toBe('1,16,18j');
  });

  it('목록 방식으로 왕복해도 같다', () => {
    expect(roundTrip([1, 2, 3, 42, 1603])).toEqual([1, 2, 3, 42, 1603]);
  });

  it('많이 고르면 비트맵으로 전환한다', () => {
    const many = Array.from({ length: 150 }, (_, i) => i + 1);
    const encoded = encodeSelection(many)!;
    expect(encoded.key).toBe(BITMAP_PARAM);
    expect(roundTrip(many)).toEqual(many);
  });

  it('비트맵은 몇 곳을 고르든 길이가 일정하다', () => {
    const upToMax = (count: number) =>
      encodeSelection([...Array.from({ length: count }, (_, i) => i + 1), 1603])!.value.length;
    expect(upToMax(150)).toBe(upToMax(400));
  });

  it('20곳을 골라도 URL 이 짧다', () => {
    const twenty = Array.from({ length: 20 }, (_, i) => i * 37 + 1);
    expect(encodeSelection(twenty)!.value.length).toBeLessThan(100);
  });

  /*
    **여기까지의 예시는 번호가 1부터 촘촘하다고 보고 있습니다.** 실제로 서버가 주는
    `shortId` 는 도서관부호 그대로라 5만부터 1,470만까지 흩어져 있어서, 비트맵으로
    만들면 가장 큰 번호만큼 자리를 잡습니다. 그 차이를 아래 두 가지가 붙듭니다.
  */
  it('번호가 흩어져 있으면 비트맵으로 가지 않는다', () => {
    // 서울 359곳을 고른 상태입니다. 비트맵으로 만들면 1.4MB 라 주소에 실을 수 없고,
    // 그것을 `replaceState` 가 체크 한 번마다 받아 가면서 화면이 0.6초씩 멈췄습니다.
    const seoul = Array.from({ length: 359 }, (_, i) => 111001 + i * 31_000);
    const encoded = encodeSelection(seoul)!;
    expect(encoded.key).toBe(LIST_PARAM);
    expect(encoded.value.length).toBeLessThan(4_000);
    expect(roundTrip(seoul)).toEqual(seoul);
  });

  it('전국을 골라도 주소가 감당할 만하다', () => {
    const nationwide = Array.from({ length: 1_619 }, (_, i) => 50_001 + i * 9_050);
    const encoded = encodeSelection(nationwide)!;
    expect(encoded.value.length).toBeLessThan(16_000);
    expect(roundTrip(nationwide)).toEqual(nationwide);
  });

  it('아무것도 안 고르면 파라미터를 만들지 않는다', () => {
    expect(encodeSelection([])).toBeNull();
  });

  it('중복을 걸러 낸다', () => {
    expect(roundTrip([3, 1, 3, 1])).toEqual([1, 3]);
  });
});

describe('손상된 링크', () => {
  it('깨진 비트맵으로 화면이 죽지 않는다', () => {
    expect(decodeSelection(new URLSearchParams(`${BITMAP_PARAM}=!!!not-base64!!!`))).toEqual([]);
  });

  it('숫자가 아닌 목록 항목은 버린다', () => {
    expect(decodeSelection(new URLSearchParams(`${LIST_PARAM}=1,,zzz!,3`))).toEqual([1, 3]);
  });

  it('파라미터가 없으면 빈 선택이다', () => {
    expect(decodeSelection(new URLSearchParams(''))).toEqual([]);
  });
});

describe('도서관부호로 되돌리기', () => {
  it('카탈로그에 있는 번호만 살린다', () => {
    // 도서관이 목록에서 빠졌을 수 있으므로 모르는 번호는 조용히 버립니다.
    const selected = paramsToSelection(new URLSearchParams(`${LIST_PARAM}=1,16,zz`), catalog);
    expect([...selected].sort()).toEqual(['lib1', 'lib42']);
  });
});
