import { useCallback, useEffect, useState } from 'react';
import {
  ApiUnavailable,
  fetchBrowse,
  fetchHoldings,
  libraryLink,
  searchBooks,
} from '../api';
import type { BrowsePopularBook, BrowseResponse, BrowseStory } from '../api';
import type { HoldingsResponse } from '../api';
import type { Library } from '../domain/types';
import { holdingState } from '../domain/holdingState';
import { linkBadge, linkLabel } from '../domain/opacLink';
import { LoanCheck } from './LoanCheck';

/**
 * 검색어를 넣기 전의 빈 화면을 채웁니다. **읽을 책을 아직 정하지 않은 사람의 입구입니다.**
 *
 * <p>지금까지 이 도구는 「찾을 책을 이미 정한 사람」만 쓸 수 있었습니다. 도서관을 골라
 * 두어도 검색 칸 아래가 텅 비어 있었기 때문입니다.
 *
 * <h2>목록에 소장을 미리 붙이지 않습니다</h2>
 *
 * <p>스무 권 × 고른 도서관이 걸친 시도 수만큼 호출이 나갑니다. 「대출 상태를 목록에 미리
 * 달지 마세요」와 같은 규칙이고, 여기서도 <b>줄을 눌렀을 때만</b> 물어봅니다.
 *
 * <p>그래서 목록 아래에 「누르기 전에는 아직 물어보지 않았다」를 적습니다.
 * <b>표시가 없는 줄을 「없다」로 읽으면 안 됩니다.</b>
 *
 * <h2>줄을 눌러도 화면을 갈아 끼우지 않습니다</h2>
 *
 * <p>목록을 훑다가 한 권 확인하자고 읽던 자리를 잃으면 안 되므로 그 자리에서 펼칩니다.
 * {@code styles.css} 의 {@code .rank__head} 가 「도서관 줄의 머리 전체가 단추입니다」라고
 * 적어 둔 것과 같은 방식입니다.
 */
