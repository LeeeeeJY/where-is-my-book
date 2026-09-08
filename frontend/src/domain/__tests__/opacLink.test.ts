import { describe, expect, it } from 'vitest';
import { linkLabel, resolveLink } from '../opacLink';
import { libraryLink } from '../../api';

describe('링크 단계 문구', () => {
  it('단계마다 다른 문구를 준다', () => {
    expect(linkLabel('ISBN_DETAIL')).toBe('이 책 페이지로 이동');
    expect(linkLabel('ISBN_SEARCH')).toBe('이 책 검색 결과로 이동');
    expect(linkLabel('TITLE_SEARCH')).toBe('제목 검색 결과로 이동');
    expect(linkLabel('HOMEPAGE')).toBe('도서관 홈페이지로 이동');
  });

  it('모르는 값은 홈페이지로 본다', () => {
    // 규칙을 아직 못 넣은 도서관입니다. 감추지 않고 홈페이지라고 말합니다.
    expect(linkLabel(undefined)).toBe('도서관 홈페이지로 이동');
  });
});

describe('도서관 링크 주소', () => {
  it('책을 주면 함께 실어 보낸다', () => {
    const url = libraryLink('111001', '9788983711892', '코스모스');
    expect(url).toContain('/api/go/111001?');
    expect(url).toContain('isbn=9788983711892');
    expect(url).toContain('title=%EC%BD%94%EC%8A%A4%EB%AA%A8%EC%8A%A4');
  });

  it('책이 없으면 물음표를 붙이지 않는다', () => {
    // 도서관 순위처럼 한 권을 가리키지 않는 자리입니다. 홈페이지로 갑니다.
    expect(libraryLink('111001')).toMatch(/\/api\/go\/111001$/);
  });

  it('도서관부호를 인코딩한다', () => {
    expect(libraryLink('a/b')).toContain('/api/go/a%2Fb');
  });
});

describe('주소 규칙이 없는 도서관', () => {
  const API = 'https://api.example/api/go/111001?isbn=9788983711892';
  const DETAIL = 'https://data4library.kr/bookV?seq=2083728';

  it('정보나루 상세 페이지로 보낸다', () => {
    // 도서관 첫 화면은 그 책에 대해 아무것도 말해 주지 않습니다.
    const link = resolveLink('HOMEPAGE', API, DETAIL);
    expect(link.href).toBe(DETAIL);
  });

  it('도서관이 아니라는 것을 문구에 밝힌다', () => {
    // 도서관 이름을 눌렀는데 다른 사이트가 뜨면, 말해 두지 않는 한 「링크가 잘못됐다」로 읽힙니다.
    expect(resolveLink('HOMEPAGE', API, DETAIL).label).toContain('정보나루');
    expect(resolveLink('HOMEPAGE', API, DETAIL).label).toContain('규칙 없음');
  });

  it('상세 주소도 없으면 홈페이지로 간다', () => {
    const link = resolveLink('HOMEPAGE', API, null);
    expect(link.href).toBe(API);
    expect(link.label).toBe('도서관 홈페이지로 이동');
  });

  it('규칙이 있으면 상세 주소가 있어도 도서관으로 보낸다', () => {
    // 도서관 그 책 페이지가 정보나루 상세보다 낫습니다. 거기서 바로 대출 신청을 합니다.
    const link = resolveLink('ISBN_SEARCH', API, DETAIL);
    expect(link.href).toBe(API);
    expect(link.label).toBe('이 책 검색 결과로 이동');
  });
});
