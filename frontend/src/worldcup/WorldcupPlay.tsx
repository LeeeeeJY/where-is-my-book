import { useEffect, useMemo, useRef, useState } from 'react';
import { navigate, paths } from '../domain/route';
import { fitsTheme, newProgress, pick, roundLabel, standing, type Progress } from './bracket';
import { BookCover } from './BookCover';
import { loadProgress, saveProgress } from './storage';
import { bookById, coverPath, themeById, type Book, type Theme } from './themes';
import { roundEvent, trackWorldcup } from './track';
import { WorldcupMissing } from './Worldcup';

/**
 * 고른 표지가 앞으로 나오는 동안. **이 시간이 지나면 다음 대결입니다.**
 *
 * <p>서른한 번을 고르는 화면이라 한 번에 0.5초를 쓰면 기다리는 시간만 15초가 넘습니다.
 * 「골랐다」가 눈에 들어올 만큼만 두고 넘깁니다. CSS 의 전환 시간(`.wc-duel .wc-cover`)이
 * 이 값보다 짧아야 끝까지 움직인 뒤에 바뀝니다.
 */
export const PICK_MS = 280;

function reducedMotion(): boolean {
  return typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export function WorldcupPlay({ themeId }: { themeId: string }) {
  const theme = themeById(themeId);
  if (!theme) return <WorldcupMissing what="없는 월드컵 주제입니다" />;
  return <Play theme={theme} />;
}

function Play({ theme }: { theme: Theme }) {
  const bookIds = useMemo(() => theme.books.map((book) => book.id), [theme]);

  /*
   * 저장해 둔 것이 이 주제의 대진과 맞으면 이어 가고, 아니면 새로 섞습니다.
   *
   * **여기서 저장하지 않습니다.** 초기값을 만드는 함수는 개발 모드에서 두 번 불리므로,
   * 안에서 저장하면 섞은 순서 둘 가운데 어느 것이 남을지 알 수 없습니다. 저장은 아래
   * 효과가 합니다.
   */
  const [{ progress, fresh }, setState] = useState<{ progress: Progress; fresh: boolean }>(() => {
    const saved = loadProgress(theme.id);
    if (saved && fitsTheme(saved, bookIds)) return { progress: saved, fresh: false };
    return { progress: newProgress(bookIds), fresh: true };
  });
  /** 방금 고른 책. 앞으로 나오는 동안에는 다시 누를 수 없습니다. */
  const [picked, setPicked] = useState<string | null>(null);
  const timer = useRef<number | null>(null);
  const started = useRef(false);

  const now = standing(progress);

  useEffect(() => saveProgress(theme.id, progress), [theme.id, progress]);

  // 새로 섞은 대진일 때만 「시작」을 셉니다. 새로 고쳐 이어 가는 것은 시작이 아닙니다.
  useEffect(() => {
    if (!fresh || started.current) return;
    started.current = true;
    trackWorldcup('start', theme.id);
  }, [fresh, theme.id]);

  // 이미 끝난 대진으로 이 주소에 들어왔으면 결과로 보냅니다.
  const winner = now?.finish?.winner ?? null;
  useEffect(() => {
    if (winner) navigate(paths.result(theme.id, winner), { replace: true });
  }, [winner, theme.id]);

  /*
   * 표지를 미리 받아 둡니다. 다음 대결로 넘어가는 순간 표지가 아직 오지 않았으면 빈 칸이
   * 먼저 보이고, 그 사이에 손가락이 내려갑니다. 서른두 장을 합쳐도 1MB 남짓입니다.
   */
  useEffect(() => {
    const images = bookIds.map((id) => {
      const image = new Image();
      image.src = coverPath(theme.id, id);
      return image;
    });
    return () => images.forEach((image) => (image.src = ''));
  }, [bookIds, theme.id]);

  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    },
    [],
  );

  if (!now || !now.pair) {
    // 끝난 대진입니다. 위의 효과가 결과 화면으로 보냅니다.
    return null;
  }

  const choose = (bookId: string) => {
    if (picked !== null) return;
    setPicked(bookId);
    const apply = () => {
      timer.current = null;
      const next = pick(progress, bookId);
      const after = standing(next);
      // 라운드가 바뀌는 순간에 한 번 셉니다. 되돌리는 길이 없어 두 번 세지 않습니다.
      if (after && !after.finish && after.matchIndex === 0) {
        const event = roundEvent(after.roundSize);
        if (event) trackWorldcup(event, theme.id);
      }
      if (after?.finish) trackWorldcup('complete', theme.id);
      setState({ progress: next, fresh: false });
      setPicked(null);
    };
    // 움직임을 줄여 달라고 한 사람에게는 기다리게 하지 않습니다.
    if (reducedMotion()) apply();
    else timer.current = window.setTimeout(apply, PICK_MS);
  };

  const [left, right] = now.pair.map((id) => bookById(theme, id)!) as [Book, Book];
  const total = bookIds.length - 1;
  const first = progress.picks.length === 0;

  return (
    <section className="results wc-card wc-play">
      <header className="wc-round">
        <p className="wc-round__theme muted">{theme.title}</p>
        <h2 className="wc-round__name">
          {roundLabel(now.roundSize)}
          <span className="wc-round__count">
            {now.matchIndex + 1}/{now.matchCount}
          </span>
        </h2>
        <div
          className="wc-meter"
          role="progressbar"
          aria-label="전체 진행"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={progress.picks.length}
        >
          <span style={{ width: `${(progress.picks.length / total) * 100}%` }} />
        </div>
      </header>

      {/* 처음 한 번만 무엇을 하는지 말합니다. 그 뒤로는 표지가 말합니다. */}
      <p className="wc-ask">{first ? '더 마음이 가는 책을 눌러 주세요.' : '둘 중 한 권을 골라 주세요.'}</p>

      {/* 대결마다 새로 그립니다. 앞 대결의 「골랐다」 표시가 다음 두 권에 남으면 안 됩니다. */}
      <div className="wc-duel" key={`${left.id}:${right.id}`} data-busy={picked !== null || undefined}>
        {[left, right].map((book) => (
          <button
            key={book.id}
            type="button"
            className="wc-duel__side"
            data-picked={picked === book.id || undefined}
            data-passed={(picked !== null && picked !== book.id) || undefined}
            disabled={picked !== null}
            onClick={() => choose(book.id)}
          >
            <span className="wc-duel__book">
              <BookCover themeId={theme.id} book={book} eager />
            </span>
            <span className="wc-duel__text">
              <span className="wc-duel__title">{book.title}</span>
              <span className="wc-duel__author muted">{book.author}</span>
            </span>
          </button>
        ))}
      </div>
    </section>
  );
}
