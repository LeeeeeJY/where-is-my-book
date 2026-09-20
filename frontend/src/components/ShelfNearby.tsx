import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  SHELF_SHAPE_FINDABLE,
  fetchShelfChunk,
  libraryLink,
  type ShelfBook,
  type ShelfMeta,
  type ShelfRoom,
} from '../api';
import type { Library } from '../domain/types';
import { coverPaper } from '../domain/coverPaper';
import { linkBadge, linkLabel } from '../domain/opacLink';
import { locate } from '../domain/shelfLayout';
import { NOT_SWIPE_FROM, swipeStep } from '../domain/shelfSwipe';
import { LoanCheck } from './LoanCheck';
import { ShelfSearch } from './ShelfSearch';

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
  onShowOnShelf,
}: {
  meta: ShelfMeta;
  room: ShelfRoom;
  /** 서가에서 몇 번째 자리인지. 좌우로 넘기면 이 값이 움직입니다. */
  index: number;
  library: Library | undefined;
  onClose: () => void;
  onMove: (index: number) => void;
  /**
   * 지금 보고 있는 책 앞에 서면서 서가로 돌아갑니다.
   *
   * <p><b>뒤로 가기와 다른 일입니다.</b> 뒤로 가기는 열었던 자리로 돌아가는 것이고,
   * 이것은 옆으로 걸어온 만큼 옮겨 간 <b>지금 이 책</b> 앞에 서는 것입니다. 화살표로
   * 백 권을 넘긴 뒤라면 두 자리가 아주 멉니다.
   */
  onShowOnShelf: (index: number) => void;
}) {
  const [chunks, setChunks] = useState<Map<number, ShelfBook[]>>(new Map());
  const [finding, setFinding] = useState(false);

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

    **다만 넘기는 자리는 서가뿐입니다.** 어디서 시작했는지를 `null` 로 적어 두고,
    규칙은 `domain/shelfSwipe` 한 곳에 있습니다.
  */
  const touchFrom = useRef<number | null>(null);

  /** 덮개 자신. 열릴 때와 덮개 안을 누를 때 키보드를 여기로 데려옵니다. */
  const dialog = useRef<HTMLDivElement>(null);

  /*
    **넘어간 방향과 걸음 수.** 미끄러지는 것은 여기서 넘긴 걸음뿐입니다. 「책 찾기」로
    수백 권을 건너뛰는 것(`onGo={onMove}`)은 옆으로 걸어간 것이 아니라 자리를 옮긴
    것이라, 한 걸음짜리 미끄러짐을 붙이면 거리를 거짓말하게 됩니다.
  */
  const walked = useRef(0);
  const stage = useRef<HTMLDivElement>(null);
  const walking = useRef<Animation | null>(null);

  const step = useCallback(
    (by: number) => {
      const next = index + by;
      if (next < 0 || next >= room.count) return;
      walked.current = by;
      onMove(next);
    },
    [index, room.count, onMove],
  );

  /*
    **옆으로 걸어가는 화면이라 줄이 미끄러져야 합니다.** 다섯 칸은 제자리에 있고
    내용만 갈아 끼워지므로, 그냥 두면 <b>책이 뚝 끊기며 바뀝니다.</b> 서가 앞에서 한
    걸음 옮긴 것이 아니라 화면이 갈린 것으로 읽힙니다.

    그래서 <b>새 내용이 그려진 뒤에 줄을 방금 있던 자리로 되돌려 놓고 제자리까지
    미끄러뜨립니다.</b> 가운데 책이 옆칸으로 옮겨 간 거리(칸 가운데 사이)가 한 걸음에
    34%, 두 걸음에 52% 라 그만큼 어긋난 데서 시작합니다.

    **CSS 애니메이션이 아니라 여기서 겁니다.** 칸도 요소도 그대로 남아 있어서 클래스로
    다시 돌릴 방법이 없고, 열쇠를 갈면 표지가 다시 붙어 깜빡입니다. 그리고 **덮개가
    열릴 때 도는 층별 애니메이션과 부딪히지 않아야 합니다.** 대본으로 건 것이 CSS
    애니메이션보다 뒤에 놓이므로 걸음이 이깁니다.
  */
  useLayoutEffect(() => {
    const by = walked.current;
    walked.current = 0;
    if (!by) return;

    const el = stage.current;
    if (!el?.animate) return;
    // 움직임을 줄여 달라고 한 사람에게는 걸지 않습니다. 미끄러지는 화면이 멀미를
    // 만드는 것은 스크롤과 여기가 다르지 않습니다.
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

    // 빠르게 여러 번 넘기면 앞엣것이 아직 돌고 있습니다. 겹치면 서로 밀어냅니다.
    walking.current?.cancel();

    const far = Math.min(Math.abs(by), 2);
    const from = (by > 0 ? 1 : -1) * (far === 1 ? 34 : 52);
    const ease = 'cubic-bezier(0.22, 1, 0.36, 1)';
    walking.current = el.animate(
      [{ transform: `translateX(${from}%)` }, { transform: 'none' }],
      { duration: far === 1 ? 260 : 320, easing: ease },
    );

    // 집어 든 책만 살짝 올라옵니다. 줄이 미끄러지는 것만으로는 **어느 것이 손에 든
    // 책인지**가 안 보입니다. 크게 하면 옆 책과 부딪히므로 3.5% 입니다.
    el.querySelector('[data-spot="0"] .nearby__book')?.animate(
      [{ transform: 'scale(0.965)' }, { transform: 'none' }],
      { duration: 300, easing: ease },
    );
  }, [index]);

  /*
    **떠날 때 가던 것을 놓습니다.** 덮개를 닫는 순간 돌고 있던 걸음이 남아 있으면
    없어진 요소를 붙들고 있게 됩니다.
  */
  useEffect(() => () => walking.current?.cancel(), []);

  /*
    **열리면 이 화면이 키보드를 넘겨받습니다.** 서가의 표지를 눌러 여는 화면이라
    그냥 두면 <b>포커스가 덮개 뒤의 그 표지 단추에 그대로 남습니다.</b> 실측으로 열린
    직후 `document.activeElement` 가 `cover__open` 이었습니다. 화면은 덮어 놓고
    키보드만 뒤에 두고 온 셈이라, 탭은 보이지도 않는 서가를 돌아다니고 화면을 읽어
    주는 쪽도 덮인 서가를 계속 읽습니다. `aria-modal` 을 달아 놓고 그렇게 두면 그
    표시가 거짓말이 됩니다.

    **그리고 손짓 한 번이 그 포커스마저 떨어뜨립니다.** 시트처럼 포커스를 받지 않는
    자리를 누르거나 끌면 포커스가 `<body>` 로 내려앉는데(실측), 화면 안의 아무것도
    키보드를 들고 있지 않은 그 상태에서는 <b>브라우저가 첫 방향키를 「어디에 줄지」
    정하는 데 써 버리는 일이 있습니다.</b> 화면에는 아무 일도 일어나지 않아서
    <b>두 번 눌러야 넘어가는 것처럼 보입니다.</b> 그래서 덮개 안을 누를 때마다
    키보드를 이 화면으로 데려옵니다(아래 `onPointerDown`).

    **떠날 때는 열었던 자리에 돌려줍니다.** 그러지 않으면 닫은 사람이 서가 맨
    처음부터 다시 탭을 눌러 그 책을 찾아가야 합니다. 돌려줄 때 `preventScroll` 을
    빠뜨리지 마세요. 포커스는 그 자리를 화면 안으로 끌어오므로, 「서가에서 보기」로
    다른 자리에 미끄러져 가는 중이라면 <b>그 이동이 통째로 되돌려집니다.</b>
  */
  useEffect(() => {
    const opener = document.activeElement;
    dialog.current?.focus({ preventScroll: true });
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) {
        opener.focus({ preventScroll: true });
      }
    };
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      /*
        **글자를 적는 중이면 넘기지 않습니다.** 「책 찾기」 칸이 이 화면 안에 있어서,
        적던 것을 고치려고 화살표로 글자 사이를 오가면 그때마다 책이 넘어갑니다.
        적는 자리는 그대로인데 밑의 책만 바뀌므로 무엇을 찾고 있었는지를 잃습니다.
      */
      if (typingIn(event.target)) return;
      if (event.key === 'ArrowLeft') {
        /*
          **기본 동작을 막습니다.** 막지 않으면 덮개 뒤의 서가가 화살표를 함께 먹어
          스크롤이 움직입니다. 닫고 돌아왔을 때 보던 줄이 사라져 있는데, 이 화면은
          「뒤로 가면 보던 자리가 그대로 있어야 한다」를 위해 덮는 것입니다.
        */
        event.preventDefault();
        step(-1);
      } else if (event.key === 'ArrowRight') {
        event.preventDefault();
        step(1);
      } else if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [step, onClose]);

  return (
    <div
      className="nearby"
      ref={dialog}
      role="dialog"
      aria-modal="true"
      aria-label="이 책 주변 서가"
      /*
        **덮개 자신이 포커스를 받을 수 있어야 합니다.** 탭 차례에는 끼지 않으므로
        `-1` 입니다. 받을 수 있는 자리가 없으면 위의 효과가 키보드를 데려올 곳이
        없습니다.
      */
      tabIndex={-1}
      onPointerDown={(event) => {
        /*
          **누를 때마다 키보드를 이 화면으로 데려옵니다.** 단추나 링크를 누른 것이면
          그것이 이미 받았으므로 두지 않습니다. 그 밖의 자리(시트의 글자, 서가의
          어두운 곳)는 포커스를 받지 못해서, 누르는 순간 포커스가 화면 밖으로
          떨어집니다. 그 상태에서 방향키가 한 번 먹히지 않는 것이 위에 적어 둔
          「두 번 눌러야 넘어간다」입니다.
        */
        if (
          event.target instanceof Element &&
          event.target.closest('a, button, input, select, textarea')
        ) {
          return;
        }
        dialog.current?.focus({ preventScroll: true });
      }}
      onTouchStart={(event) => {
        /*
          **넘기는 자리인지는 손가락을 댄 자리가 정합니다.** 하단 시트와 머리 줄에서
          시작한 것은 넘기는 것이 아니므로 `null` 로 적어 둡니다. 규칙과 그 이유는
          `domain/shelfSwipe` 에 있습니다.
        */
        const at = event.target instanceof Element ? event.target : null;
        touchFrom.current = at?.closest(NOT_SWIPE_FROM)
          ? null
          : (event.touches[0]?.clientX ?? null);
      }}
      onTouchEnd={(event) => {
        const by = swipeStep(touchFrom.current, event.changedTouches[0]?.clientX);
        touchFrom.current = null;
        if (by) step(by);
      }}
    >
      {/*
        **여기서도 자리를 옮길 수 있어야 합니다.** 옆으로 한 권씩 넘기는 것과 눈금을
        끄는 것만으로는 수천 권짜리 서가에서 「그 책」 앞에 설 수 없습니다. 서가 화면과
        같은 통로를 쓰므로 정보나루 호출은 여기서도 0건입니다.
      */}
      <div className="nearby__bar">
        {/*
          **뒤로 가기는 화살표 하나입니다.** 열었던 자리로 되돌아가는 것뿐이라 글자로
          설명할 것이 없고, 머리 줄의 자리도 아껴야 찾기가 제 줄을 씁니다. 눈으로
          보이지 않는 이름은 `aria-label` 이 답니다.

          **글자 「←」를 쓰지 마세요.** 글꼴마다 그 글리프가 네모 안에서 앉는 높이가
          달라서, 칸을 가운데로 맞춰도 <b>획이 위로 떠 보입니다.</b> 실제로 옆의
          「책 찾기」보다 눈에 띄게 높이 붙어 있었습니다. 그려 넣으면 그 글꼴 사정이
          사라지고 선 굵기도 칩 테두리와 맞출 수 있습니다.
        */}
        <button
          type="button"
          className="nearby__back chip chip--sm chip--icon"
          onClick={onClose}
          aria-label="서가로 돌아가기"
          title="서가로 돌아가기"
        >
          <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true" focusable="false">
            <path
              d="M9.5 3.5 5 8l4.5 4.5"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <path d="M5.4 8H13" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        </button>
        {(meta.shapeVersion ?? 0) >= SHELF_SHAPE_FINDABLE && (
          <ShelfSearch
            meta={meta}
            room={room}
            open={finding}
            onOpen={setFinding}
            onGo={onMove}
          />
        )}
      </div>

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

      <div className="nearby__stage" ref={stage}>
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
        **눈금은 읽는 것이지 끄는 것이 아닙니다.** 이 서가 전체에서 지금 어디쯤인지를
        말합니다. 좌우로 넘기다 보면 어디까지 왔는지를 잃는데, 숫자만으로는 감이 오지
        않고 선이 있으면 한눈에 들어옵니다.

        **한때 손으로 끌 수 있었는데 걷어냈습니다.** 수천 권짜리 서가를 가로지르는
        길이 필요해서 붙였던 것인데, 「책 찾기」가 그 일을 훨씬 정확하게 합니다. 끌기는
        「대충 3분의 2쯤」밖에 못 하고, 그러자고 치르던 값이 작지 않았습니다. 손가락이
        집을 수 있게 누르는 자리를 44px 로 잡아 무대를 그만큼 밀어냈고, 바깥 상자의
        좌우 스와이프와 부딪혀 한 번 끌 때 자리가 두 번 움직이지 않도록 따로 막아야
        했습니다. **읽는 일만 남기면 그 둘이 함께 사라집니다.**

        **차오르는 값이 바로 아래 숫자와 같아야 합니다.** 선과 「1,276 / 4,000」이 한
        자리에 붙어 있는데 계산이 갈리면 둘 중 어느 쪽이 맞는지 알 방법이 없습니다.
        자리를 읽어 주는 것은 그 숫자가 이미 하므로 선은 감춥니다.
      */}
      <div className="nearby__ruler" aria-hidden="true">
        <span style={{ width: `${((index + 1) / Math.max(1, room.count)) * 100}%` }} />
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
            {/*
              **여기서는 ISBN 하나만 물어봅니다.** 「저작에 묶인 판본을 전부 물어보라」는
              규칙은 <b>그 도서관이 어느 판을 가졌는지 모를 때</b>의 규칙인데, 서가에 선
              이 책은 정보나루가 <b>그 도서관의 장서로 알려 준 바로 그 ISBN</b> 입니다.
              청구기호까지 함께 온 그 한 권을 지금 눈앞에서 보고 있는 것이라, 다른 판을
              끼워 물으면 <b>옆 서가에 있는 다른 책의 대출 상태를 이 책의 것으로</b>
              보여 주게 됩니다. 소장 조회를 이 화면에 붙이게 되면 이 예외도 함께
              거둬들여야 합니다.
            */}
            <p className="nearby__go chips">
              {/*
                **「서가에서 보기」는 이 책에 관한 일이라 책 옆에 둡니다.** 머리 줄의
                뒤로 가기는 열었던 자리로 돌아가는 것이고, 이것은 지금 보고 있는 이 책
                앞에 서는 것입니다. 둘을 한자리에 두면 같은 말처럼 읽힙니다.
              */}
              <button
                type="button"
                className="chip"
                onClick={() => onShowOnShelf(index)}
              >
                서가에서 보기
              </button>
              <LoanCheck libCode={meta.libCode} isbn13List={[center.isbn]} />
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

/**
 * 글자를 적는 칸에서 난 키인지.
 *
 * <p>그 안의 화살표는 <b>글자 사이를 오가는 것</b>이지 책을 넘기라는 뜻이 아닙니다.
 * 이 화면 안에 「책 찾기」 칸이 있어서 실제로 겹칩니다.
 */
function typingIn(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}
