import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiUnavailable, fetchHoldings, fetchLoanStatus, hasCriteria, libraryLink, searchBooks } from '../api';
import type { LoanStatus } from '../api';
import { anyHomepageOnly, linkBadge, linkLabel } from '../domain/opacLink';
import { LOAN_DISCLAIMER, loanPhrase } from '../domain/loanStatus';
import type { SearchCriteria, SearchResponse, WorkResult } from '../api';
import { holdingState } from '../domain/holdingState';
import type { HoldingFacts } from '../domain/holdingState';
import type { Library } from '../domain/types';

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

/**
 * 한 권 검색 화면.
 *
 * <b>표시하지 않는 것이 표시하는 것만큼 중요합니다.</b> 대출 가능 여부는 어떤 경우에도
 * 보여주지 않고, 조회에 실패한 것을 미소장으로 섞지 않습니다.
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
  /** 소장한 책을 위로 올릴지. 기본은 검색어와의 일치도 순입니다. */
  const [heldFirst, setHeldFirst] = useState(false);
  /**
   * 도착한 소장 결과와 **그것이 어느 선택 기준인지**.
   *
   * <p>키를 함께 들고 다니는 것이 핵심입니다. 도서관 선택이 바뀌면 이 결과는 예전 선택으로
   * 낸 답이 되므로 그대로 보여 주면 안 됩니다. 방금 체크를 푼 도서관이 「여기 있습니다」에
   * 남아 있게 됩니다. 키가 다르면 아예 쓰지 않습니다.
   */
  const [checked, setChecked] = useState<{ key: string; facts: Map<number, HoldingFacts> } | null>(
    null,
  );
  const [checking, setChecking] = useState(false);
  const [asOf, setAsOf] = useState<string | null>(null);
  const running = useRef<{ cancelled: boolean } | null>(null);

  // Set 은 렌더마다 새 객체로 올 수 있어 그대로 의존성에 쓰면 조회가 되풀이됩니다.
  const selectedKey = useMemo(() => [...selected].sort().join(','), [selected]);

  /**
   * 저작마다 소장을 따로 물어 도착하는 대로 채웁니다.
   *
   * <p>**검색 응답에 소장을 함께 실으면 안 됩니다.** 예전에는 서버가 저작마다 조회를 돌리고
   * 한꺼번에 답했는데, 저작이 스무 개면 그만큼 순서대로 기다려야 해서 검색이 수십 초가
   * 됐습니다. 사용자는 그것을 「검색이 안 된다」로 읽습니다.
   *
   * <p>**그리고 도서관을 체크할 때마다 부르지 않습니다.** 체크 한 번에 스무 권을 다시
   * 묻게 되는데, 도서관을 열 곳 고르는 동안 200번이 나갑니다. 화면은 계속 버벅이고
   * 하루 호출 예산도 그만큼 새어 나갑니다. 검색할 때 한 번 부르고, 그 뒤로 선택이
   * 바뀌면 사용자가 누를 때만 다시 부릅니다.
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
          if (!token.cancelled) setAsOf(result.asOf);
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
        heldFirst={heldFirst}
        onHeldFirst={setHeldFirst}
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
  heldFirst,
  onHeldFirst,
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
  heldFirst: boolean;
  onHeldFirst: (on: boolean) => void;
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

  const { works, totalWorks, droppedNoIsbn, retriedTitle } = state.response;
  if (works.length === 0) {
    return (
      <div className="banner banner--warn">
        <strong>찾은 책이 없습니다.</strong> 정보나루는 넣은 글자를 그대로 찾으므로
        띄어쓰기가 다르면 걸리지 않습니다. 「마의 산」과 「마의산」이 서로 다른 검색입니다.
        부제를 빼거나 띄어쓰기를 바꿔 보세요.
        {droppedNoIsbn > 0 && (
          <>
            <br />
            찾기는 했지만 ISBN 이 없어 뺀 자료가 {droppedNoIsbn}건 있습니다. 소장 조회를
            ISBN 으로만 할 수 있어서 어느 도서관에 있는지 알려 드릴 수 없는 자료입니다.
          </>
        )}
      </div>
    );
  }

  const visible = works.slice(0, shown);
  // **확인이 끝난 뒤에만 다시 세웁니다.** 답이 도착할 때마다 순서가 바뀌면 읽던 자리가
  // 사라집니다. 확인이 끝나는 순간에 한 번만 움직이게 해 두면 사용자가 그 움직임을
  // 예상할 수 있습니다.
  const ordered =
    heldFirst && !checking && facts !== null
      ? [...visible].sort((a, b) => heldRank(facts, a, selectedCount)
          - heldRank(facts, b, selectedCount))
      : visible;

  return (
    <>
      {/*
        띄어쓰기를 바꿔 다시 찾았으면 반드시 밝힙니다. 넣은 것과 다른 결과가 말없이
        나오면 사용자는 검색이 엉뚱하게 동작한다고 생각합니다.
      */}
      {retriedTitle && (
        <div className="banner banner--info">
          넣으신 제목으로는 한 건도 없어서 <strong>「{retriedTitle}」</strong>로 다시
          찾았습니다. 정보나루는 넣은 글자를 그대로 찾기 때문에 띄어쓰기가 다르면
          걸리지 않습니다.
        </div>
      )}
      <p className="asof">
        {checking ? (
          <>
            소장 확인 중 {facts?.size ?? 0}/{Math.min(shown, works.length)} · 출처: 도서관
            정보나루
          </>
        ) : (
          <>소장 정보 {formatAsOf(asOf ?? state.response.asOf)} 조회 기준 · 출처: 도서관 정보나루</>
        )}
        {facts !== null && [...facts.values()].some((f) => !f.complete) && (
          <>
            <br />
            <strong>일부 판본을 확인하지 못했습니다.</strong> 확인하지 못한 것을 미소장으로
            세지 않았습니다.
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
      {/* 소장한 책을 위로 올릴지는 사용자가 정합니다. 저절로 움직이면 읽던 자리를 잃습니다. */}
      {selectedCount > 0 && facts !== null && (
        <p className="sort-toggle">
          <label>
            <input
              type="checkbox"
              checked={heldFirst}
              onChange={(e) => onHeldFirst(e.target.checked)}
            />{' '}
            있는 책 먼저 보기
          </label>
        </p>
      )}
      <ul className="book-list">
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
            ISBN 이 없어 뺀 자료가 {droppedNoIsbn}건 있습니다. 소장 조회를 ISBN 으로만 할 수
            있어서 어느 도서관에 있는지 알려 드릴 수 없는 자료입니다.
          </>
        )}
      </p>
    </>
  );
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
                <a
                  className="lib__name"
                  href={libraryLink(code, work.isbn13List[0], work.title)}
                  target="_blank"
                  rel="noreferrer"
                  title={linkLabel(library?.linkKind)}
                >
                  {library ? library.name : code}
                </a>
                <span className="lib__kind">{linkBadge(library?.linkKind)}</span>
              </div>
              <LoanCheck libCode={code} isbn13List={work.isbn13List} />
            </li>
          );
        })}
      </ul>
      {anyHomepageOnly(held.map((code) => byCode.get(code)?.linkKind)) && (
        <p className="muted holding__note">
          「홈페이지」는 그 도서관의 주소 규칙이 아직 없어 첫 화면으로 보낸다는 뜻입니다.
          거기서 책 제목을 다시 검색해 주세요.
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
/**
 * 소장한 책을 위로 올릴 때 쓰는 등급. 작을수록 위입니다.
 *
 * <p>**미소장을 맨 아래에 둡니다.** 확인 불가는 다시 확인하면 있을 수 있는 책이라
 * 미소장보다 위입니다. 둘을 같이 두면 「없는 책」과 「모르는 책」이 섞입니다.
 */
function heldRank(
  facts: Map<number, HoldingFacts>,
  work: WorkResult,
  selectedCount: number,
): number {
  switch (holdingState(facts.get(work.workId) ?? null, selectedCount)) {
    case 'held':
      return 0;
    case 'unknown':
      return 1;
    case 'pending':
      return 2;
    default:
      return 3;
  }
}

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

/**
 * 그 도서관에 지금 빌릴 수 있는지. **누를 때만 물어봅니다.**
 *
 * <p>이 조회는 (도서관 하나 × 책 하나)라서, 목록에 미리 달면 30권 확인 한 번에 1,800회가
 * 나가고 하루 한도가 열여섯 번 만에 사라집니다. 「이 도서관에 가려는데 빌릴 수 있나」가
 * 실제 행동이고, 그건 한 곳만 물어보면 됩니다.
 *
 * <p>그리고 <b>결과에서 기준 날짜를 떼지 않습니다.</b> 정보나루가 주는 값은 조회일 기준
 * 전날의 상태입니다. 「대출 가능」 네 글자만 남으면 사용자는 그것을 지금 상태로 읽고,
 * 그 믿음으로 갔다가 허탕치는 것이 이 도구를 못 쓰게 만드는 가장 큰 요인입니다.
 */
/**
 * 누를 때만 대출 상태를 물어봅니다.
 *
 * <p>목록에 미리 붙이면 안 됩니다. `bookExist` 는 (도서관 하나 × ISBN 하나)라, 여러 권
 * 확인 화면에 그냥 달면 30권 × 판본 3개 × 도서관 20곳 = 1,800회가 되고 하루 한도가
 * 열여섯 번 만에 사라집니다.
 */
function LoanCheck({ libCode, isbn13List }: { libCode: string; isbn13List: string[] }) {
  const [state, setState] = useState<'idle' | 'asking' | 'failed'>('idle');
  const [status, setStatus] = useState<LoanStatus | null>(null);

  if (isbn13List.length === 0) return null;

  if (status) {
    const phrase = loanPhrase(status);
    return (
      <span className={phrase.caution ? 'loan loan--caution' : 'loan'}>
        {' · '}
        {phrase.text}
      </span>
    );
  }

  if (state === 'failed') {
    // 못 물어본 것이지 「빌릴 수 없다」가 아닙니다. 둘을 섞으면 헛걸음이 됩니다.
    return <span className="muted"> · 대출 상태를 확인하지 못했습니다</span>;
  }

  return (
    <button
      type="button"
      className="link-button"
      disabled={state === 'asking'}
      title={LOAN_DISCLAIMER}
      onClick={() => {
        setState('asking');
        fetchLoanStatus(libCode, isbn13List).then(
          (result) => {
            setStatus(result);
            setState('idle');
          },
          () => setState('failed'),
        );
      }}
    >
      {state === 'asking' ? '확인 중' : '대출 상태 확인'}
    </button>
  );
}
