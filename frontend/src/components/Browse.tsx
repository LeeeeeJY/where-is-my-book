import { useEffect, useState } from 'react';
import { fetchBrowse, libraryLink } from '../api';
import type { BrowsePopularBook, BrowseResponse, BrowseStory } from '../api';
import type { Library } from '../domain/types';
import { linkBadge, linkLabel } from '../domain/opacLink';
import { LoanCheck } from './LoanCheck';

/**
 * 읽을 책을 아직 정하지 않은 사람의 입구입니다. 그 도서관의 인기대출 목록과
 * 오늘의 이야기 한 권을 보여 줍니다.
 *
 * <h2>소장을 묻지 않습니다</h2>
 *
 * <p>처음에는 줄마다 「어디 있나」를 두고 눌러서 소장을 확인하게 했는데, <b>이 목록은
 * 그 도서관에서 많이 빌려 간 책의 순위입니다.</b> 그 도서관에 있느냐고 되묻는 셈이라
 * 물음 자체가 어긋나 있었습니다. 사용자가 실제로 알고 싶은 것은 <b>지금 빌릴 수
 * 있는가</b>이고, 그것만 누를 때 물어봅니다.
 *
 * <p>덕분에 줄을 펼칠 때 나가던 검색 한 번과 소장 조회 한 번이 통째로 사라졌습니다.
 *
 * <h2>탭 하나를 차지합니다</h2>
 *
 * <p>처음에는 한 권 검색의 빈 화면에 얹었고, 그다음에는 검색 카드 아래의 다른 카드로
 * 떼어 냈습니다. 랭킹의 목적이 「검색어를 정하게 돕는 것」이라 그 자리가 맞아 보였는데,
 * <b>실제로 띄워 보니 검색 칸 넷 아래에 오늘의 이야기와 스무 권짜리 목록과 안내 문단이
 * 겹쳐 첫 화면이 복잡했습니다.</b> 카드를 나눈 것으로는 길이가 줄지 않습니다. 검색하러
 * 온 사람에게는 검색 칸만 보이는 편이 낫습니다.
 *
 * <p>다만 <b>카드 안의 생김새는 그때 정리한 것을 그대로 씁니다.</b> 둘러볼 도서관을
 * 제목 옆의 알약에서 고르는 것이 그것입니다. 검색 입력 칸과 같은 모양이면 다섯째 검색
 * 조건처럼 읽힙니다.
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
    한 번에 골랐더니 기본값이 「분당도서관」이었습니다.
  */
  const codes = libraries
    .filter((library) => selected.has(library.libCode))
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
        // 조용히 물러납니다.
        if (!cancelled) setData('failed');
      },
    );
    return () => {
      cancelled = true;
    };
  }, [current]);

  const currentLibrary = libraries.find((library) => library.libCode === current);
  const response = typeof data === 'string' ? null : data;
  const groups = response?.popular ?? [];
  const shown = groups.find((row) => row.key === group) ?? groups[0];

  return (
    <section className="browse">
      {/*
        **둘러볼 도서관은 제목 옆에서, 맨 위 한 곳에서만 정합니다.** 목록 머리마다 따로
        두면 위의 「오늘의 이야기」와 아래 목록의 범위가 서로 달라 보입니다. 그리고 검색
        입력 칸과 같은 모양이면 다섯째 검색 조건처럼 읽히므로 칩 모양의 알약으로 둡니다.
      */}
      <header className="browse__head">
        <h2>둘러보기</h2>
        {codes.length > 1 ? (
          <select
            className="browse__pick"
            aria-label="둘러볼 도서관"
            title="둘러볼 도서관"
            value={current ?? ''}
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
          current && <span className="browse__lib muted">{currentLibrary?.name ?? current}</span>
        )}
      </header>

      {!current && (
        <p className="muted">
          도서관을 고르면 그 도서관에서 요즘 많이 빌려 간 책과 오늘의 이야기를 보여 드립니다.
        </p>
      )}

      {data === 'failed' && (
        <div className="banner banner--info">
          둘러보기 목록을 지금은 불러오지 못했습니다. 검색은 평소대로 됩니다.
        </div>
      )}

      {response?.story && current && (
        <Story story={response.story} libCode={current} library={currentLibrary} />
      )}

      {groups.length > 0 && current && (
        <>
          <h3 className="browse__title">요즘 많이 빌려 간 책</h3>
          {/*
            연령 묶음은 서버가 한 번에 다 받아 두었으므로 눌러도 정보나루를 부르지
            않습니다. **비어 있는 묶음은 서버가 아예 담지 않습니다.**
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

          <ul className="browse__list">
            {shown?.books.map((book) => (
              <PopularRow key={book.isbn13 + book.rank} book={book} libCode={current} />
            ))}
          </ul>

          <p className="muted browse__note">
            최근 30일 동안 {currentLibrary?.name ?? '이 도서관'}에서 많이 빌려 간 순서입니다.
            {/*
              **연령대를 「그 나이가 읽을 책」으로 읽히게 두지 마세요.** 실제로 받아 보니
              어느 도서관의 「성인」 1·2위가 「흔한남매 과학 탐험대」와 「설민석의 한국사
              대모험」으로 「전체」 1·2위와 같았습니다. 어른이 아이 책을 빌린 것으로 보이는데
              매뉴얼은 그저 「성인 인기대출목록」이라고만 적어 두어 어느 쪽인지 단정할 수
              없습니다. 그래서 **대출 기록의 구분이라는 사실만 말하고 누구의 나이인지는
              말하지 않습니다.** 자세한 것은 `docs/가정과-검증상태.md` 2-13 에 있습니다.
            */}
            {groups.length > 1 && ' 연령대는 정보나루가 대출 기록을 나눠 놓은 것입니다.'}
          </p>
        </>
      )}

      {data === 'loading' && current && <p className="muted">둘러볼 것을 받는 중입니다.</p>}
    </section>
  );
}

