import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiUnavailable, fetchHoldings, libraryLink, resolveLines } from '../api';
import type { LineResult, WorkResult } from '../api';
import { olderAsOf } from '../domain/asOf';
import { holdingState } from '../domain/holdingState';
import { isbnsForLink, linkBadge, linkLabel } from '../domain/opacLink';
import { haversineKm } from '../domain/selection';
import { countByState, planTrip, rankLibraries } from '../domain/tripPlan';
import type { BookRow, BookState, LibraryRank, TripStep } from '../domain/tripPlan';
import type { Library, UserPosition } from '../domain/types';
import { LibraryLoanSweep, sweepTally, useLoanSweeps } from './LoanCheck';
import type { SweepBook, SweepState } from './LoanCheck';
import { ProgressBar } from './ProgressBar';

/** 한 번에 확인할 수 있는 줄 수. 백엔드의 LineParser.MAX_LINES 와 같습니다. */
const MAX_LINES = 50;

/**
 * 후보를 펼쳤을 때 한 번에 보여 주는 수. 한 권 검색의 묶음 크기와 같습니다.
 *
 * <p>서버가 후보를 한 권 검색과 같은 상한(100)까지 주므로, 한 번에 다 그리면 고전 한 줄이
 * 카드 백 개가 됩니다. 순서가 한 권 검색과 같아 찾는 판은 대개 앞쪽에 있습니다.
 */
const PICK_PAGE = 20;

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
   * 「아직 안 골랐다」가 같은 값입니다. 고른 책 카드는 어느 쪽이든 선택 표시를 달되,
   * 자동으로 고른 것과 직접 고른 것을 글자로 갈라 말합니다. 자동으로 고른 것을 표시하지
   * 않으면 사용자는 아무것도 고르지 않은 것으로 읽고, 직접 고른 것과 같게 말하면 자기가
   * 누른 것이 먹히지 않았다고 읽습니다.
   */
  picked: boolean;
  holdings: {
    libCodes: string[];
    heldIsbns?: Record<string, string[]>;
    complete: boolean;
    unreadable: boolean;
  } | null;
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
 * 입력 칸의 줄 수. 남은 높이를 채우지 않고 이만큼만 보여 줍니다. 더 긴 목록은 모서리를
 * 끌어 늘릴 수 있습니다.
 */
const INPUT_ROWS = 8;

/**
 * 입력 칸에 비쳐 보이는 예시.
 *
 * <b>다섯 줄이 저마다 다른 형태입니다.</b> 제목만, 「제목 - 저자」, 「제목 - 출판사」,
 * 「제목 / 저자」, ISBN. 같은 모양으로 여러 줄을 적으면 예시가 사실상 하나뿐이라, 제목만
 * 넣어도 된다는 것도 ISBN 이 된다는 것도 알 수 없습니다. <b>예전에는 다섯 줄 가운데 셋이
 * 「제목 - 저자」로 같았습니다.</b> 줄 수만 채우고 형태는 못 보여 준 셈이었습니다.
 *
 * <p><b>저자 줄과 출판사 줄은 생김새가 같지만 둘 다 세웁니다.</b> 되풀이로 보이지만
 * 되풀이가 아닙니다. 구분자 뒤에 오는 말이 <b>저자일 수도 출판사일 수도 있다</b>는 것은
 * 다른 줄로 보여 주지 않으면 알 방법이 없고, 그것을 모르면 출판사를 적어 볼 생각을 하지
 * 못합니다. <b>고전 번역서에서 판을 가르는 것은 저자가 아니라 출판사입니다.</b> 저작을
 * 출판사별로 갈라 놓았으므로 「마의 산 - 토마스 만」은 저자를 붙여도 후보가 줄지 않습니다.
 *
 * <p><b>책이 줄마다 다른 것도 의도한 것입니다.</b> 실제로 붙여 넣는 목록이 그렇게 생겼기
 * 때문입니다. 어디선가 옮겨 적은 목록은 줄마다 적은 방식이 제각각인데, 예시가 가지런하면
 * 「이 모양으로 맞춰서 넣어야 하나」로 읽힙니다. 예시는 비쳐 보이기만 하고 보내지지
 * 않으므로 줄이 합쳐지는 일도 없습니다.
 *
 * <p><b>목록 번호와 기호(「1.」, 「-」)는 예시로 세우지 않고 아래 안내 문구로 말합니다.</b>
 * {@link kr.wimb.query.LineParser} 가 그것을 떼어 내므로 지우지 않고 붙여 넣어도 되는데,
 * <b>예시는 모방의 대상이라</b> 번호가 비쳐 보이면 「번호를 붙여야 하나」라는 반대 방향의
 * 오해를 만듭니다. 흉내 낼 것은 예시로, 흉내 낼 필요가 없는 관용은 글로 말합니다.
 *
 * <p><b>저자 이름만 적은 줄은 넣지 않습니다.</b> 넣을 만해 보이지만 {@code LineParser} 에
 * 「저자만」이라는 형태가 없어서 <b>그 이름이 제목으로 넘어갑니다.</b> 실제로 배포된 서버에
 * 「빅토르 위고」를 물어보니 위고가 쓴 책은 <b>한 권도 없이</b> 평전과 학습만화가
 * 스물세 건 왔고, 여러 권 검색은 그 가운데 하나를 골라 확정합니다(2026-09-10 실측).
 * 「좀머 씨 이야기 - 파트리크 쥐스킨트」가 「파트리크 쥐스킨트 작품집」으로 확정되던 것과
 * 같은 사고입니다. 한 줄이 한 권으로 확정되는 화면이라, 여러 권을 가리키는 입력은 형태를
 * 먼저 만들지 않으면 예시로 세울 수 없습니다.
 *
 * <p><b>서점 주소도 넣지 않습니다.</b> 붙여 넣으면 지금도 읽기는 하지만, 주소 안에서
 * ISBN13 을 찾아 쓰는 방식이라 <b>예스24나 알라딘처럼 자체 상품 번호만 쓰는 주소는
 * 읽지 못합니다.</b> 많이 쓰는 서점일수록 그렇습니다. 되는 경우가 드문 것을 예시로 세우면
 * 사용자는 안 되는 주소를 넣어 보고 고장이라고 여깁니다.
 *
 * <p><b>다섯 줄 모두 실제로 책이 나오는 값입니다.</b> 앞의 넷은 2026-09-10 에 배포된
 * 서버로 확인했고, 「마의 산 - 을유문화사」는 `docs/가정과-검증상태.md` 2-3 의 실측에서
 * 가져왔습니다(을유문화사 판 `9788932403311`, `9788932403328` 이 실제로 있고
 * `publisher=을유문화사` 가 정보나루에서 동작합니다). 따라 쳐 보는 사람이 있으므로
 * <b>지어내지 마세요.</b> ISBN 은 「특성 없는 남자」(문학동네)이고, 바꿀 때는
 * `Isbn.isValidIsbn13` 과 `Isbn.isNotABookNumber` 로 먼저 확인하세요. 체크디지트가 틀린
 * 숫자는 제목으로 넘어가 0건이 나오고, 사용자는 그것을 「이 책이 없다」로 읽습니다.
 */
