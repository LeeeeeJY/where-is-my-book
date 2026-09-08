import { useMemo, useState } from 'react';
import { ApiUnavailable, fetchHoldings, libraryLink, resolveLines } from '../api';
import { resolveLink } from '../domain/opacLink';
import type { LineResult, WorkResult } from '../api';
import { holdingState } from '../domain/holdingState';
import { countByState, planTrip, rankLibraries, toPlainText } from '../domain/tripPlan';
import type { BookRow } from '../domain/tripPlan';
import type { Library } from '../domain/types';

/** 한 번에 확인할 수 있는 줄 수. 백엔드의 LineParser.MAX_LINES 와 같습니다. */
const MAX_LINES = 50;

/** 동시에 보낼 소장 조회 수. 우리 서버와 정보나루 양쪽에 예의를 지킵니다. */
const CONCURRENCY = 4;

type Row = LineResult & {
  /** 모호한 줄에서 사용자가 고른 후보 */
  chosen: number;
  holdings: { libCodes: string[]; complete: boolean; unreadable: boolean } | null;
  /** 조회를 시도했지만 답을 받지 못했는지 */
  failed: boolean;
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
export function MultiCheck({
  libraries,
  selected,
}: {
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
}) {
  const [text, setText] = useState('');
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [rows, setRows] = useState<Row[]>([]);
  const [asOf, setAsOf] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

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
      ...line, chosen: 0, holdings: null, failed: false,
    }));
    setRows(initial);
    setPhase({ kind: 'ready', truncated: resolved.truncated });

    void loadHoldings(initial);
  }

  /** 확정된 줄부터 소장을 물어보고, 답이 오는 대로 그 줄만 갱신합니다. */
  async function loadHoldings(current: Row[]) {
    if (selected.size === 0) return;
    const libCodes = [...selected];

    const jobs = current
      .map((row, index) => ({ row, index }))
      .filter(({ row }) => row.candidates.length > 0);

    let next = 0;
    const workers = Array.from({ length: Math.min(CONCURRENCY, jobs.length) }, async () => {
      while (next < jobs.length) {
        const { row, index } = jobs[next++];
        const work = row.candidates[row.chosen] ?? row.candidates[0];
        try {
          const holdings = await fetchHoldings(work.isbn13List, libCodes);
          setAsOf(holdings.asOf);
          setRows((prev) => replace(prev, index, { holdings, failed: false }));
        } catch {
          // 한 권의 조회가 실패해도 나머지는 계속합니다.
          // 실패한 줄은 확인 불가로 남고, 미소장으로 섞이지 않습니다.
          setRows((prev) => replace(prev, index, { holdings: null, failed: true }));
        }
      }
    });
    await Promise.all(workers);
  }

  function choose(index: number, candidate: number) {
    setRows((prev) => replace(prev, index, { chosen: candidate, holdings: null, failed: false }));
    // 고른 책이 바뀌었으므로 그 줄만 다시 물어봅니다.
    const row = rows[index];
    const work = row?.candidates[candidate];
    if (!work || selected.size === 0) return;
    fetchHoldings(work.isbn13List, [...selected]).then(
      (holdings) => {
        setAsOf(holdings.asOf);
        setRows((prev) => replace(prev, index, { holdings, failed: false }));
      },
      () => setRows((prev) => replace(prev, index, { holdings: null, failed: true })),
    );
  }

  const bookRows: BookRow[] = useMemo(
    () => rows.filter((row) => row.candidates.length > 0).map((row) => toBookRow(row, selected.size)),
    [rows, selected.size],
  );
  const counts = countByState(bookRows);
  const ranks = useMemo(() => rankLibraries(bookRows, selectedLibraries), [bookRows, selectedLibraries]);
  const plan = useMemo(() => planTrip(bookRows, selectedLibraries), [bookRows, selectedLibraries]);
  const stillChecking = counts.pending > 0 && selected.size > 0;

  function copy() {
    navigator.clipboard
      .writeText(toPlainText(bookRows, selectedLibraries, asOf))
      .then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      }, () => setCopied(false));
  }

  return (
    <section className="results">
      <header className="picker__head">
        <h2>여러 권 한 번에 확인</h2>
        {selected.size === 0 && <span className="picker__count">도서관 미선택</span>}
      </header>

      <form onSubmit={submit}>
        <label className="muted" htmlFor="multi-input">
          한 줄에 한 권씩 넣어 주세요. 제목, ISBN, 서점 주소를 섞어도 됩니다. 최대 {MAX_LINES}줄입니다.
        </label>
        <textarea
          id="multi-input"
          className="text-input multi-input"
          rows={6}
          value={text}
          placeholder={'코스모스\n미움받을 용기\n9788934972464\n총 균 쇠 - 재레드 다이아몬드'}
          onChange={(e) => setText(e.target.value)}
        />
        <button className="button" type="submit" disabled={phase.kind === 'resolving'}>
          {phase.kind === 'resolving' ? '읽는 중' : '확인'}
        </button>
      </form>

      {selected.size === 0 && (
        <p className="muted">
          도서관을 고르지 않으면 책만 찾고 소장 여부는 확인하지 않습니다.
          도서관 선택에서 자주 가는 곳을 먼저 골라 주세요.
        </p>
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
              {MAX_LINES}줄까지만 확인했습니다. 나머지는 다음에 나눠서 넣어 주세요.
            </div>
          )}

          <Progress counts={counts} total={bookRows.length} asOf={asOf} checking={stillChecking} />

          {plan.length > 0 && (
            <TripPanel
              plan={plan}
              checking={stillChecking}
              byCode={byCode}
              checkedTotal={counts.held + counts.none}
            />
          )}

          {ranks.length > 0 && (
            <LibraryRanks ranks={ranks} checking={stillChecking} byCode={byCode} />
          )}

          <Leftovers rows={rows} selectedCount={selected.size} />

          <h3 className="section-title">넣은 목록을 이렇게 읽었습니다</h3>
          <ul className="line-list">
            {rows.map((row, index) => (
              <LineRow
                key={row.lineNo}
                row={row}
                index={index}
                selectedCount={selected.size}
                byCode={byCode}
                onChoose={choose}
              />
            ))}
          </ul>

          {bookRows.length > 0 && (
            <div className="share">
              <button className="button" onClick={copy} type="button">
                {copied ? '결과를 복사했습니다' : '결과 복사'}
              </button>
              <p className="muted">
                메모 앱에 붙여 넣어 도서관에 가져갈 수 있습니다. 확인하지 못한 책은 없는 책과
                따로 적힙니다.
              </p>
            </div>
          )}
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
  );
}