/**
 * 오늘의 이야기. **그 도서관 장서에서 뽑은 소설 한 권입니다.**
 *
 * <p>서버가 (한국 날짜 + 도서관부호)로 정하므로 새로 고쳐도 같은 책입니다. 그래서
 * <b>날짜를 제목 옆에 답니다.</b> 아무 표시가 없으면 바뀌지 않는 것을 고장으로 읽고
 * 계속 새로 고칩니다.
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
            <span className="daily__meta">
              {describe(story.authors, story.publisher, story.publicationYear)}
            </span>
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
              href={libraryLink(libCode, [story.isbn13], story.title)}
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
        오늘 하나를 골랐습니다. 내일은 다른 책을 고릅니다.
      </p>
    </section>
  );
}

/**
 * 인기 목록의 한 줄.
 *
 * <h2>순위 숫자를 적지 않습니다</h2>
 *
 * <p>15절이 대출건수 없이 순위만 주는데, <b>실제로 재어 보니 동점이 너무 많아 숫자가
 * 아무 말도 하지 못했습니다.</b> 일곱 도서관 41개 목록에서 스무 권에 든 서로 다른 순위가
 * 중앙값 <b>두 개</b>였고, 가장 큰 동점 덩어리가 중앙값 <b>열두 권</b>이었으며, 여덟 목록은
 * <b>전원이 같은 순위</b>였습니다. 「3」이 여섯 줄, 「1」이 스무 줄 이어지는 화면입니다.
 *
 * <p>목록 자체가 이미 많이 빌려 간 차례이므로 <b>순서만 남기고 숫자를 뗐습니다.</b>
 * 1부터 20까지 새로 매기지는 않습니다. 정보나루가 주지 않은 순서를 우리가 지어내는
 * 것이라, 동점인 스무 권에 없는 등수를 붙이게 됩니다. 값 자체는 진단에 쓰므로 응답에는
 * 그대로 둡니다.
 *
 * <p><b>소장을 묻지 않습니다.</b> 이 목록이 곧 그 도서관에서 빌려 간 기록이라 거기 있느냐고
 * 되물을 이유가 없습니다. 물어볼 값어치가 있는 것은 지금 빌릴 수 있는지뿐입니다.
 *
 * <p>대출 조회에 ISBN 하나만 넘기는 것도 같은 이유입니다. 「저작에 묶인 판본을 전부
 * 물어보라」는 규칙은 <b>어느 판을 가졌는지 모를 때</b>의 규칙인데, 여기서는 그 도서관이
 * 실제로 빌려 준 바로 그 ISBN 을 정보나루가 알려 준 것입니다.
 */
function PopularRow({ book, libCode }: { book: BrowsePopularBook; libCode: string }) {
  return (
    <li className="pick pick--flat">
      <Cover src={book.imageUrl} className="pick__cover" />
      <span className="pick__body">
        <span className="pick__title">{book.title}</span>
        <span className="pick__meta">
          {describe(book.authors, book.publisher, book.publicationYear)}
        </span>
        <span className="browse__actions">
          {book.detailUrl && (
            <a className="chip chip--sm chip--go" href={book.detailUrl} target="_blank" rel="noreferrer">
              정보나루 책 정보
            </a>
          )}
          <LoanCheck libCode={libCode} isbn13List={[book.isbn13]} />
        </span>
      </span>
    </li>
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
  return <img className={className} src={src} alt="" loading="lazy" onError={() => setFailed(true)} />;
}

function describe(authors: string | null, publisher: string | null, year: string | null): string {
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
