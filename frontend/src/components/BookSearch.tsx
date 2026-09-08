import { useEffect, useMemo, useState } from 'react';
import { ApiUnavailable, fetchHoldings, fetchLoanStatus, libraryLink, searchBooks } from '../api';
import type { LoanStatus } from '../api';
import { anyHomepageOnly, linkBadge, linkLabel } from '../domain/opacLink';
import { LOAN_DISCLAIMER, loanPhrase } from '../domain/loanStatus';
import type { SearchResponse, WorkResult } from '../api';
import { holdingState } from '../domain/holdingState';
import type { HoldingFacts } from '../domain/holdingState';
import type { Library } from '../domain/types';

/** 소장을 동시에 몇 권까지 물어볼지. 남의 서버를 몰아치지 않는 선입니다. */
const CONCURRENCY = 4;

type State =
  | { kind: 'idle' }
  | { kind: 'searching'; query: string }
  | { kind: 'done'; query: string; response: SearchResponse }
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
  const [query, setQuery] = useState('');
  const [state, setState] = useState<State>({ kind: 'idle' });
  // workId 마다 도착한 소장 결과. **키가 없으면 아직 안 물어본 것입니다.**
  // 실패는 unreadable 로 넣어 두어야 「확인 중」에 영영 머무르지 않습니다.
  const [holdings, setHoldings] = useState<Map<number, HoldingFacts>>(new Map());
  const [asOf, setAsOf] = useState<string | null>(null);

  // Set 은 렌더마다 새 객체로 올 수 있어 그대로 의존성에 쓰면 조회가 되풀이됩니다.
  const selectedKey = useMemo(() => [...selected].sort().join(','), [selected]);

  /**
   * 검색이 끝나면 저작마다 소장을 따로 물어 도착하는 대로 채웁니다.
   *
   * **검색 응답에 소장을 함께 실으면 안 됩니다.** 예전에는 서버가 저작마다 조회를 돌리고
   * 한꺼번에 답했는데, 저작이 스무 개면 그만큼 순서대로 기다려야 해서 검색이 수십 초가
   * 됐습니다. 사용자는 그것을 「검색이 안 된다」로 읽습니다.
   */
  useEffect(() => {
    setHoldings(new Map());
    if (state.kind !== 'done') return;
    const works = state.response.works;
    const libCodes = selectedKey === '' ? [] : selectedKey.split(',');
    if (libCodes.length === 0 || works.length === 0) return;

    let cancelled = false;
    let next = 0;
    const record = (workId: number, facts: HoldingFacts) => {
      if (cancelled) return;
      setHoldings((prev) => new Map(prev).set(workId, facts));
    };
    const workers = Array.from({ length: Math.min(CONCURRENCY, works.length) }, async () => {
      while (next < works.length && !cancelled) {
        const work = works[next++];
        try {
          const result = await fetchHoldings(work.isbn13List, libCodes);
          if (!cancelled) setAsOf(result.asOf);
          record(work.workId, result);
        } catch {
          // 한 권이 실패해도 나머지는 계속합니다. 실패는 확인 불가로 남기고
          // **절대 미소장으로 섞지 않습니다.**
          record(work.workId, { libCodes: [], complete: false, unreadable: true });
        }
      }
    });
    void Promise.all(workers);
    return () => {
      cancelled = true;
    };
  }, [state, selectedKey]);

  const byCode = useMemo(() => {
    const map = new Map<string, Library>();
    for (const library of libraries) map.set(library.libCode, library);
    return map;
  }, [libraries]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;

    setState({ kind: 'searching', query: trimmed });
    try {
      const response = await searchBooks(trimmed, [...selected]);
      setState({ kind: 'done', query: trimmed, response });
    } catch (error) {
      if (error instanceof ApiUnavailable) setState({ kind: 'offline' });
      else setState({ kind: 'error', message: (error as Error).message });
    }
  }

  return (
    <section className="results">
      <header className="picker__head">
        <h2>책 검색</h2>
        {selected.size === 0 && <span className="picker__count">도서관 미선택</span>}
      </header>

      <form className="search-bar" onSubmit={submit}>
        <input
          className="text-input"
          type="search"
          value={query}
          placeholder="책 제목"
          onChange={(e) => setQuery(e.target.value)}
          aria-label="책 제목"
        />
        <button className="button" type="submit" disabled={state.kind === 'searching'}>
          {state.kind === 'searching' ? '찾는 중' : '검색'}
        </button>
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
        holdings={holdings}
        asOf={asOf}
      />
    </section>
  );
}

function SearchState({
  state,
  byCode,
  selectedCount,
  holdings,
  asOf,
}: {
  state: State;
  byCode: Map<string, Library>;
  selectedCount: number;
  holdings: Map<number, HoldingFacts>;
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
    return <p className="muted">「{state.query}」을(를) 찾고 있습니다.</p>;
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

  const { works } = state.response;
  if (works.length === 0) {
    return (
      <p className="muted">
        「{state.query}」으로 찾은 책이 없습니다. 띄어쓰기를 바꾸거나 부제를 빼고 다시
        해 보세요.
      </p>
    );
  }

  return (
    <>
      <p className="asof">
        {selectedCount > 0 && holdings.size < works.length ? (
          <>
            소장 확인 중 {holdings.size}/{works.length} · 출처: 도서관 정보나루
          </>
        ) : (
          <>소장 정보 {formatAsOf(asOf ?? state.response.asOf)} 조회 기준 · 출처: 도서관 정보나루</>
        )}
        {[...holdings.values()].some((facts) => !facts.complete) && (
          <>
            <br />
            <strong>일부 판본을 확인하지 못했습니다.</strong> 확인하지 못한 것을 미소장으로
            세지 않았습니다.
          </>
        )}
      </p>
      <ul className="book-list">
        {works.map((work) => (
          <BookCard
            key={work.workId}
            work={work}
            byCode={byCode}
            selectedCount={selectedCount}
            facts={holdings.get(work.workId) ?? null}
          />
        ))}
      </ul>
    </>
  );
}

function BookCard({
  work,
  byCode,
  selectedCount,
  facts,
}: {
  work: WorkResult;
  byCode: Map<string, Library>;
  selectedCount: number;
  /** 아직 도착하지 않았으면 null. **빈 결과와 구분해야 합니다.** */
  facts: HoldingFacts | null;
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

        <Holdings work={work} byCode={byCode} selectedCount={selectedCount} facts={facts} />
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
}: {
  work: WorkResult;
  byCode: Map<string, Library>;
  selectedCount: number;
  facts: HoldingFacts | null;
}) {
  // 판정은 holdingState 한 곳에서만 합니다. 화면마다 따로 판정하면 언젠가 한쪽이
  // 확인 불가를 미소장으로 그리게 되고, 그때는 아무도 눈치채지 못합니다.
  const state = holdingState(facts, selectedCount);

  if (state === 'pending') {
    // 노란 상자는 "확인 불가"에만 씁니다. 안내까지 같은 색으로 칠하면
    // 정작 확인하지 못한 책이 눈에 띄지 않습니다.
    //
    // **「고르지 않았다」와 「기다리는 중」을 갈라 놓습니다.** 답을 기다리는 중인데
    // "도서관을 고르면 확인합니다"라고 하면 이미 고른 사용자가 무엇을 해야 할지 모릅니다.
    return selectedCount === 0 ? (
      <p className="holding muted">도서관을 고르면 어디에 있는지 확인합니다.</p>
    ) : (
      <p className="holding muted">어디에 있는지 확인하고 있습니다…</p>
    );
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
