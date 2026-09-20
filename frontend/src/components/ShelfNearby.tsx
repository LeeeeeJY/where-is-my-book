import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchShelfChunk, libraryLink, type ShelfBook, type ShelfMeta, type ShelfRoom } from '../api';
import type { Library } from '../domain/types';
import { coverPaper } from '../domain/coverPaper';
import { linkBadge, linkLabel } from '../domain/opacLink';
import { locate } from '../domain/shelfLayout';

/**
 * 고른 책을 가운데 두고 <b>그 양옆에 실제로 꽂혀 있는 책</b>을 보여 줍니다.
 *
 * <h2>서가 앞에 선 사람이 하는 일</h2>
 *
 * <p>도서관에서 책 한 권을 집으면 대개 그 옆의 책도 봅니다. 청구기호가 가까운 책은
 * 같은 갈래의 같은 저자 언저리라, 찾던 책이 대출 중일 때 실제로 집어 드는 것이
 * 그 옆 책입니다. <b>목록으로는 이것이 되지 않습니다.</b> 「관련 도서」가 아니라
 * 「옆자리」여야 합니다.
 *
 * <h2>호출이 늘지 않습니다</h2>
 *
 * <p>서가는 이미 청구기호 순으로 잘라 둔 파일이라, 옆자리는 <b>그 파일의 앞뒤 칸</b>
 * 입니다. 새로 물어볼 것이 없고 조각 경계를 넘을 때만 이웃 조각을 한 번 더 받습니다.
 */
