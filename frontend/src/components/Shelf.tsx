import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  SHELF_SHAPE_FINDABLE,
  fetchShelfChunk,
  fetchShelfMeta,
  fetchShelfSubjects,
  type ShelfBook,
  type ShelfMeta,
  type ShelfRoom,
  type ShelfSection,
  type ShelfSubject,
} from '../api';
import type { Library } from '../domain/types';
import {
  COLS,
  chunksFor,
  locate,
  rowAtTop,
  rowCount,
  scrollToRow,
  scrollToSection,
  visibleRows,
} from '../domain/shelfLayout';
import { sectionLabel } from '../domain/shelfSection';
import { ShelfCover } from './ShelfCover';
import { ShelfNearby } from './ShelfNearby';
import { ShelfOpening } from './ShelfOpening';
import { ShelfSearch } from './ShelfSearch';

/** 오른쪽 색인에 세우는 초성. 서버의 {@code Chosung.INDEX} 와 같은 열넷입니다. */
const CHOSUNG = ['ㄱ', 'ㄴ', 'ㄷ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];

/**
 * 표지 한 권이 차지하는 칸의 가로세로 비. 판형이 제각각이라 실제 비율은 표지마다
 * 다르지만 <b>칸은 같아야 줄이 맞습니다.</b> 표지는 이 칸 안에서 아래 선에 맞춰 서므로,
 * 낮은 책은 낮게 높은 책은 높게 서서 실제 서가처럼 보입니다.
 */
const COVER_RATIO = 1.45;

/** 선반 판과 그 아래 그림자가 차지하는 높이. CSS 의 `.shelf__plank` 와 같아야 합니다. */
const PLANK = 18;

/** 서가 안쪽 여백과 칸 사이. CSS 와 같은 값이어야 줄이 맞습니다. */
const SHELF_PAD = 16;
const GAP = 10;

/**
 * 도서관 장서를 <b>실제 서가처럼</b> 보여 주는 화면.
 *
 * <h2>검색이 없습니다</h2>
 *
 * <p>찾는 책이 있는 사람은 검색 탭으로 갑니다. 여기는 <b>무엇을 읽을지 아직 정하지
 * 않은 사람</b>이 서가 사이를 걷는 자리입니다.
 *
 * <p>머리말의 「책 찾기」는 그 규칙의 예외가 아닙니다. 돌려주는 것이 <b>이 서가의 자리
 * 번호</b>뿐이라 소장도 대출도 말하지 않고, 하는 일은 수천 권짜리 서가를 가로질러 그
 * 앞에 서는 것입니다. 자세한 것은 {@link ShelfSearch} 에 적어 두었습니다.
 *
 * <h2>서가는 열 때 세웁니다</h2>
 *
 * <p>전국 1,619곳을 미리 받으면 48만 회에 디스크 65GB 라 들어가지 않습니다. 그래서
 * <b>사람이 여는 서가만</b> 세우고, 한 번 세운 것은 그 뒤로 정보나루를 한 번도 부르지
 * 않습니다. 처음 여는 사람만 기다립니다.
 */
export function Shelf({
  libraries,
  selected,
}: {
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
}) {
  /*
    **고른 순서가 아니라 목록 순서로 셉니다.** `Set` 의 순서는 사용자가 체크한 차례라,
    시도 하나를 통째로 고르면 첫 곳이 도서관 목록의 첫 곳과 달라집니다.
  */
  const mine = libraries.filter((one) => selected.has(one.libCode));

  const [libCode, setLibCode] = useState<string | null>(null);
  const [subject, setSubject] = useState<ShelfSubject | null>(null);

  const current = libCode && selected.has(libCode) ? libCode : (mine[0]?.libCode ?? null);
  const library = libraries.find((one) => one.libCode === current);

  return (
    <div className="shelf-screen">
      {!current ? (
        <p className="shelf-empty muted">
          도서관을 고르면 그 도서관 서가를 둘러볼 수 있습니다. 왼쪽 「도서관 선택」에서
          자주 가는 곳을 먼저 골라 주세요.
        </p>
      ) : !subject ? (
        <SubjectPicker
          key={current}
          libCode={current}
          libraryName={library?.name ?? current}
          libraries={mine}
          onLibrary={setLibCode}
          onPick={setSubject}
        />
      ) : (
        <ShelfGate
          key={`${current}/${subject.code}`}
          libCode={current}
          subject={subject}
          library={library}
          libraryName={library?.name ?? current}
          onLeave={() => setSubject(null)}
        />
      )}
    </div>
  );
}

/**
 * 서가를 고르는 자리. <b>어느 것이 바로 열리는지 미리 말해 줍니다.</b>
 *
 * <p>세워 둔 적 없는 서가는 누르면 몇십 초를 기다려야 하는데, 그것을 누르고 나서야
 * 알게 되면 누른 것을 후회합니다. 표시가 있으면 기다릴지 말지를 누르기 전에 정합니다.
 */
function SubjectPicker({
  libCode,
  libraryName,
  libraries,
  onLibrary,
  onPick,
}: {
  libCode: string;
  libraryName: string;
  libraries: readonly Library[];
  onLibrary: (code: string) => void;
  onPick: (subject: ShelfSubject) => void;
}) {
  const [subjects, setSubjects] = useState<ShelfSubject[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchShelfSubjects(libCode).then(
      (found) => {
        if (cancelled) return;
        setSubjects(found.subjects);
        // `found.built` 은 받아 두되 화면에 쓰지 않습니다. 어느 갈래가 이미 세워져
        // 있는지는 진단에 쓰는 값이고, 고르는 사람이 할 수 있는 일이 아닙니다.
      },
      () => !cancelled && setFailed(true),
    );
    return () => {
      cancelled = true;
    };
  }, [libCode]);

  return (
    <div className="picker-shelf">
      <header className="picker-shelf__head">
        <h2 className="picker-shelf__title">서가 둘러보기</h2>
        {libraries.length > 1 ? (
          <select
            className="shelf-head__pick"
            aria-label="서가를 볼 도서관"
            value={libCode}
            onChange={(event) => onLibrary(event.target.value)}
          >
            {libraries.map((one) => (
              <option key={one.libCode} value={one.libCode}>
                {one.name}
              </option>
            ))}
          </select>
        ) : (
          <span className="shelf-head__lib">{libraryName}</span>
        )}
      </header>

      {failed && (
        <p className="banner banner--info">
          서가 목록을 불러오지 못했습니다. 검색은 평소대로 됩니다.
        </p>
      )}

      <ul className="subjects">
        {/*
          **어느 갈래가 바로 열리는지 표시하지 않습니다.** 예전에는 「바로 열림」과
          「처음 여는 서가」를 갈라 적었는데, 그것은 우리가 안쪽에서 어떻게 해 두었는지를
          말하는 것이지 **고르는 사람이 할 수 있는 일이 아닙니다.** 읽는 사람은 읽고 싶은
          갈래를 고를 뿐이고, 기다려야 하면 기다리는 화면이 그때 말해 줍니다.
        */}
        {subjects.map((one) => (
          <li key={one.code}>
            <button type="button" className="subject" onClick={() => onPick(one)}>
              <span className="subject__code">{one.code}00</span>
              <span className="subject__label">{one.label}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 세워져 있으면 서가를, 아니면 세우는 화면을 보여 줍니다. */
function ShelfGate({
  libCode,
  subject,
  library,
  libraryName,
  onLeave,
}: {
  libCode: string;
  subject: ShelfSubject;
  library: Library | undefined;
  libraryName: string;
  onLeave: () => void;
}) {
  const [meta, setMeta] = useState<ShelfMeta | 'opening' | 'failed'>('opening');

  const load = useCallback(() => {
    fetchShelfMeta(libCode, subject.code).then(
      (found) => setMeta(found),
      () => setMeta('failed'),
    );
  }, [libCode, subject.code]);

  if (meta === 'opening') {
    return (
      <ShelfOpening
        libCode={libCode}
        subject={subject}
        libraryName={libraryName}
        onReady={load}
        onGiveUp={onLeave}
      />
    );
  }

  if (meta === 'failed') {
    return (
      <div className="shelf-open">
        <p className="banner banner--info">그 서가를 불러오지 못했습니다.</p>
        <button type="button" className="chip" onClick={onLeave}>
          다른 서가 고르기
        </button>
      </div>
    );
  }

  return (
    <ShelfView
      meta={meta}
      subject={subject}
      library={library}
      libraryName={libraryName}
      onLeave={onLeave}
    />
  );
}

/** 세워진 서가 하나. */
function ShelfView({
  meta,
  subject,
  library,
  libraryName,
  onLeave,
}: {
  meta: ShelfMeta;
  subject: ShelfSubject;
  library: Library | undefined;
  libraryName: string;
  onLeave: () => void;
}) {
  const shelfBox = useRef<HTMLDivElement | null>(null);
  const headBox = useRef<HTMLElement | null>(null);
  const [roomSlug, setRoomSlug] = useState<string | null>(null);
  const [size, setSize] = useState({ rowHeight: 0, viewport: 0, top: 0, head: 0 });
  const [scrollTop, setScrollTop] = useState(0);
  const [chunks, setChunks] = useState<Map<string, ShelfBook[]>>(new Map());
  const [picked, setPicked] = useState<string | null>(null);
  /*
    **표지를 누르면 그 자리의 주변 서가를 봅니다.** 자리 번호로 기억합니다. 책으로
    기억하면 같은 책이 복본으로 두 자리에 있을 때 어느 자리에서 눌렀는지를 잃습니다.
  */
  const [nearby, setNearby] = useState<number | null>(null);
  /*
    **찾아간 자리를 표시해 둡니다.** 스무 권이 한 화면에 서 있어서, 그 자리로 옮겨만
    놓으면 <b>어느 것을 찾은 것인지 알 수 없습니다.</b> 표지에는 글자가 없어서 더
    그렇습니다. 자료실을 바꾸면 번호의 뜻이 달라지므로 그때 버립니다.
  */
  const [found, setFound] = useState<number | null>(null);
  /*
    **찾기를 펼쳤는지를 여기서 들고 있습니다.** 펼치면 머리말이 그만큼 높아지고,
    서가가 시작하는 자리도 함께 내려갑니다. 그 자리로 몇 번째 줄인지를 세므로 다시
    재지 않으면 줄이 어긋납니다. 접힘과 같은 이유라 같은 자리에서 함께 다시 잽니다.
  */
  const [finding, setFinding] = useState(false);

  /*
    **가장 큰 자료실을 먼저 엽니다.** 서가를 보러 온 사람이 보고 싶은 것은 대개
    종합자료실이고 그것이 거의 언제나 가장 큽니다. 이름으로 고르려 하면 도서관마다
    표기가 달라 규칙이 곧 틀립니다.
  */
  const room = useMemo<ShelfRoom | null>(() => {
    if (meta.rooms.length === 0) return null;
    return (
      meta.rooms.find((one) => one.slug === roomSlug) ??
      meta.rooms.reduce((big, one) => (one.count > big.count ? one : big))
    );
  }, [meta.rooms, roomSlug]);

  const rows = rowCount(room?.count ?? 0);

  /*
    **칸 크기와 서가가 시작하는 자리를 함께 잽니다.**

    칸 너비는 한 줄에 네 권이라 화면 너비를 따라가고 줄 높이는 거기서 나옵니다. 값을
    고정하면 좁은 화면에서 표지가 겹치거나 넓은 화면에서 우표만 해집니다.

    시작하는 자리가 필요한 이유는 <b>페이지 스크롤을 그대로 쓰기 때문</b>입니다. 서가에
    제 스크롤 칸을 주면 브라우저 스크롤바가 하나 더 생겨 따로 놉니다. 이 저장소가 예전에
    두 칸을 각자 스크롤하게 두었다가 같은 이유로 걷어냈습니다. 대신 「페이지를 얼마나
    내렸는가」에서 「서가가 어디서 시작하는가」를 빼야 몇 번째 줄인지 알 수 있습니다.

    머리말 높이를 함께 재는 것은 <b>머리말이 화면 위에 붙어 그만큼을 덮기 때문</b>입니다.
    빼지 않으면 초성이나 찾기로 간 책이 머리말 뒤에 반쯤 가린 채 섭니다. 실측으로 표지
    106px 가운데 42px 이 가려졌습니다.
  */
  const measure = useCallback(() => {
    const element = shelfBox.current;
    if (!element) return;
    const width = element.clientWidth;
    if (width <= 0) return;
    const inner = width - SHELF_PAD * 2 - GAP * (COLS - 1);
    const slot = Math.max(24, inner / COLS);
    const next = {
      rowHeight: Math.round(slot * COVER_RATIO) + PLANK,
      viewport: window.innerHeight,
      top: Math.round(element.getBoundingClientRect().top + window.scrollY),
      head: Math.round(headBox.current?.getBoundingClientRect().height ?? 0),
    };
    // 같은 값을 다시 넣지 않습니다. 넣으면 그릴 때마다 상태가 바뀌어 헛돕니다.
    setSize((before) =>
      before.rowHeight === next.rowHeight &&
      before.viewport === next.viewport &&
      before.top === next.top &&
      before.head === next.head
        ? before
        : next,
    );
  }, []);

  useLayoutEffect(() => {
    const element = shelfBox.current;
    if (!element) return;
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    window.addEventListener('resize', measure);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, [measure]);

  /*
    페이지를 얼마나 내렸는지 지켜봅니다. **그릴 때마다 자리를 다시 재지 않습니다.**
    스크롤은 한 번 밀 때 수십 번 일어나는데 그때마다 `getBoundingClientRect` 를 부르면
    브라우저가 배치를 다시 계산합니다.
  */
  useEffect(() => {
    const onScroll = () => setScrollTop(window.scrollY);
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const range = visibleRows(scrollTop - size.top, size.viewport, size.rowHeight, rows);

  /*
    보이는 줄에 필요한 조각을 받아 둡니다. **이미 받은 것은 다시 받지 않습니다.**
    스크롤은 한 번 밀 때 수십 번 일어나므로 그때마다 같은 조각을 부르면 그 일만
    하게 됩니다.
  */
  useEffect(() => {
    if (!room) return;
    const wanted = chunksFor(range, meta.chunkSize).filter(
      (at) => !chunks.has(keyOf(room.slug, at)),
    );
    if (wanted.length === 0) return;

    let cancelled = false;
    for (const at of wanted) {
      fetchShelfChunk(meta.libCode, meta.kdc, room.slug, at, meta.asOf).then(
        (books) => {
          if (cancelled) return;
          setChunks((before) => {
            const key = keyOf(room.slug, at);
            if (before.has(key)) return before;
            const after = new Map(before);
            after.set(key, books);
            return after;
          });
        },
        () => {
          /*
            **조각 하나를 못 받아도 서가는 그대로 둡니다.** 그 자리는 빈 칸으로 남고
            다시 스크롤하면 한 번 더 시도합니다. 서가 전체를 오류 화면으로 바꾸면
            멀쩡한 나머지까지 못 보게 됩니다.
          */
        },
      );
    }
    return () => {
      cancelled = true;
    };
  }, [range.from, range.to, meta, room, chunks]);

  const bookAt = useCallback(
    (index: number): ShelfBook | null => {
      if (!room) return null;
      const { chunk, at } = locate(index, meta.chunkSize);
      return chunks.get(keyOf(room.slug, chunk))?.[at] ?? null;
    },
    [chunks, meta.chunkSize, room],
  );

  /**
   * 그 자리가 있는 줄로 옮겨 갑니다. <b>초성 색인과 찾기가 같은 계산을 씁니다.</b>
   * 갈리면 한쪽만 머리말 뒤에 가려 서는데, 어느 쪽이 그런지는 눌러 본 사람만 압니다.
   */
  const scrollToIndex = useCallback(
    (index: number, snug = false) => {
      if (size.rowHeight <= 0) return;
      const row = Math.floor(index / COLS);
      window.scrollTo({
        // 서가가 시작하는 자리에서 그 줄만큼 더 내려가되, 머리말이 덮는 만큼은 뺍니다.
        // 갈래로 갈 때만 위를 남기지 않습니다. 남기면 화면 맨 위가 앞 갈래의 끝이 되어
        // 큰 제목과 갈래 목록이 방금 고른 것이 아니라 앞 갈래를 말합니다.
        top:
          size.top +
          (snug
            ? scrollToSection(row, size.rowHeight, size.head)
            : scrollToRow(row, size.rowHeight, size.viewport, size.head)),
        behavior: 'smooth',
      });
    },
    [size.head, size.rowHeight, size.top, size.viewport],
  );

  /** 초성을 누르면 그 자리로 갑니다. 없는 초성은 누를 수 없습니다. */
  const jump = useCallback(
    (chosung: string) => {
      const index = room?.chosungAt[chosung];
      if (index === undefined) return;
      setPicked((before) => (before === chosung ? null : chosung));
      scrollToIndex(index);
    },
    [room, scrollToIndex],
  );

  /**
   * 찾은 자리로 옮겨 갑니다. 초성 색인이 뛰는 것과 같은 계산이되, 초성은 줄을 알고
   * 여기는 자리를 압니다.
   */
  const goTo = useCallback(
    (at: number) => {
      setFound(at);
      // 초성으로 걸러 둔 것을 풉니다. 그러지 않으면 찾아간 책이 흐리게 그려집니다.
      setPicked(null);
      scrollToIndex(at);
    },
    [scrollToIndex],
  );

  /*
    **큰 제목은 보이는 줄을 말합니다.** 그려 둔 줄의 첫 줄(`range.from`)로 세면 보이는
    곳보다 몇 줄 위를 가리켜, 화면에 없는 갈래를 말하게 됩니다. 갈래 고르는 목록이 이
    값을 그대로 쓰므로 여기서 어긋나면 목록도 함께 어긋납니다.
  */
  const heading = useHeading(
    bookAt(rowAtTop(scrollTop - size.top + size.head, size.rowHeight, rows) * COLS),
    subject.label,
  );

  /*
    **접는 기준과 펴는 기준을 벌려 둡니다. 한 값으로 되돌리지 마세요.**

    머리말이 접히면 <b>문서가 그만큼 짧아집니다</b>(실측 144px → 57px, 87px). 그러면
    브라우저가 보던 자리를 지키려고 스크롤 위치를 그만큼 되돌립니다(scroll anchoring).
    기준이 하나면 그 순간 기준 아래로 내려가 다시 펴지고, 펴지면 문서가 길어지며 스크롤이
    다시 밀려 올라가 또 접힙니다. <b>손을 떼도 혼자 펴졌다 접혔다 합니다.</b> 실측으로
    740px 로 보낸 스크롤이 734px 에, 760px 이 736px 에, 780px 이 742px 에 멈췄습니다.

    벌린 폭(130px)이 줄어드는 높이(87px)보다 커야 합니다. 접힌 뒤 스크롤이 되돌려져도
    펴는 기준 위에 남고, 펴진 뒤 밀려 올라가도 접는 기준 아래에 남습니다.
  */
  const collapsed = useCollapsed(scrollTop - size.top);

  /*
    **접힘이 바뀌면 자리를 다시 잽니다.** 서가가 시작하는 자리가 87px 올라오는데,
    그 값으로 몇 번째 줄인지를 셉니다. 다시 재지 않으면 접힌 동안 줄이 한 줄 가까이
    어긋나, 스크롤하다 책이 건너뛰거나 겹쳐 보입니다.
  */
  useLayoutEffect(() => {
    measure();
  }, [collapsed, finding, measure]);

  if (!room) {
    return (
      <div className="shelf-open">
        <p className="banner banner--info">
          이 서가에는 청구기호가 있는 책이 없어 세울 수 없었습니다.
        </p>
        <button type="button" className="chip" onClick={onLeave}>
          다른 서가 고르기
        </button>
      </div>
    );
  }

  return (
    <>
      <header
        className="shelf-head"
        ref={headBox}
        data-collapsed={collapsed ? '' : undefined}
      >
        <div className="shelf-head__bar">
          <button type="button" className="chip chip--sm" onClick={onLeave}>
            서가 바꾸기
          </button>
          {/*
            **찾기 색인이 없는 서가에는 단추를 내지 않습니다.** 색인은 세울 때 함께
            적으므로 색인이 생기기 전에 세운 서가에는 없는데, 눌러 봐야 실패하는 단추를
            두면 사람은 자기가 뭘 잘못했나 싶어집니다. 서버가 그런 서가를 낡은 것으로
            보고 뒤에서 다시 세우므로 다음에 열면 단추가 있습니다.
          */}
          {(meta.shapeVersion ?? 0) >= SHELF_SHAPE_FINDABLE && (
            <ShelfSearch
              meta={meta}
              room={room}
              open={finding}
              onOpen={setFinding}
              onGo={goTo}
            />
          )}
          {/* 접혔을 때만 보이는 작은 제목. 큰 제목이 사라진 자리를 대신합니다. */}
          <span className="shelf-head__small">{heading}</span>
        </div>

        <div className="shelf-head__big">
          {/*
            **한 겹을 더 두는 것이 꼭 필요합니다.** 접는 방법이 `grid-template-rows` 를
            `1fr` 에서 `0fr` 로 옮기는 것인데, 그 규칙은 <b>줄 하나</b>를 정의합니다.
            자식이 둘이면 둘째가 암묵적 줄로 밀려나 높이가 `auto` 로 남고, 그러면
            <b>글자만 투명해지고 자리는 그대로 남습니다.</b> 실제로 접었는데도 머리말이
            117px 이었고, 서가 위에 빈 띠가 생겼습니다.
          */}
          <div className="shelf-head__inner">
            <h1 className="shelf-head__title">{heading}</h1>
            <p className="shelf-head__meta">
              <span className="shelf-head__lib">{libraryName}</span>
              <RoomPick
                rooms={meta.rooms}
                current={room}
                onPick={(slug) => {
                  // 자리 번호는 자료실 안에서만 뜻이 있습니다. 그대로 두면 다른
                  // 자료실의 엉뚱한 자리가 표시됩니다.
                  setFound(null);
                  setRoomSlug(slug);
                }}
              />
              <SectionPick
                sections={room.sections}
                here={heading}
                onGo={(at) => scrollToIndex(at, true)}
              />
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
          </div>
        </div>

        {picked && (
          <p className="shelf-head__filter">
            {/*
              **자모 한 글자는 알약에 담습니다.** 「ㅁ」은 글자 자체가 네모라, 문장
              가운데 홀로 두면 <b>글꼴이 없어 나온 네모</b>로 읽힙니다. 굵게 해도
              마찬가지입니다. 알약에 담으면 「우리가 고른 글자」라는 것이 모양으로
              드러납니다. 「ㅇ」과 「ㅁ」처럼 도형에 가까운 자모가 여럿입니다.
            */}
            <span>
              <span className="shelf-head__jamo">{picked}</span> 으로 시작하는 저자만 밝게
              보입니다
            </span>
            <button type="button" className="chip chip--sm" onClick={() => setPicked(null)}>
              해제
            </button>
          </p>
        )}
      </header>

      <div className="shelf-scroll">
        <div className="shelf" ref={shelfBox} style={{ height: rows * size.rowHeight }}>
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
                      const book = index < room.count ? bookAt(index) : null;
                      if (!book) return <span className="shelf__gap" key={column} />;
                      return (
                        <ShelfCover
                          key={book.isbn + index}
                          book={book}
                          dimmed={picked !== null && book.chosung !== picked}
                          lifted={
                            index === found || (picked !== null && book.chosung === picked)
                          }
                          found={index === found}
                          onPick={() => setNearby(index)}
                        />
                      );
                    })}
                  </div>
                  <div className="shelf__plank" aria-hidden="true" />
                </div>
              );
            })}
        </div>
      </div>

      {nearby !== null && (
        <ShelfNearby
          meta={meta}
          room={room}
          index={nearby}
          library={library}
          onClose={() => setNearby(null)}
          onMove={setNearby}
        />
      )}

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

/**
 * 큰 제목. <b>받지 못한 사이에 비우지 않습니다.</b>
 *
 * <p>스크롤하면 그 줄의 조각을 아직 못 받은 순간이 있는데, 그때 제목을 비우면 스크롤
 * 하는 내내 제목이 깜빡입니다. 새 값이 올 때까지 이전 값을 그대로 둡니다.
 */
const COLLAPSE_AT = 150;
const EXPAND_AT = 20;

/**
 * 머리말을 접을지. <b>접는 자리와 펴는 자리가 다릅니다.</b>
 *
 * <p>접으면 문서가 87px 짧아지고 브라우저가 스크롤을 그만큼 되돌리므로, 기준이 하나면
 * 그 자리에서 접힘과 펴짐이 번갈아 일어납니다. 벌린 폭이 줄어드는 높이보다 크면 어느
 * 쪽으로 넘어가도 되돌려진 자리가 반대쪽 기준을 넘지 못합니다.
 */
function useCollapsed(past: number): boolean {
  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => {
    setCollapsed((before) => (before ? past > EXPAND_AT : past > COLLAPSE_AT));
  }, [past]);
  return collapsed;
}

function useHeading(book: ShelfBook | null, fallback: string): string {
  const [heading, setHeading] = useState(fallback);
  useEffect(() => {
    if (book?.classNm) setHeading(book.classNm);
  }, [book?.classNm]);
  return heading;
}

/**
 * 갈래 고르기. <b>거르는 것이 아니라 걸어가는 것입니다.</b>
 *
 * <p>서가가 청구기호 순이고 분류번호가 그 앞자리를 정하므로, 같은 갈래의 책은 서가에서
 * 한 덩어리로 붙어 있습니다. 그래서 갈래를 고르는 것이 곧 그 구역 앞에 서는 일이 됩니다.
 * 초성 색인이 도서기호 첫 글자로 데려다주는 것과 같은 장치이고, 값이 이미 조각에 실려
 * 있어 <b>정보나루 호출이 한 건도 늘지 않습니다.</b>
 *
 * <p><b>지금 서 있는 갈래를 그대로 보여 줍니다.</b> 고르고 나면 값이 남는 목록은 스크롤해
 * 다른 데로 간 뒤에도 예전 것을 말하게 되는데, 서가의 큰 제목이 이미 「지금 어느 갈래
 * 앞인가」를 말하고 있으므로 그 값을 그대로 씁니다. 그러면 목록이 한 번도 거짓말을 하지
 * 않고, 고른 뒤 비워 두는 어색함도 없습니다.
 *
 * <p><b>칩으로 늘어놓지 않습니다.</b> 실측으로 한 자료실에 갈래가 스물여덟 가지였습니다.
 * 칩이면 좁은 화면에서 서너 줄을 차지해 머리말이 그만큼 두꺼워지고, 그 높이는 서가가
 * 시작하는 자리를 밀어냅니다. 목록은 높이를 쓰지 않으면서 권수까지 함께 보여 줍니다.
 */
function SectionPick({
  sections,
  here,
  onGo,
}: {
  sections: ShelfSection[] | undefined;
  /** 지금 화면 맨 위에 선 책의 분류명. 서가의 큰 제목과 같은 값입니다. */
  here: string;
  onGo: (at: number) => void;
}) {
  // 갈래가 하나뿐이면 고를 것이 없습니다. 갈래 구간이 생기기 전에 세운 서가도 여깁니다.
  if (!sections || sections.length <= 1) return null;

  const standing = sections.find((one) => one.name === here);
  return (
    <select
      className="shelf-head__pick shelf-head__pick--section"
      aria-label="갈래"
      value={standing?.name ?? ''}
      onChange={(event) => {
        const picked = sections.find((one) => one.name === event.target.value);
        if (picked) onGo(picked.at);
      }}
    >
      {/*
        아직 어느 갈래에 선 것인지 모르는 순간이 있습니다. 조각을 받기 전이 그렇습니다.
        그때 첫 갈래가 골라진 것처럼 보이면 안 되므로 빈 자리를 둡니다.
      */}
      {!standing && <option value="">갈래</option>}
      {sections.map((one) => (
        <option key={one.name + one.at} value={one.name}>
          {`${sectionLabel(one.name)} (${one.count.toLocaleString('ko-KR')}권)`}
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
          {`${one.name || one.code || one.slug} (${one.count.toLocaleString('ko-KR')}권)`}
        </option>
      ))}
    </select>
  );
}

/** 자료실을 바꾸면 조각도 갈립니다. 열쇠에 자료실을 넣지 않으면 남의 책이 섞입니다. */
function keyOf(roomSlug: string, chunk: number): string {
  return `${roomSlug}/${chunk}`;
}

/** 「9월 20일」. 서버가 주는 것은 ISO 날짜입니다. */
function formatDay(iso: string): string {
  const parts = iso.split('-');
  if (parts.length !== 3) return iso;
  return `${Number(parts[1])}월 ${Number(parts[2])}일`;
}
