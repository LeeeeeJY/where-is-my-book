import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  fetchShelfChunk,
  fetchShelfLibraries,
  fetchShelfMeta,
  type ShelfBook,
  type ShelfMeta,
  type ShelfRoom,
} from '../api';
import type { Library } from '../domain/types';
import {
  COLS,
  chunksFor,
  locate,
  rowCount,
  scrollToRow,
  visibleRows,
} from '../domain/shelfLayout';
import { ShelfCover } from './ShelfCover';

/** 오른쪽 색인에 세우는 초성. 서버의 {@code Chosung.INDEX} 와 같은 열넷입니다. */
const CHOSUNG = ['ㄱ', 'ㄴ', 'ㄷ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];

/**
 * 표지 한 권이 차지하는 칸의 가로세로 비. 판형이 제각각이라 실제 비율은 표지마다
 * 다르지만, <b>칸은 같아야 줄이 맞습니다.</b> 표지는 이 칸 안에서 아래 선에 맞춰
 * 서므로, 낮은 책은 낮게 높은 책은 높게 서서 실제 서가처럼 보입니다.
 */
const COVER_RATIO = 1.45;

/** 선반 판과 그 아래 그림자가 차지하는 높이. */
const PLANK = 16;

/**
 * 도서관 장서를 <b>실제 서가처럼</b> 보여 주는 화면.
 *
 * <h2>검색이 없습니다</h2>
 *
 * <p>찾는 책이 있는 사람은 검색 탭으로 갑니다. 여기는 <b>무엇을 읽을지 아직 정하지
 * 않은 사람</b>이 서가 사이를 걷는 자리입니다. 검색 칸을 두면 두 가지를 한 화면에서
 * 하게 되고, 그러면 어느 쪽도 제대로 되지 않습니다.
 *
 * <h2>정보나루를 부르지 않습니다</h2>
 *
 * <p>서버가 미리 받아 적어 둔 파일만 읽습니다. 몇 번을 열어도 하루 호출 예산이 줄지
 * 않고, 정보나루가 멈춰 있어도 서가는 평소대로 열립니다.
 *
 * <h2>보이는 줄만 그립니다</h2>
 *
 * <p>도서관 한 곳이 20만 권입니다. 한 줄에 네 권이면 5만 줄이고 그 전부를 DOM 에
 * 올리면 브라우저가 멈춥니다. 자리는 전체 높이로 잡아 두고 <b>보이는 줄과 그 위아래
 * 몇 줄만</b> 그립니다. 세는 일은 {@code domain/shelfLayout.ts} 가 합니다.
 */
export function Shelf({
  libraries,
  selected,
  tabs,
}: {
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
  /** 화면 아래에 띄울 탭. 서가가 화면을 통째로 쓰므로 위의 탭이 가려집니다. */
  tabs: React.ReactNode;
}) {
  const [available, setAvailable] = useState<string[] | 'loading' | 'failed'>('loading');
  const [libCode, setLibCode] = useState<string | null>(null);
  const [meta, setMeta] = useState<ShelfMeta | 'loading' | 'failed' | null>(null);
  const [roomSlug, setRoomSlug] = useState<string | null>(null);
  /*
    **탭 바는 내려갈 때 숨고 올라올 때 돌아옵니다.** 서가는 손가락으로 계속 미는
    화면이라 아래 40px 이 늘 가려져 있으면 그만큼 책이 덜 보입니다. 그렇다고 아주
    없애면 다른 탭으로 갈 길이 사라집니다. 읽는 동안 물러나고 되돌아보려 할 때
    나타나는 것이 두 가지를 다 지킵니다.
  */
  const [tabsHidden, setTabsHidden] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchShelfLibraries().then(
      (codes) => !cancelled && setAvailable(codes),
      () => !cancelled && setAvailable('failed'),
    );
    return () => {
      cancelled = true;
    };
  }, []);

  /*
    고른 도서관 가운데 서가가 있는 곳을 먼저 보여 줍니다. 하나도 없으면 서가가 있는
    곳 전부를 보여 주되, **고른 곳이 아니라는 사실을 화면이 밝힙니다.** 조용히 다른
    도서관의 서가를 보여 주면 자기가 고른 곳의 장서라고 읽습니다.
  */
  const ready = Array.isArray(available) ? available : [];
  const mine = ready.filter((code) => selected.has(code));
  const offered = mine.length > 0 ? mine : ready;
  const current = libCode && offered.includes(libCode) ? libCode : (offered[0] ?? null);

  useEffect(() => {
    if (!current) return;
    let cancelled = false;
    setMeta('loading');
    setRoomSlug(null);
    fetchShelfMeta(current).then(
      (received) => !cancelled && setMeta(received),
      () => !cancelled && setMeta('failed'),
    );
    return () => {
      cancelled = true;
    };
  }, [current]);

  const shelf = meta && typeof meta !== 'string' ? meta : null;
  /*
    **가장 큰 자료실을 먼저 엽니다.** 서가를 보러 온 사람이 보고 싶은 것은 대개
    종합자료실이고, 그것이 거의 언제나 가장 큽니다. 자료실 이름으로 고르려 하면
    도서관마다 표기가 달라 규칙이 곧 틀립니다.
  */
  const room = useMemo(() => {
    if (!shelf || shelf.rooms.length === 0) return null;
    const picked = shelf.rooms.find((one) => one.slug === roomSlug);
    if (picked) return picked;
    return shelf.rooms.reduce((big, one) => (one.count > big.count ? one : big));
  }, [shelf, roomSlug]);

  const library = libraries.find((one) => one.libCode === current);

  return (
    <div className="shelf-screen">
      {available === 'loading' && <p className="shelf-empty muted">서가를 여는 중입니다.</p>}

      {available === 'failed' && (
        <p className="shelf-empty">
          <span className="banner banner--info">
            서가를 불러오지 못했습니다. 검색은 평소대로 됩니다.
          </span>
        </p>
      )}

      {Array.isArray(available) && offered.length === 0 && (
        <p className="shelf-empty muted">
          아직 서가를 만들어 둔 도서관이 없습니다. 장서 전체를 미리 받아 두어야 하는
          화면이라, 준비된 도서관에서만 열립니다.
        </p>
      )}

      {shelf && room && current && (
        <ShelfView
          key={`${current}/${room.slug}`}
          meta={shelf}
          room={room}
          libraryName={library?.name ?? current}
          rooms={shelf.rooms}
          onRoom={setRoomSlug}
          libCodes={offered}
          libraries={libraries}
          onLibrary={setLibCode}
          borrowed={mine.length === 0}
          onHideTabs={setTabsHidden}
        />
      )}

      {meta === 'loading' && current && <p className="shelf-empty muted">서가를 여는 중입니다.</p>}
      {meta === 'failed' && current && (
        <p className="shelf-empty">
          <span className="banner banner--info">그 도서관의 서가를 불러오지 못했습니다.</span>
        </p>
      )}

      <nav className="tabbar" data-hidden={tabsHidden ? '' : undefined}>
        {tabs}
      </nav>
    </div>
  );
}

