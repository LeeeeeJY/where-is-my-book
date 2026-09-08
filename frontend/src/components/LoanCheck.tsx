import { useState } from 'react';
import { fetchLoanStatus } from '../api';
import type { LoanStatus } from '../api';
import { LOAN_DISCLAIMER, loanPhrase } from '../domain/loanStatus';

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
 * <p><b>한 권 검색과 여러 권 확인이 같은 것을 씁니다.</b> 예전에는 한 권 검색에만 있어서
 * 여러 권 확인에서는 대출 상태를 볼 수 없었습니다. 한쪽에만 두면 반드시 갈립니다.
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

/**
 * 한 도서관에서 **거기 있는 책 전부**의 대출 상태를 한 번에 물어봅니다.
 *
 * <p>여러 권 확인에서 실제 행동은 「이 도서관에 가면 이 중에 몇 권을 빌릴 수 있나」입니다.
 * 책마다 도서관마다 따로 누르게 하면 그 답을 얻는 데 스무 번을 눌러야 합니다.
 *
 * <p><b>그래도 누를 때만, 그 도서관에 있는 책 수만큼만 부릅니다.</b> 도서관 스무 곳에 미리
 * 달면 (도서관 × 책)으로 호출이 폭발합니다. 한 곳을 골라 누르는 것은 그 곳에 갈 생각이라는
 * 뜻이고, 그때는 책 수만큼입니다. 차례로 부르므로 남의 서버를 몰아치지도 않습니다.
 *
 * <p>못 물어본 책은 「확인하지 못했습니다」로 남깁니다. 「빌릴 수 없다」와 섞지 않습니다.
 */
export function LibraryLoanSweep({ libCode, books }: { libCode: string; books: SweepBook[] }) {
  const [results, setResults] = useState<Map<string, LoanStatus | 'failed'>>(new Map());
  const [running, setRunning] = useState(false);
  const started = running || results.size > 0;

  if (books.length === 0) return null;

  async function run() {
    setRunning(true);
    const next = new Map<string, LoanStatus | 'failed'>();
    for (const book of books) {
      try {
        next.set(book.key, await fetchLoanStatus(libCode, book.isbn13List));
      } catch {
        next.set(book.key, 'failed');
      }
      // 도착하는 대로 그 줄만 채웁니다. 다 끝날 때까지 빈 채로 두면 멈춘 것처럼 보입니다.
      setResults(new Map(next));
    }
    setRunning(false);
  }

  if (!started) {
    return (
      <button type="button" className="link-button" title={LOAN_DISCLAIMER} onClick={() => void run()}>
        이 도서관에서 빌릴 수 있는지 확인 ({books.length}권)
      </button>
    );
  }

  return (
    <div className="loan-sweep">
      <ul className="loan-sweep__list">
        {books.map((book) => {
          const result = results.get(book.key);
          return (
            <li key={book.key}>
              <span className="loan-sweep__title">{book.title}</span>
              {result === undefined ? (
                <span className="loan muted">확인 중</span>
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
      <p className="loan-sweep__note muted">{LOAN_DISCLAIMER}</p>
    </div>
  );
}
