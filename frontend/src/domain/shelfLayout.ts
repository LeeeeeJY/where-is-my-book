/**
 * 서가를 몇 줄이나 그릴지, 그 가운데 <b>지금 보이는 줄이 어디인지</b>를 셉니다.
 *
 * <h2>왜 전부 그리지 않나</h2>
 *
 * <p>도서관 한 곳이 20만 권입니다. 한 줄에 네 권이면 5만 줄이고, 그 전부를 DOM 에
 * 올리면 브라우저가 멈춥니다. <b>보이는 줄과 그 위아래 몇 줄만</b> 그리고 나머지는
 * 빈 높이로 자리만 잡아 둡니다.
 *
 * <h2>화면 코드에서 떼어 놓은 이유</h2>
 *
 * <p>여기서 한 줄만 틀려도 <b>스크롤할 때 책이 건너뛰거나 겹칩니다.</b> 그런데 그것은
 * 빠르게 밀 때만 잠깐 보여서 눈으로 잡기가 매우 어렵습니다. 수와 자리를 세는 일을
 * 화면에서 떼어 내면 시험으로 붙들 수 있습니다.
 */

/** 한 줄에 세우는 권수. 모바일 우선이라 좁은 화면에서도 네 권입니다. */
export const COLS = 4;

/**
 * 보이는 칸 위아래로 더 그려 두는 줄 수.
 *
 * <p>0 이면 밀자마자 빈 칸이 보입니다. 브라우저가 스크롤과 그리기를 같은 순간에
 * 맞춰 주지 않아서, 새 줄이 들어오는 것보다 눈이 먼저 도착합니다. 반대로 크게 잡으면
 * 한 번에 그리는 표지가 늘어 처음 여는 것이 느려집니다.
 */
export const OVERSCAN = 6;

export type Range = {
  /** 그릴 첫 줄(0부터). */
  from: number;
  /** 그리지 않을 첫 줄. {@link from} 과 같으면 그릴 것이 없습니다. */
  to: number;
};

/** 복본 수로 줄 수를 셉니다. */
export function rowCount(count: number): number {
  return Math.ceil(Math.max(0, count) / COLS);
}

/**
 * 지금 그려야 하는 줄의 범위.
 *
 * <p><b>범위를 서가 밖으로 넘기지 않습니다.</b> 맨 위에서 더 끌어올리거나 맨 아래에서
 * 더 끌어내리면(휴대폰의 탄성 스크롤) {@code scrollTop} 이 음수가 되거나 전체 높이를
 * 넘는데, 그대로 두면 없는 줄을 그리려다 빈 화면이 됩니다.
 */
export function visibleRows(
  scrollTop: number,
  viewportHeight: number,
  rowHeight: number,
  rows: number,
): Range {
  if (rowHeight <= 0 || rows <= 0) return { from: 0, to: 0 };

  const top = Math.max(0, scrollTop);
  const first = Math.floor(top / rowHeight) - OVERSCAN;
  const last = Math.ceil((top + Math.max(0, viewportHeight)) / rowHeight) + OVERSCAN;

  return {
    from: Math.max(0, Math.min(first, rows)),
    to: Math.max(0, Math.min(last, rows)),
  };
}

/**
 * 그 줄들을 그리려면 받아야 하는 조각 번호.
 *
 * <p>조각 하나에 {@code chunkSize} 권이 들어 있고 한 줄이 네 권이라, 줄 범위를 권
 * 범위로 바꾼 다음 조각 번호로 나눕니다. <b>한 줄이 두 조각에 걸칠 수 있습니다.</b>
 * 조각 크기가 네 권으로 나누어떨어지지 않으면 늘 그렇고, 그때 한 조각만 받으면 그
 * 줄의 뒷부분이 빈 채로 남습니다.
 */
export function chunksFor(range: Range, chunkSize: number): number[] {
  if (chunkSize <= 0 || range.to <= range.from) return [];

  const firstItem = range.from * COLS;
  const lastItem = range.to * COLS - 1;

  const out: number[] = [];
  for (let at = Math.floor(firstItem / chunkSize); at <= Math.floor(lastItem / chunkSize); at++) {
    out.push(at);
  }
  return out;
}

/**
 * 그 권이 있는 조각 번호와 그 안에서의 자리.
 *
 * <p>초성 색인이 「ㅅ 은 1,523번째」라고 알려 주면, 그 권을 보여 주려고 이 계산으로
 * 조각을 찾습니다.
 */
export function locate(index: number, chunkSize: number): { chunk: number; at: number } {
  if (chunkSize <= 0) return { chunk: 0, at: 0 };
  return { chunk: Math.floor(index / chunkSize), at: index % chunkSize };
}

/**
 * 그 권이 화면 가운데쯤 오도록 하는 스크롤 자리.
 *
 * <p><b>맨 위로 올리지 않습니다.</b> 초성을 눌러 간 자리가 화면 맨 윗줄이면 그 앞에
 * 무엇이 있었는지 보이지 않아, 서가에서 손가락으로 짚은 느낌이 나지 않습니다. 조금
 * 위를 남겨 두면 「여기부터」가 눈에 들어옵니다.
 */
export function scrollToRow(row: number, rowHeight: number, viewportHeight: number): number {
  const lead = Math.min(rowHeight * 1.5, Math.max(0, viewportHeight) * 0.25);
  return Math.max(0, row * rowHeight - lead);
}
