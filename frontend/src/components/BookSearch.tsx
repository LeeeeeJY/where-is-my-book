import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiUnavailable, fetchHoldings, hasCriteria, libraryLink, searchBooks } from '../api';
import { anyHomepageOnly, linkBadge, linkLabel } from '../domain/opacLink';
import type { DroppedBook, SearchCriteria, SearchResponse, WorkResult } from '../api';
import { holdingState } from '../domain/holdingState';
import type { HoldingFacts } from '../domain/holdingState';
import { allChecked, checkedCount, orderByHolding } from '../domain/resultOrder';
import type { Library } from '../domain/types';
import { LoanCheck } from './LoanCheck';
import { ProgressBar } from './ProgressBar';

/**
 * ISBN 을 판별하지 못해 뺀 자료를 이름으로 보여 줍니다.
 *
 * **건수만 말하면 사용자가 할 수 있는 일이 없습니다.** 찾던 책이 하필 그 자료였는지
 * 알려 주지 않으므로 결국 아무 단서 없이 사라진 것과 같습니다. 표제와 출판사를 보여 주면
 * 적어도 「이 책이구나」 하고 도서관에서 직접 찾아볼 수 있습니다.
 *
 * **저작 목록에 섞지 않습니다.** 소장을 확인할 수 없는 책이라, 목록에 넣으면 「고른
 * 도서관에 없는 책」과 구별되지 않습니다. 둘은 사용자가 할 일이 다릅니다.
 */
function DroppedList({ books, total }: { books: DroppedBook[]; total: number }) {
  if (books.length === 0) return null;
  return (
    <ul className="dropped-list">
      {books.map((book, i) => (
        <li key={`${book.title ?? ''}-${i}`}>
          {book.title ?? '(제목 없음)'}
          {book.publisher ? ` · ${book.publisher}` : ''}
        </li>
      ))}
      {total > books.length && <li>… 그 밖에 {total - books.length}건</li>}
    </ul>
  );
}

/** 소장을 동시에 몇 권까지 물어볼지. 남의 서버를 몰아치지 않는 선입니다. */
const CONCURRENCY = 4;

/**
 * 한 번에 펼쳐 보이는 저작 수.
 *
 * <p>서버는 100개까지 주지만 화면은 이만큼씩 보여 줍니다. **펼친 것만 소장을 물어보므로
 * 이 숫자가 곧 「더 보기」 한 번에 나가는 조회 횟수**입니다. 서버를 다시 부르지는 않습니다.
 */
const PAGE = 20;

const EMPTY_CRITERIA: SearchCriteria = { title: '', author: '', publisher: '', isbn: '' };

type State =
  | { kind: 'idle' }
  | { kind: 'searching' }
  | { kind: 'done'; criteria: SearchCriteria; response: SearchResponse }
  | { kind: 'offline' }
  | { kind: 'error'; message: string };

/** 도착한 소장 결과와 **그것이 어느 선택 기준인지**. */
type Checked = { key: string; facts: Map<number, HoldingFacts> };

/**
 * 한 권 검색 화면.
 *
 * <b>표시하지 않는 것이 표시하는 것만큼 중요합니다.</b> 대출 가능 여부는 누를 때만, 날짜와
 * 함께 보여주고, 조회에 실패한 것을 미소장으로 섞지 않습니다.
 */
