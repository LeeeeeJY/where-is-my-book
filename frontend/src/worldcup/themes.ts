/**
 * 월드컵의 주제와 후보.
 *
 * <p><b>주제는 `themes/` 아래 JSON 파일 하나입니다.</b> 파일 이름이 곧 주제의 이름표이고,
 * 파일을 더하면 주제가 늘어납니다. 여기에 주제를 글자로 적지 않습니다.
 *
 * <p>파일을 더한 뒤에는 `node tools/make-worldcup-assets.mjs` 를 한 번 돌립니다. 표지와
 * 공유 미리보기 그림, 서버가 받는 책 명단을 그 파일에서 만들어 냅니다.
 *
 * <h2>책의 이름표(`id`)는 바꾸지 않습니다</h2>
 *
 * <p>공유되는 결과 주소에 들어가고 서버 집계의 열쇠이기도 합니다. 바꾸면 공유된 링크가
 * 깨지고 그 책의 집계가 둘로 갈립니다. 대표 판본을 바꾸고 싶으면 `isbn13` 과 표지를
 * 바꾸고 이름표는 그대로 둡니다. 그래서 ISBN 을 이름표로 쓰지 않았습니다.
 *
 * <h2>줄거리를 적는 칸이 없습니다</h2>
 *
 * <p>아직 읽지 않은 사람이 고르는 화면입니다. 책에 관해 적는 것은 제목·저자·출판사뿐이고,
 * 소개 문구를 넣을 자리를 아예 두지 않았습니다.
 */
export type Book = {
  id: string;
  title: string;
  author: string;
  publisher: string;
  /** 대표 판본. 표지와 「이 판」을 가리키는 값이고, 도서관 찾기는 제목으로 합니다. */
  isbn13: string;
  /** 정보나루가 알려 준 표지 주소. 도구가 여기서 받아 `public/worldcup/` 에 둡니다. */
  coverSource: string;
  /** 분권이나 표제에 관한 메모. 화면에는 그리지 않습니다. */
  note?: string;
  /** 도서관 찾기에 쓸 제목이 표시 제목과 다를 때만 적습니다. */
  searchTitle?: string;
};

export type Theme = {
  id: string;
  title: string;
  description: string;
  books: Book[];
};

type ThemeFile = Omit<Theme, 'id'>;

const files = import.meta.glob<ThemeFile>('./themes/*.json', { eager: true, import: 'default' });

export const THEMES: Theme[] = Object.entries(files)
  .map(([path, file]) => ({ id: path.replace(/^.*\/([^/]+)\.json$/, '$1'), ...file }))
  .sort((a, b) => a.id.localeCompare(b.id));

export function themeById(id: string): Theme | null {
  return THEMES.find((theme) => theme.id === id) ?? null;
}

export function bookById(theme: Theme, id: string): Book | null {
  return theme.books.find((book) => book.id === id) ?? null;
}

/**
 * 표지 파일의 자리. <b>우리 주소에서 줍니다.</b>
 *
 * <p>정보나루가 알려 주는 표지는 다른 회사의 그림 서버에 있고, 그 가운데 절반은 그림을
 * 캔버스에 올려 저장하는 것을 허락하지 않습니다(2026-11-10 확인). 저장용 카드를 만들려면
 * 같은 주소의 파일이어야 해서 도구가 미리 받아 둡니다.
 */
export function coverPath(themeId: string, bookId: string): string {
  return `/worldcup/${themeId}/covers/${bookId}.jpg`;
}
