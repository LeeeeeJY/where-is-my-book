import { useState } from 'react';
import type { ShelfBook } from '../api';

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
 * <p>색은 <b>분류번호에서 정합니다.</b> 같은 갈래의 책들이 비슷한 색을 띠게 되는데,
 * 실제 서가에서 한 출판사의 총서가 같은 장정으로 늘어서 있는 것과 같은 모양입니다.
 * 무작위로 칠하면 서가가 알록달록해져 오히려 눈이 쉴 곳이 없습니다.
 */
export function ShelfCover({
  book,
  dimmed,
  lifted,
  onPick,
}: {
  book: ShelfBook;
  /** 초성을 골랐는데 이 책이 아닐 때. 흐리게 물러납니다. */
  dimmed: boolean;
  /** 고른 초성의 저자일 때. 서가에서 살짝 앞으로 나옵니다. */
  lifted: boolean;
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
      onClick={onPick}
      aria-label={describe(book)}
    >
      <span className="cover__body" style={showImage ? undefined : paperOf(book)}>
        {showImage ? (
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

/**
 * 대체 표지의 바탕. <b>분류번호가 색을 정합니다.</b>
 *
 * <p>같은 갈래끼리 비슷한 색이 되어 서가가 한 덩어리로 보입니다. 무작위로 칠하면
 * 알록달록해져 눈이 쉴 곳이 없고, 새로 고칠 때마다 색이 달라져 같은 책이 다른 책처럼
 * 보입니다. 분류번호로 정하면 몇 번을 열어도 같습니다.
 */
function paperOf(book: ShelfBook): React.CSSProperties {
  const klass = (book.call ?? book.isbn ?? '').replace(/[^0-9]/g, '');
  // 분류번호가 없으면 ISBN 이라도 씁니다. 색이 없는 것보다 아무 색이나 같은 규칙으로
  // 정해지는 편이 낫습니다. 어차피 이 색은 뜻을 담지 않고 갈래만 나눕니다.
  const seed = Number(klass.slice(0, 3) || '0');
  const hue = (seed * 37) % 360;
  return {
    // 채도와 밝기를 좁게 묶어 둡니다. 이 값을 넓히면 서가가 알록달록해집니다.
    backgroundImage: `linear-gradient(160deg,
      hsl(${hue} 22% 42%) 0%,
      hsl(${hue} 26% 32%) 55%,
      hsl(${hue} 24% 27%) 100%)`,
  };
}