export function BookSearch({
  libraries,
  selected,
}: {
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
}) {
  const [criteria, setCriteria] = useState<SearchCriteria>(EMPTY_CRITERIA);
  const [state, setState] = useState<State>({ kind: 'idle' });
  /** 지금까지 펼쳐 보인 저작 수. 「더 보기」가 이것을 늘립니다. */
  const [shown, setShown] = useState(PAGE);
  /**
   * 도착한 소장 결과와 **그것이 어느 선택 기준인지**.
   *
   * <p>키를 함께 들고 다니는 것이 핵심입니다. 도서관 선택이 바뀌면 이 결과는 예전 선택으로
   * 낸 답이 되므로 그대로 보여 주면 안 됩니다. 방금 체크를 푼 도서관이 「여기 있습니다」에
   * 남아 있게 됩니다. 키가 다르면 아예 쓰지 않습니다.
   */
  const [checked, setChecked] = useState<Checked | null>(null);
  const [checking, setChecking] = useState(false);
  /**
   * 소장 정보의 기준 날짜. **여러 답 가운데 가장 오래된 날짜**입니다.
   *
   * <p>서버가 (ISBN, 지역) 답을 몇 시간 기억해 두므로 스무 권의 답이 서로 다른 날짜일 수
   * 있습니다. 가장 최근 날짜로 말하면 옛 답을 새 답인 것처럼 읽게 됩니다.
   */
  const [asOf, setAsOf] = useState<string | null>(null);
  const running = useRef<{ cancelled: boolean } | null>(null);

  // Set 은 렌더마다 새 객체로 올 수 있어 그대로 의존성에 쓰면 조회가 되풀이됩니다.
  const selectedKey = useMemo(() => [...selected].sort().join(','), [selected]);

  /**
   * 저작마다 소장을 따로 물어 도착하는 대로 기록합니다.
   *
   * <p>**검색 응답에 소장을 함께 실으면 안 됩니다.** 예전에는 서버가 저작마다 조회를 돌리고
   * 한꺼번에 답했는데, 저작이 스무 개면 그만큼 순서대로 기다려야 해서 검색이 수십 초가
   * 됐습니다. 사용자는 그것을 「검색이 안 된다」로 읽습니다.
   *
   * <p>**그리고 도서관을 체크할 때마다 부르지 않습니다.** 체크 한 번에 스무 권을 다시
   * 묻게 되는데, 도서관을 열 곳 고르는 동안 200번이 나갑니다. 화면은 계속 버벅이고
   * 하루 호출 예산도 그만큼 새어 나갑니다. 검색할 때 한 번 부르고, 그 뒤로 선택이
   * 바뀌면 사용자가 누를 때만 다시 부릅니다.
   *
   * <p>답이 도착하는 대로 목록을 다시 그리지는 않습니다. 묶음의 답이 다 오면 그때 세워서
   * 한 번에 그립니다. 그동안은 진행 막대가 어디까지 왔는지 말합니다.
   */
  const check = useCallback(async (works: WorkResult[], key: string, mode: 'reset' | 'add') => {
    if (running.current) running.current.cancelled = true;
    const token = { cancelled: false };
    running.current = token;

    const libCodes = key === '' ? [] : key.split(',');
    // 「더 보기」로 펼친 것만 새로 물어봅니다. 이미 받아 둔 답을 버리고 다시 물으면
    // 스무 권이 그대로 다시 나갑니다.
    setChecked((prev) =>
      mode === 'add' && prev !== null && prev.key === key ? prev : { key, facts: new Map() },
    );
    if (mode === 'reset') setAsOf(null);
    if (libCodes.length === 0 || works.length === 0) return;

    setChecking(true);
    const record = (workId: number, facts: HoldingFacts) => {
      if (token.cancelled) return;
      setChecked((prev) =>
        prev === null || prev.key !== key
          ? prev
          : { key, facts: new Map(prev.facts).set(workId, facts) },
      );
    };
    let next = 0;
    const workers = Array.from({ length: Math.min(CONCURRENCY, works.length) }, async () => {
      while (next < works.length && !token.cancelled) {
        const work = works[next++];
        try {
          const result = await fetchHoldings(work.isbn13List, libCodes);
          if (!token.cancelled) {
            // 날짜 문자열(YYYY-MM-DD)은 그대로 견줘도 앞선 날짜가 작습니다.
            setAsOf((prev) => (prev === null || result.asOf < prev ? result.asOf : prev));
          }
          record(work.workId, result);
        } catch {
          // 한 권이 실패해도 나머지는 계속합니다. 실패는 확인 불가로 남기고
          // **절대 미소장으로 섞지 않습니다.**
          record(work.workId, { libCodes: [], complete: false, unreadable: true });
        }
      }
    });
    await Promise.all(workers);
    if (!token.cancelled) setChecking(false);
  }, []);

  // 다른 검색어로 넘어가면 예전 결과를 들고 있을 이유가 없습니다.
  useEffect(() => {
    if (state.kind !== 'done') {
      if (running.current) running.current.cancelled = true;
      setChecked(null);
      setChecking(false);
    }
  }, [state]);

  /** 지금 고른 도서관 기준으로 확인된 것만 씁니다. 기준이 다르면 없는 것으로 칩니다. */
  const facts = checked !== null && checked.key === selectedKey ? checked.facts : null;
  /** 고른 도서관은 있는데 그 기준으로 아직 확인하지 않은 상태. */
  const needsCheck = selected.size > 0 && facts === null && !checking;

  const byCode = useMemo(() => {
    const map = new Map<string, Library>();
    for (const library of libraries) map.set(library.libCode, library);
    return map;
  }, [libraries]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!hasCriteria(criteria)) return;

    setState({ kind: 'searching' });
    setShown(PAGE);
    try {
      const response = await searchBooks(criteria, [...selected]);
      setState({ kind: 'done', criteria, response });
      // 검색은 「이 책 어디 있나」를 묻는 것이므로 그 자리에서 한 번 확인합니다.
      // 되풀이해서 부르지 않는 것과 아예 안 부르는 것은 다릅니다.
      // **펼쳐 보이는 만큼만 물어봅니다.** 나머지는 「더 보기」를 눌러야 나갑니다.
      void check(response.works.slice(0, PAGE), selectedKey, 'reset');
    } catch (error) {
      if (error instanceof ApiUnavailable) setState({ kind: 'offline' });
      else setState({ kind: 'error', message: (error as Error).message });
    }
  }

  /** 스무 개를 더 펼치고, **새로 펼친 것만** 소장을 물어봅니다. */
  function showMore() {
    if (state.kind !== 'done') return;
    const works = state.response.works;
    const next = Math.min(shown + PAGE, works.length);
    const fresh = works.slice(shown, next);
    setShown(next);
    // 아직 확인하지 않은 상태라면 펼치기만 합니다. 「확인」을 누를 때 함께 나갑니다.
    if (facts !== null && selected.size > 0) void check(fresh, selectedKey, 'add');
  }

  return (
    <section className="results">
      <header className="picker__head">
        <h2>책 검색</h2>
        {selected.size === 0 && <span className="picker__count">도서관 미선택</span>}
      </header>

      {/*
        제목·저자·출판사를 따로 받습니다. 정보나루가 이 셋을 각각 받고 둘 이상 주면
        AND 로 걸어 주므로, 한 칸에 다 넣고 우리가 쪼개는 것보다 정확합니다.
        다만 대부분은 제목만 넣으므로 나머지는 접어 둡니다.
      */}
      <form onSubmit={submit}>
        <div className="search-bar">
          <input
            className="text-input"
            type="search"
            value={criteria.title}
            placeholder="책 제목"
            onChange={(e) => setCriteria({ ...criteria, title: e.target.value })}
            aria-label="책 제목"
          />
          <button className="button" type="submit" disabled={state.kind === 'searching'}>
            {state.kind === 'searching' ? '찾는 중' : '검색'}
          </button>
        </div>

        <div className="search-more">
          <input
            className="text-input"
            type="search"
            value={criteria.author}
            placeholder="저자"
            onChange={(e) => setCriteria({ ...criteria, author: e.target.value })}
            aria-label="저자"
          />
          <input
            className="text-input"
            type="search"
            value={criteria.publisher}
            placeholder="출판사"
            onChange={(e) => setCriteria({ ...criteria, publisher: e.target.value })}
            aria-label="출판사"
          />
          <input
            className="text-input"
            type="search"
            inputMode="numeric"
            value={criteria.isbn}
            placeholder="ISBN"
            onChange={(e) => setCriteria({ ...criteria, isbn: e.target.value })}
            aria-label="ISBN"
          />
        </div>
        <p className="search-hint muted">
          여러 칸을 채우면 모두 만족하는 책만 찾습니다. ISBN 은 하이픈을 넣어도 됩니다.
          띄어쓰기는 어느 쪽으로 넣어도 같은 책을 찾습니다.
        </p>
      </form>

      {selected.size === 0 && (
        <p className="muted">
          도서관을 고르지 않아도 책은 찾을 수 있지만, 어디에 있는지는 알려 드리지 못합니다.
          도서관 선택에서 자주 가는 곳을 먼저 골라 주세요.
        </p>
      )}

      <SearchState
        state={state}
        byCode={byCode}
        selectedCount={selected.size}
        facts={facts}
        checking={checking}
        needsCheck={needsCheck}
        shown={shown}
        onMore={showMore}
        onCheck={() => {
          if (state.kind === 'done') {
            void check(state.response.works.slice(0, shown), selectedKey, 'reset');
          }
        }}
        asOf={asOf}
      />
    </section>
  );
}

