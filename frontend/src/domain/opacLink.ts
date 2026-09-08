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
      return '도서관 홈페이지로 이동';
  }
}

/** 링크 하나를 그리는 데 필요한 것 전부. */
export type ResolvedLink = { href: string; label: string };

/**
 * 어디로 보낼지와 그것을 뭐라고 부를지를 함께 정합니다.
 *
 * <p><b>도서관 주소 규칙이 없으면 정보나루의 책 상세 페이지로 보냅니다.</b> 도서관 첫 화면은
 * 그 책에 대해 아무것도 말해 주지 않아서, 사용자가 거기서 다시 검색해야 합니다. 정보나루
 * 상세 페이지는 적어도 어떤 책인지 보여 줍니다.
 *
 * <p>다만 <b>그곳은 도서관이 아니라는 사실을 문구에 그대로 씁니다.</b> 도서관 이름을 눌렀는데
 * 다른 사이트가 뜨면, 그 사실을 미리 말해 두지 않는 한 「링크가 잘못됐다」로 읽힙니다.
 */
export function resolveLink(
  kind: LinkKind | undefined,
  apiHref: string,
  detailUrl: string | null | undefined,
): ResolvedLink {
  if ((kind === undefined || kind === 'HOMEPAGE') && detailUrl) {
    return { href: detailUrl, label: '정보나루 책 정보로 이동 (이 도서관 페이지 규칙 없음)' };
  }
  return { href: apiHref, label: linkLabel(kind) };
}
