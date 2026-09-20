import { useState } from 'react';
import type { ShelfBook } from '../api';
import { coverPaper } from '../domain/coverPaper';

/**
 * 서가에 꽂힌 책 한 권. <b>표지 그림만 보이고 글자는 없습니다.</b>
 *
 * <h2>표지 아래에 제목을 적지 않습니다</h2>
 *
 * <p>실제 서가에서 책을 고르는 일은 <b>표지를 훑는 일</b>입니다. 제목을 한 권씩 읽는
 * 것이 아니라 눈에 걸리는 것을 집어 듭니다. 표지마다 제목을 달면 화면이 목록이 되고,
 * 그러면 검색 결과와 다를 것이 없어집니다.
 *
 * <p>대신 <b>{@code aria-label} 에 제목·저자·청구기호를 전부 담습니다.</b> 눈으로
 * 훑는 사람에게는 그림이 정보이지만, 화면을 읽어 주는 사람에게는 그림이 아무것도
 * 말하지 않습니다. 글자를 감춘 만큼 여기에 실어야 합니다.
 *
 * <h2>표지가 없는 책이 적지 않습니다</h2>
 *
 * <p>없다고 빈 자리로 두면 서가에 구멍이 뚫립니다. 실제 서가에서 책이 빠진 자리처럼
 * 보여서 「여기 뭔가 잘못됐다」로 읽힙니다. 그래서 <b>제목과 저자로 표지를 그립니다.</b>
 *
 * <p>색은 <b>분류번호에서 정합니다.</b> 「이 책 주변 서가」와 같은 규칙을 써야 하므로
 * {@code domain/coverPaper.ts} 한 곳에 두었습니다. 갈리면 녹색 책을 눌렀는데 갈색
 * 책이 열립니다.
 */
export function ShelfCover({
  book,
  dimmed,
  lifted,
  found,
  onPick,
}: {
  book: ShelfBook;
  /** 초성을 골랐는데 이 책이 아닐 때. 흐리게 물러납니다. */
  dimmed: boolean;
  /** 고른 초성의 저자일 때. 서가에서 살짝 앞으로 나옵니다. */
  lifted: boolean;
  /**
   * 짚어 둔 그 한 권일 때. <b>「책 찾기」로 찾아간 책과 손으로 눌러 본 책이 둘 다
   * 여기에 해당합니다.</b>
   *
   * <p><b>자리로 옮겨만 놓으면 어느 것을 찾은 것인지 알 수 없습니다.</b> 한 화면에
   * 스무 권이 서 있고 표지에는 글자가 없어서, 그 앞에 세워 주고 끝내면 사람이 다시
   * 하나씩 눌러 보게 됩니다. 누르고 들어갔다 나온 경우도 마찬가지라, 표시가 없으면
   * 방금 본 책을 찾으려고 같은 자리를 또 누릅니다.
   */
  found?: boolean;
  onPick: () => void;
}) {
  const [failed, setFailed] = useState(false);
  const showImage = Boolean(book.cover) && !failed;

  return (
    <button
      type="button"
      className="cover"
      data-dim={dimmed ? '' : undefined}
      data-lift={lifted ? '' : undefined}
      data-found={found ? '' : undefined}
      onClick={onPick}
      aria-label={describe(book)}
    >
      {/*
        **찾은 책에는 서표를 끼웁니다.** 테두리만으로는 찾은 것이 보이지 않습니다.
        실제 서가에서 「토지」를 찾으면 **같은 표지가 열여덟 권 나란히** 서 있는데,
        그 가운데 한 권에 두른 3px 선은 눈에 들어오지 않습니다. 표지가 없는 책은
        분류번호로 칠한 종이라 옆 책과 색까지 비슷합니다.

        서표는 책 위로 삐져나오므로 줄 안에서 혼자 튀어 보이고, 도서관 책에 실제로
        꽂혀 있는 것이라 이 화면의 말과도 맞습니다. **자리는 차지하지 않습니다.**
        띄워 두었으므로(`position: absolute`) 줄 높이가 변하지 않습니다.
      */}
      {found && <span className="cover__mark" aria-hidden="true" />}
      <span
        className="cover__body"
        style={showImage ? undefined : { backgroundImage: coverPaper(book) }}
      >
        {showImage ? (
          /*
            **나타나는 효과를 붙이지 마세요.** 보이는 줄만 그리므로 스크롤하면 표지가
            끊임없이 새로 붙는데, 그때마다 투명도가 0 에서 시작하면 **줄이 지나갈 때마다
            네 권이 한꺼번에 깜빡입니다.** 자리는 `cover__art` 의 `aspect-ratio` 가 이미
            잡아 두므로 배치는 흔들리지 않습니다.
          */
          <img
            className="cover__art"
            src={book.cover}
            alt=""
            loading="lazy"
            decoding="async"
            onError={() => setFailed(true)}
          />
        ) : (
          /*
            대체 표지. 실제 책의 장정처럼 제목이 위쪽에, 저자가 아래쪽에 놓입니다.
            표지가 있는 책과 같은 칸을 쓰므로 서가의 줄이 흐트러지지 않습니다.
          */
          <span className="cover__made">
            <span className="cover__made-title">{book.title}</span>
            {book.author && <span className="cover__made-author">{book.author}</span>}
          </span>
        )}
      </span>
    </button>
  );
}

/**
 * 화면을 읽어 주는 사람에게 이 책이 무엇인지.
 *
 * <p><b>청구기호까지 넣습니다.</b> 이 화면의 값어치가 「서가의 이 자리에 이 책이
 * 있다」는 것인데, 청구기호가 빠지면 그 자리를 알려 주지 못한 채 제목만 읽어 주는
 * 셈이 됩니다.
 */
function describe(book: ShelfBook): string {
  return [book.title, book.author, book.call].filter(Boolean).join(', ');
}