function SearchState({
  state,
  byCode,
  selectedCount,
  facts,
  checking,
  needsCheck,
  onCheck,
  shown,
  onMore,
  asOf,
}: {
  state: State;
  byCode: Map<string, Library>;
  selectedCount: number;
  /** 지금 고른 도서관 기준으로 확인된 결과. 기준이 다르거나 아직 안 물어봤으면 null. */
  facts: Map<number, HoldingFacts> | null;
  checking: boolean;
  needsCheck: boolean;
  onCheck: () => void;
  shown: number;
  onMore: () => void;
  asOf: string | null;
}) {
  if (state.kind === 'idle') {
    return (
      <p className="muted">
        제목을 넣으면 도서관 정보나루에서 서지를 찾고, 고른 도서관에 그 책이 있는지 확인합니다.
      </p>
    );
  }

  if (state.kind === 'searching') {
    return <p className="muted">찾고 있습니다.</p>;
  }

  if (state.kind === 'offline') {
    return (
      <div className="banner banner--warn">
        <strong>검색 서버에 연결하지 못했습니다.</strong> 도서관 선택은 그대로 쓸 수 있습니다.
        책 검색은 API 서버가 떠 있어야 하고, 그 서버에는 정보나루에 등록한 인증키가 필요합니다.
      </div>
    );
  }

  if (state.kind === 'error') {
    return (
      <div className="banner banner--warn">
        <strong>검색이 실패했습니다.</strong> {state.message}
        <br />
        찾지 못한 것과 확인하지 못한 것은 다릅니다. 이 화면은 후자입니다.
      </div>
    );
  }

  const { works, totalWorks, foundBooks, droppedNoIsbn, droppedBooks, alsoSearchedTitles,
    recoveredByAuthor } = state.response;
  if (works.length === 0) {
    return (
      <div className="banner banner--warn">
        <strong>찾은 책이 없습니다.</strong>{' '}
        {alsoSearchedTitles.length > 0
          ? `띄어쓰기를 바꾼 ${quoteAll(alsoSearchedTitles)} 표기로도 찾아봤습니다.`
          : '띄어쓰기를 바꿔 다시 찾을 것도 없었습니다.'}{' '}
        정보나루는 어절의 앞에서부터 맞추므로 제목의 첫 어절만 넣거나 부제를 빼고 다시 찾아 보세요.
        {droppedNoIsbn > 0 && (
          <>
            <br />
ISBN 을 알 수 없어 소장을 확인하지 못하는 자료가 {droppedNoIsbn}건 있습니다.
            <DroppedList books={droppedBooks} total={droppedNoIsbn} />
          </>
        )}
        {/*
          **어디서 없어졌는지 밝힙니다.** 「없습니다」 한 마디로 끝내면 정보나루가 못 찾은
          것인지 우리가 버린 것인지 알 수 없고, 그러면 엉뚱한 곳을 고치게 됩니다.
        */}
        <br />
        <span className="muted">
          정보나루가 준 서지 {foundBooks}건, 그중 쓸 수 있는 것으로 만든 책 {totalWorks}개.
        </span>
      </div>
    );
  }

  const visible = works.slice(0, shown);
  /**
   * 펼친 순서대로 스무 개씩 묶습니다. **묶음마다 확인이 끝나면 그 묶음만 세워서 그립니다.**
   *
   * <p>전체를 매번 다시 세우면 「더 보기」를 누를 때마다 위에 있던 책이 자리를 옮깁니다.
   * 읽던 자리가 사라지는 것이라, 이미 그린 묶음은 그대로 두고 새 묶음만 그 아래에
   * 세워서 붙입니다.
   */
  const blocks: WorkResult[][] = [];
  for (let at = 0; at < visible.length; at += PAGE) blocks.push(visible.slice(at, at + PAGE));

  return (
    <>
      {/*
        함께 찾아본 표기와 저자로 되찾은 사실을 반드시 밝힙니다. 넣은 것과 다른 표기의 책이
        목록에 섞여 있는 것이라, 말하지 않으면 검색이 엉뚱한 것을 가져왔다고 읽힙니다.
      */}
      {(alsoSearchedTitles.length > 0 || recoveredByAuthor) && (
        <div className="banner banner--info">
          {alsoSearchedTitles.length > 0 && (
            <>
              띄어쓰기가 다른 <strong>{quoteAll(alsoSearchedTitles)}</strong> 표기로도 함께
              찾았습니다.
            </>
          )}
          {alsoSearchedTitles.length > 0 && recoveredByAuthor && ' '}
          {recoveredByAuthor && (
            <>
              제목으로는 걸리지 않던 판을 <strong>같은 저자의 책</strong>에서 더 찾았습니다.
            </>
          )}
        </div>
      )}
      <p className="asof">
        {checking ? (
          <>소장 확인 중 · 출처: 도서관 정보나루</>
        ) : (
          <>소장 정보 {formatAsOf(asOf ?? state.response.asOf)} 조회 기준 · 출처: 도서관 정보나루</>
        )}
        {facts !== null && [...facts.values()].some((f) => !f.complete) && (
          <>
            <br />
            {/*
              판본이 빠질 수도 있고 도서관이 빠질 수도 있습니다. 도서관 주소에서 시도를
              알아내지 못하면 그곳은 조회 대상에서 아예 빠집니다. 어느 쪽이든 「물어보지
              못했다」이므로 판본이라고 단정하지 않습니다.
            */}
            <strong>일부를 확인하지 못했습니다.</strong> 확인하지 못한 판본이나 도서관이
            있습니다. 확인하지 못한 것을 미소장으로 세지 않았습니다.
          </>
        )}
      </p>
      {/*
        **도서관을 체크할 때마다 조회하지 않습니다.** 체크 한 번에 스무 권을 다시 묻게
        되는데, 열 곳을 고르는 동안 200번이 나가고 화면은 계속 버벅입니다. 하루 호출
        예산도 그만큼 새어 나갑니다. 그래서 누를 때만 부릅니다.
      */}
      {needsCheck && (
        <p className="recheck">
          <button type="button" className="button button--quiet" onClick={onCheck}>
            고른 도서관 {selectedCount}곳에서 확인
          </button>
        </p>
      )}
      {/*
        **확인이 끝난 묶음만 그립니다.** 예전에는 답이 도착하는 대로 채우고 「있는 책 먼저
        보기」를 켜야 소장한 책이 위로 올라왔습니다. 이제 묶음의 답이 다 오면 소장 → 미소장 →
        확인 불가 순으로 한 번 세워서 그리고, 그때까지는 진행 막대가 어디까지 왔는지 말합니다.
        답이 올 때마다 순서가 바뀌면 읽던 자리가 사라지므로, 세우는 것은 한 번뿐입니다.

        도서관을 고르지 않았거나 아직 「확인」을 누르지 않았으면 기다릴 것이 없으므로
        검색어 일치도 순으로 바로 그립니다.
      */}
      {blocks.map((block, index) => {
        const ready = selectedCount === 0 || facts === null || !checking || allChecked(block, facts);
        if (!ready) {
          return (
            <ProgressBar
              key={`progress-${index}`}
              done={checkedCount(block, facts)}
              total={block.length}
              label={`고른 도서관 ${selectedCount}곳에서 확인하는 중`}
            />
          );
        }
        const ordered =
          selectedCount > 0 && facts !== null ? orderByHolding(block, facts, selectedCount) : block;
        return (
          <ul className="book-list" key={`block-${index}`}>
            {ordered.map((work) => (
              <BookCard
                key={work.workId}
                work={work}
                byCode={byCode}
                selectedCount={selectedCount}
                facts={facts?.get(work.workId) ?? null}
                checking={checking}
              />
            ))}
          </ul>
        );
      })}

      {/*
        **지금 보는 것이 전부인지 잘린 것인지 밝힙니다.** 스무 개만 보여 주고 아무 말도
        하지 않으면 사용자는 찾던 책이 없다고 결론짓습니다. 실제로는 스물한 번째에
        있을 수 있습니다.
      */}
      <p className="more">
        {totalWorks > works.length
          ? `${totalWorks}개를 찾아 위에서 ${works.length}개까지 봅니다 · ${shown}개 보는 중`
          : `${totalWorks}개 중 ${Math.min(shown, works.length)}개 보는 중`}
        {shown < works.length && (
          <>
            {' '}
            <button
              type="button"
              className="button button--quiet"
              onClick={onMore}
              disabled={checking}
            >
              {checking ? '확인 중' : `${Math.min(PAGE, works.length - shown)}개 더 보기`}
            </button>
          </>
        )}
        {droppedNoIsbn > 0 && (
          <>
            <br />
ISBN 을 알 수 없어 소장을 확인하지 못하는 자료가 {droppedNoIsbn}건 있습니다.
            <DroppedList books={droppedBooks} total={droppedNoIsbn} />
          </>
        )}
      </p>
    </>
  );
}

