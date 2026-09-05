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
