import type { LinkKind } from './types';

/**
 * 링크가 어느 단계인지 말해 주는 문구. **판정은 여기 한 곳에서만 합니다.**
 *
 * <p>화면마다 따로 적으면 언젠가 한쪽이 홈페이지 링크를 「이 책 페이지」라고 말하게 되고,
 * 그때는 아무도 눈치채지 못합니다. 사용자는 엉뚱한 곳에 도착해 놓고 소장 정보 자체가
 * 틀렸다고 생각합니다.
 */
export function linkLabel(kind: LinkKind | undefined): string {
  switch (kind) {
    case 'ISBN_DETAIL':
      return '이 책 페이지로 이동';
    case 'ISBN_SEARCH':
      return '이 책 검색 결과로 이동';
    case 'TITLE_SEARCH':
      return '제목 검색 결과로 이동';
    default:
      // 규칙을 아직 못 넣은 도서관입니다. 감추지 않고 그대로 밝힙니다.
      return '도서관 홈페이지로 이동';
  }
}