export function Browse({
  libraries,
  selected,
}: {
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
}) {
  /*
    **고른 순서가 아니라 목록 순서로 셉니다.** `Set` 의 순서는 사용자가 체크한 차례라,
    시도 하나를 통째로 고르면 첫 곳이 도서관 목록의 첫 곳과 달라집니다. 실제로 경기도를
    한 번에 골랐더니 기본값이 「분당도서관」이었습니다. 목록은 시도 → 이름 순으로 정해져
    있으므로 그것을 그대로 따르면 같은 선택에서 늘 같은 곳이 먼저입니다.
  */
  const codes = libraries.filter((library) => selected.has(library.libCode))
    .map((library) => library.libCode);
  const [libCode, setLibCode] = useState<string | null>(null);
  const [data, setData] = useState<BrowseResponse | 'loading' | 'failed'>('loading');
  const [group, setGroup] = useState<string | null>(null);

  // 고른 도서관이 바뀌면 보고 있던 도서관이 목록에서 사라질 수 있습니다.
  const current = libCode && selected.has(libCode) ? libCode : (codes[0] ?? null);

  useEffect(() => {
    if (!current) return;
    let cancelled = false;
    setData('loading');
    setGroup(null);
    fetchBrowse(current).then(
      (response) => {
        if (!cancelled) setData(response);
      },
      () => {
        // **둘러보기는 곁들이 화면입니다.** 이것 때문에 검색까지 막히면 안 되므로
        // 조용히 물러납니다. 화면에는 안내 한 줄만 남습니다.
        if (!cancelled) setData('failed');
      },
    );
    return () => {
      cancelled = true;
    };
  }, [current]);

  /*
    **고른 도서관이 없으면 아무것도 그리지 않습니다.** 여기에 「도서관을 고르면 보여
    드립니다」를 두면 바로 위의 「도서관 선택에서 자주 가는 곳을 먼저 골라 주세요」와
    같은 말이 두 번 나옵니다. 실제로 그 상태에서 같은 뜻의 문단이 셋이었습니다.
    화면이 자기가 한 일을 전부 설명하면 정작 사용자가 할 일이 묻힙니다.
  */
  if (!current) return null;

  const currentLibrary = libraries.find((library) => library.libCode === current);
  const response = typeof data === 'string' ? null : data;
  const groups = response?.popular ?? [];
  const shown = groups.find((row) => row.key === group) ?? groups[0];

  return (
    <section className="browse">
      {/*
        **검색 카드와 다른 카드이고, 둘러볼 도서관은 제목 옆에서 정합니다.** 예전에는 검색
        입력 칸 바로 아래 같은 카드 안에 「둘러볼 도서관」 선택 칸이 있었는데, 그 칸이 검색
        입력 칸과 같은 모양이라 다섯째 검색 조건처럼 읽혔습니다. 실제로 헷갈린다는 말을
        들었습니다. 카드를 나누고 선택은 칩 모양의 알약으로 바꿔, 여기가 「검색」이 아니라
        「구경」이라는 것을 모양으로 말합니다.

        **맨 위 한 곳에서만 정합니다.** 목록 머리마다 따로 두면 위의 「오늘의 이야기」와
        아래 목록의 범위가 서로 달라 보입니다.
      */}
      <header className="browse__head">
        <h2>둘러보기</h2>
        {codes.length > 1 ? (
          <select
            className="browse__pick"
            aria-label="둘러볼 도서관"
            title="둘러볼 도서관"
            value={current}
            onChange={(event) => setLibCode(event.target.value)}
          >
            {codes.map((code) => (
              <option key={code} value={code}>
                {libraries.find((library) => library.libCode === code)?.name ?? code}
              </option>
            ))}
          </select>
        ) : (
          /* 고를 것이 없으면 이름만 적습니다. 누를 것처럼 생긴 것은 눌려야 합니다. */
          <span className="browse__lib muted">{currentLibrary?.name ?? current}</span>
        )}
      </header>

      {data === 'failed' && (
        <div className="banner banner--info">
          둘러보기 목록을 지금은 불러오지 못했습니다. 검색은 평소대로 됩니다.
        </div>
      )}

      {response?.story && (
        <Story story={response.story} libCode={current} library={currentLibrary} />
      )}

      {groups.length > 0 && (
        <>
          <h3 className="browse__title">요즘 많이 빌려 간 책</h3>
          {/*
            연령 묶음은 서버가 한 번에 다 받아 두었으므로 눌러도 정보나루를 부르지
            않습니다. **비어 있는 묶음은 서버가 아예 담지 않습니다.** 그려 두고 눌렀는데
            아무것도 안 나오면 고장으로 읽힙니다.
          */}
          {groups.length > 1 && (
            <div className="chips">
              {groups.map((row) => (
                <button
                  key={row.key}
                  type="button"
                  className={row.key === shown?.key ? 'chip chip--sm chip--on' : 'chip chip--sm'}
                  aria-pressed={row.key === shown?.key}
                  onClick={() => setGroup(row.key)}
                >
                  {row.label}
                </button>
              ))}
            </div>
          )}

          <ul className="picks__list">
            {shown?.books.map((book) => (
              <PopularRow
                key={book.isbn13 + book.rank}
                book={book}
                libraries={libraries}
                selected={selected}
              />
            ))}
          </ul>

          <p className="muted browse__note">
            최근 30일 동안 {currentLibrary?.name ?? '이 도서관'}에서 많이 빌려 간 순서입니다.
            줄을 누르면 그때 고른 도서관에 있는지 확인합니다.{' '}
            <strong>누르기 전에는 아직 물어보지 않은 것이라, 표시가 없는 줄은 없다는 뜻이
            아닙니다.</strong>
          </p>
        </>
      )}

      {data === 'loading' && <p className="muted">둘러볼 것을 받는 중입니다.</p>}
    </section>
  );
}

/**
 * 오늘의 이야기. **그 도서관 장서에서 뽑은 소설 한 권입니다.**
 *
 * <p>서버가 (한국 날짜 + 도서관부호)로 정하므로 새로 고쳐도 같은 책입니다. 그래서
 * <b>날짜를 제목 옆에 답니다.</b> 아무 표시가 없으면 바뀌지 않는 것을 고장으로 읽고
 * 계속 새로 고칩니다.
 *
 * <p>소장을 따로 묻지 않습니다. 뽑은 통이 곧 그 도서관 장서 목록입니다.
 */
