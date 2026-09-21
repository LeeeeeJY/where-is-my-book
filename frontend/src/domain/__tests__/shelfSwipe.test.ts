import { describe, expect, it } from 'vitest';
import { NOT_SWIPE_FROM, SWIPE_MIN_PX, swipeStep } from '../shelfSwipe';

describe('swipeStep', () => {
  it('왼쪽으로 끌면 다음 책, 오른쪽으로 끌면 앞 책입니다', () => {
    expect(swipeStep(300, 300 - SWIPE_MIN_PX)).toBe(1);
    expect(swipeStep(100, 100 + SWIPE_MIN_PX)).toBe(-1);
  });

  it('짧은 움직임은 넘긴 것이 아니라 누른 것입니다', () => {
    expect(swipeStep(300, 300 - (SWIPE_MIN_PX - 1))).toBe(0);
    expect(swipeStep(300, 300 + (SWIPE_MIN_PX - 1))).toBe(0);
    expect(swipeStep(300, 300)).toBe(0);
  });

  /*
    **넘기는 자리가 아닌 곳에서 시작한 손가락은 `null` 로 옵니다.** 하단 시트에서
    아무리 멀리 끌어도 책이 넘어가면 안 됩니다. 시트는 지금 보고 있는 그 한 권에 관한
    자리라, 읽는 동안 밑의 책이 바뀌면 방금 본 것이 무엇이었는지를 잃습니다.
  */
  it('넘기는 자리가 아니면 아무리 멀리 끌어도 넘어가지 않습니다', () => {
    expect(swipeStep(null, 0)).toBe(0);
    expect(swipeStep(null, 1000)).toBe(0);
  });

  /* 손이 화면 밖에서 떨어지면 뗀 자리가 없습니다. */
  it('뗀 자리를 모르면 넘기지 않습니다', () => {
    expect(swipeStep(300, undefined)).toBe(0);
  });

  it('넘기지 않는 자리는 시트와 머리 줄입니다', () => {
    expect(NOT_SWIPE_FROM).toContain('.nearby__sheet');
    expect(NOT_SWIPE_FROM).toContain('.nearby__bar');
  });
});
