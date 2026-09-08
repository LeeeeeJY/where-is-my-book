import { useMemo, useRef, useState } from 'react';
import { ApiUnavailable, fetchHoldings, libraryLink, resolveLines } from '../api';
import { linkLabel } from '../domain/opacLink';
import type { LineResult, WorkResult } from '../api';
import { holdingState } from '../domain/holdingState';
import { countByState, fullCoverage, planTrip, rankLibraries } from '../domain/tripPlan';
import type { BookRow } from '../domain/tripPlan';
import type { Library, UserPosition } from '../domain/types';
import { FullCoverage } from './FullCoverage';
import { LibraryLoanSweep, LoanCheck } from './LoanCheck';
import type { SweepBook } from './LoanCheck';
import { ProgressBar } from './ProgressBar';

/** 한 번에 확인할 수 있는 줄 수. 백엔드의 LineParser.MAX_LINES 와 같습니다. */
const MAX_LINES = 50;

/**
 * 동시에 보낼 소장 조회 수.
 *
 * <p>정보나루의 소장 조회는 한 번에 4~5초라 넷씩 물으면 서른 권에 몇 분이 걸립니다.
 * 정보나루에 한꺼번에 나가는 요청 수는 서버가 따로 묶어 두므로, 여기서 늘리는 것은 우리
 * 서버로 가는 요청일 뿐입니다.
 */
const CONCURRENCY = 8;

type Row = LineResult & {
  /** 모호한 줄에서 사용자가 고른 후보 */
  chosen: number;
  /**
   * 사용자가 실제로 골랐는지.
   *
   * <p>{@code chosen} 만으로는 알 수 없습니다. 처음부터 0이라 「첫 후보를 골랐다」와
   * 「아직 안 골랐다」가 같은 값입니다. 이것을 구분하지 않으면 골라 놓아도 배지가
   * 「골라 주세요」에 머물러, 사용자는 자기가 누른 것이 먹히지 않았다고 읽습니다.
   */
  picked: boolean;
  holdings: { libCodes: string[]; complete: boolean; unreadable: boolean } | null;
  /** 조회를 시도했지만 답을 받지 못했는지 */
  failed: boolean;
  /**
   * 이 줄만 따로 조회하는 중인지. 후보를 바꾸면 그 줄만 다시 묻는데, 그동안 「위의 확인을
   * 누르면」이라고 적혀 있었습니다. 눌러야 하는 것이 아니라 기다리면 되는 것입니다.
   */
  loading: boolean;
};

type Phase =
  | { kind: 'idle' }
  | { kind: 'resolving' }
  | { kind: 'ready'; truncated: boolean }
  | { kind: 'offline' }
  | { kind: 'error'; message: string };

/**
 * 여러 권을 한 번에 확인하는 화면.
 *
 * <b>이것이 이 도구를 실제로 쓰게 만드는 화면입니다.</b> 한 번 방문해서 여러 권을 빌리는
 * 것이 실제 행동이고, 그 확인을 권수 × 도서관 수만큼 반복하는 것이 이 도구를 만든 이유입니다.
 */

/**
 * 입력 칸에 비쳐 보이는 예시.
 *
 * <b>넣을 수 있는 형태를 한 줄에 하나씩 보여 줍니다.</b> 같은 모양으로 다섯 줄을 적으면
 * 예시가 사실상 하나뿐이라, 제목만 넣어도 된다는 것도 ISBN 이 된다는 것도 알 수 없습니다.
 *
 * <p><b>서점 주소는 넣지 않습니다.</b> 붙여 넣으면 지금도 읽기는 하지만, 주소 안에서
 * ISBN13 을 찾아 쓰는 방식이라 <b>예스24나 알라딘처럼 자체 상품 번호만 쓰는 주소는
 * 읽지 못합니다.</b> 많이 쓰는 서점일수록 그렇습니다. 되는 경우가 드문 것을 예시로 세우면
 * 사용자는 안 되는 주소를 넣어 보고 고장이라고 여깁니다.
 *
 * <p>ISBN 은 <b>실제로 동작하는 값</b>입니다(「좀머 씨 이야기」, 열린책들). 따라 쳐 보는
 * 사람이 있으므로 지어내지 않습니다.
 */
const INPUT_EXAMPLES = [
  '레 미제라블',
  '마담 보바리 - 귀스타브 플로베르',
  '목로주점 - 에밀 졸라',
  '향수 - 파트리크 쥐스킨트',
  '9788932900209',
].join('\n');

