/** 도서관 마스터 한 건. 백엔드의 library 테이블에 대응합니다. */
export type Library = {
  /** 도서관부호. 모든 소스를 잇는 공통 키입니다. */
  libCode: string;
  /**
   * URL 인코딩에 쓰는 내부 번호.
   * 한 번 발급하면 절대 바뀌지 않습니다. 값이 바뀌면 공유된 링크가 전부 깨집니다.
   */
  shortId: number;
  name: string;
  /**
   * 주소에서 시도를 알아내지 못하면 비어 있습니다. 그런 도서관은 **지역 트리에만** 나오지
   * 않고 이름 검색에서는 그대로 찾힙니다. 목록에서 아예 빼면 사용자가 「그런 도서관이 없다」로
   * 읽게 됩니다.
   */
  sido: string | null;
  sigungu: string | null;
  latitude: number | null;
  longitude: number | null;
  homepageUrl: string | null;
  /**
   * 이 도서관 링크가 어느 단계까지 가는지. 서버가 주소 규칙 표를 보고 알려 줍니다.
   * 샘플 목록에는 없으므로 선택 항목이고, 없으면 홈페이지로 봅니다.
   */
  linkKind?: LinkKind;
};

/** 위에 있을수록 좋은 링크입니다. 백엔드 `OpacLink.Kind` 와 같은 값입니다. */
export type LinkKind = 'ISBN_DETAIL' | 'ISBN_SEARCH' | 'TITLE_SEARCH' | 'HOMEPAGE';

export type CheckState = 'all' | 'some' | 'none';