/**
 * 「한 곳에서 다 빌리기」.
 *
 * "한 번 방문해서 여러 권을 빌린다"는 실제 행동에 가장 직접적으로 답하는 출력입니다.
 */
function TripPanel({
  plan, checking, byCode, checkedTotal,
}: {
  plan: ReturnType<typeof planTrip>;
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
      <ol className="trip__list">
        {plan.map((step, index) => (
          <li key={step.libCode}>
            <span className="trip__count">{index + 1}곳</span>
            {/*
              「+」는 이 도서관을 더한다는 뜻이고, 뒤의 숫자는 거기까지 들렀을 때
              빌릴 수 있는 누적 권수입니다. 둘을 붙여 쓰면 "3권을 더 빌린다"로 읽힙니다.
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
    </div>
  );
}

function LibraryRanks({
  ranks, checking, byCode,
}: {
  ranks: ReturnType<typeof rankLibraries>;
  checking: boolean;
  byCode: Map<string, Library>;
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
  row, index, selectedCount, byCode, onChoose,
}: {
  row: Row;
  index: number;
  selectedCount: number;
  byCode: Map<string, Library>;
  onChoose: (index: number, candidate: number) => void;
}) {
  const state = row.candidates.length > 0 ? toBookRow(row, selectedCount).state : null;
  const chosen = row.candidates[row.chosen];

  return (
    <li className="line">
      <div className="line__head">
        <span className="line__no">{row.lineNo}</span>
        <span className="line__raw">{row.raw}</span>
        <StatusBadge status={row.status} state={state} />
      </div>

      {/* 어떻게 읽었는지 항상 되돌려 보여 줍니다. 그래야 사용자가 스스로 고칠 수 있습니다. */}
      <p className="line__explain muted">
        {row.explanation}
        {row.mergedFrom.length > 0 && ` · ${row.mergedFrom.join(', ')}줄과 같은 책으로 보았습니다`}
      </p>

      {row.status === 'AMBIGUOUS' && (
        <div className="chips">
          {row.candidates.map((candidate, i) => (
            <button
              key={candidate.workId}
              className={i === row.chosen ? 'chip chip--on' : 'chip'}
              onClick={() => onChoose(index, i)}
            >
              {candidate.title}
              <span className="muted"> {candidate.author ?? ''}</span>
            </button>
          ))}
        </div>
      )}

      {chosen && (
        <LineHoldings row={row} work={chosen} selectedCount={selectedCount} byCode={byCode} />
      )}
    </li>
  );
}

function LineHoldings({
  row, work, selectedCount, byCode,
}: {
  row: Row;
  work: WorkResult;
  selectedCount: number;
  byCode: Map<string, Library>;
}) {
  const state = toBookRow(row, selectedCount).state;

  if (state === 'pending') {
    return (
      <p className="holding muted">
        {selectedCount === 0 ? '도서관을 고르면 어디에 있는지 확인합니다.' : '확인 중입니다.'}
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
    <p className="holding holding--held">
      <strong>{held.length}곳:</strong>{' '}
      {held.map((code, i) => (
        <span key={code}>
          {i > 0 && ' · '}
          <a
            href={
              resolveLink(
                byCode.get(code)?.linkKind,
                libraryLink(code, work.isbn13List[0], work.title),
                work.detailUrl,
              ).href
            }
            target="_blank"
            rel="noreferrer"
            title={
              resolveLink(
                byCode.get(code)?.linkKind,
                libraryLink(code, work.isbn13List[0], work.title),
                work.detailUrl,
              ).label
            }
          >
            {byCode.get(code)?.name ?? code}
          </a>
        </span>
      ))}
      {row.holdings && !row.holdings.complete && (
        <span className="muted"> 확인하지 못한 판본이 있어 더 있을 수 있습니다.</span>
      )}
      {work.isbn13List.length > 1 && (
        <span className="muted"> · 판본 {work.isbn13List.length}개를 함께 조회했습니다.</span>
      )}
    </p>
  );
}

function StatusBadge({
  status, state,
}: {
  status: LineResult['status'];
  state: BookRow['state'] | null;
}) {
  if (status === 'UNREADABLE') return <span className="badge badge--warn">읽지 못함</span>;
  if (status === 'LOOKUP_FAILED') return <span className="badge badge--warn">확인 불가</span>;
  if (status === 'NOT_FOUND') return <span className="badge badge--warn">책을 찾지 못함</span>;
  if (status === 'AMBIGUOUS') return <span className="badge badge--warn">골라 주세요</span>;
  if (state === 'pending') return <span className="badge">확인 중</span>;
  if (state === 'unknown') return <span className="badge badge--warn">확인 불가</span>;
  if (state === 'held') return <span className="badge badge--ok">소장</span>;
  return <span className="badge">미소장</span>;
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
