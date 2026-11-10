import { useState } from 'react';
import type { Book } from './themes';
import { coverPath } from './themes';

/**
 * 후보 책의 표지 한 장.
 *
 * <p><b>표지가 오기 전에 자리를 잡아 둡니다.</b> 높이를 그림에 맡기면 도착하는 순간 칸이
 * 솟아올라 그 아래의 제목과 단추가 밀립니다. 대결 화면에서는 그 순간이 곧 손가락이 내려가는
 * 순간이라, 밀린 자리의 다른 책을 누르게 됩니다.
 *
 * <p><b>표지를 못 받으면 제목과 저자로 그립니다.</b> 빈 칸으로 두면 무엇과 무엇을 견주는지
 * 알 수 없습니다. 서가의 대체 표지와 같은 생김새입니다.
 */
export function BookCover({
  themeId,
  book,
  eager = false,
}: {
  themeId: string;
  book: Book;
  /** 첫 화면에 바로 보이는 표지인지. 아니면 보일 때쯤 받습니다. */
  eager?: boolean;
}) {
  const [failed, setFailed] = useState(false);

  if (failed) {
    return (
      <span className="wc-cover wc-cover--made" aria-hidden>
        <span className="wc-cover__title">{book.title}</span>
        <span className="wc-cover__author">{book.author}</span>
      </span>
    );
  }
  return (
    <img
      className="wc-cover"
      src={coverPath(themeId, book.id)}
      // 제목과 저자는 바로 옆에 글로 적혀 있습니다. 여기에 또 적으면 화면을 읽어 주는
      // 쪽에서 같은 말을 두 번 듣습니다.
      alt=""
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}
