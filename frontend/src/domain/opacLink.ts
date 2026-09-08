import type { LinkKind } from './types';

/**
 * 링크가 어느 단계인지 말해 주는 문구. **판정은 여기 한 곳에서만 합니다.**
 *
 * <p>화면마다 따로 적으면 언젠가 한쪽이 홈페이지 링크를 「이 책 페이지」라고 말하게 되고,
 * 그때는 아무도 눈치채지 못합니다. 사용자는 엉뚱한 곳에 도착해 놓고 소장 정보 자체가
 * 틀렸다고 생각합니다.
 *
 * <p><b>도서관 이름을 누르면 그 도서관으로 갑니다.</b> 한동안은 주소 규칙이 없을 때 정보나루
 * 책 상세로 보냈는데, 규칙 표가 비어 있어서 결국 모든 도서관이 정보나루로 갔습니다.
 * 도서관을 눌렀는데 도서관이 아닌 곳이 뜨는 데다, 소장 도서관이 스무 곳이면 같은 링크가
 * 스무 번 반복됐습니다. 정보나루 책 정보는 책마다 한 번만 따로 답니다.
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
      return '도서관 홈페이지로 이동 (주소 규칙 없음)';
  }
}
