import { describe, expect, it } from 'vitest';
import { linkLabel } from '../opacLink';

describe('링크 단계 문구', () => {
  it('어느 단계인지 그대로 말한다', () => {
    expect(linkLabel('ISBN_DETAIL')).toBe('이 책 페이지로 이동');
    expect(linkLabel('ISBN_SEARCH')).toBe('이 책 검색 결과로 이동');
    expect(linkLabel('TITLE_SEARCH')).toBe('제목 검색 결과로 이동');
  });

  it('홈페이지로 내려앉은 것을 감추지 않는다', () => {
    // 조용히 강등하면 사용자는 도서관 첫 화면에 도착해 놓고
    // 「소장한다더니 그 책이 없네」로 읽고 검색 결과 자체를 의심합니다.
    expect(linkLabel('HOMEPAGE')).toContain('홈페이지');
    expect(linkLabel('HOMEPAGE')).toContain('규칙 없음');
  });

  it('단계를 모를 때도 아는 척하지 않는다', () => {
    // 서버가 항목을 안 주는 예전 응답이 섞여도 「이 책 페이지」라고 말하면 안 됩니다.
    expect(linkLabel(undefined)).toBe(linkLabel('HOMEPAGE'));
  });
});