export function MultiCheck({
  libraries,
  selected,
  position,
}: {
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
  /** 「내 주변」이 잡은 위치. 다 빌릴 수 있는 도서관이 여럿일 때 가까운 순으로 세웁니다. */
  position: UserPosition | null;
}) {
  const [text, setText] = useState('');
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [rows, setRows] = useState<Row[]>([]);
  const [asOf, setAsOf] = useState<string | null>(null);
  // 결과가 나오면 입력을 접되, 고치려는 사람은 다시 펼 수 있어야 합니다.
  const [editing, setEditing] = useState(false);
  /**
   * 지금 들고 있는 소장 결과가 **어느 도서관 선택 기준인지**.
   *
   * <p>이것을 안 들고 다니면 선택이 바뀐 뒤에도 예전 답을 그대로 보여 주게 됩니다.
   * 방금 체크를 푼 도서관이 소장 목록에 남고, 새로 고른 도서관은 물어본 적이 없는데도
   * 「없음」으로 나옵니다. 뒤엣것이 특히 나쁩니다. 실제로 있는 책을 없다고 답하는 것이라
   * 헛걸음을 만듭니다.
   */
  const [checkedKey, setCheckedKey] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const running = useRef<{ cancelled: boolean } | null>(null);

  // Set 은 렌더마다 새 객체로 올 수 있어 그대로 비교하면 늘 달라 보입니다.
  const selectedKey = useMemo(() => [...selected].sort().join(','), [selected]);

  const selectedLibraries = useMemo(
    () => libraries.filter((l) => selected.has(l.libCode)),
    [libraries, selected],
  );
  const byCode = useMemo(
    () => new Map(libraries.map((l) => [l.libCode, l])),
    [libraries],
  );

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const lines = text.split('\n').filter((line) => line.trim().length > 0);
    if (lines.length === 0) return;

    setPhase({ kind: 'resolving' });
    setRows([]);
    setAsOf(null);

    let resolved;
    try {
      resolved = await resolveLines(lines);
    } catch (error) {
      setPhase(error instanceof ApiUnavailable
        ? { kind: 'offline' }
        : { kind: 'error', message: (error as Error).message });
      return;
    }

    // 줄 해석은 외부 호출 없이 끝나므로 여기서 곧바로 목록을 그립니다.
    // 소장 조회는 아래에서 도착하는 대로 채워 넣습니다.
    const initial: Row[] = resolved.lines.map((line) => ({
      ...line, chosen: 0, picked: false, holdings: null, failed: false, loading: false,
    }));
    setRows(initial);
    setPhase({ kind: 'ready', truncated: resolved.truncated });

    void loadHoldings(initial, selectedKey);
  }

  /**
   * 확정된 줄부터 소장을 물어보고, 답이 오는 대로 그 줄만 갱신합니다.
   *
   * <p>**도서관을 체크할 때마다 부르지 않습니다.** 30권이면 체크 한 번에 30회가 나가고,
   * 열 곳을 고르는 동안 300회입니다. 화면은 계속 버벅이고 하루 호출 예산도 그만큼
   * 새어 나갑니다. 목록을 확인할 때 한 번 부르고, 그 뒤로 선택이 바뀌면 누를 때만 부릅니다.
   */
  async function loadHoldings(current: Row[], key: string) {
    if (running.current) running.current.cancelled = true;
    const token = { cancelled: false };
    running.current = token;

    setCheckedKey(key);
    const libCodes = key === '' ? [] : key.split(',');
    if (libCodes.length === 0) return;

    const jobs = current
      .map((row, index) => ({ row, index }))
      .filter(({ row }) => row.candidates.length > 0);

    setChecking(true);
    let next = 0;
    const workers = Array.from({ length: Math.min(CONCURRENCY, jobs.length) }, async () => {
      while (next < jobs.length && !token.cancelled) {
        const { row, index } = jobs[next++];
        const work = row.candidates[row.chosen] ?? row.candidates[0];
        try {
          const holdings = await fetchHoldings(work.isbn13List, libCodes);
          if (token.cancelled) return;
          // 서버가 답을 몇 시간 기억해 두므로 줄마다 날짜가 다를 수 있습니다. 가장 오래된
          // 날짜를 말해야 옛 답이 새 답처럼 읽히지 않습니다.
          setAsOf((prev) => (prev === null || holdings.asOf < prev ? holdings.asOf : prev));
          setRows((prev) => replace(prev, index, { holdings, failed: false }));
        } catch {
          // 한 권의 조회가 실패해도 나머지는 계속합니다.
          // 실패한 줄은 확인 불가로 남고, 미소장으로 섞이지 않습니다.
          if (token.cancelled) return;
          setRows((prev) => replace(prev, index, { holdings: null, failed: true }));
        }
      }
    });
    await Promise.all(workers);
    if (!token.cancelled) setChecking(false);
  }

  /** 지금 고른 도서관 기준으로 처음부터 다시 물어봅니다. */
  function recheck() {
    const cleared = rows.map((row) => ({ ...row, holdings: null, failed: false, loading: false }));
    setRows(cleared);
    void loadHoldings(cleared, selectedKey);
  }

  /** 들고 있는 답이 예전 선택 기준인지. 그러면 그대로 보여 주면 안 됩니다. */
  const stale = checkedKey !== null && checkedKey !== selectedKey;
  /** 고른 도서관은 있는데 그 기준으로 아직 확인하지 않은 상태. */
  const needsCheck =
    phase.kind === 'ready' && selected.size > 0 && !checking
    && (checkedKey === null || checkedKey !== selectedKey);

  // 기준이 다른 답은 없는 것으로 칩니다. 화면 곳곳에서 따로 판단하면 언젠가 한 곳이
  // 예전 답을 그대로 그리게 되고, 그때는 아무도 눈치채지 못합니다.
  const shown: Row[] = useMemo(
    () => (stale ? rows.map((row) => ({ ...row, holdings: null, failed: false })) : rows),
    [rows, stale],
  );

  function choose(index: number, candidate: number) {
    // 고른 책이 바뀌었으므로 그 줄만 다시 물어봅니다. 다만 도서관 선택이 이미 어긋나
    // 있으면 지금 물어봐야 그 줄만 기준이 달라집니다. 그때는 「다시 확인」에 맡깁니다.
    const row = rows[index];
    const work = row?.candidates[candidate];
    const willAsk = Boolean(work) && selected.size > 0 && !stale;
    setRows((prev) =>
      replace(prev, index, {
        chosen: candidate, picked: true, holdings: null, failed: false, loading: willAsk,
      }));
    if (!willAsk || !work) return;
    fetchHoldings(work.isbn13List, [...selected]).then(
      (holdings) => {
        setAsOf((prev) => (prev === null || holdings.asOf < prev ? holdings.asOf : prev));
        setRows((prev) => replace(prev, index, { holdings, failed: false, loading: false }));
      },
      () => setRows((prev) => replace(prev, index, { holdings: null, failed: true, loading: false })),
    );
  }

  const bookRows: BookRow[] = useMemo(
    () => shown.filter((row) => row.candidates.length > 0).map((row) => toBookRow(row, selected.size)),
    [shown, selected.size],
  );
  const counts = countByState(bookRows);
  const ranks = useMemo(() => rankLibraries(bookRows, selectedLibraries), [bookRows, selectedLibraries]);
  /** 도서관별 소장에서 대출 상태를 한 번에 물어볼 때, 줄 식별자로 고른 책을 되찾습니다. */
  const sweepBooks = useMemo(() => {
    const map = new Map<string, SweepBook>();
    for (const row of shown) {
      const work = row.candidates[row.chosen] ?? row.candidates[0];
      if (work) map.set(String(row.lineNo), { key: String(row.lineNo), title: work.title, isbn13List: work.isbn13List });
    }
    return map;
  }, [shown]);
  const plan = useMemo(() => planTrip(bookRows, selectedLibraries), [bookRows, selectedLibraries]);
  /** 어딘가에 있는 책 전부를 가진 도서관. 여럿이면 거리와 대출 상태로 세웁니다. */
  const full = useMemo(() => fullCoverage(bookRows, selectedLibraries), [bookRows, selectedLibraries]);
  /** 그 도서관들에서 대출 상태를 물어볼 책. 어딘가에 있는 것으로 확인된 책 전부입니다. */
  const heldBooks = useMemo(
    () => bookRows
      .filter((row) => row.state === 'held')
      .map((row) => sweepBooks.get(row.key))
      .filter((book): book is SweepBook => book !== undefined),
    [bookRows, sweepBooks],
  );
  const stillChecking = checking;

  // 쉰 줄 한도가 있는 화면이라 지금 몇 줄인지 그 자리에서 보여야 합니다.
  const lineCount = text.split('\n').filter((l) => l.trim() !== '').length;

  return (
    <section className={phase.kind === 'ready' ? 'results' : 'results results--fill'}>
      <header className="picker__head">
        <h2>여러 권 한 번에 확인</h2>
        <span className="picker__count">
          {selected.size === 0 ? '도서관 미선택' : `도서관 ${selected.size}곳`}
        </span>
      </header>

      {/*
        **결과가 없을 때는 입력 칸이 남은 높이를 채웁니다.** 예전에는 여섯 줄로 고정해 두어,
        쉰 줄까지 받는 화면인데 아래가 통째로 비어 있었습니다. 목록을 붙여 넣는 화면에서
        가장 중요한 것은 넣은 것이 한눈에 보이는 일입니다.
      */}
      {/*
        **결과가 나오면 입력을 접습니다.** 펼쳐 둔 채로는 정작 보러 온 결과가 화면 아래로
        밀려납니다. 목록을 고치려는 사람만 다시 폅니다.
      */}
      {phase.kind === 'ready' && !editing ? (
        <p className="multi-done muted">
          {lineCount}줄을 확인했습니다.{' '}
          <button type="button" className="link-button" onClick={() => setEditing(true)}>
            목록 고치기
          </button>
        </p>
      ) : (
        <form className={phase.kind === 'ready' ? 'multi-form' : 'multi-form multi-form--tall'}
              onSubmit={submit}>
          <textarea
            id="multi-input"
            className="text-input multi-input"
            value={text}
            placeholder={INPUT_EXAMPLES}
            onChange={(e) => setText(e.target.value)}
          />
          <div className="multi-form__foot">
            {/*
              **서점 주소는 안내하지 않습니다.** 붙여 넣으면 읽기는 하지만 되는 경우가
              드뭅니다. 자세한 이유는 INPUT_EXAMPLES 에 적어 두었습니다.
            */}
            <span className="muted">
              한 줄에 한 권씩. 제목만 넣어도 되고, 「제목 - 저자」로 적으면 더 정확합니다.
              ISBN 도 됩니다{lineCount > 0 && ` · ${lineCount}줄`}
            </span>
            <button className="button" type="submit" disabled={phase.kind === 'resolving'}>
              {phase.kind === 'resolving' ? '읽는 중' : '확인'}
            </button>
          </div>
        </form>
      )}

      {selected.size === 0 && (
        <p className="muted">도서관을 고르면 어디에 있는지까지 알려 드립니다.</p>
      )}

      {phase.kind === 'offline' && (
        <div className="banner banner--warn">
          <strong>검색 서버에 연결하지 못했습니다.</strong> 확인하지 못한 것이지 책이 없는
          것이 아닙니다.
        </div>
      )}
      {phase.kind === 'error' && (
        <div className="banner banner--warn">
          <strong>확인이 실패했습니다.</strong> {phase.message}
        </div>
      )}

      {phase.kind === 'ready' && (
        <>
          {phase.truncated && (
            <div className="banner banner--warn">
{MAX_LINES}줄까지만 확인했습니다. 나머지는 나눠서 넣어 주세요.
            </div>
          )}

          <Progress counts={counts} total={bookRows.length} asOf={asOf} checking={stillChecking} />

          {/*
            도서관을 체크할 때마다 부르지 않습니다. 30권이면 체크 한 번에 30회이고
            열 곳을 고르는 동안 300회입니다. 누를 때만 부릅니다.
          */}
          {needsCheck && (
            <p className="recheck">
              <button type="button" className="button button--quiet" onClick={recheck}>
                고른 도서관 {selected.size}곳에서 확인
              </button>
            </p>
          )}

          {plan.length > 0 && (
            <TripPanel
              plan={plan}
              full={full}
              books={heldBooks}
              position={position}
              checking={stillChecking}
              byCode={byCode}
              checkedTotal={counts.held + counts.none}
            />
          )}

          {ranks.length > 0 && (
            <LibraryRanks ranks={ranks} checking={stillChecking} byCode={byCode} sweepBooks={sweepBooks} />
          )}

          {/*
            **넣은 목록이 먼저입니다.** 사용자가 보러 온 것은 「내가 적은 책들이 어디에
            있는가」이고, 「없는 책」과 「고쳐야 하는 줄」은 그것을 본 다음에 챙기는
            나머지입니다. 순서를 뒤집어 두면 정작 찾은 결과가 화면 아래로 밀립니다.
          */}
          <h3 className="section-title">넣은 목록을 이렇게 읽었습니다</h3>
          <ul className="line-list">
            {shown.map((row, index) => (
              <LineRow
                key={row.lineNo}
                row={row}
                index={index}
                selectedCount={selected.size}
                byCode={byCode}
                onChoose={choose}
                checking={checking}
              />
            ))}
          </ul>

          <Leftovers rows={shown} selectedCount={selected.size} />
        </>
      )}
    </section>
  );
}

