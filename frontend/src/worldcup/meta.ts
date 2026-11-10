/**
 * 월드컵 화면마다의 제목과 공유 미리보기.
 *
 * <p><b>화면과 빌드가 같은 함수를 씁니다.</b> 화면은 탭의 제목을 바꾸는 데 쓰고, 빌드는
 * 경로마다 따로 내보내는 HTML 의 미리보기 태그를 채우는 데 씁니다(`vite.config.ts`).
 * 둘이 따로 문구를 들고 있으면 공유했을 때 보이는 제목과 들어가서 보이는 제목이 갈립니다.
 *
 * <p><b>브라우저의 것을 쓰지 않습니다.</b> 빌드가 Node 에서 이 파일을 읽습니다. 그래서
 * 주제 파일을 여기서 불러오지 않고 인자로 받습니다.
 */
export const SITE_TITLE = 'WIMB · Where Is My Book';

export type PageMeta = {
  /** 빗금으로 시작하는 경로. 물음표 뒤는 없습니다. */
  path: string;
  title: string;
  description: string;
  /** 미리보기 그림의 경로(1200×630). */
  image: string;
  imageAlt: string;
};

type ThemeText = { title: string; description: string };
type BookText = { id: string; title: string; author: string };

export const WORLDCUP_NAME = '인생 책 월드컵';

export function homeMeta(): PageMeta {
  return {
    path: '/worldcup',
    title: `${WORLDCUP_NAME} · WIMB`,
    description: '둘 중 더 마음이 가는 책을 고르다 보면 나의 인생 책 한 권이 남습니다.',
    image: '/worldcup/og.jpg',
    imageAlt: `${WORLDCUP_NAME} — 둘씩 견주어 나의 인생 책 한 권을 고릅니다.`,
  };
}

export function playMeta(themeId: string, theme: ThemeText): PageMeta {
  return {
    path: `/worldcup/${themeId}`,
    title: `${theme.title} · ${WORLDCUP_NAME}`,
    description: theme.description,
    image: '/worldcup/og.jpg',
    imageAlt: `${WORLDCUP_NAME} — ${theme.title}`,
  };
}

export function resultMeta(themeId: string, theme: ThemeText, book: BookText): PageMeta {
  return {
    path: `/worldcup/${themeId}/result/${book.id}`,
    title: `《${book.title}》 · ${WORLDCUP_NAME}`,
    description: `${theme.title}에서 우승한 책은 《${book.title}》(${book.author})입니다. 나의 인생 책도 골라 보세요.`,
    image: `/worldcup/${themeId}/og/${book.id}.jpg`,
    imageAlt: `${WORLDCUP_NAME} 우승 책 《${book.title}》의 표지`,
  };
}
