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
  sido: string;
  sigungu: string;
  latitude: number | null;
  longitude: number | null;
  homepageUrl: string | null;
};

export type CheckState = 'all' | 'some' | 'none';