function Progress({
  counts, total, asOf, checking,
}: {
  counts: Record<string, number>;
  total: number;
  asOf: string | null;
  checking: boolean;
}) {
  const done = total - counts.pending;
  return (
    <>
      {/*
        진행 중에는 막대가 어디까지 왔는지 말합니다. 서른 권이면 정보나루 호출이 수십 번이라
        몇 초가 걸리는데, 숫자만 바뀌는 것보다 막대가 차오르는 편이 멈추지 않았다는 것을
        한눈에 보여 줍니다.
      */}
      {checking && <ProgressBar done={done} total={total} label="소장을 확인하는 중" />}
      <p className="asof">
        {checking
          ? `${total}권 중 ${done}권 확인됨`
          : `${total}권을 확인했습니다`}
        {asOf && ` · 소장 정보 ${formatAsOf(asOf)} 조회 기준`} · 출처: 도서관 정보나루
        {counts.unknown > 0 && (
          <>
            <br />
            <strong>{counts.unknown}권은 확인하지 못했습니다.</strong> 없다는 뜻이 아니므로
            아래 집계에서 빼 두었습니다.
          </>
        )}
      </p>
    </>
  );
}

/**
 * 「한 곳에서 다 빌리기」.
 *
 * "한 번 방문해서 여러 권을 빌린다"는 실제 행동에 가장 직접적으로 답하는 출력입니다.
 */