/** 「레 미제라블」, 「레미제라블」처럼 표기를 겹낫표로 묶어 이어 붙입니다. */
function quoteAll(titles: string[]): string {
  return titles.map((title) => `「${title}」`).join(', ');
}

function BookCard({
  work,
  byCode,
  selectedCount,
  facts,
  checking,
}: {
  work: WorkResult;
  byCode: Map<string, Library>;
  selectedCount: number;
  /** 아직 도착하지 않았으면 null. **빈 결과와 구분해야 합니다.** */
  facts: HoldingFacts | null;
  checking: boolean;
}) {
  return (
    <li className="book">
      {work.coverUrl ? (
        <img
          className="book__cover"
          src={work.coverUrl}
          alt=""
          loading="lazy"
          // 표지 서버가 답하지 않으면 깨진 그림 자리가 남습니다. 표지는 없어도 되는
          // 정보라, 실패하면 조용히 지우는 편이 화면이 깔끔합니다.
          onError={(e) => {
            e.currentTarget.style.display = 'none';
          }}
        />
      ) : (
        <div className="book__cover book__cover--empty" aria-hidden />
      )}

      <div className="book__body">
        <h3 className="book__title">{work.title}</h3>
        <p className="book__meta muted">
          {[work.author, work.publisher].filter(Boolean).join(' · ')}
        </p>
        {work.editionLabels.length > 0 && (
          <p className="book__editions muted">판본 {work.editionLabels.join(' · ')}</p>
        )}
        {work.isbn13List.length > 1 && (
          <p className="book__editions muted">
            {/*
              판본을 전부 조회한다는 것이 이 도구의 핵심이라 밝혀 둡니다. 다만 확인이
              끝나지 않았는데 "모두 조회했다"고 쓰면 아래의 확인 불가 표시와 어긋납니다.
            */}
            {checkedEveryEdition(facts, selectedCount)
              ? `판본 ${work.isbn13List.length}개를 모두 조회했습니다.`
              : `이 저작에 판본 ${work.isbn13List.length}개가 묶여 있습니다.`}
          </p>
        )}

        {/*
          정보나루 책 정보는 **책마다 한 번만** 답니다. 아래 소장 목록의 도서관 링크는
          그 도서관으로 갑니다. 예전에는 주소 규칙이 없으면 도서관 링크를 여기로 보냈는데,
          규칙 표가 비어 있어서 결국 모든 도서관이 정보나루로 가고 같은 링크가 스무 번씩
          반복됐습니다.
        */}
        {work.detailUrl && (
          <p className="book__editions">
            <a href={work.detailUrl} target="_blank" rel="noreferrer">
              정보나루에서 이 책 정보 보기
            </a>
          </p>
        )}

        <Holdings
          work={work}
          byCode={byCode}
          selectedCount={selectedCount}
          facts={facts}
          checking={checking}
        />
      </div>
    </li>
  );
}