const INPUT_EXAMPLES = [
  '불안의 책',
  '생의 이면 - 이승우',
  '마의 산 - 을유문화사',
  '목로주점 1 / 에밀 졸라',
  '9788954691468',
].join('\n');

/**
 * 여러 권을 한 번에 검색하는 화면.
 *
 * <b>이것이 이 도구를 실제로 쓰게 만드는 화면입니다.</b> 한 번 방문해서 여러 권을 빌리는
 * 것이 실제 행동이고, 그 확인을 권수 × 도서관 수만큼 반복하는 것이 이 도구를 만든 이유입니다.
 *
 * <p><b>화면은 자료가 흐르는 순서대로 위에서 아래로 내려갑니다.</b> ① 목록을 넣고 → ② 줄마다
 * 어떤 책으로 읽었는지 확인하고(다른 후보로 바꾸면 그 줄만 다시 묻습니다) → ③ 그 책들이
 * 어느 도서관에 있는지 봅니다. 예전에는 ③ 이 ② 위에 있어서, 맨 아래에서 후보를 바꾼 뒤
 * 다시 위로 올라가 결과를 봐야 했습니다.
 *
 * <p><b>도서관에 관한 것은 전부 ③ 의 도서관 줄 하나에 모읍니다.</b> 그 도서관에 있는 책,
 * 없는 책, 대출 상태 확인, 홈페이지 링크가 거기 한 곳에만 있습니다. 예전에는 「한 곳에서
 * 다 빌리기」와 「도서관별 소장」과 줄마다의 소장 목록이 저마다 도서관 링크와 대출 확인
 * 단추를 달고 있어서, 같은 단추가 세 곳에 흩어져 있었습니다. ② 의 줄은 어느 도서관에
 * 있는지를 이름으로만 말하고 단추를 달지 않습니다.
 */