function Story({
  story,
  libCode,
  library,
}: {
  story: BrowseStory;
  libCode: string;
  library: Library | undefined;
}) {
  return (
    <section className="daily">
      <div className="daily__head">
        <h3 className="browse__title">오늘의 이야기</h3>
        <span className="daily__date muted">{formatDay(story.date)}</span>
      </div>

      <div className="daily__card">
        <div className="daily__book">
          <Cover src={story.imageUrl} className="book__cover" />
          <div className="daily__body">
            <span className="daily__title">{story.title}</span>
            <span className="daily__meta">{describe(story.authors, story.publisher, story.publicationYear)}</span>
            {/* 서가에서 책을 찾을 때 실제로 쓰는 값입니다. 못 읽으면 아예 없습니다. */}
            {story.callNumber && <span className="daily__call">청구기호 {story.callNumber}</span>}
          </div>
        </div>

        {story.detailUrl && (
          <p className="book__links">
            <a className="chip chip--sm chip--go" href={story.detailUrl} target="_blank" rel="noreferrer">
              정보나루 책 정보
            </a>
          </p>
        )}

        {/* 한 권 검색의 소장 도서관 줄과 같은 구조입니다. 칩 둘이 `.lib` 바로 아래에 놓입니다. */}
        <ul className="holding__list">
          <li className="lib">
            <a
              className="chip chip--strong chip--go chip--wrap"
              href={libraryLink(libCode, story.isbn13, story.title)}
              target="_blank"
              rel="noreferrer"
              title={linkLabel(library?.linkKind)}
            >
              <span className="lib__name">{library?.name ?? libCode}</span>
              {linkBadge(library?.linkKind) && (
                <span className="lib__kind">{linkBadge(library?.linkKind)}</span>
              )}
            </a>
            <LoanCheck libCode={libCode} isbn13List={[story.isbn13]} />
          </li>
        </ul>
      </div>

      <p className="muted daily__note">
        {library?.name ?? '이 도서관'} 서가의 소설 {story.poolSize.toLocaleString('ko-KR')}권 가운데
        오늘 하나를 골랐습니다. 내일 다른 책이 옵니다.
      </p>
    </section>
  );
}

/** 인기 목록의 한 줄. 누르면 그 자리에서 소장이 펼쳐집니다. */
function PopularRow({
  book,
  libraries,
  selected,
}: {
  book: BrowsePopularBook;
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
}) {
  const [open, setOpen] = useState(false);
  const [held, setHeld] = useState<Held | null>(null);

  const ask = useCallback(async () => {
    if (held !== null) return;
    setHeld({ kind: 'asking' });
    try {
      /*
        **판본 하나만 물어보면 안 됩니다.** 도서관이 다른 판을 가지고 있어도 미소장으로
        나옵니다. 인기 목록은 책마다 ISBN 을 하나만 주므로, 먼저 그 ISBN 으로 저작을
        찾아 묶인 판본 전체를 받은 뒤에 소장을 물어봅니다.
      */
      const found = await searchBooks(
        { title: '', author: '', publisher: '', isbn: book.isbn13 },
        [],
      );
      const isbn13List = found.works[0]?.isbn13List ?? [book.isbn13];
      const holdings = await fetchHoldings(isbn13List, [...selected]);
      setHeld({ kind: 'done', holdings, isbn13List });
    } catch (error) {
      // **물어보지 못한 것과 없는 것을 섞지 않습니다.** 서버가 안 떠 있어도 마찬가지입니다.
      setHeld({
        kind: 'done',
        holdings: { libCodes: [], complete: false, unreadable: true, asOf: null },
        isbn13List: [book.isbn13],
        offline: error instanceof ApiUnavailable,
      });
    }
  }, [book.isbn13, held, selected]);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) void ask();
  };

  const facts = held?.kind === 'done' ? held.holdings : null;
  const state = held?.kind === 'asking' ? 'pending' : holdingState(facts, selected.size);

  return (
    <li className={open ? 'picks__item picks__item--open' : 'picks__item'}>
      <button
        type="button"
        className={open ? 'pick pick--on' : 'pick'}
        aria-expanded={open}
        onClick={toggle}
      >
        <span className="browse__rank">{book.rank || ''}</span>
        <Cover src={book.imageUrl} className="pick__cover" />
        <span className="pick__body">
          <span className="pick__title">{book.title}</span>
          <span className="pick__meta">
            {describe(book.authors, book.publisher, book.publicationYear)}
          </span>
        </span>
        <span className="pick__where">{open ? '접기' : '어디 있나'}</span>
      </button>

      {open && (
        <div className="drawer">
          {book.detailUrl && (
            <p className="book__links">
              <a
                className="chip chip--sm chip--go"
                href={book.detailUrl}
                target="_blank"
                rel="noreferrer"
              >
                정보나루 책 정보
              </a>
            </p>
          )}
          <Held
            state={state}
            facts={facts}
            isbn13List={held?.kind === 'done' ? held.isbn13List : [book.isbn13]}
            title={book.title}
            libraries={libraries}
            offline={held?.kind === 'done' ? held.offline === true : false}
          />
        </div>
      )}
    </li>
  );
}

