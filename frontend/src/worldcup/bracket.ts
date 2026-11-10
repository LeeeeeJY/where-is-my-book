/**
 * 토너먼트의 대진. **화면도 저장소도 모르는 순수한 계산입니다.**
 *
 * <p>들고 다니는 것은 둘뿐입니다. 처음에 섞은 순서(`order`)와 지금까지 고른 책(`picks`).
 * 라운드도, 지금 몇 번째 대결인지도, 누가 떨어졌는지도 이 둘에서 매번 다시 셉니다.
 * 따로 적어 두면 새로 고쳤을 때 적어 둔 것과 실제 진행이 어긋날 수 있습니다.
 */
export type Progress = {
  v: 1;
  /** 섞은 순서. 앞에서부터 둘씩 짝이 됩니다. */
  order: string[];
  /** 대결마다 고른 책. 대결이 일어난 차례 그대로입니다. */
  picks: string[];
  /** 완주를 서버에 알렸는지. 새로 고칠 때마다 다시 보내지 않으려고 둡니다. */
  reported?: boolean;
};

export type Finish = {
  winner: string;
  runnerUp: string;
  /** 4강에서 떨어진 두 권. 4강이 없는 작은 대진이면 비어 있습니다. */
  semifinalists: string[];
};

export type Standing = {
  /** 지금 라운드에 남은 책 수. 32 → 16 → 8 → 4 → 2. */
  roundSize: number;
  /** 이 라운드에서 지금이 몇 번째 대결인지(0부터). */
  matchIndex: number;
  /** 이 라운드의 대결 수. */
  matchCount: number;
  /** 지금 견줄 두 권. 끝났으면 null. */
  pair: [string, string] | null;
  /** 지금까지의 승패. [이긴 책, 진 책]. */
  matches: [string, string][];
  finish: Finish | null;
};

/** 후보 수는 2의 거듭제곱이어야 둘씩 짝이 끝까지 맞습니다. */
export function isBracketSize(count: number): boolean {
  return count >= 2 && (count & (count - 1)) === 0;
}

/** 피셔-예이츠. 난수를 받아 두는 것은 시험에서 같은 순서를 다시 만들기 위해서입니다. */
export function shuffle<T>(items: readonly T[], random: () => number = Math.random): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

export function newProgress(bookIds: readonly string[], random?: () => number): Progress {
  return { v: 1, order: shuffle(bookIds, random), picks: [] };
}

/** 「32강」「16강」「8강」「4강」「결승」. */
export function roundLabel(roundSize: number): string {
  return roundSize === 2 ? '결승' : `${roundSize}강`;
}

/**
 * 지금 어디까지 왔는지 셉니다. **기록이 대진과 맞지 않으면 null 입니다.**
 *
 * <p>저장해 둔 기록은 믿을 수 없는 입력입니다. 주제의 책이 바뀐 뒤에 예전 기록이 남아
 * 있을 수 있고, 사람이 손으로 고칠 수도 있습니다. 맞지 않는 기록으로 이어 가면 없는 책이
 * 나오거나 대결이 끝나지 않습니다.
 */
export function standing(progress: Progress): Standing | null {
  if (!isBracketSize(progress.order.length)) return null;
  if (new Set(progress.order).size !== progress.order.length) return null;

  const matches: [string, string][] = [];
  let alive = progress.order;
  let used = 0;
  let semifinalists: string[] = [];
  let runnerUp = '';

  while (alive.length > 1) {
    const matchCount = alive.length / 2;
    const next: string[] = [];
    const fallen: string[] = [];
    for (let m = 0; m < matchCount; m++) {
      const a = alive[m * 2];
      const b = alive[m * 2 + 1];
      if (used >= progress.picks.length) {
        return {
          roundSize: alive.length,
          matchIndex: m,
          matchCount,
          pair: [a, b],
          matches,
          finish: null,
        };
      }
      const pick = progress.picks[used++];
      if (pick !== a && pick !== b) return null;
      const lost = pick === a ? b : a;
      matches.push([pick, lost]);
      next.push(pick);
      fallen.push(lost);
    }
    if (alive.length === 4) semifinalists = fallen;
    if (alive.length === 2) runnerUp = fallen[0];
    alive = next;
  }
  // 끝났는데 고른 것이 더 남아 있으면 기록이 대진보다 깁니다.
  if (used !== progress.picks.length) return null;

  return {
    roundSize: 1,
    matchIndex: 0,
    matchCount: 0,
    pair: null,
    matches,
    finish: { winner: alive[0], runnerUp, semifinalists },
  };
}

/** 저장된 기록이 이 주제의 것이 맞는지. 책이 하나라도 다르면 처음부터 다시 합니다. */
export function fitsTheme(progress: Progress, bookIds: readonly string[]): boolean {
  if (progress.order.length !== bookIds.length) return false;
  const known = new Set(bookIds);
  return progress.order.every((id) => known.has(id)) && standing(progress) !== null;
}

/** 한 번 고릅니다. 지금 견주는 두 권이 아니면 그대로 돌려줍니다. */
export function pick(progress: Progress, bookId: string): Progress {
  const now = standing(progress);
  if (!now?.pair || !now.pair.includes(bookId)) return progress;
  return { ...progress, picks: [...progress.picks, bookId] };
}
