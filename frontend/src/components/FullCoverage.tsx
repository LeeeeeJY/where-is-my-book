import { useMemo, useState } from 'react';
import { fetchLoanStatus, libraryLink } from '../api';
import { LOAN_DISCLAIMER, loanTallyPhrase } from '../domain/loanStatus';
import { haversineKm } from '../domain/selection';
import type { Library, UserPosition } from '../domain/types';
import type { SweepBook } from './LoanCheck';
import { ProgressBar } from './ProgressBar';

/** 한 도서관에서 물어본 결과의 집계. */
type Tally = {
  /** 빌릴 수 있다고 나온 권수 */
  available: number;
  /** 답을 받은 권수(실패 포함) */
  asked: number;
  /** 물어보지 못한 권수. 「빌릴 수 없다」와 섞지 않습니다. */
  failed: number;
  /** 기준 날짜(어제). 한 권도 답을 못 받았으면 null */
  asOf: string | null;
};

/** 한 번에 몇 쌍(도서관 × 책)을 물어볼지. 누를 때만 도는 것이라 넷이면 충분합니다. */
const CONCURRENCY = 4;

/**
 * 한 곳에서 다 빌릴 수 있는 도서관이 여럿일 때의 순위.
 *
 * <p>「한 곳에서 다 빌리기」가 한 곳만 고르면, 같은 책을 전부 가진 다른 도서관이 있어도
 * 보이지 않습니다. 실제로 갈 곳을 고르는 기준은 <b>얼마나 가까운가</b>와 <b>가서 실제로
 * 빌릴 수 있는가</b>입니다. 그래서 전부 가진 도서관을 모두 보여 주고, 위치를 알면 가까운
 * 순으로 세우고, 누르면 대출 상태까지 물어 빌릴 수 있는 권수가 많은 순으로 다시 세웁니다.
 *
 * <p><b>대출 상태는 누를 때만, (도서관 × 책)만큼만 부릅니다.</b> 목록에 미리 달면 도서관
 * 스무 곳 × 책 서른 권이 되어 하루 한도가 금방 사라집니다. 여기서는 전부 가진 도서관만
 * 대상이라 보통 두세 곳입니다. 그리고 <b>기준 날짜(어제)를 값에서 떼지 않습니다.</b>
 *
 * <p>순위는 확인이 끝난 뒤 한 번만 바뀝니다. 답이 올 때마다 자리를 옮기면 읽던 곳이
 * 사라집니다.
 */