type Held =
  | { kind: 'asking' }
  | { kind: 'done'; holdings: HoldingsResponse; isbn13List: string[]; offline?: boolean };

/**
 * 네 상태를 그립니다. **판정은 하지 않습니다.** `holdingState` 한 곳에서만 판정하고
 * 여기는 그 결과를 그대로 그립니다. 화면마다 따로 판정하면 언젠가 한쪽이 확인 불가를
 * 미소장으로 그리게 되고, 그때는 아무도 눈치채지 못합니다.
 */
function Held({
  state,
  facts,
  isbn13List,
  title,
  libraries,
  offline,
}: {
  state: ReturnType<typeof holdingState>;
  facts: HoldingsResponse | null;
  isbn13List: string[];
  title: string;
  libraries: readonly Library[];
  offline: boolean;
}) {
  if (state === 'pending') return <p className="muted holding">확인 중입니다.</p>;

  if (state === 'unknown') {
    return (
      <>
        <div className="holding holding--unknown">확인 불가</div>
        <p className="muted holding__note">
          {offline
            ? '검색 서버에 연결하지 못해 확인하지 못했습니다.'
            : '정보나루가 답하지 않아 고른 도서관에 있는지 확인하지 못했습니다.'}{' '}
          잠시 뒤에 다시 눌러 주세요.
        </p>
      </>
    );
  }

  if (state === 'none') {
    return (
      <p className="holding holding--none">
        고른 도서관에는 없습니다{facts?.asOf ? ` (${facts.asOf} 조회)` : ''}
      </p>
    );
  }

  const held = facts?.libCodes ?? [];
  return (
    <div className="holding">
      <p className="holding__count">
        <strong>{held.length}곳</strong>에 있습니다
      </p>
      <ul className="holding__list">
        {held.map((code) => {
          const library = libraries.find((row) => row.libCode === code);
          return (
            <li key={code} className="lib">
              <a
                className="chip chip--strong chip--go chip--wrap"
                href={libraryLink(code, isbn13List[0], title)}
                target="_blank"
                rel="noreferrer"
                title={linkLabel(library?.linkKind)}
              >
                <span className="lib__name">{library?.name ?? code}</span>
                {linkBadge(library?.linkKind) && (
                  <span className="lib__kind">{linkBadge(library?.linkKind)}</span>
                )}
              </a>
              <LoanCheck libCode={code} isbn13List={isbn13List} />
            </li>
          );
        })}
      </ul>
      {/* 빠짐없이 확인하지 못했으면 그 사실을 적습니다. 미소장이라고 말하지는 않습니다. */}
      {facts && !facts.complete && (
        <p className="muted holding__note">
          일부 판본을 확인하지 못했습니다. 다른 도서관에도 있을 수 있습니다.
        </p>
      )}
      {facts?.asOf && <p className="muted holding__note">{facts.asOf} 에 조회한 결과입니다.</p>}
    </div>
  );
}

/**
 * 표지. **실패해도 자리를 남겨 둡니다.**
 *
 * <p>`display: none` 으로 지우면 표지가 흐름에서 빠져 본문이 첫 칸으로 밀려 들어가고,
 * 제목과 저자가 두세 글자마다 줄바꿈됩니다. 표지 주소가 죽은 책에서만 나타나 눈에 잘
 * 띄지 않습니다.
 */
function Cover({ src, className }: { src: string | null; className: string }) {
  const [failed, setFailed] = useState(false);
  if (!src || failed) return <span className={`${className} ${className}--empty`} />;
  return (
    <img
      className={className}
      src={src}
      alt=""
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}

function describe(
  authors: string | null,
  publisher: string | null,
  year: string | null,
): string {
  const tail = [publisher, year].filter(Boolean).join(' · ');
  if (authors && tail) return `${authors} / ${tail}`;
  return authors ?? tail;
}

/** 「9월 10일」. 서버가 주는 것은 ISO 날짜입니다. */
function formatDay(iso: string): string {
  const parts = iso.split('-');
  if (parts.length !== 3) return iso;
  return `${Number(parts[1])}월 ${Number(parts[2])}일`;
}
