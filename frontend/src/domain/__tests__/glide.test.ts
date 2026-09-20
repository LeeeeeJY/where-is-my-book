import { describe, expect, it } from 'vitest';
import { easeGlide, glideDuration } from '../glide';

/**
 * 서가를 가로지르는 움직임입니다. **브라우저에 맡기면 스무 줄 내려가는 것과 4만
 * 픽셀을 가로지르는 것이 같은 시간**이라, 먼 거리가 잘라 붙인 것처럼 보입니다.
 */
describe('걸리는 시간', () => {
  it('멀수록 오래 걸린다', () => {
    expect(glideDuration(40_000)).toBeGreaterThan(glideDuration(3_000));
    expect(glideDuration(3_000)).toBeGreaterThan(glideDuration(300));
  });

  it('가까워도 너무 빠르지 않고, 멀어도 갇힌 느낌이 나지 않는다', () => {
    // 한 줄 아래로 가는 것도 눈이 따라갈 수 있어야 합니다.
    expect(glideDuration(1)).toBeGreaterThanOrEqual(280);
    // 서가 끝에서 끝으로 가도 1.1초를 넘지 않습니다. 그 이상은 기다림이 됩니다.
    expect(glideDuration(200_000)).toBeLessThanOrEqual(1100);
  });

  it('위로 가든 아래로 가든 같다', () => {
    expect(glideDuration(-9_000)).toBe(glideDuration(9_000));
  });

  /** 거리에 정비례시키면 먼 거리가 몇 초씩 걸립니다. */
  it('거리가 백 배가 되어도 시간은 네 배를 넘지 않는다', () => {
    expect(glideDuration(400_000)).toBeLessThan(glideDuration(4_000) * 4);
  });
});

describe('떠나고 도착하는 곡선', () => {
  it('처음과 끝이 맞아떨어진다', () => {
    expect(easeGlide(0)).toBe(0);
    expect(easeGlide(1)).toBe(1);
    expect(easeGlide(0.5)).toBeCloseTo(0.5, 10);
  });

  it('되돌아가지 않는다', () => {
    // 잠깐이라도 뒤로 가면 화면이 흔들린 것으로 보입니다.
    let before = -1;
    for (let i = 0; i <= 100; i++) {
      const now = easeGlide(i / 100);
      expect(now).toBeGreaterThanOrEqual(before);
      before = now;
    }
  });

  /**
   * **가운데에서 속도가 튀지 않아야 합니다.** 값만 이어지고 기울기가 끊기면 그 순간
   * 화면이 한 번 걸립니다.
   */
  it('가운데에서 속도가 이어진다', () => {
    const step = 1e-5;
    const before = (easeGlide(0.5) - easeGlide(0.5 - step)) / step;
    const after = (easeGlide(0.5 + step) - easeGlide(0.5)) / step;
    expect(after).toBeCloseTo(before, 3);
  });

  it('떠날 때는 천천히 붙고 끝에서는 길게 내려앉는다', () => {
    // 앞의 10% 동안 간 거리가 아주 짧아야 「스르르 떠나는」 느낌이 납니다.
    expect(easeGlide(0.1)).toBeLessThan(0.01);
    // 뒤의 10% 는 거의 다 온 자리에서 천천히 멈춥니다.
    expect(easeGlide(0.9)).toBeGreaterThan(0.99);
  });

  it('범위를 벗어난 값도 받아 낸다', () => {
    expect(easeGlide(-1)).toBe(0);
    expect(easeGlide(2)).toBe(1);
  });
});
