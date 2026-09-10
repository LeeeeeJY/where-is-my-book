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
    case 'DETAIL_LOOKUP':
      // 누를 때 찾는 것이라 못 찾을 수 있습니다. 그때는 검색 결과로 내려가는데, 그 사실을
      // 미리 말해 두지 않으면 사용자는 검색 결과 화면을 보고 「페이지라더니」로 읽습니다.
      return '이 책 페이지로 이동 (찾지 못하면 검색 결과)';
    case 'ISBN_SEARCH':
      return '이 책 검색 결과로 이동';
    case 'TITLE_SEARCH':
      return '제목 검색 결과로 이동';
    default:
      return '도서관 홈페이지로 이동 (주소 규칙 없음)';
  }
}

/**
 * 목록에 붙이는 짧은 표시. <b>기본값일 때는 붙이지 않습니다.</b>
 *
 * <p>주소 규칙 표가 아직 비어 있어서 <b>지금은 모든 도서관이 홈페이지로 갑니다.</b> 그래서
 * 「홈페이지」 배지가 소장 도서관 수만큼 똑같이 반복되는데, 다 같은 값이면 아무것도
 * 가려 주지 못하면서 자리만 차지합니다. 게다가 테두리가 둥글어 <b>누를 수 있는 것처럼
 * 보였습니다.</b>
 *
 * <p><b>그렇다고 조용히 강등하는 것은 아닙니다.</b> 홈페이지로 내려앉았다는 사실은 목록
 * 아래에 {@link anyHomepageOnly} 로 한 번 밝힙니다. 그리고 규칙이 채워져 「이 책 페이지」로
 * 갈 수 있게 되면 그 도서관에만 배지가 붙어 <b>오히려 눈에 띕니다.</b> 드문 것에 표시를
 * 다는 편이 흔한 것에 다는 편보다 잘 읽힙니다.
 */
export function linkBadge(kind: LinkKind | undefined): string {
  switch (kind) {
    case 'ISBN_DETAIL':
    case 'DETAIL_LOOKUP':
      return '이 책 페이지';
    case 'ISBN_SEARCH':
      return '이 책 검색';
    case 'TITLE_SEARCH':
      return '제목 검색';
    default:
      return '';
  }
}

/** 목록에 홈페이지로 내려앉은 도서관이 섞여 있는지. 있으면 아래에 한 번 설명합니다. */
export function anyHomepageOnly(kinds: (LinkKind | undefined)[]): boolean {
  return kinds.some((kind) => kind === undefined || kind === 'HOMEPAGE');
}

/**
 * 도서관 링크에 넣을 ISBN 을 **그 도서관이 실제로 가진 판부터** 세웁니다.
 *
 * <p>저작의 첫 ISBN 으로 보내면, 그 판이 없고 다른 판만 있는 도서관에서는 OPAC 의 ISBN 검색이
 * **규칙이 맞아도 0건**이 됩니다. 사용자에게는 「소장한다더니 그 책이 없네」로 보이고, 판본이
 * 많은 책일수록 자주 그렇습니다. 서버가 `/api/holdings` 의 `heldIsbns` 로 어느 판을 가졌는지
 * 알려 주므로 그것을 앞에 세우고, 나머지 판은 뒤에 붙입니다. 서버가 상세를 찾을 때 첫 판에
 * 없으면 다음 판으로 한 번 더 찾습니다.
 *
 * <p>`heldIsbns` 가 없으면(예전 서버 응답이거나 조회 전) 저작의 목록 그대로입니다. 그때는
 * 예전과 같은 동작이고, 나아지지는 않지만 나빠지지도 않습니다.
 */
export function isbnsForLink(
  held: readonly string[] | undefined,
  all: readonly string[],
): string[] {
  const first = held ?? [];
  const rest = all.filter((isbn) => !first.includes(isbn));
  return [...first, ...rest];
}