/** 서가 하나. 도서관과 자료실이 바뀌면 통째로 다시 만듭니다(`key`). */
function ShelfView({
  meta,
  room,
  rooms,
  libraryName,
  libCodes,
  libraries,
  onLibrary,
  onRoom,
  borrowed,
  onHideTabs,
}: {
  meta: ShelfMeta;
  room: ShelfRoom;
  rooms: ShelfRoom[];
  libraryName: string;
  libCodes: string[];
  libraries: readonly Library[];
  onLibrary: (code: string) => void;
  onRoom: (slug: string) => void;
  /** 고른 도서관에 서가가 없어 다른 곳을 보여 주고 있는지. */
  borrowed: boolean;
  onHideTabs: (hidden: boolean) => void;
}) {
  const scroller = useRef<HTMLDivElement | null>(null);
  const [size, setSize] = useState({ rowHeight: 0, viewport: 0 });
  const [scrollTop, setScrollTop] = useState(0);
  const [chunks, setChunks] = useState<Map<number, ShelfBook[]>>(new Map());
  const [picked, setPicked] = useState<string | null>(null);

  const rows = rowCount(room.count);

  /*
    **칸 크기를 화면에서 잽니다.** 서가는 한 줄에 네 권이라 칸 너비가 화면 너비를
    따라가고, 줄 높이는 거기서 나옵니다. 값을 고정하면 좁은 화면에서 표지가 겹치거나
    넓은 화면에서 우표만 해집니다.
  */
  useLayoutEffect(() => {
    const element = scroller.current;
    if (!element) return;

    const measure = () => {
      const width = element.clientWidth;
      if (width <= 0) return;
      // 서가 안쪽 여백과 칸 사이를 뺀 나머지를 넷으로 나눕니다.
      const inner = width - SHELF_PAD * 2 - GAP * (COLS - 1);
      const slot = Math.max(24, inner / COLS);
      setSize({ rowHeight: Math.round(slot * COVER_RATIO) + PLANK, viewport: element.clientHeight });
    };

    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const range = visibleRows(scrollTop, size.viewport, size.rowHeight, rows);

  /*
    보이는 줄에 필요한 조각을 받아 둡니다. **이미 받은 것은 다시 받지 않습니다.**
    스크롤은 한 번 밀 때 수십 번 일어나므로, 그때마다 같은 조각을 부르면 서버도
    브라우저도 그 일만 하게 됩니다.
  */
  useEffect(() => {
    const wanted = chunksFor(range, meta.chunkSize).filter((at) => !chunks.has(at));
    if (wanted.length === 0) return;

    let cancelled = false;
    for (const at of wanted) {
      fetchShelfChunk(meta.libCode, room.slug, at, meta.asOf).then(
        (books) => {
          if (cancelled) return;
          setChunks((before) => {
            if (before.has(at)) return before;
            const after = new Map(before);
            after.set(at, books);
            return after;
          });
        },
        () => {
          /*
            **조각 하나를 못 받아도 서가는 그대로 둡니다.** 그 자리는 빈 칸으로
            남고 다시 스크롤하면 한 번 더 시도합니다. 서가 전체를 오류 화면으로
            바꾸면 멀쩡한 나머지까지 못 보게 됩니다.
          */
        },
      );
    }
    return () => {
      cancelled = true;
    };
  }, [range.from, range.to, meta, room.slug, chunks]);

  const bookAt = useCallback(
    (index: number): ShelfBook | null => {
      const { chunk, at } = locate(index, meta.chunkSize);
      return chunks.get(chunk)?.[at] ?? null;
    },
    [chunks, meta.chunkSize],
  );

  /*
    스크롤한 자리와 **방향**을 함께 봅니다. 방향은 탭 바를 숨길지 정하는 데만 쓰고
    그리는 데는 쓰지 않습니다.

    **작은 움직임은 세지 않습니다.** 손가락이 닿기만 해도 몇 px 은 움직이는데, 그때마다
    탭 바가 오르내리면 화면이 떱니다. 그리고 맨 위 가까이에서는 늘 보여 줍니다. 서가에
    막 들어온 사람에게 나갈 길이 감춰져 있으면 안 됩니다.
  */
  const lastTop = useRef(0);
  const onScroll = useCallback(() => {
    const element = scroller.current;
    if (!element) return;
    const top = element.scrollTop;
    setScrollTop(top);

    const moved = top - lastTop.current;
    if (Math.abs(moved) < 8) return;
    lastTop.current = top;
    onHideTabs(top > 80 && moved > 0);
  }, [onHideTabs]);

  /** 초성을 누르면 그 자리로 갑니다. 없는 초성은 누를 수 없습니다. */
  const jump = useCallback(
    (chosung: string) => {
      const index = room.chosungAt[chosung];
      if (index === undefined || !scroller.current || size.rowHeight <= 0) return;
      setPicked((before) => (before === chosung ? null : chosung));
      scroller.current.scrollTo({
        top: scrollToRow(Math.floor(index / COLS), size.rowHeight, size.viewport),
        behavior: 'smooth',
      });
    },
    [room.chosungAt, size.rowHeight, size.viewport],
  );

  /*
    **큰 제목은 지금 보이는 책의 분류명입니다.** 스크롤하면서 바뀌어 「지금 어느 갈래
    앞에 서 있는가」를 말합니다. 아직 그 조각을 받지 못했으면 이전 값을 그대로 둡니다.
    받을 때마다 제목이 비었다가 돌아오면 깜빡이는 것으로 보입니다.
  */
  const heading = useHeading(bookAt(range.from * COLS));

  const collapsed = scrollTop > 40;

  return (
    <>
      <header className="shelf-head" data-collapsed={collapsed ? '' : undefined}>
        <div className="shelf-head__bar">
          <LibraryPick
            libCodes={libCodes}
            libraries={libraries}
            current={meta.libCode}
            currentName={libraryName}
            onPick={onLibrary}
          />
          {/* 접혔을 때만 보이는 작은 제목. 큰 제목이 사라진 자리를 대신합니다. */}
          <span className="shelf-head__small">{heading}</span>
        </div>

        <div className="shelf-head__big">
          <h1 className="shelf-head__title">{heading}</h1>
          <p className="shelf-head__meta">
            <RoomPick rooms={rooms} current={room} onPick={onRoom} />
            {room.firstCall && room.lastCall && (
              <span className="shelf-head__range">
                {room.firstCall} – {room.lastCall}
              </span>
            )}
            {/*
              **기준일을 값에서 떼지 마세요.** 이 서가는 실시간이 아니라 그날 받아 둔
              것입니다. 날짜가 없으면 사람은 그것을 지금 상태로 읽고, 그 뒤에 들어온
              새 책이 없는 것을 고장으로 여깁니다.
            */}
            <span className="shelf-head__asof">
              장서 기준 {formatDay(meta.asOf)} · {room.count.toLocaleString('ko-KR')}권
            </span>
          </p>
          {borrowed && (
            <p className="shelf-head__note muted">
              고른 도서관에는 아직 서가가 없어 다른 도서관의 서가를 보여 드립니다.
            </p>
          )}
        </div>

        {picked && (
          <p className="shelf-head__filter">
            <span>
              <b>{picked}</b> 으로 시작하는 저자만 밝게 보입니다
            </span>
            <button type="button" className="chip chip--sm" onClick={() => setPicked(null)}>
              해제
            </button>
          </p>
        )}
      </header>

      <div className="shelf-scroll" ref={scroller} onScroll={onScroll}>
        <div className="shelf" style={{ height: rows * size.rowHeight }}>
          {size.rowHeight > 0 &&
            Array.from({ length: range.to - range.from }, (_, offset) => {
              const row = range.from + offset;
              return (
                <div
                  className="shelf__row"
                  key={row}
                  style={{ top: row * size.rowHeight, height: size.rowHeight }}
                >
                  <div className="shelf__books">
                    {Array.from({ length: COLS }, (_, column) => {
                      const index = row * COLS + column;
                      if (index >= room.count) return <span className="shelf__gap" key={column} />;
                      const book = bookAt(index);
                      if (!book) return <span className="shelf__gap" key={column} />;
                      return (
                        <ShelfCover
                          key={book.isbn + index}
                          book={book}
                          dimmed={picked !== null && book.chosung !== picked}
                          lifted={picked !== null && book.chosung === picked}
                          onPick={() => {
                            /* 7단계에서 「이 책 주변 서가 보기」로 이어집니다. */
                          }}
                        />
                      );
                    })}
                  </div>
                  <div className="shelf__plank" aria-hidden="true" />
                </div>
              );
            })}
        </div>

        {/*
          출처는 이용 조건상의 의무이고 신뢰 표시이기도 합니다. 서가가 화면을 통째로
          쓰므로 아래 푸터가 가려집니다. 여기 한 줄을 둡니다.
        */}
        <p className="shelf-source muted">
          출처:{' '}
          <a href="https://www.data4library.kr" target="_blank" rel="noreferrer noopener">
            도서관 정보나루
          </a>{' '}
          (국립중앙도서관)
        </p>
      </div>

      <nav className="rail" aria-label="저자 초성으로 찾기">
        {CHOSUNG.map((one) => {
          const here = room.chosungAt[one] !== undefined;
          return (
            <button
              key={one}
              type="button"
              className="rail__key"
              data-off={here ? undefined : ''}
              data-on={picked === one ? '' : undefined}
              disabled={!here}
              aria-pressed={picked === one}
              aria-label={here ? `${one} 으로 시작하는 저자` : `${one} 으로 시작하는 저자 없음`}
              onClick={() => jump(one)}
            >
              {one}
            </button>
          );
        })}
      </nav>
    </>
  );
}

/** 서가 안쪽 여백과 칸 사이. CSS 와 같은 값이어야 줄이 맞습니다. */
const SHELF_PAD = 14;
const GAP = 10;

/**
 * 큰 제목. <b>받지 못한 사이에 비우지 않습니다.</b>
 *
 * <p>스크롤하면 그 줄의 조각을 아직 못 받은 순간이 있는데, 그때 제목을 비우면 스크롤
 * 하는 내내 제목이 깜빡입니다. 새 값이 올 때까지 이전 값을 그대로 둡니다.
 */
function useHeading(book: ShelfBook | null): string {
  const [heading, setHeading] = useState('서가');
  useEffect(() => {
    if (book?.classNm) setHeading(book.classNm);
  }, [book?.classNm]);
  return heading;
}

/** 도서관 고르기. 한 곳뿐이면 고를 것이 없으므로 이름만 적습니다. */
function LibraryPick({
  libCodes,
  libraries,
  current,
  currentName,
  onPick,
}: {
  libCodes: string[];
  libraries: readonly Library[];
  current: string;
  currentName: string;
  onPick: (code: string) => void;
}) {
  if (libCodes.length <= 1) return <span className="shelf-head__lib">{currentName}</span>;
  return (
    <select
      className="shelf-head__pick"
      aria-label="서가를 볼 도서관"
      value={current}
      onChange={(event) => onPick(event.target.value)}
    >
      {libCodes.map((code) => (
        <option key={code} value={code}>
          {libraries.find((one) => one.libCode === code)?.name ?? code}
        </option>
      ))}
    </select>
  );
}

/** 자료실 고르기. 층이 다르면 아예 다른 서가라 이어 붙이지 않고 갈라 둡니다. */
function RoomPick({
  rooms,
  current,
  onPick,
}: {
  rooms: ShelfRoom[];
  current: ShelfRoom;
  onPick: (slug: string) => void;
}) {
  const label = current.name || current.code || '자료실';
  if (rooms.length <= 1) return <span className="shelf-head__room">{label}</span>;
  return (
    <select
      className="shelf-head__pick shelf-head__pick--room"
      aria-label="자료실"
      value={current.slug}
      onChange={(event) => onPick(event.target.value)}
    >
      {rooms.map((one) => (
        <option key={one.slug} value={one.slug}>
          {(one.name || one.code || one.slug) + ` (${one.count.toLocaleString('ko-KR')}권)`}
        </option>
      ))}
    </select>
  );
}

/** 「9월 20일」. 서버가 주는 것은 ISO 날짜입니다. */
function formatDay(iso: string): string {
  const parts = iso.split('-');
  if (parts.length !== 3) return iso;
  return `${Number(parts[1])}월 ${Number(parts[2])}일`;
}
