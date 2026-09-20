import { describe, expect, it } from 'vitest';
import {
  COLS,
  OVERSCAN,
  chunksFor,
  locate,
  rowCount,
  scrollToRow,
  visibleRows,
} from '../shelfLayout';

/**
 * 서가는 20만 권이라 보이는 줄만 그립니다. 여기서 한 줄만 틀려도 **스크롤할 때 책이
 * 건너뛰거나 겹치는데**, 빠르게 밀 때만 잠깐 보여서 눈으로는 잡기 어렵습니다.
 */
describe('줄 세기', () => {
  it('한 줄에 네 권씩 나눈다', () => {
    expect(rowCount(0)).toBe(0);
    expect(rowCount(1)).toBe(1);
    expect(rowCount(COLS)).toBe(1);
    expect(rowCount(COLS + 1)).toBe(2);
    expect(rowCount(200_000)).toBe(50_000);
  });

  it('권수가 음수로 와도 줄이 음수가 되지 않는다', () => {
    expect(rowCount(-5)).toBe(0);
  });
});

describe('보이는 줄 고르기', () => {
  const ROW = 200;
  const VIEW = 800;

  it('보이는 칸에 더해 위아래를 조금 더 그린다', () => {
    // 4,000px 을 내려왔으면 20번째 줄이 맨 위이고, 화면에는 네 줄이 들어갑니다.
    const range = visibleRows(4_000, VIEW, ROW, 1_000);
    expect(range.from).toBe(20 - OVERSCAN);
    expect(range.to).toBe(24 + OVERSCAN);
  });

  /**
   * 휴대폰은 맨 위에서 더 끌어올릴 수 있어 `scrollTop` 이 음수가 됩니다. 그대로
   * 두면 없는 줄을 그리려다 **서가가 통째로 빈 화면**이 됩니다.
   */
  it('맨 위에서 더 끌어올려도 서가 밖으로 나가지 않는다', () => {
    const range = visibleRows(-300, VIEW, ROW, 1_000);
    expect(range.from).toBe(0);
    expect(range.to).toBeGreaterThan(0);
  });

  it('맨 아래에서 더 끌어내려도 없는 줄을 그리지 않는다', () => {
    const range = visibleRows(10_000_000, VIEW, ROW, 50);
    expect(range.from).toBeLessThanOrEqual(50);
    expect(range.to).toBe(50);
  });

  it('책이 없으면 그릴 것도 없다', () => {
    expect(visibleRows(0, VIEW, ROW, 0)).toEqual({ from: 0, to: 0 });
  });

  /** 줄 높이는 화면 너비에서 계산합니다. 아직 재지 못했을 때 0 이 들어옵니다. */
  it('줄 높이를 아직 재지 못했으면 그리지 않는다', () => {
    expect(visibleRows(0, VIEW, 0, 1_000)).toEqual({ from: 0, to: 0 });
  });
});

describe('받아야 하는 조각 고르기', () => {
  /**
   * **한 줄이 두 조각에 걸칠 수 있습니다.** 조각 크기가 네 권으로 나누어떨어지지
   * 않으면 늘 그렇고, 그때 한 조각만 받으면 그 줄의 뒷부분이 빈 채로 남습니다.
   */
  it('줄이 두 조각에 걸치면 둘 다 받는다', () => {
    // 조각 크기 10, 한 줄 4권. 세 번째 줄(8~11번)이 0번 조각과 1번 조각에 걸칩니다.
    expect(chunksFor({ from: 2, to: 3 }, 10)).toEqual([0, 1]);
  });

  it('한 조각 안에 들어가면 하나만 받는다', () => {
    expect(chunksFor({ from: 0, to: 2 }, 200)).toEqual([0]);
  });

  it('여러 조각에 걸치면 사이의 것까지 빠짐없이 받는다', () => {
    expect(chunksFor({ from: 0, to: 150 }, 200)).toEqual([0, 1, 2]);
  });

  it('그릴 줄이 없으면 받을 것도 없다', () => {
    expect(chunksFor({ from: 5, to: 5 }, 200)).toEqual([]);
    expect(chunksFor({ from: 0, to: 10 }, 0)).toEqual([]);
  });
});

describe('그 권이 있는 조각 찾기', () => {
  /** 초성 색인이 「ㅅ 은 1,523번째」라고 알려 주면 그 권을 찾아가야 합니다. */
  it('권 번호를 조각 번호와 그 안의 자리로 나눈다', () => {
    expect(locate(0, 200)).toEqual({ chunk: 0, at: 0 });
    expect(locate(199, 200)).toEqual({ chunk: 0, at: 199 });
    expect(locate(200, 200)).toEqual({ chunk: 1, at: 0 });
    expect(locate(1_523, 200)).toEqual({ chunk: 7, at: 123 });
  });
});

describe('초성을 눌렀을 때 가는 자리', () => {
  /**
   * **맨 윗줄에 딱 붙이지 않습니다.** 그 앞에 무엇이 있었는지 보이지 않으면 서가에서
   * 손가락으로 짚은 느낌이 나지 않습니다.
   */
  it('누른 줄 위를 조금 남긴다', () => {
    const at = scrollToRow(100, 200, 800);
    expect(at).toBeLessThan(100 * 200);
    expect(at).toBeGreaterThan(100 * 200 - 400);
  });

  it('맨 앞 줄이면 위로 더 올라가지 않는다', () => {
    expect(scrollToRow(0, 200, 800)).toBe(0);
  });
});
