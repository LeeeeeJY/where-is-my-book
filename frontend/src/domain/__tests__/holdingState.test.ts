import { describe, expect, it } from 'vitest';
import { holdingState } from '../holdingState';

describe('소장 결과를 네 상태로 가른다', () => {
  it('고른 도서관이 없으면 물어본 적이 없는 것이다', () => {
    expect(holdingState(null, 0)).toBe('pending');
    // 결과가 비어 있어도 마찬가지입니다. 미소장이 아닙니다.
    expect(holdingState({ libCodes: [], complete: true, unreadable: false }, 0)).toBe('pending');
  });

  it('답이 아직 도착하지 않았으면 확인 중이다', () => {
    expect(holdingState(null, 3)).toBe('pending');
  });

  it('조회가 실패하면 확인 불가다', () => {
    expect(holdingState({ libCodes: [], complete: false, unreadable: true }, 3)).toBe('unknown');
  });

  it('찾은 곳이 없는데 확인하지 못한 판본이 있으면 확인 불가다', () => {
    // 이것이 가장 놓치기 쉬운 자리입니다. 빈 결과라고 미소장으로 그리면 안 됩니다.
    expect(holdingState({ libCodes: [], complete: false, unreadable: false }, 3)).toBe('unknown');
  });

  it('빠짐없이 확인했는데 없으면 그때만 미소장이다', () => {
    expect(holdingState({ libCodes: [], complete: true, unreadable: false }, 3)).toBe('none');
  });

  it('찾은 곳이 있으면 소장이다', () => {
    expect(holdingState({ libCodes: ['111001'], complete: true, unreadable: false }, 3)).toBe('held');
    // 일부를 확인하지 못했어도 찾은 곳이 있으면 소장입니다. 더 있을 수 있을 뿐입니다.
    expect(holdingState({ libCodes: ['111001'], complete: false, unreadable: false }, 3)).toBe('held');
  });
});
