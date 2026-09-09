import { useCallback, useRef, useState } from 'react';
import { fetchLoanStatus } from '../api';
import type { LoanStatus } from '../api';
import { LOAN_DISCLAIMER, loanPhrase, loanTallyPhrase } from '../domain/loanStatus';
import type { LoanPhrase } from '../domain/loanStatus';

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
 *
 * <p><b>한 권 검색이 도서관마다 이것을 답니다.</b> 여러 권 검색은 (줄 × 도서관)마다 단추를
 * 달지 않고, 도서관 줄 하나에 {@link LibraryLoanSweep} 하나만 둡니다. 같은 문구 함수를
 * 쓰므로 두 화면의 표현이 갈리지 않습니다.
 */
export function LoanCheck({ libCode, isbn13List }: { libCode: string; isbn13List: string[] }) {
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

/** 한 도서관에서 물어볼 책 한 권. */
export type SweepBook = { key: string; title: string; isbn13List: string[] };

/** 한 권의 답. 못 물어본 것은 `'failed'` 로 남기고 「빌릴 수 없다」와 섞지 않습니다. */
export type SweepResult = LoanStatus | 'failed';

/**
 * 한 도서관에서 물어본 답들.
 *
 * <p>열쇠는 줄 번호가 아니라 <b>책의 ISBN 목록</b>입니다. 모호한 줄에서 후보를 바꾸면 같은
 * 줄이 다른 책이 되는데, 줄 번호로 기억해 두면 예전 책의 대출 상태가 새 책의 것처럼
 * 보입니다. ISBN 으로 기억하면 바뀐 책은 자연히 빗나가 다시 묻게 됩니다.
 */
export type SweepState = { results: Map<string, SweepResult>; running: boolean };

function isbnKey(book: SweepBook): string {
  return book.isbn13List.join(',');
}

/**
 * 도서관별 대출 조회의 상태를 <b>도서관 목록 전체에서 하나로</b> 들고 있습니다.
 *
 * <p>예전에는 펼친 도서관 안에 상태가 있어서, 다른 도서관을 펼치면 방금 물어본 답이
 * 통째로 사라졌습니다. 호출은 이미 나갔는데 화면만 잃는 것이라, 사용자는 같은 것을 다시
 * 눌러 예산을 두 번 씁니다. 답은 목록이 살아 있는 동안 남고, 새 목록을 넣을 때 비웁니다.
 *
 * <p><b>누를 때만, 아직 답이 없는 책만, 차례로 부릅니다.</b> 한 도서관에서 답이 있는
 * 책을 다시 묻지 않으므로 소장 확인이 늦게 도착해 책이 늘어도 늘어난 만큼만 나갑니다.
 */
export function useLoanSweeps() {
  const [sweeps, setSweeps] = useState<Map<string, SweepState>>(new Map());
  // 부르는 순간의 최신 상태를 보려고 거울을 둡니다. 클로저의 값은 한 렌더 전일 수 있습니다.
  const latest = useRef(sweeps);
  latest.current = sweeps;

  const reset = useCallback(() => setSweeps(new Map()), []);

  const run = useCallback(async (libCode: string, books: SweepBook[]) => {
    const current = latest.current.get(libCode);
    if (current?.running) return;
    const todo = books.filter((book) => !current?.results.has(isbnKey(book)));
    if (todo.length === 0) return;

    const patch = (running: boolean, key?: string, result?: SweepResult) =>
      setSweeps((prev) => {
        const before = prev.get(libCode) ?? { results: new Map(), running: false };
        const results = new Map(before.results);
        if (key !== undefined && result !== undefined) results.set(key, result);
        return new Map(prev).set(libCode, { results, running });
      });

    patch(true);
    for (const book of todo) {
      let result: SweepResult;
      try {
        result = await fetchLoanStatus(libCode, book.isbn13List);
      } catch {
        result = 'failed';
      }
      // 도착하는 대로 그 줄만 채웁니다. 다 끝날 때까지 빈 채로 두면 멈춘 것처럼 보입니다.
      patch(true, isbnKey(book), result);
    }
    patch(false);
  }, []);

  return { sweeps, run, reset };
}

/**
 * 한 도서관의 답을 한 줄로 모읍니다. 답이 하나도 없으면 null 이라 줄 자체를 만들지 않습니다.
 *
 * <p>물어본 책만 셉니다. 아직 안 물어본 책을 분모에 넣으면 「7권 중 2권 대출 가능」이
 * 되어 나머지 다섯 권을 못 빌리는 것처럼 읽힙니다.
 */
export function sweepTally(books: SweepBook[], state: SweepState | undefined): LoanPhrase | null {
  if (!state) return null;
  let asked = 0;
  let available = 0;
  let failed = 0;
  let asOf: string | null = null;
  for (const book of books) {
    const result = state.results.get(isbnKey(book));
    if (result === undefined) continue;
    asked += 1;
    if (result === 'failed') {
      failed += 1;
    } else {
      if (result.hasBook && result.loanAvailable) available += 1;
      asOf = result.asOf;
    }
  }
  if (asked === 0) return null;
  if (state.running) {
    return { text: `대출 상태 확인 중 ${asked}/${books.length}권`, caution: false };
  }
  return loanTallyPhrase(available, asked, failed, asOf);
}

/**
 * 한 도서관에서 **거기 있는 책 전부**의 대출 상태를 한 번에 물어봅니다.
 *
 * <p>여러 권 검색에서 실제 행동은 「이 도서관에 가면 이 중에 몇 권을 빌릴 수 있나」입니다.
 * 책마다 도서관마다 따로 누르게 하면 그 답을 얻는 데 스무 번을 눌러야 합니다.
 *
 * <p><b>그래도 누를 때만, 그 도서관에 있는 책 수만큼만 부릅니다.</b> 도서관 스무 곳에 미리
 * 달면 (도서관 × 책)으로 호출이 폭발합니다. 한 곳을 골라 누르는 것은 그 곳에 갈 생각이라는
 * 뜻이고, 그때는 책 수만큼입니다.
 *
 * <p>상태는 {@link useLoanSweeps} 가 들고 있고 이 컴포넌트는 그리기만 합니다. 못 물어본
 * 책은 「확인하지 못했습니다」로 남깁니다. 「빌릴 수 없다」와 섞지 않습니다.
 */
export function LibraryLoanSweep({
  books, state, onRun, disabled = false,
}: {
  books: SweepBook[];
  state: SweepState | undefined;
  onRun: () => void;
  /** 소장 확인이 아직 도는 중이면 책 목록이 바뀌므로 그동안은 누르지 못하게 합니다. */
  disabled?: boolean;
}) {
  if (books.length === 0) return null;

  const running = state?.running ?? false;
  const answered = books.filter((book) => state?.results.has(isbnKey(book)));
  const todo = books.length - answered.length;

  const button = todo > 0 && !running && (
    <button
      type="button"
      className="link-button"
      title={disabled ? '소장 확인이 끝나면 물어볼 수 있습니다.' : LOAN_DISCLAIMER}
      disabled={disabled}
      onClick={onRun}
    >
      {answered.length === 0
        ? `이 도서관에서 빌릴 수 있는지 확인 (${books.length}권)`
        : `나머지 ${todo}권도 확인`}
    </button>
  );

  if (answered.length === 0 && !running) return <>{button}</>;

  return (
    <div className="loan-sweep">
      <ul className="loan-sweep__list">
        {books.map((book) => {
          const result = state?.results.get(isbnKey(book));
          return (
            <li key={book.key}>
              <span className="loan-sweep__title">{book.title}</span>
              {result === undefined ? (
                <span className="loan muted">{running ? '확인 중' : '아직 안 물어봄'}</span>
              ) : result === 'failed' ? (
                <span className="loan muted">확인하지 못했습니다</span>
              ) : (
                <span className={loanPhrase(result).caution ? 'loan loan--caution' : 'loan'}>
                  {loanPhrase(result).text}
                </span>
              )}
            </li>
          );
        })}
      </ul>
      {button}
      <p className="loan-sweep__note muted">{LOAN_DISCLAIMER}</p>
    </div>
  );
}
