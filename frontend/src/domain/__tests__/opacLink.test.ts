import { describe, expect, it } from 'vitest';
import { anyHomepageOnly, isbnsForLink, linkBadge, linkLabel } from '../opacLink';

describe('링크 단계 문구', () => {
  it('어느 단계인지 그대로 말한다', () => {
    expect(linkLabel('ISBN_DETAIL')).toBe('이 책 페이지로 이동');
    expect(linkLabel('ISBN_SEARCH')).toBe('이 책 검색 결과로 이동');
    expect(linkLabel('TITLE_SEARCH')).toBe('제목 검색 결과로 이동');
  });

  it('누를 때 찾는 상세는 못 찾을 수 있다는 것을 미리 말한다', () => {
    // 상세 주소가 내부 키라 서버가 검색 결과에서 링크를 찾아 보내는데, 못 찾으면 검색 결과로
    // 내려갑니다. 말해 두지 않으면 사용자는 검색 결과 화면을 보고 「페이지라더니」로 읽습니다.
    expect(linkLabel('DETAIL_LOOKUP')).toContain('이 책 페이지');
    expect(linkLabel('DETAIL_LOOKUP')).toContain('검색 결과');
    // 배지는 짧게, 미리 만든 상세와 같은 말입니다. 사용자에게는 같은 곳입니다.
    expect(linkBadge('DETAIL_LOOKUP')).toBe(linkBadge('ISBN_DETAIL'));
  });

  it('홈페이지로 내려앉은 것을 감추지 않는다', () => {
    // 조용히 강등하면 사용자는 도서관 첫 화면에 도착해 놓고
    // 「소장한다더니 그 책이 없네」로 읽고 검색 결과 자체를 의심합니다.
    expect(linkLabel('HOMEPAGE')).toContain('홈페이지');
    // 도착할 곳을 제대로 말하는 것이 요구이고, **왜 그런지는 요구가 아닙니다.** 예전에는
    // 「주소 규칙 없음」을 괄호에 달았는데, 규칙을 307줄 넣어 두고 정확도를 확인하는 동안
    // 꺼 둔 지금은 사실도 아닙니다. 방문자가 할 일은 어느 쪽이든 같습니다.
    expect(linkLabel('HOMEPAGE')).not.toContain('이 책');
  });

  it('단계를 모를 때도 아는 척하지 않는다', () => {
    // 서버가 항목을 안 주는 예전 응답이 섞여도 「이 책 페이지」라고 말하면 안 됩니다.
    expect(linkLabel(undefined)).toBe(linkLabel('HOMEPAGE'));
  });

  it('기본값에는 배지를 달지 않되, 강등 사실은 다른 곳에서 밝힌다', () => {
    // 지금은 주소 규칙 표가 비어 있어 모든 도서관이 홈페이지로 갑니다. 다 같은 값이면
    // 아무것도 가려 주지 못하면서 자리만 차지하고, 테두리 때문에 눌리는 것처럼 보입니다.
    expect(linkBadge('HOMEPAGE')).toBe('');
    expect(linkBadge(undefined)).toBe('');
    // 대신 목록 아래 안내가 그 사실을 말합니다.
    expect(anyHomepageOnly(['HOMEPAGE'])).toBe(true);
    // 규칙이 있는 도서관에는 그대로 붙습니다. 드문 것에 표시를 달아야 눈에 띕니다.
    expect(linkBadge('ISBN_DETAIL')).toBe('이 책 페이지');
  });
});

describe('링크에 넣을 ISBN 의 순서', () => {
  it('그 도서관이 실제로 가진 판을 앞에 세운다', () => {
    // 저작의 첫 ISBN 으로 보내면 그 판이 없는 도서관의 OPAC 검색이 규칙이 맞아도 0건이 됩니다.
    expect(isbnsForLink(['특별판'], ['초판', '특별판', '전자책'])).toEqual(['특별판', '초판', '전자책']);
  });

  it('가진 판이 여럿이면 그 순서대로, 나머지는 뒤에', () => {
    expect(isbnsForLink(['B', 'C'], ['A', 'B', 'C'])).toEqual(['B', 'C', 'A']);
  });

  it('무엇을 가졌는지 모르면 저작의 목록 그대로다', () => {
    // 예전 서버 응답이거나 조회 전입니다. 나아지지는 않지만 나빠지지도 않습니다.
    expect(isbnsForLink(undefined, ['A', 'B'])).toEqual(['A', 'B']);
    expect(isbnsForLink([], ['A', 'B'])).toEqual(['A', 'B']);
  });
});