export function FullCoverage({
  libraries,
  books,
  position,
  checking,
}: {
  libraries: Library[];
  books: SweepBook[];
  position: UserPosition | null;
  /** 소장 확인이 아직 도는 중이면 목록이 바뀌므로 대출 상태를 묻지 않습니다. */
  checking: boolean;
}) {
  const [tallies, setTallies] = useState<Map<string, Tally>>(new Map());
  const [running, setRunning] = useState(false);
  const [done, setDone] = useState(false);

  const distanceOf = (library: Library): number | null =>
    position === null ? null : haversineKm(position.lat, position.lon, library);

  const ordered = useMemo(() => {
    const list = [...libraries];
    list.sort((a, b) => {
      // 대출 상태를 다 물어봤으면 빌릴 수 있는 권수가 많은 곳이 먼저입니다.
      if (done) {
        const availableA = tallies.get(a.libCode)?.available ?? -1;
        const availableB = tallies.get(b.libCode)?.available ?? -1;
        if (availableA !== availableB) return availableB - availableA;
      }
      // 그다음은 가까운 곳. 거리를 모르는 곳은 뒤로 보냅니다.
      const kmA = distanceOf(a);
      const kmB = distanceOf(b);
      if (kmA !== null && kmB !== null && kmA !== kmB) return kmA - kmB;
      if (kmA === null && kmB !== null) return 1;
      if (kmA !== null && kmB === null) return -1;
      return a.name.localeCompare(b.name, 'ko');
    });
    return list;
    // distanceOf 는 position 에서만 달라집니다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [libraries, tallies, done, position]);

  const totalPairs = libraries.length * books.length;
  const askedPairs = [...tallies.values()].reduce((sum, tally) => sum + tally.asked, 0);

  async function run() {
    setRunning(true);
    setDone(false);
    const next = new Map<string, Tally>(
      libraries.map((library) => [library.libCode, { available: 0, asked: 0, failed: 0, asOf: null }]),
    );
    setTallies(snapshot(next));
    const jobs = libraries.flatMap((library) => books.map((book) => ({ library, book })));
    let at = 0;
    const workers = Array.from({ length: Math.min(CONCURRENCY, jobs.length) }, async () => {
      while (at < jobs.length) {
        const { library, book } = jobs[at++];
        const tally = next.get(library.libCode)!;
        try {
          const status = await fetchLoanStatus(library.libCode, book.isbn13List);
          tally.asked += 1;
          if (status.hasBook && status.loanAvailable) tally.available += 1;
          tally.asOf = status.asOf;
        } catch {
          // 못 물어본 것은 못 물어봤다고 말합니다. 「빌릴 수 없다」와 섞지 않습니다.
          tally.asked += 1;
          tally.failed += 1;
        }
        setTallies(snapshot(next));
      }
    });
    await Promise.all(workers);
    setRunning(false);
    setDone(true);
  }

  if (libraries.length === 0 || books.length === 0) return null;

  return (
    <div className="coverage">
      <p className="coverage__lead muted">
        {libraries.length === 1
          ? '이 도서관 한 곳에 전부 있습니다.'
          : `${libraries.length}곳에 전부 있습니다. ${
              position === null
                ? '「내 주변」에서 위치를 잡으면 가까운 곳부터 보여 드립니다.'
                : done
                  ? '빌릴 수 있는 권수가 많은 곳, 그다음 가까운 곳 순입니다.'
                  : '가까운 곳부터입니다.'
            }`}
      </p>
      <ol className="coverage__list">
        {ordered.map((library) => {
          const km = distanceOf(library);
          const tally = tallies.get(library.libCode);
          const phrase =
            tally === undefined
              ? null
              : tally.asked < books.length && !done
                ? { text: `확인 중 ${tally.asked}/${books.length}권`, caution: false }
                : loanTallyPhrase(tally.available, books.length, tally.failed, tally.asOf);
          return (
            <li key={library.libCode} className="coverage__item">
              <a href={libraryLink(library.libCode)} target="_blank" rel="noreferrer">
                {library.name}
              </a>
              {km !== null && <span className="muted"> · {formatKm(km)}</span>}
              {phrase && (
                <span className={phrase.caution ? 'loan loan--caution' : 'loan'}>
                  {' · '}
                  {phrase.text}
                </span>
              )}
            </li>
          );
        })}
      </ol>
      {running && (
        <ProgressBar done={askedPairs} total={totalPairs} label="대출 상태를 확인하는 중" />
      )}
      {!running && !done && !checking && (
        <button type="button" className="link-button" title={LOAN_DISCLAIMER} onClick={() => void run()}>
          {libraries.length === 1
            ? `이 도서관에서 빌릴 수 있는지 확인 (${books.length}권)`
            : `대출 상태로 순위 매기기 (${libraries.length}곳 × ${books.length}권 확인)`}
        </button>
      )}
      {done && <p className="coverage__note muted">{LOAN_DISCLAIMER}</p>}
    </div>
  );
}

/** React 가 바뀐 것을 알아보도록 새 Map 과 새 객체로 복사합니다. */
function snapshot(tallies: Map<string, Tally>): Map<string, Tally> {
  return new Map([...tallies].map(([code, tally]) => [code, { ...tally }]));
}

function formatKm(km: number): string {
  return km < 10 ? `${km.toFixed(1)}km` : `${Math.round(km)}km`;
}