export function ShelfNearby({
  meta,
  room,
  index,
  library,
  onClose,
  onMove,
}: {
  meta: ShelfMeta;
  room: ShelfRoom;
  /** 서가에서 몇 번째 자리인지. 좌우로 넘기면 이 값이 움직입니다. */
  index: number;
  library: Library | undefined;
  onClose: () => void;
  onMove: (index: number) => void;
}) {
  const [chunks, setChunks] = useState<Map<number, ShelfBook[]>>(new Map());

  /*
    가운데 책과 양옆 둘씩이면 다섯 칸입니다. 조각 경계에 걸치면 이웃 조각도 받아야
    하므로 **보이는 자리에 필요한 조각을 전부** 셉니다.
  */
  useEffect(() => {
    const wanted = new Set<number>();
    for (let at = index - 2; at <= index + 2; at++) {
      if (at < 0 || at >= room.count) continue;
      wanted.add(locate(at, meta.chunkSize).chunk);
    }

    let cancelled = false;
    for (const chunk of wanted) {
      if (chunks.has(chunk)) continue;
      fetchShelfChunk(meta.libCode, meta.kdc, room.slug, chunk, meta.asOf).then(
        (books) => {
          if (cancelled) return;
          setChunks((before) => {
            if (before.has(chunk)) return before;
            const after = new Map(before);
            after.set(chunk, books);
            return after;
          });
        },
        () => {
          /* 옆자리 하나를 못 받아도 가운데 책은 그대로 보입니다. */
        },
      );
    }
    return () => {
      cancelled = true;
    };
  }, [index, meta, room, chunks]);

  const bookAt = useCallback(
    (at: number): ShelfBook | null => {
      if (at < 0 || at >= room.count) return null;
      const spot = locate(at, meta.chunkSize);
      return chunks.get(spot.chunk)?.[spot.at] ?? null;
    },
    [chunks, meta.chunkSize, room.count],
  );

  const center = bookAt(index);

  /*
    **좌우로 넘깁니다.** 서가 앞에서 옆으로 걸어가는 동작이라, 위아래가 아니라
    좌우여야 합니다. 손가락과 키보드 양쪽을 받습니다.
  */
  const touchFrom = useRef<number | null>(null);
  const step = useCallback(
    (by: number) => {
      const next = index + by;
      if (next < 0 || next >= room.count) return;
      onMove(next);
    },
    [index, room.count, onMove],
  );

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'ArrowLeft') step(-1);
      else if (event.key === 'ArrowRight') step(1);
      else if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [step, onClose]);

  return (
    <div
      className="nearby"
      role="dialog"
      aria-modal="true"
      aria-label="이 책 주변 서가"
      onTouchStart={(event) => {
        touchFrom.current = event.touches[0]?.clientX ?? null;
      }}
      onTouchEnd={(event) => {
        const from = touchFrom.current;
        const to = event.changedTouches[0]?.clientX;
        touchFrom.current = null;
        // 짧은 움직임은 넘긴 것이 아니라 누른 것입니다.
        if (from === null || to === undefined || Math.abs(to - from) < 40) return;
        step(to < from ? 1 : -1);
      }}
    >
      <button type="button" className="nearby__close chip chip--sm" onClick={onClose}>
        서가로 돌아가기
      </button>

      {/*
        **선반 앞면의 서가 라벨.** 실제 도서관 서가 끝에 붙어 있는 것과 같은 것이고,
        지금 보는 자리가 그 서가의 어디쯤인지를 말합니다.
      */}
      <p className="nearby__label">
        <span className="nearby__label-room">{room.name || room.code || '자료실'}</span>
        <span className="nearby__label-range">
          {room.firstCall} – {room.lastCall}
        </span>
      </p>

      <div className="nearby__stage">
        {[-2, -1, 0, 1, 2].map((offset) => {
          const book = bookAt(index + offset);
          const spot = Math.abs(offset);
          return (
            <div
              className="nearby__slot"
              key={offset}
              data-spot={spot}
              aria-hidden={offset === 0 ? undefined : 'true'}
            >
              {book ? (
                <button
                  type="button"
                  className="nearby__book"
                  tabIndex={offset === 0 ? 0 : -1}
                  onClick={() => offset !== 0 && step(offset)}
                  aria-label={
                    offset === 0
                      ? [book.title, book.author, book.call].filter(Boolean).join(', ')
                      : undefined
                  }
                >
                  {book.cover ? (
                    <img className="nearby__art" src={book.cover} alt="" loading="lazy" />
                  ) : (
                    /*
                      **서가와 같은 색으로 그립니다.** 규칙이 갈리면 녹색 책을
                      눌렀는데 갈색 책이 나와, 누른 것과 열린 것이 같은 책인지 알 수
                      없어집니다.
                    */
                    <span
                      className="nearby__made"
                      style={{ backgroundImage: coverPaper(book) }}
                    >
                      <span className="nearby__made-title">{book.title}</span>
                      {book.author && <span className="nearby__made-author">{book.author}</span>}
                    </span>
                  )}
                </button>
              ) : (
                /* 서가의 끝입니다. 빈 자리를 남겨 두어야 끝이라는 것이 보입니다. */
                <span className="nearby__end" />
              )}
            </div>
          );
        })}
      </div>

      <div className="nearby__plank" aria-hidden="true" />

      {/*
        **눈금.** 이 서가 전체에서 지금 어디쯤인지를 말합니다. 좌우로 넘기다 보면
        어디까지 왔는지를 잃는데, 숫자만으로는 감이 오지 않고 막대가 있으면 한눈에
        들어옵니다.
      */}
      <div className="nearby__ruler" aria-hidden="true">
        <span style={{ left: `${(index / Math.max(1, room.count - 1)) * 100}%` }} />
      </div>
      <p className="nearby__where muted">
        {(index + 1).toLocaleString('ko-KR')} / {room.count.toLocaleString('ko-KR')}
      </p>

      {/* 하단 시트. 가운데 책에 관한 것만 담습니다. */}
      <div className="nearby__sheet">
        {center ? (
          <>
            <h2 className="nearby__title">{center.title}</h2>
            <p className="nearby__meta">
              {[center.author, center.publisher, center.year].filter(Boolean).join(' · ')}
            </p>
            <dl className="nearby__facts">
              <div>
                <dt>청구기호</dt>
                <dd className="nearby__call">{center.call || '—'}</dd>
              </div>
              <div>
                <dt>자료실</dt>
                <dd>{room.name || room.code || '—'}</dd>
              </div>
            </dl>

            {/*
              **도서관으로 넘기는 링크는 이미 있는 것을 그대로 씁니다.** 주소 규칙이
              없는 도서관은 홈페이지로 내려앉고, 그 사실은 `linkLabel` 이 밝힙니다.
              조용히 강등하면 사용자는 검색 결과 자체가 틀렸다고 생각합니다.
            */}
            <p className="nearby__go">
              <a
                className="chip chip--strong chip--go chip--wrap"
                href={libraryLink(meta.libCode, [center.isbn], center.title)}
                target="_blank"
                rel="noreferrer"
                title={linkLabel(library?.linkKind)}
              >
                <span className="lib__name">도서관 사이트에서 보기</span>
                {linkBadge(library?.linkKind) && (
                  <span className="lib__kind">{linkBadge(library?.linkKind)}</span>
                )}
              </a>
            </p>
          </>
        ) : (
          <p className="muted">책을 불러오는 중입니다.</p>
        )}
      </div>

      {/* 좌우 단추. 손가락으로 넘길 수 있지만 그것만으로는 있는 줄 모릅니다. */}
      <button
        type="button"
        className="nearby__step nearby__step--back"
        onClick={() => step(-1)}
        disabled={index <= 0}
        aria-label="앞 책"
      >
        ‹
      </button>
      <button
        type="button"
        className="nearby__step nearby__step--next"
        onClick={() => step(1)}
        disabled={index >= room.count - 1}
        aria-label="뒤 책"
      >
        ›
      </button>
    </div>
  );
}