function TripPanel({
  plan, full, books, position, checking, byCode, checkedTotal,
}: {
  plan: ReturnType<typeof planTrip>;
  /** 어딘가에 있는 책 전부를 가진 도서관. 비어 있으면 여러 곳을 엮은 plan 을 보입니다. */
  full: Library[];
  /** 그 도서관들에서 대출 상태를 물어볼 책 */
  books: SweepBook[];
  position: UserPosition | null;
  checking: boolean;
  byCode: Map<string, Library>;
  /** 분모는 확인이 끝난 책 수입니다. 확인하지 못한 책을 "못 빌리는 책"으로 세면 안 됩니다. */
  checkedTotal: number;
}) {
  return (
    <div className="trip">
      <h3 className="section-title">
        한 곳에서 다 빌리기
        {checking && <span className="muted"> (확인 중이라 바뀔 수 있습니다)</span>}
      </h3>
      {/*
        **전부 가진 도서관이 있으면 그 도서관들을 전부 보여 줍니다.** 예전에는 이름 순으로
        한 곳만 골라 보여 줘서, 같은 책을 전부 가진 더 가까운 도서관이 있어도 알 수 없었습니다.
        실제로 갈 곳을 고르는 기준은 거리와 빌릴 수 있는지이므로 그 둘로 세웁니다.
        한 곳도 없을 때만 여러 곳을 엮은 순서를 보입니다.
      */}
      {full.length > 0 ? (
        <FullCoverage libraries={full} books={books} position={position} checking={checking} />
      ) : (
        <ol className="trip__list">
          {plan.map((step, index) => (
            <li key={step.libCode}>
              {/*
                「+」는 이 도서관을 더한다는 뜻이고, 뒤의 숫자는 거기까지 들렀을 때
                빌릴 수 있는 누적 권수입니다. 둘을 붙여 쓰면 "3권을 더 빌린다"로 읽힙니다.
                「1곳」 「2곳」 같은 차례 표시는 뺐습니다. 한 줄뿐일 때 아무 뜻이 없었습니다.
              */}
              <span>
                {index === 0 ? '' : '+ '}
                <a href={libraryLink(step.libCode)} target="_blank" rel="noreferrer">
                  {byCode.get(step.libCode)?.name ?? step.name}
                </a>
              </span>
              <strong className="trip__score">
                {step.cumulative}/{checkedTotal}권
              </strong>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function LibraryRanks({
  ranks, checking, byCode, sweepBooks,
}: {
  ranks: ReturnType<typeof rankLibraries>;
  checking: boolean;
  byCode: Map<string, Library>;
  /** 줄 식별자로 고른 책을 되찾는 표. 그 도서관에 있는 책의 대출 상태를 한 번에 물을 때 씁니다. */
  sweepBooks: Map<string, SweepBook>;
}) {
  const [expanded, setExpanded] = useState<string | null>(null);
  return (
    <>
      <h3 className="section-title">
        도서관별 소장
        {checking && <span className="muted"> (확인 중)</span>}
      </h3>
      <ul className="rank-list">
        {ranks.map((rank) => {
          const open = expanded === rank.libCode;
          const total = rank.held.length + rank.missing.length;
          return (
            <li key={rank.libCode} className="rank">
              <div className="rank__head">
                <button
                  className="rank__toggle"
                  aria-expanded={open}
                  onClick={() => setExpanded(open ? null : rank.libCode)}
                >
                  <span className={open ? 'caret caret--open' : 'caret'} aria-hidden>▸</span>
                  {byCode.get(rank.libCode)?.name ?? rank.name}
                </button>
                <strong className="rank__score">
                  {total}권 중 {rank.held.length}권
                </strong>
                <a href={libraryLink(rank.libCode)} target="_blank" rel="noreferrer">
                  도서관 홈페이지
                </a>
              </div>
              {open && (
                <div className="rank__detail muted">
                  <p>소장: {rank.held.join(' · ')}</p>
                  {rank.missing.length > 0 && <p>없음: {rank.missing.join(' · ')}</p>}
                  {/*
                    「이 도서관에 가면 이 중에 몇 권을 빌릴 수 있나」가 여러 권 확인의 실제
                    행동입니다. 누를 때만, 이 도서관에 있는 책 수만큼만 부릅니다. 확인이
                    끝나기 전에는 목록이 바뀌므로 열지 않습니다.
                  */}
                  {!checking && (
                    <LibraryLoanSweep
                      key={rank.heldKeys.join(',')}
                      libCode={rank.libCode}
                      books={rank.heldKeys
                        .map((key) => sweepBooks.get(key))
                        .filter((book): book is SweepBook => book !== undefined)}
                    />
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}

/**
 * 어디에도 없는 책과 확인하지 못한 책.
 *
 * <b>둘을 한 목록에 넣으면 안 됩니다.</b> 앞은 서점이나 전자책으로 넘어가야 하는 책이고,
 * 뒤는 다시 확인해 보면 있을 수 있는 책입니다. 행동이 다릅니다.
 */
function Leftovers({ rows, selectedCount }: { rows: Row[]; selectedCount: number }) {
  const withBook = rows.filter((row) => row.candidates.length > 0);
  const nowhere = withBook.filter((row) => toBookRow(row, selectedCount).state === 'none');
  const unknown = withBook.filter((row) => toBookRow(row, selectedCount).state === 'unknown');
  // 고칠 수 있는 줄과, 고칠 것이 없고 다시 해 보면 되는 줄을 갈라 놓습니다.
  const fixable = rows.filter(
    (row) => row.candidates.length === 0 && row.status !== 'LOOKUP_FAILED',
  );
  const notAsked = rows.filter((row) => row.status === 'LOOKUP_FAILED');

  if (nowhere.length === 0 && unknown.length === 0
      && fixable.length === 0 && notAsked.length === 0) return null;

  return (
    <div className="leftovers">
      {nowhere.length > 0 && (
        <div className="leftover">
          <h3 className="section-title">고른 도서관에 없는 책</h3>
          <p className="muted">{titlesOf(nowhere).join(' · ')}</p>
        </div>
      )}
      {unknown.length > 0 && (
        <div className="leftover leftover--unknown">
          <h3 className="section-title">확인하지 못한 책</h3>
          <p>
            {titlesOf(unknown).join(' · ')}
          </p>
          <p className="muted">없다는 뜻이 아닙니다. 잠시 후 다시 확인해 주세요.</p>
        </div>
      )}
      {notAsked.length > 0 && (
        <div className="leftover leftover--unknown">
          <h3 className="section-title">확인하지 못한 줄</h3>
          <ul>
            {notAsked.map((row) => (
              <li key={row.lineNo}>
                {row.lineNo}줄 「{row.raw}」
              </li>
            ))}
          </ul>
          <p className="muted">
            검색 자체가 되지 않았습니다. 그런 책이 없다는 뜻이 아니므로 줄을 고치지 마시고
            잠시 후 다시 확인해 주세요.
          </p>
        </div>
      )}
      {fixable.length > 0 && (
        <div className="leftover">
          <h3 className="section-title">고쳐야 하는 줄</h3>
          <ul className="muted">
            {fixable.map((row) => (
              <li key={row.lineNo}>
                {row.lineNo}줄 「{row.raw}」 — {row.explanation ?? '찾은 책이 없습니다.'}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function LineRow({
  row, index, selectedCount, byCode, onChoose, checking,
}: {
  row: Row;
  index: number;
  selectedCount: number;
  byCode: Map<string, Library>;
  onChoose: (index: number, candidate: number) => void;
  checking: boolean;
}) {
  const state = row.candidates.length > 0 ? toBookRow(row, selectedCount).state : null;
  const chosen = row.candidates[row.chosen];
  const [allPicks, setAllPicks] = useState(false);
  /*
    **골랐으면 후보를 접습니다.** 여섯 권을 확인하면 줄마다 후보가 열 개씩 남아 화면이
    끝없이 길어지고, 정작 어느 도서관에 있는지가 저 아래로 밀립니다. 고른 뒤에는 고른
    것 하나만 남기고, 다시 고르고 싶은 사람만 펼칩니다.
  */
  /*
    **처음부터 접어 둡니다.** 예전에는 확정되지 않은 줄이면 후보를 전부 펼쳐 놓았는데,
    한 줄에 카드가 열두 개씩 깔리면서 정작 「어디에 있는가」가 저 아래로 밀렸습니다.
    사용자가 보러 온 것은 후보 목록이 아니라 답입니다. 가장 잘 맞는 것 하나로 답하고,
    그게 아닐 때만 펼칩니다.
  */
  const collapsed = !allPicks;
  const shownCandidates = collapsed
    ? [row.candidates[row.chosen]].filter(Boolean)
    : row.candidates;

  return (
    <li className="line">
      <div className="line__head">
        <span className="line__no">{row.lineNo}</span>
        <span className="line__raw">{row.raw}</span>
        <StatusBadge
          status={row.status}
          state={state}
          picked={row.picked}
          heldLabel={`${row.holdings?.libCodes.length ?? 0}곳에 있음`}
        />
      </div>

      {/*
        **제목과 저자만으로는 어느 책인지 모릅니다.** 같은 제목의 번역본이 출판사마다
        있고, 사용자가 찾는 것은 대개 자기가 아는 그 판입니다. 표지와 출판사가 있으면
        한눈에 가려집니다.
      */}
      {/*
        **답이 먼저입니다.** 어느 도서관에 있는지가 이 화면의 목적이고, 어느 판으로 보았는지는
        그 답이 맞는지 가늠하는 근거입니다. 순서를 뒤집어 두면 후보 카드가 화면을 채우고
        정작 결과가 아래로 밀립니다.
      */}
      {chosen && (
        <LineHoldings
          row={row}
          work={chosen}
          selectedCount={selectedCount}
          byCode={byCode}
          checking={checking}
        />
      )}

      {row.status === 'AMBIGUOUS' && (
        <div className="picks">
          {!collapsed && (
            <p className="picks__ask muted">어느 책인가요? 후보 {row.candidates.length}개</p>
          )}
          <ul className="picks__list">
            {shownCandidates.map((candidate, i) => (
              <li key={candidate.workId}>
                <button
                  type="button"
                  className={i === row.chosen && row.picked ? 'pick pick--on' : 'pick'}
                  aria-pressed={i === row.chosen && row.picked}
                  onClick={() => onChoose(index, i)}
                >
                  {candidate.coverUrl ? (
                    <img
                      className="pick__cover"
                      src={candidate.coverUrl}
                      alt=""
                      loading="lazy"
                      // 표지 주소가 죽어 있을 수 있습니다. 깨진 그림 자리를 남기지 않습니다.
                      onError={(e) => { e.currentTarget.style.visibility = 'hidden'; }}
                    />
                  ) : (
                    <span className="pick__cover pick__cover--empty" aria-hidden="true" />
                  )}
                  <span className="pick__body">
                    <span className="pick__title">{candidate.title}</span>
                    <span className="pick__meta muted">
                      {[candidate.author, candidate.publisher].filter(Boolean).join(' · ')}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {/*
            **후보를 잘라 놓고 말하지 않으면 「내 책이 없다」로 읽힙니다.** 출판사가 다른
            번역본을 갈라 놓은 뒤로 고전은 후보가 스무 개를 넘습니다. 처음에는 몇 개만
            보여 주되 몇 개가 더 있는지 밝히고, 눌러서 전부 볼 수 있게 합니다.
          */}
          {row.candidates.length > 1 && (
            <button type="button" className="link-button" onClick={() => setAllPicks((v) => !v)}>
              {collapsed ? `이 책이 아닌가요? (후보 ${row.candidates.length}개)` : '후보 접기'}
            </button>
          )}
          {/*
            어떻게 읽었는지 항상 되돌려 보여 줍니다. 그래야 사용자가 스스로 고칠 수 있습니다.
            다만 **답보다 앞에 두지는 않습니다.** 이 화면에서 먼저 읽혀야 하는 것은 어디에
            있는가이고, 이것은 그 답이 맞는지 가늠하는 근거입니다.
          */}
          <p className="line__explain muted">
            {row.explanation}
            {row.mergedFrom.length > 0 && ` · ${row.mergedFrom.join(', ')}줄과 같은 책으로 보았습니다`}
          </p>
        </div>
      )}
    </li>
  );
}

function LineHoldings({
  row, work, selectedCount, byCode, checking,
}: {
  row: Row;
  work: WorkResult;
  selectedCount: number;
  byCode: Map<string, Library>;
  checking: boolean;
}) {
  const state = toBookRow(row, selectedCount).state;

  if (state === 'pending') {
    // 고르지 않은 것과, 기다리는 중인 것과, 선택이 바뀌어 아직 안 물어본 것은
    // 사용자가 할 일이 서로 다릅니다. 하나로 뭉치면 눌러야 하는데 기다리게 만듭니다.
    return (
      <p className="holding muted">
        {selectedCount === 0
          ? '도서관을 고르면 어디에 있는지 확인합니다.'
          : checking || row.loading
            ? '확인 중입니다.'
            : '위의 「확인」을 누르면 알려 드립니다.'}
      </p>
    );
  }
  if (state === 'unknown') {
    return (
      <p className="holding holding--unknown">
        <strong>확인 불가.</strong> 없다는 뜻이 아니라 알 수 없다는 뜻입니다.
      </p>
    );
  }
  if (state === 'none') {
    return <p className="holding holding--none">고른 도서관에는 없습니다.</p>;
  }

  const held = row.holdings?.libCodes ?? [];
  return (
    <div className="holding holding--held">
      <p className="holding__count">
        <strong className="holding__where">있는 곳 {held.length}곳</strong>
        {row.holdings && !row.holdings.complete && (
          <span className="muted"> · 확인하지 못한 판본이 있어 더 있을 수 있습니다.</span>
        )}
        {work.isbn13List.length > 1 && (
          <span className="muted"> · 판본 {work.isbn13List.length}개를 함께 조회했습니다.</span>
        )}
        {work.detailUrl && (
          <span>
            {' · '}
            <a href={work.detailUrl} target="_blank" rel="noreferrer">
              정보나루 책 정보
            </a>
          </span>
        )}
      </p>
      {/*
        **도서관마다 대출 상태를 누를 수 있습니다.** 예전에는 한 권 검색에만 있어서 여러 권
        확인에서는 어느 도서관에 있는지까지만 알 수 있었습니다. 한 권 검색과 같은 부품을
        쓰고, 누를 때만 그 도서관 하나만 물어봅니다.
      */}
      <ul className="holding__list holding__list--inline">
        {held.map((code) => (
          <li key={code} className="lib lib--inline">
            {/* 도서관 이름을 누르면 그 도서관으로 갑니다. 정보나루 책 정보는 따로 답니다. */}
            <a
              className="lib__name"
              href={libraryLink(code, work.isbn13List[0], work.title)}
              target="_blank"
              rel="noreferrer"
              title={linkLabel(byCode.get(code)?.linkKind)}
            >
              {byCode.get(code)?.name ?? code}
            </a>
            <LoanCheck libCode={code} isbn13List={work.isbn13List} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function StatusBadge({
  status, state, picked, heldLabel,
}: {
  status: LineResult['status'];
  state: BookRow['state'] | null;
  /** 모호한 줄에서 사용자가 실제로 골랐는지. */
  picked: boolean;
  /** 소장일 때 배지에 적을 말. 「3곳에 있음」처럼 숫자를 그 자리에서 보여 줍니다. */
  heldLabel: string;
}) {
  if (status === 'UNREADABLE') return <span className="badge badge--warn">읽지 못함</span>;
  if (status === 'LOOKUP_FAILED') return <span className="badge badge--warn">확인 불가</span>;
  if (status === 'NOT_FOUND') return <span className="badge badge--warn">책을 찾지 못함</span>;
  /*
    **배지는 소장 상태를 말합니다.** 예전에는 확정되지 않은 줄이면 「골라 주세요」를 띄워,
    어디에 있는지 이미 알아냈는데도 배지가 그것을 가렸습니다. 사용자가 이 화면에서 알고
    싶은 것은 「어디 있는가」이고, 「어느 판으로 보았는가」는 그 아래 「이 책이 아닌가요?」가
    이미 말하고 있습니다.
  */
  if (state === 'pending') return <span className="badge">확인 중</span>;
  if (state === 'unknown') return <span className="badge badge--warn">확인 불가</span>;
  if (state === 'held') return <span className="badge badge--ok">{heldLabel}</span>;
  if (state === 'none') return <span className="badge">없음</span>;
  // 아직 도서관을 고르지 않은 경우입니다. 그때만 어느 판인지 물어봅니다.
  if (status === 'AMBIGUOUS' && !picked) {
    return <span className="badge badge--warn">골라 주세요</span>;
  }
  return <span className="badge">확인 전</span>;
}

function toBookRow(row: Row, selectedCount: number): BookRow {
  const work = row.candidates[row.chosen] ?? row.candidates[0];
  // 조회를 시도했지만 답을 받지 못한 것은 확인 불가입니다. 아직 안 물어본 것과 다릅니다.
  const facts = row.failed
    ? { libCodes: [], complete: false, unreadable: true }
    : row.holdings;
  return {
    key: String(row.lineNo),
    title: work?.title ?? row.raw,
    state: holdingState(facts, selectedCount),
    holdingLibCodes: row.holdings?.libCodes ?? [],
  };
}

function titlesOf(rows: Row[]): string[] {
  return rows.map((row) => row.candidates[row.chosen]?.title ?? row.raw);
}

function replace(rows: Row[], index: number, patch: Partial<Row>): Row[] {
  return rows.map((row, i) => (i === index ? { ...row, ...patch } : row));
}

/** 표시는 전부 Asia/Seoul 기준입니다. */
function formatAsOf(asOf: string): string {
  const date = new Date(`${asOf}T00:00:00+09:00`);
  if (Number.isNaN(date.getTime())) return asOf;
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric', timeZone: 'Asia/Seoul',
  }).format(date);
}