export function MultiCheck({
  libraries,
  selected,
  position,
}: {
  libraries: readonly Library[];
  selected: ReadonlySet<string>;
  /** 「내 주변」이 잡은 위치. 도서관 목록을 가까운 순으로 세우는 데 씁니다. */
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
  const sweeps = useLoanSweeps();

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

  // 「목록 고치기」를 누르면 입력 칸이 다시 펼쳐지는데, 아래쪽 줄에서 눌렀으면 입력 칸은
  // 화면 위에 있습니다. 초점을 옮겨 그 자리로 데려갑니다.
  useEffect(() => {
    if (editing) document.getElementById('multi-input')?.focus();
  }, [editing]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const lines = text.split('\n').filter((line) => line.trim().length > 0);
    if (lines.length === 0) return;

    setPhase({ kind: 'resolving' });
    setRows([]);
    setAsOf(null);
    sweeps.reset();

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
    setEditing(false);
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
          setAsOf((prev) => olderAsOf(prev, holdings.asOf));
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
        setAsOf((prev) => olderAsOf(prev, holdings.asOf));
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
  const distanceOf = useCallback(
    (library: Library) =>
      position === null ? null : haversineKm(position.lat, position.lon, library),
    [position],
  );
  const ranks = useMemo(
    () => rankLibraries(bookRows, selectedLibraries, position === null ? undefined : distanceOf),
    [bookRows, selectedLibraries, position, distanceOf],
  );
  /** 한 곳에 다 있는 도서관이 없을 때 여러 곳을 엮은 조합. */
  const plan = useMemo(() => planTrip(bookRows, selectedLibraries), [bookRows, selectedLibraries]);
  /** 도서관별 결과에서 줄 식별자로 고른 책을 되찾는 표. 대출 상태를 묻고 책 링크를 만드는 데 씁니다. */
  const sweepBooks = useMemo(() => {
    const map = new Map<string, SweepBook>();
    for (const row of shown) {
      const work = row.candidates[row.chosen] ?? row.candidates[0];
      if (work) {
        map.set(String(row.lineNo), {
          key: String(row.lineNo),
          title: work.title,
          isbn13List: work.isbn13List,
          heldIsbns: row.holdings?.heldIsbns,
        });
      }
    }
    return map;
  }, [shown]);

  // 쉰 줄 한도가 있는 화면이라 지금 몇 줄인지 그 자리에서 보여야 합니다.
  const lineCount = text.split('\n').filter((l) => l.trim() !== '').length;
  const withBook = shown.filter((row) => row.candidates.length > 0).length;
  const fixable = shown.filter(
    (row) => row.candidates.length === 0 && row.status !== 'LOOKUP_FAILED',
  ).length;
  const notAsked = shown.filter((row) => row.status === 'LOOKUP_FAILED').length;

  return (
    <section className="results">
      <header className="picker__head">
        <h2>여러 권 검색</h2>
        <span className="picker__count">
          {selected.size === 0 ? '도서관 미선택' : `도서관 ${selected.size}곳`}
        </span>
      </header>

      <StepTitle
        no={1}
        title="목록 넣기"
        sub={phase.kind === 'ready'
          ? undefined
          : '넣으면 ② 어떤 책인지 확인하고 ③ 어느 도서관에 있는지 알려 드립니다.'}
      />

      {/*
        **입력 칸은 여덟 줄입니다. 남은 높이를 채우지 않습니다.** 한때 결과가 없는 동안
        입력 칸이 화면의 남은 높이를 전부 차지하게 했는데, 넓은 화면에서는 그것이 수십 줄짜리
        빈 칸이 되어 실제로 「너무 크다」는 말을 들었습니다. 붙여 넣는 목록은 대개 몇 줄에서
        열 줄 남짓이라 여덟 줄이면 한눈에 들어오고, 더 긴 목록은 모서리를 끌어 늘릴 수
        있습니다(resize: vertical).
      */}
      {/*
        **결과가 나오면 입력을 접습니다.** 펼쳐 둔 채로는 정작 보러 온 결과가 화면 아래로
        밀려납니다. 목록을 고치려는 사람만 다시 폅니다.
      */}
      {phase.kind === 'ready' && !editing ? (
        <p className="multi-done muted">
          {lineCount}줄을 읽었습니다.{' '}
          <button type="button" className="link-button" onClick={() => setEditing(true)}>
            목록 고치기
          </button>
        </p>
      ) : (
        <form className="multi-form" onSubmit={submit}>
          <textarea
            id="multi-input"
            className="text-input multi-input"
            rows={INPUT_ROWS}
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
              한 줄에 한 권씩. 제목만 넣어도 되고, 「제목 - 저자」나 「제목 - 출판사」로
              적으면 더 정확합니다. ISBN 도 되고, 앞에 번호나 기호가 붙어 있어도 그대로
              넣으면 됩니다
              {lineCount > 0 && ` · ${lineCount}줄`}
            </span>
            <button className="button" type="submit" disabled={phase.kind === 'resolving'}>
              {phase.kind === 'resolving' ? '읽는 중' : '검색'}
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
          <strong>검색이 실패했습니다.</strong> {phase.message}
        </div>
      )}

      {phase.kind === 'ready' && (
        <>
          {phase.truncated && (
            <div className="banner banner--warn">
              {MAX_LINES}줄까지만 읽었습니다. 나머지는 나눠서 넣어 주세요.
            </div>
          )}

          <Progress counts={counts} total={bookRows.length} asOf={asOf} checking={checking} />

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

          {/*
            **줄 목록이 집계보다 먼저입니다.** 어떤 책으로 읽었는지가 맞아야 그 아래의
            도서관 결과가 뜻을 가집니다. 후보를 바꾸는 자리가 결과보다 아래에 있으면
            바꾼 뒤 다시 위로 올라가 확인해야 합니다.
          */}
          <StepTitle
            no={2}
            title="어떤 책인지 확인"
            sub={[
              `${withBook}권`,
              fixable > 0 && `${fixable}줄은 책을 찾지 못했습니다`,
              notAsked > 0 && `${notAsked}줄은 확인하지 못했습니다`,
            ].filter(Boolean).join(' · ')}
          />
          <ul className="line-list">
            {shown.map((row, index) => (
              <LineRow
                key={row.lineNo}
                row={row}
                index={index}
                selectedCount={selected.size}
                byCode={byCode}
                onChoose={choose}
                onEdit={() => setEditing(true)}
                checking={checking}
              />
            ))}
          </ul>

          <StepTitle
            no={3}
            title="어느 도서관에 있나"
            sub={checking ? '확인 중이라 바뀔 수 있습니다' : undefined}
          />
          <LibrarySection
            ranks={ranks}
            plan={plan}
            bookRows={bookRows}
            counts={counts}
            sweepBooks={sweepBooks}
            sweeps={sweeps.sweeps}
            onSweep={sweeps.run}
            checking={checking}
            needsCheck={needsCheck}
            selectedCount={selected.size}
            position={position}
            byCode={byCode}
          />
        </>
      )}
    </section>
  );
}

/**
 * 단계 제목. 번호를 달아 두는 것은 화면이 위에서 아래로 흐른다는 것을 눈으로 말하기
 * 위해서입니다. 「①」만 보고도 아래에 ②, ③ 이 이어진다는 것을 알 수 있습니다.
 */
function StepTitle({ no, title, sub }: { no: number; title: string; sub?: string }) {
  return (
    <h3 className="step-title">
      <span className="step-title__no" aria-hidden>{no}</span>
      <span className="step-title__text">
        <span className="visually-hidden">{no}단계. </span>
        {title}
      </span>
      {sub && <span className="step-title__sub muted">{sub}</span>}
    </h3>
  );
}

function Progress({
  counts, total, asOf, checking,
}: {
  counts: Record<BookState, number>;
  total: number;
  asOf: string | null;
  checking: boolean;
}) {
  const done = total - counts.pending;
  // 아직 아무것도 물어보지 않았거나(도서관 미선택), 선택이 바뀌어 들고 있던 답을 치운
  // 상태입니다. 그때 「확인했습니다」라고 적으면 치운 답을 아직 믿고 있는 것처럼 읽힙니다.
  if (total === 0 || (done === 0 && !checking)) return null;
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
 * ② 줄 하나. 넣은 글자, 어떤 책으로 읽었는지, 그 책이 어디에 있는지 순서입니다.
 *
 * <p><b>고른 책은 늘 카드로 보입니다.</b> 예전에는 확정된 줄은 카드가 아예 없었고, 모호한
 * 줄은 첫 후보를 자동으로 골라 놓고도 선택 표시를 달지 않았습니다. 그래서 무엇으로 읽었는지
 * 알 수 없거나, 골라져 있는데 안 골라진 것처럼 보였습니다.
 */
function LineRow({
  row, index, selectedCount, byCode, onChoose, onEdit, checking,
}: {
  row: Row;
  index: number;
  selectedCount: number;
  byCode: Map<string, Library>;
  onChoose: (index: number, candidate: number) => void;
  /** 「목록 고치기」. 입력 칸은 위에 있으므로 여기서 눌러도 그리로 갑니다. */
  onEdit: () => void;
  checking: boolean;
}) {
  const state = row.candidates.length > 0 ? toBookRow(row, selectedCount).state : null;
  const chosen = row.candidates[row.chosen];
  /*
    **후보는 처음부터 접어 둡니다.** 한 줄에 카드가 열두 개씩 깔리면 정작 「어디에
    있는가」가 저 아래로 밀립니다. 사용자가 보러 온 것은 후보 목록이 아니라 답입니다.
    가장 잘 맞는 것 하나로 답하고, 그게 아닐 때만 펼칩니다. 고르면 다시 접습니다.
  */
  const [allPicks, setAllPicks] = useState(false);
  /** 펼쳤을 때 몇 개까지 보여 줄지. 「후보 더 보기」가 늘립니다. */
  const [pickLimit, setPickLimit] = useState(PICK_PAGE);
  const others = row.candidates.length - 1;
  const visiblePicks = row.candidates.slice(0, pickLimit);
  const pendingLabel = selectedCount > 0 && (checking || row.loading) ? '확인 중' : '확인 전';

  return (
    <li className="line">
      <div className="line__head">
        <span className="line__no">{row.lineNo}</span>
        <span className="line__raw">{row.raw}</span>
        <StatusBadge
          status={row.status}
          state={state}
          pendingLabel={pendingLabel}
          heldLabel={`${row.holdings?.libCodes.length ?? 0}곳에 있음`}
        />
      </div>

      {chosen ? (
        <div className="line__body">
          <ChosenBook
            work={chosen}
            tag={row.status === 'AMBIGUOUS' ? (row.picked ? '직접 선택' : '자동 선택') : '확정'}
            tagTitle={row.status === 'AMBIGUOUS'
              ? row.picked
                ? '직접 고른 책입니다.'
                : '가장 잘 맞는 후보를 자동으로 골랐습니다. 아니면 「이 책이 아닌가요?」에서 바꿔 주세요.'
              : '제목이 그대로 맞아 이 책으로 확정했습니다.'}
          />

          <LineWhere row={row} selectedCount={selectedCount} byCode={byCode} checking={checking} />

          {/*
            어떻게 읽었는지 항상 되돌려 보여 줍니다. 그래야 사용자가 스스로 고칠 수 있습니다.
            **후보를 잘라 놓고 말하지 않으면 「내 책이 없다」로 읽힙니다.** 출판사가 다른
            번역본을 갈라 놓은 뒤로 고전은 후보가 스무 개를 넘습니다. 몇 개가 더 있는지
            밝히고, 눌러서 전부 볼 수 있게 합니다.
          */}
          <p className="line__explain muted">
            {row.explanation}
            {row.mergedFrom.length > 0 && ` · ${row.mergedFrom.join(', ')}줄과 같은 책으로 보았습니다`}
            {others > 0 && (
              <>
                {' · '}
                <button
                  type="button"
                  className="link-button link-button--inline"
                  aria-expanded={allPicks}
                  onClick={() => setAllPicks((v) => !v)}
                >
                  {allPicks ? '후보 접기' : `이 책이 아닌가요? (다른 후보 ${others}개)`}
                </button>
              </>
            )}
          </p>

          {allPicks && (
            <ul className="picks__list">
              {visiblePicks.map((candidate, i) => (
                <li key={candidate.workId}>
                  <button
                    type="button"
                    className={i === row.chosen ? 'pick pick--on' : 'pick'}
                    aria-pressed={i === row.chosen}
                    onClick={() => {
                      onChoose(index, i);
                      setAllPicks(false);
                    }}
                  >
                    <Cover url={candidate.coverUrl} />
                    <span className="pick__body">
                      <span className="pick__title">{candidate.title}</span>
                      <span className="pick__meta muted">
                        {[candidate.author, candidate.publisher].filter(Boolean).join(' · ')}
                      </span>
                    </span>
                    {i === row.chosen && <span className="pick__tag">✓ 지금 고른 책</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
          {/*
            **잘라 놓고 말하지 않으면 「내 책이 없다」로 읽힙니다.** 한 권 검색의 「더 보기」와
            같은 크기로 나눠 보여 주되, 몇 개가 더 있는지 밝힙니다.
          */}
          {allPicks && row.candidates.length > pickLimit && (
            <p className="picks__more muted">
              후보 {row.candidates.length}개 중 {pickLimit}개를 보고 있습니다.{' '}
              <button
                type="button"
                className="link-button link-button--inline"
                onClick={() => setPickLimit((n) => n + PICK_PAGE)}
              >
                후보 더 보기
              </button>
            </p>
          )}
        </div>
      ) : (
        /*
          책을 찾지 못한 줄은 여기서 바로 말합니다. 예전에는 화면 맨 아래 「고쳐야 하는 줄」에
          따로 모았는데, 고치는 자리(입력 칸)는 위에 있어서 아래에서 읽고 다시 위로 올라가야
          했습니다. **「고쳐야 하는 줄」과 「확인하지 못한 줄」은 여기서도 갈라 말합니다.**
          앞은 사용자가 고칠 수 있는 것이고 뒤는 고칠 것이 없는 것입니다.
        */
        <p className={row.status === 'LOOKUP_FAILED' ? 'line__body line__fail' : 'line__body line__explain muted'}>
          {row.status === 'LOOKUP_FAILED' ? (
            <>
              검색 자체가 되지 않았습니다. 그런 책이 없다는 뜻이 아니므로 줄을 고치지 마시고
              잠시 후 다시 검색해 주세요.
            </>
          ) : (
            <>
              {row.explanation ?? '찾은 책이 없습니다.'}
              {' · '}
              <button type="button" className="link-button link-button--inline" onClick={onEdit}>
                목록 고치기
              </button>
            </>
          )}
        </p>
      )}
    </li>
  );
}

/**
 * 고른 책 카드. 후보 단추와 같은 모양에 선택 표시를 얹습니다.
 *
 * <p>정보나루 책 정보 링크는 <b>책마다 여기 한 번만</b> 답니다. 도서관 링크는 ③ 에 있습니다.
 */
function ChosenBook({ work, tag, tagTitle }: { work: WorkResult; tag: string; tagTitle: string }) {
  return (
    <div className="pick pick--on pick--chosen">
      <Cover url={work.coverUrl} />
      <span className="pick__body">
        <span className="pick__title">{work.title}</span>
        <span className="pick__meta muted">
          {[work.author, work.publisher].filter(Boolean).join(' · ')}
          {work.isbn13List.length > 1 && ` · 판본 ${work.isbn13List.length}개`}
        </span>
      </span>
      <span className="pick__tag" title={tagTitle}>✓ {tag}</span>
      {/*
        칩을 격자(.pick__body)에 바로 두면 칸 폭만큼 늘어나 카드 너비의 단추가 됩니다.
        한 권 검색과 같은 줄(.book__links)에 담아 제 크기로 둡니다.

        **그 줄은 본문 칸 안이 아니라 카드의 둘째 줄입니다.** 본문 칸은 선택 표시 옆이라
        표시 너비만큼 좁은데, 표시는 한 줄뿐이라 그 아래는 비어 있습니다. 칩을 좁은 칸에
        두면 자리가 남는데도 두 줄로 접혔습니다(styles.css 의 `.pick--chosen` 참고).
      */}
      {work.detailUrl && (
        <span className="book__links">
          <a
            className="chip chip--sm chip--go"
            href={work.detailUrl}
            target="_blank"
            rel="noreferrer"
          >
            정보나루 책 정보
          </a>
        </span>
      )}
    </div>
  );
}

function Cover({ url }: { url: string | null }) {
  return url ? (
    <img
      className="pick__cover"
      src={url}
      alt=""
      loading="lazy"
      // 표지 주소가 죽어 있을 수 있습니다. 깨진 그림 자리를 남기지 않습니다.
      onError={(e) => { e.currentTarget.style.visibility = 'hidden'; }}
    />
  ) : (
    <span className="pick__cover pick__cover--empty" aria-hidden="true" />
  );
}

/**
 * 이 줄의 책이 어디에 있는지. <b>이름만 적고 단추는 달지 않습니다.</b> 도서관으로 가는 링크와
 * 대출 확인은 ③ 의 도서관 줄에 있습니다. 여기에도 달면 같은 단추가 (줄 × 도서관)만큼
 * 흩어집니다.
 */
function LineWhere({
  row, selectedCount, byCode, checking,
}: {
  row: Row;
  selectedCount: number;
  byCode: Map<string, Library>;
  checking: boolean;
}) {
  const state = toBookRow(row, selectedCount).state;

  if (state === 'pending') {
    // 고르지 않은 것과, 기다리는 중인 것과, 선택이 바뀌어 아직 안 물어본 것은
    // 사용자가 할 일이 서로 다릅니다. 하나로 뭉치면 눌러야 하는데 기다리게 만듭니다.
    return (
      <p className="line__where muted">
        {selectedCount === 0
          ? '도서관을 고르면 어디에 있는지 확인합니다.'
          : checking || row.loading
            ? '어디에 있는지 확인하는 중입니다.'
            : `위의 「고른 도서관 ${selectedCount}곳에서 확인」을 누르면 어디에 있는지 알려 드립니다.`}
      </p>
    );
  }
  if (state === 'unknown') {
    return (
      <p className="line__where holding--unknown">
        <strong>확인 불가.</strong> 없다는 뜻이 아니라 알 수 없다는 뜻입니다.
      </p>
    );
  }
  if (state === 'none') {
    return <p className="line__where holding--none">고른 도서관에는 없습니다.</p>;
  }

  const held = row.holdings?.libCodes ?? [];
  return (
    /*
      **도서관 하나가 한 줄입니다**(`.namelist`). 가운뎃점으로 이으면 한국어는 음절마다 줄이
      바뀔 수 있어 이름이 가운데서 끊기고, 어디까지가 한 도서관인지 읽히지 않습니다. 이 줄은
      「그래서 어디로 가면 되나」에 답하는 자리라 그것을 못 읽으면 아무 말도 하지 않은 셈입니다.
      이름표는 격자의 첫 칸이라 한 곳뿐일 때는 이름 옆에 그대로 섭니다.
    */
    <div className="line__where labeled">
      <span className="line__where-label labeled__label">있는 곳</span>
      <ul className="namelist">
        {held.map((code) => (
          <li key={code}>{byCode.get(code)?.name ?? code}</li>
        ))}
      </ul>
      {row.holdings && !row.holdings.complete && (
        <p className="labeled__note muted">확인하지 못한 판본이 있어 더 있을 수 있습니다.</p>
      )}
    </div>
  );
}

function StatusBadge({
  status, state, pendingLabel, heldLabel,
}: {
  status: LineResult['status'];
  state: BookState | null;
  /** 아직 답이 없을 때의 말. 도서관을 안 골랐거나 아직 안 눌렀으면 「확인 전」, 기다리는 중이면 「확인 중」. */
  pendingLabel: string;
  /** 소장일 때 배지에 적을 말. 「3곳에 있음」처럼 숫자를 그 자리에서 보여 줍니다. */
  heldLabel: string;
}) {
  if (status === 'UNREADABLE') return <span className="badge badge--warn">읽지 못함</span>;
  if (status === 'LOOKUP_FAILED') return <span className="badge badge--warn">확인 불가</span>;
  if (status === 'NOT_FOUND') return <span className="badge badge--warn">책을 찾지 못함</span>;
  /*
    **배지는 소장 상태를 말합니다.** 어느 판으로 보았는가는 바로 아래 카드의 「자동 선택」
    표시가 말하므로 여기서 다시 말하지 않습니다. 예전에는 도서관을 고르지 않았을 때도
    「확인 중」이라고 적혀 있었는데, 아무것도 확인하고 있지 않은데 기다리게 만드는 말입니다.
  */
  if (state === 'pending') return <span className="badge">{pendingLabel}</span>;
  if (state === 'unknown') return <span className="badge badge--warn">확인 불가</span>;
  if (state === 'held') return <span className="badge badge--ok">{heldLabel}</span>;
  if (state === 'none') return <span className="badge">없음</span>;
  return <span className="badge">확인 전</span>;
}

/**
 * ③ 어느 도서관에 있나. <b>도서관에 관한 것은 전부 여기 한 곳에 있습니다.</b>
 *
 * <p>맨 위 한 문장이 답입니다. 한 곳에 다 있으면 그 도서관을, 여럿이면 몇 곳인지를,
 * 한 곳도 없으면 어느 곳들을 엮어야 하는지를 말합니다. 그 아래 목록은 소장 권수 순이고
 * 전부 가진 곳에는 「전부 있음」이 붙습니다. 줄을 펼치면 있는 책과 없는 책, 대출 상태
 * 확인 단추, 홈페이지 링크가 나옵니다. 예전에는 이것들이 「한 곳에서 다 빌리기」와
 * 「도서관별 소장」과 줄마다의 소장 목록에 나뉘어 있었습니다.
 *
 * <p><b>대출 상태를 물어본 뒤에도 순서를 바꾸지 않습니다.</b> 예전 「한 곳에서 다 빌리기」는
 * 다 물어본 뒤 빌릴 수 있는 권수로 다시 세웠는데, 이제는 도서관마다 따로 누르므로 언제
 * 다 물어본 것인지가 없습니다. 답이 올 때마다 자리를 옮기면 읽던 곳이 사라집니다. 대신
 * 집계를 줄에 적어 두어 접어도 보이게 합니다.
 */
function LibrarySection({
  ranks, plan, bookRows, counts, sweepBooks, sweeps, onSweep, checking, needsCheck,
  selectedCount, position, byCode,
}: {
  ranks: LibraryRank[];
  plan: TripStep[];
  bookRows: BookRow[];
  counts: Record<BookState, number>;
  sweepBooks: Map<string, SweepBook>;
  sweeps: Map<string, SweepState>;
  onSweep: (libCode: string, books: SweepBook[]) => void;
  checking: boolean;
  needsCheck: boolean;
  selectedCount: number;
  position: UserPosition | null;
  byCode: Map<string, Library>;
}) {
  // 여러 곳을 펼쳐 놓고 견줄 수 있어야 합니다. 한 곳만 펼쳐지면 두 도서관의 대출 상태를
  // 번갈아 여닫으며 견주게 됩니다.
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (code: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });

  return (
    <div className="libs">
      <p className="libs__lead">
        <LibrarySummary
          ranks={ranks}
          plan={plan}
          counts={counts}
          checking={checking}
          needsCheck={needsCheck}
          selectedCount={selectedCount}
          position={position}
        />
      </p>

      {ranks.length > 0 && (
        <ul className="rank-list">
          {ranks.map((rank) => {
            const books = rank.heldKeys
              .map((key) => sweepBooks.get(key))
              .filter((book): book is SweepBook => book !== undefined);
            return (
              <LibraryRow
                key={rank.libCode}
                rank={rank}
                library={byCode.get(rank.libCode)}
                books={books}
                sweep={sweeps.get(rank.libCode)}
                onSweep={() => onSweep(rank.libCode, books)}
                open={open.has(rank.libCode)}
                onToggle={() => toggle(rank.libCode)}
                checking={checking}
              />
            );
          })}
        </ul>
      )}

      <Leftovers rows={bookRows} />
    </div>
  );
}

function LibrarySummary({
  ranks, plan, counts, checking, needsCheck, selectedCount, position,
}: {
  ranks: LibraryRank[];
  plan: TripStep[];
  counts: Record<BookState, number>;
  checking: boolean;
  needsCheck: boolean;
  selectedCount: number;
  position: UserPosition | null;
}) {
  if (selectedCount === 0) return <>도서관을 고르면 어디에 있는지 알려 드립니다.</>;
  if (needsCheck) {
    return <>도서관 선택이 바뀌었습니다. 위의 「고른 도서관 {selectedCount}곳에서 확인」을 누르면 알려 드립니다.</>;
  }
  if (ranks.length === 0) {
    if (checking) return <>확인하는 중입니다.</>;
    if (counts.held + counts.none + counts.unknown === 0) return <>확인할 책이 없습니다.</>;
    if (counts.unknown > 0 && counts.none === 0) return <>확인하지 못한 책뿐이라 아직 알 수 없습니다.</>;
    return <>고른 도서관에는 찾은 책이 한 권도 없습니다.</>;
  }

  const full = ranks.filter((rank) => rank.full);
  if (full.length === 1) {
    return (
      <>
        <strong>{full[0].name}</strong> 한 곳에 {counts.held}권이 전부 있습니다.
      </>
    );
  }
  if (full.length > 1) {
    return (
      <>
        <strong>{full.length}곳</strong>에 {counts.held}권이 전부 있습니다.{' '}
        {position === null
          ? '「내 주변」에서 위치를 잡으면 가까운 곳부터 세워 드립니다.'
          : '가까운 곳부터입니다.'}
      </>
    );
  }
  // 한 곳에 다 있는 도서관이 없을 때만 여러 곳을 엮습니다. 이름을 링크로 만들지 않는 것은
  // 아래 목록에 같은 도서관이 있기 때문입니다. 같은 링크를 두 번 달지 않습니다.
  const last = plan[plan.length - 1];
  return (
    <>
      한 곳에 다 있는 도서관은 없습니다.{' '}
      {plan.length > 1 && last && (
        <>
          <strong>{plan.map((step) => `${step.name} ${step.added}권`).join(' + ')}</strong>
          {`으로 ${last.cumulative}권을 전부 빌릴 수 있습니다.`}
        </>
      )}
    </>
  );
}

/**
 * 도서관 한 줄. 머리는 이름, 「전부 있음」, 거리, 권수뿐이고 눌러서 펼칩니다. 펼치면
 * 있는 책, 없는 책, 대출 상태 확인, 홈페이지 링크가 나옵니다.
 */
function LibraryRow({
  rank, library, books, sweep, onSweep, open, onToggle, checking,
}: {
  rank: LibraryRank;
  library: Library | undefined;
  books: SweepBook[];
  sweep: SweepState | undefined;
  onSweep: () => void;
  open: boolean;
  onToggle: () => void;
  checking: boolean;
}) {
  const total = rank.held.length + rank.missing.length;
  const tally = sweepTally(books, sweep);
  const name = library?.name ?? rank.name;
  /*
    **책 제목에 링크를 다는 것은 그 도서관의 책 페이지로 갈 수 있을 때뿐입니다.** 주소 규칙이
    없는 도서관은 어느 책을 눌러도 홈페이지가 열리는데, 그러면 같은 링크가 책 수만큼
    반복됩니다. 그때는 홈페이지 링크 하나만 두고 그 사실을 밝힙니다.
  */
  const bookLinks = linkBadge(library?.linkKind) !== '';

  return (
    <li className="rank">
      <button type="button" className="rank__head" aria-expanded={open} onClick={onToggle}>
        <span className={open ? 'caret caret--open' : 'caret'} aria-hidden>▸</span>
        <span className="rank__name">{name}</span>
        {rank.full && <span className="badge badge--ok">전부 있음</span>}
        {rank.km !== null && <span className="rank__km muted">{formatKm(rank.km)}</span>}
        <strong className="rank__score">{total}권 중 {rank.held.length}권</strong>
      </button>
      {tally && (
        <p className={tally.caution ? 'rank__tally loan loan--caution' : 'rank__tally loan'}>
          {tally.text}
        </p>
      )}
      {open && (
        <div className="rank__detail">
          {/*
            **책 하나가 한 줄입니다**(`.namelist`, ② 의 「있는 곳」과 같은 규칙). 제목을
            가운뎃점으로 이으면 한국어는 음절마다 줄이 바뀔 수 있어 제목이 가운데서 끊기고,
            어디까지가 한 권인지 읽히지 않습니다. 「거기 가면 무엇을 빌리나」에 답하는 자리라
            그것을 못 읽으면 목록이 아무 말도 하지 않습니다. 제목이 링크일 때는 더 그렇습니다.
            누를 자리가 두 줄에 걸쳐 끊겨 있으면 어느 것을 누르는지 알 수 없습니다.
          */}
          <div className="rank__books labeled">
            <span className="rank__books-label labeled__label">있음</span>
            <ul className="namelist">
              {books.map((book) => (
                <li key={book.key}>
                  {bookLinks ? (
                    <a
                      href={libraryLink(
                        rank.libCode,
                        isbnsForLink(book.heldIsbns?.[rank.libCode], book.isbn13List),
                        book.title,
                      )}
                      target="_blank"
                      rel="noreferrer"
                      title={linkLabel(library?.linkKind)}
                    >
                      {book.title}
                    </a>
                  ) : (
                    book.title
                  )}
                </li>
              ))}
            </ul>
          </div>
          {rank.missing.length > 0 && (
            <div className="rank__books muted labeled">
              <span className="rank__books-label labeled__label">없음</span>
              <ul className="namelist">
                {/* 제목만 오므로 같은 제목이 두 줄에서 나올 수 있습니다. 자리까지 열쇠에 넣습니다. */}
                {rank.missing.map((title, i) => (
                  <li key={`${i}-${title}`}>{title}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="rank__actions">
            {/*
              **채운 칩은 「그 도서관으로 간다」는 뜻입니다.** 한 권 검색의 도서관 이름과
              같은 모양이라, 두 화면에서 같은 색이 같은 말을 합니다.
            */}
            <a
              className="chip chip--strong chip--go"
              href={libraryLink(rank.libCode)}
              target="_blank"
              rel="noreferrer"
              title={linkLabel(library?.linkKind)}
            >
              도서관 홈페이지
            </a>
            {/*
              「이 도서관에 가면 이 중에 몇 권을 빌릴 수 있나」가 여러 권 검색의 실제
              행동입니다. 누를 때만, 이 도서관에 있는 책 수만큼만 부릅니다. 소장 확인이
              끝나기 전에는 목록이 바뀌므로 그동안은 누르지 못합니다. 링크를 앞에 두는
              것은 답이 목록으로 펼쳐진 뒤에도 링크가 같은 자리에 있게 하기 위해서입니다.
            */}
            <LibraryLoanSweep books={books} state={sweep} onRun={onSweep} disabled={checking} />
          </div>
          {!bookLinks && (
            <p className="rank__note muted">
              이 도서관은 책 페이지 주소 규칙이 아직 없어 홈페이지로 갑니다. 거기서 제목을
              검색해 주세요.
            </p>
          )}
        </div>
      )}
    </li>
  );
}

/**
 * 어디에도 없는 책과 확인하지 못한 책.
 *
 * <b>둘을 한 목록에 넣으면 안 됩니다.</b> 앞은 서점이나 전자책으로 넘어가야 하는 책이고,
 * 뒤는 다시 확인해 보면 있을 수 있는 책입니다. 행동이 다릅니다.
 *
 * <p>책을 찾지 못한 줄은 여기 없습니다. 고치는 자리가 위에 있으므로 ② 의 그 줄에서 바로
 * 말합니다.
 */
function Leftovers({ rows }: { rows: BookRow[] }) {
  const nowhere = rows.filter((row) => row.state === 'none');
  const unknown = rows.filter((row) => row.state === 'unknown');
  if (nowhere.length === 0 && unknown.length === 0) return null;

  return (
    <div className="leftovers">
      {/*
        여기도 **책 하나가 한 줄입니다**(`.namelist`). 위의 목록들과 같은 이유인데, 이름표가
        짧은 말이 아니라 제목 줄이라 `.labeled` 는 쓰지 않습니다. 목록만 그 아래에 둡니다.
      */}
      {nowhere.length > 0 && (
        <div className="leftover">
          <h4 className="section-title">고른 도서관에 없는 책 {nowhere.length}권</h4>
          <ul className="namelist muted">
            {nowhere.map((row) => (
              <li key={row.key}>{row.title}</li>
            ))}
          </ul>
        </div>
      )}
      {unknown.length > 0 && (
        <div className="leftover leftover--unknown">
          <h4 className="section-title">확인하지 못한 책 {unknown.length}권</h4>
          <ul className="namelist">
            {unknown.map((row) => (
              <li key={row.key}>{row.title}</li>
            ))}
          </ul>
          <p className="leftover__note muted">없다는 뜻이 아닙니다. 잠시 후 다시 확인해 주세요.</p>
        </div>
      )}
    </div>
  );
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

function replace(rows: Row[], index: number, patch: Partial<Row>): Row[] {
  return rows.map((row, i) => (i === index ? { ...row, ...patch } : row));
}

function formatKm(km: number): string {
  return km < 10 ? `${km.toFixed(1)}km` : `${Math.round(km)}km`;
}

/** 표시는 전부 Asia/Seoul 기준입니다. */
function formatAsOf(asOf: string): string {
  const date = new Date(`${asOf}T00:00:00+09:00`);
  if (Number.isNaN(date.getTime())) return asOf;
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric', timeZone: 'Asia/Seoul',
  }).format(date);
}
