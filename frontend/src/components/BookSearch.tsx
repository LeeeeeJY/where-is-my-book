import { useMemo, useState } from 'react';
import { ApiUnavailable, libraryLink, searchBooks } from '../api';
import { resolveLink } from '../domain/opacLink';
import type { SearchResponse, WorkResult } from '../api';
import { holdingState } from '../domain/holdingState';
import type { Library } from '../domain/types';

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

      <SearchState state={state} byCode={byCode} selectedCount={selected.size} />
    </section>
  );
}

function SearchState({
  state,
  byCode,
  selectedCount,
}: {
  state: State;
  byCode: Map<string, Library>;
  selectedCount: number;
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

  const { works, asOf } = state.response;
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
        소장 정보 {formatAsOf(asOf)} 조회 기준 · 출처: 도서관 정보나루
        {!state.response.allHoldingsChecked && (
          <>
            <br />
            <strong>일부 판본을 확인하지 못했습니다.</strong> 확인하지 못한 것을 미소장으로
            세지 않았습니다.
          </>
        )}
      </p>
      <ul className="book-list">
        {works.map((work) => (
          <BookCard key={work.workId} work={work} byCode={byCode} selectedCount={selectedCount} />
        ))}
      </ul>
    </>
  );
}

function BookCard({
  work,
  byCode,
  selectedCount,
}: {
  work: WorkResult;
  byCode: Map<string, Library>;
  selectedCount: number;
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
            {checkedEveryEdition(work, selectedCount)
              ? `판본 ${work.isbn13List.length}개를 모두 조회했습니다.`
              : `이 저작에 판본 ${work.isbn13List.length}개가 묶여 있습니다.`}
          </p>
        )}

        <Holdings work={work} byCode={byCode} selectedCount={selectedCount} />
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
}: {
  work: WorkResult;
  byCode: Map<string, Library>;
  selectedCount: number;
}) {
  // 판정은 holdingState 한 곳에서만 합니다. 화면마다 따로 판정하면 언젠가 한쪽이
  // 확인 불가를 미소장으로 그리게 되고, 그때는 아무도 눈치채지 못합니다.
  const state = holdingState(
    {
      libCodes: work.holdingLibCodes,
      complete: work.holdingsComplete,
      unreadable: work.holdingsUnreadable,
    },
    selectedCount,
  );

  if (state === 'pending') {
    // 노란 상자는 "확인 불가"에만 씁니다. 안내까지 같은 색으로 칠하면
    // 정작 확인하지 못한 책이 눈에 띄지 않습니다.
    return <p className="holding muted">도서관을 고르면 어디에 있는지 확인합니다.</p>;
  }

  if (state === 'unknown') {
    return (
      <p className="holding holding--unknown">
        <strong>확인 불가.</strong>{' '}
        {work.holdingsUnreadable
          ? '조회가 실패했습니다. 없다는 뜻이 아니라 알 수 없다는 뜻입니다.'
          : '확인하지 못한 판본이 있어 미소장이라고 말할 수 없습니다.'}
      </p>
    );
  }

  if (state === 'none') {
    return <p className="holding holding--none">고른 도서관에는 없습니다.</p>;
  }

  const held = work.holdingLibCodes;
  return (
    <div className="holding holding--held">
      <p className="holding__count">
        <strong>{held.length}곳에 있습니다.</strong>
        {!work.holdingsComplete && ' 확인하지 못한 판본이 있어 더 있을 수 있습니다.'}
      </p>
      <ul className="holding__list">
        {held.map((code) => {
          const library = byCode.get(code);
          return (
            <li key={code}>
              {/*
                어느 단계의 링크인지 밝힙니다. 조용히 홈페이지로 보내면 사용자는
                검색 결과 자체가 틀렸다고 생각합니다.
              */}
              {(() => {
                const link = resolveLink(
                  library?.linkKind,
                  libraryLink(code, work.isbn13List[0], work.title),
                  work.detailUrl,
                );
                return (
                  <>
                    <a href={link.href} target="_blank" rel="noreferrer">
                      {library ? library.name : code}
                    </a>
                    <span className="muted"> {link.label}</span>
                  </>
                );
              })()}
            </li>
          );
        })}
      </ul>
      <p className="muted holding__note">
        대출 가능 여부는 도서관 페이지에서 확인해 주세요. 제공되는 대출 상태가 전날 기준이라
        여기에는 표시하지 않습니다.
      </p>
    </div>
  );
}

/** 저작에 묶인 판본을 빠짐없이 조회했는지. 조회를 아예 하지 않은 경우도 아닙니다. */
function checkedEveryEdition(work: WorkResult, selectedCount: number): boolean {
  const state = holdingState(
    {
      libCodes: work.holdingLibCodes,
      complete: work.holdingsComplete,
      unreadable: work.holdingsUnreadable,
    },
    selectedCount,
  );
  return state === 'held' || state === 'none' ? work.holdingsComplete : false;
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