/**
 * 소장 결과.
 *
 * <b>네 가지를 절대 섞지 않습니다.</b> 확인하지 않음 · 확인 불가 · 소장 · 미소장입니다.
 * 실패를 미소장으로 표시하면 실제로 있는 책을 없다고 답하게 되어 헛걸음을 만듭니다.
 */
function Holdings({
  work,
  byCode,
  selectedCount,
  facts,
  checking,
}: {
  work: WorkResult;
  byCode: Map<string, Library>;
  selectedCount: number;
  facts: HoldingFacts | null;
  checking: boolean;
}) {
  // 판정은 holdingState 한 곳에서만 합니다. 화면마다 따로 판정하면 언젠가 한쪽이
  // 확인 불가를 미소장으로 그리게 되고, 그때는 아무도 눈치채지 못합니다.
  const state = holdingState(facts, selectedCount);

  if (state === 'pending') {
    // 노란 상자는 "확인 불가"에만 씁니다. 안내까지 같은 색으로 칠하면
    // 정작 확인하지 못한 책이 눈에 띄지 않습니다.
    //
    // **셋을 갈라 놓습니다.** 고르지 않은 것과, 기다리는 중인 것과, 고른 도서관이
    // 바뀌어 아직 안 물어본 것은 사용자가 할 일이 서로 다릅니다. 하나로 뭉치면
    // 이미 고른 사용자에게 고르라고 하거나, 눌러야 하는데 기다리게 만듭니다.
    if (selectedCount === 0) {
      return <p className="holding muted">도서관을 고르면 어디에 있는지 확인합니다.</p>;
    }
    if (checking) {
      return <p className="holding muted">어디에 있는지 확인하고 있습니다…</p>;
    }
    return <p className="holding muted">위의 「확인」을 누르면 어디에 있는지 알려 드립니다.</p>;
  }

  if (state === 'unknown') {
    return (
      <p className="holding holding--unknown">
        <strong>확인 불가.</strong>{' '}
        {facts?.unreadable
          ? '조회가 실패했습니다. 없다는 뜻이 아니라 알 수 없다는 뜻입니다.'
          : '확인하지 못한 판본이 있어 미소장이라고 말할 수 없습니다.'}
      </p>
    );
  }

  if (state === 'none') {
    return <p className="holding holding--none">고른 도서관에는 없습니다.</p>;
  }

  const held = facts?.libCodes ?? [];
  return (
    <div className="holding holding--held">
      <p className="holding__count">
        <strong>{held.length}곳에 있습니다.</strong>
        {facts !== null && !facts.complete && ' 확인하지 못한 판본이 있어 더 있을 수 있습니다.'}
      </p>
      <ul className="holding__list">
        {held.map((code) => {
          const library = byCode.get(code);
          return (
            <li key={code} className="lib">
              <div className="lib__head">
                {/*
                  어느 단계의 링크인지 밝힙니다. 조용히 홈페이지로 보내면 사용자는
                  검색 결과 자체가 틀렸다고 생각합니다. 다만 줄마다 한 문장씩 붙이면
                  스무 곳에서 같은 말이 스무 번 반복되어 목록이 읽히지 않습니다.
                  줄에는 짧게 붙이고 뜻은 목록 아래에 한 번만 풀어 씁니다.
                */}
                {/*
                  **배지까지 링크 안에 둡니다.** 「홈페이지」 배지는 테두리가 둥글어
                  버튼처럼 보이는데, 예전에는 이름에만 링크가 걸려 있어 배지를 누르면
                  아무 일도 일어나지 않았습니다. 누를 것처럼 생긴 것은 눌려야 합니다.
                */}
                <a
                  className="lib__link"
                  href={libraryLink(code, work.isbn13List[0], work.title)}
                  target="_blank"
                  rel="noreferrer"
                  title={linkLabel(library?.linkKind)}
                >
                  <span className="lib__name">{library ? library.name : code}</span>
                  {/* 기본값(홈페이지)에는 배지를 달지 않습니다. 이유는 linkBadge 에 있습니다. */}
                  {linkBadge(library?.linkKind) && (
                    <span className="lib__kind">{linkBadge(library?.linkKind)}</span>
                  )}
                </a>
              </div>
              <LoanCheck libCode={code} isbn13List={work.isbn13List} />
            </li>
          );
        })}
      </ul>
      {anyHomepageOnly(held.map((code) => byCode.get(code)?.linkKind)) && (
        <p className="muted holding__note">
          도서관 이름을 누르면 첫 화면으로 갑니다. 그 도서관의 책 페이지 주소 규칙이 아직
          없어서인데, 거기서 제목을 다시 검색해 주세요.
        </p>
      )}
    </div>
  );
}

/** 저작에 묶인 판본을 빠짐없이 조회했는지. 조회를 아예 하지 않은 경우도 아닙니다. */
function checkedEveryEdition(facts: HoldingFacts | null, selectedCount: number): boolean {
  const state = holdingState(facts, selectedCount);
  return (state === 'held' || state === 'none') && (facts?.complete ?? false);
}

/** 표시는 전부 Asia/Seoul 기준입니다. */
function formatAsOf(asOf: string | null): string {
  if (!asOf) return '방금';
  const date = new Date(`${asOf}T00:00:00+09:00`);
  if (Number.isNaN(date.getTime())) return asOf;
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    timeZone: 'Asia/Seoul',
  }).format(date);
}
