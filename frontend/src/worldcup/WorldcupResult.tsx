import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchWorldcupStats, finishWorldcup, type WorldcupStats } from '../api';
import { navigate, paths } from '../domain/route';
import { fitsTheme, standing, type Finish } from './bracket';
import { BookCover } from './BookCover';
import { WORLDCUP_NAME } from './meta';
import { canShareFile, copyText, shareWithFile } from './share';
import { renderShareCard } from './shareCard';
import { rankValue, ranking, winnerLine } from './stats';
import { clearProgress, hasSubmitted, loadProgress, markSubmitted, saveProgress } from './storage';
import { bookById, coverPath, themeById, type Book, type Theme } from './themes';
import { trackWorldcup } from './track';
import { WorldcupMissing } from './Worldcup';

export function WorldcupResult({ themeId, bookId }: { themeId: string; bookId: string }) {
  const theme = themeById(themeId);
  const book = theme ? bookById(theme, bookId) : null;
  if (!theme) return <WorldcupMissing what="없는 월드컵 주제입니다" />;
  if (!book) return <WorldcupMissing what="이 월드컵에 없는 책입니다" />;
  return <Result theme={theme} winner={book} />;
}

/**
 * 이 탭에서 방금 끝낸 대진이 이 결과와 같은지.
 *
 * <p><b>주소만으로는 「내가 고른 결과」인지 「남이 보낸 링크」인지 알 수 없습니다.</b> 두
 * 사람이 같은 주소를 봅니다. 가르는 것은 이 탭에 남아 있는 진행 기록뿐이고, 그것이 끝까지
 * 가 있고 우승이 주소의 책과 같을 때만 본인의 결과로 봅니다. 공유받은 사람의 탭에는 그
 * 기록이 없습니다.
 */
function ownFinish(theme: Theme, bookId: string): { finish: Finish; matches: [string, string][]; reported: boolean } | null {
  const saved = loadProgress(theme.id);
  if (!saved || !fitsTheme(saved, theme.books.map((b) => b.id))) return null;
  const now = standing(saved);
  if (!now?.finish || now.finish.winner !== bookId) return null;
  return { finish: now.finish, matches: now.matches, reported: saved.reported === true };
}

/**
 * 같은 완주를 두 번 내지 않게 붙잡아 둡니다. 개발 모드는 효과를 두 번 돌리고, 그러면
 * 완주 한 번이 집계에 두 번 들어갑니다.
 */
const reporting = new Map<string, Promise<WorldcupStats>>();

type Loaded =
  | { kind: 'loading' }
  /** `counted` 는 이번 완주가 방금 집계에 더해졌는지. */
  | { kind: 'ready'; stats: WorldcupStats; counted: boolean }
  /** 받지 못했습니다. 통계 자리만 비우고 나머지는 그대로 둡니다. */
  | { kind: 'missing' };

type Card = { kind: 'drawing' } | { kind: 'ready'; file: File; url: string } | { kind: 'failed' };

function Result({ theme, winner }: { theme: Theme; winner: Book }) {
  // 처음 그릴 때 한 번 읽습니다. 「다시 하기」로 기록을 지우면 이 화면을 떠납니다.
  const [own] = useState(() => ownFinish(theme, winner.id));
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [card, setCard] = useState<Card>({ kind: 'drawing' });
  const [panel, setPanel] = useState(false);
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');
  const cardPromise = useRef<Promise<Card> | null>(null);

  const resultUrl = `${window.location.origin}${paths.result(theme.id, winner.id)}`;

  /*
   * 집계를 받습니다. 본인의 완주이고 아직 알리지 않았으면 알리면서 받습니다.
   *
   * **같은 브라우저에서 이미 낸 적이 있으면 다시 내지 않습니다.** 「다시 하기」를 여러 번
   * 누르는 보통의 사용이 순위표를 흔들지 않게 하는 정도의 장치입니다.
   */
  useEffect(() => {
    let cancelled = false;
    const shouldReport = own !== null && !own.reported && !hasSubmitted(theme.id);

    let request: Promise<WorldcupStats>;
    if (shouldReport) {
      const key = `${theme.id}:${own.matches.map(([w]) => w).join(',')}`;
      request =
        reporting.get(key) ??
        finishWorldcup(theme.id, winner.id, own.matches).then((stats) => {
          markSubmitted(theme.id);
          const saved = loadProgress(theme.id);
          if (saved) saveProgress(theme.id, { ...saved, reported: true });
          return stats;
        });
      reporting.set(key, request);
    } else {
      request = fetchWorldcupStats(theme.id);
    }

    request.then(
      (stats) => {
        if (!cancelled) setLoaded({ kind: 'ready', stats, counted: shouldReport });
      },
      () => {
        if (!cancelled) setLoaded({ kind: 'missing' });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [own, theme.id, winner.id]);

  /*
   * 저장용 카드를 미리 그려 둡니다. **누른 뒤에 그리기 시작하면 공유 창이 열리지 않는
   * 브라우저가 있습니다.** 공유 창은 사람이 누른 그 순간에만 열 수 있는데, 표지를 받고
   * 그리는 동안 그 순간이 지나가 버립니다.
   */
  const runnerUp = own ? bookById(theme, own.finish.runnerUp) : null;
  const semifinalists = useMemo(
    () => (own ? own.finish.semifinalists.map((id) => bookById(theme, id)).filter((b): b is Book => b !== null) : []),
    [own, theme],
  );

  useEffect(() => {
    if (!own) return;
    let url: string | null = null;
    let cancelled = false;
    const toCard = (book: Book) => ({
      title: book.title,
      author: book.author,
      cover: coverPath(theme.id, book.id),
    });
    const drawing: Promise<Card> = renderShareCard({
      themeTitle: theme.title,
      headline: '나의 인생 책',
      winner: toCard(winner),
      runnerUp: runnerUp ? toCard(runnerUp) : null,
      semifinalists: semifinalists.map(toCard),
      siteLabel: `${window.location.host}${paths.worldcup()}`,
    }).then(
      (blob): Card => {
        const file = new File([blob], `wimb-worldcup-${winner.id}.png`, { type: 'image/png' });
        url = URL.createObjectURL(file);
        return { kind: 'ready', file, url };
      },
      (): Card => ({ kind: 'failed' }),
    );
    cardPromise.current = drawing;
    void drawing.then((next) => {
      if (!cancelled) setCard(next);
    });
    return () => {
      cancelled = true;
      void drawing.then(() => {
        if (url) URL.revokeObjectURL(url);
      });
    };
  }, [own, theme, winner, runnerUp, semifinalists]);

  const findLibrary = () => {
    trackWorldcup('find_library', theme.id);
    navigate(paths.search(winner.searchTitle ?? winner.title));
  };

  const playAgain = () => {
    // 끝난 기록을 지우고 새로 섞습니다. 하다 만 대진이 있으면 그것을 이어 갑니다.
    const saved = loadProgress(theme.id);
    if (!saved || standing(saved)?.finish) clearProgress(theme.id);
    navigate(paths.play(theme.id));
  };

  const share = async () => {
    trackWorldcup('share', theme.id);
    const ready = card.kind === 'drawing' ? await cardPromise.current : card;
    if (ready?.kind === 'ready' && canShareFile(ready.file)) {
      const outcome = await shareWithFile({
        file: ready.file,
        title: `${WORLDCUP_NAME} · ${theme.title}`,
        text: `나의 인생 책은 《${winner.title}》`,
        url: resultUrl,
      });
      if (outcome !== 'failed') return;
    }
    // 공유 창이 없거나 열지 못했습니다. 저장과 복사 단추를 내놓습니다.
    setPanel(true);
  };

  const copy = async () => {
    setCopied((await copyText(resultUrl)) ? 'done' : 'failed');
  };

  const stats = loaded.kind === 'ready' ? loaded.stats : null;
  const line = stats
    ? winnerLine(stats, winner.id, {
        counted: loaded.kind === 'ready' && loaded.counted,
        own: own !== null,
      })
    : null;
  const rows = stats ? ranking(stats, theme) : [];

  return (
    <section className="results wc-card wc-result">
      <p className="wc-round__theme muted">
        {WORLDCUP_NAME} · {theme.title}
      </p>
      <h2 className="wc-result__head">
        {own ? '나의 인생 책은' : '이 월드컵의 우승 책:'}
        <span className="wc-result__book">《{winner.title}》</span>
      </h2>

      <div className="wc-winner">
        <div className="wc-winner__book">
          <BookCover themeId={theme.id} book={winner} eager />
        </div>
        <div className="wc-plank" aria-hidden />
      </div>
      <p className="wc-winner__meta muted">
        {winner.author} · {winner.publisher}
      </p>
      {line && <p className="wc-statline">{line}</p>}

      {own ? (
        <div className="wc-actions">
          <button className="button wc-wide" onClick={findLibrary}>
            이 책이 어느 도서관에 있는지 찾기
          </button>
          <button className="button button--quiet wc-wide" onClick={() => void share()}>
            결과 공유
          </button>
          <button className="button button--quiet wc-wide" onClick={playAgain}>
            다시 하기
          </button>
        </div>
      ) : (
        <div className="wc-actions">
          {/* 공유 링크로 들어온 사람이 할 일은 하나입니다. 가장 크게 둡니다. */}
          <button className="button wc-wide wc-wide--big" onClick={playAgain}>
            나도 해 보기
          </button>
          <button className="button button--quiet wc-wide" onClick={findLibrary}>
            이 책이 어느 도서관에 있는지 찾기
          </button>
        </div>
      )}

      {own && panel && (
        <div className="wc-share">
          {card.kind === 'ready' ? (
            <>
              <img className="wc-share__card" src={card.url} alt={`나의 인생 책 《${winner.title}》 결과 카드`} />
              <div className="wc-share__buttons">
                <a
                  className="chip"
                  href={card.url}
                  download={card.file.name}
                  onClick={() => trackWorldcup('save_image', theme.id)}
                >
                  이미지 저장
                </a>
                <button className="chip" onClick={() => void copy()}>
                  링크 복사
                </button>
              </div>
              {/*
                앱 안의 브라우저는 내려받기를 막는 경우가 많습니다. 그때 쓸 수 있는 길을
                함께 적습니다.
              */}
              <p className="wc-share__hint muted">
                저장이 되지 않으면 위의 그림을 길게 눌러 저장해 주세요.
              </p>
            </>
          ) : (
            <>
              <p className="muted">
                {card.kind === 'drawing' ? '결과 카드를 그리는 중입니다.' : '결과 카드를 만들지 못했습니다. 링크는 복사할 수 있습니다.'}
              </p>
              <div className="wc-share__buttons">
                <button className="chip" onClick={() => void copy()}>
                  링크 복사
                </button>
              </div>
            </>
          )}
          {copied === 'done' && <p className="wc-share__hint" role="status">링크를 복사했습니다.</p>}
          {copied === 'failed' && (
            <p className="wc-share__hint" role="status">
              복사하지 못했습니다. 주소창의 주소를 직접 복사해 주세요.
            </p>
          )}
        </div>
      )}

      {own && (runnerUp || semifinalists.length > 0) && (
        <div className="wc-podium">
          <h3 className="wc-sub">끝까지 겨룬 책</h3>
          <ul className="wc-podium__list">
            {runnerUp && <PodiumBook theme={theme} book={runnerUp} label="준우승" />}
            {semifinalists.map((book) => (
              <PodiumBook key={book.id} theme={theme} book={book} label="4강" />
            ))}
          </ul>
        </div>
      )}

      {stats && rows.length > 0 && (
        <div className="wc-rank">
          <h3 className="wc-sub">전체 순위</h3>
          <p className="wc-rank__note muted">
            지금까지 {stats.completions.toLocaleString('ko-KR')}번의 월드컵에서 우승한 횟수입니다.
          </p>
          <ol className="wc-rank__list">
            {rows.map((row) => (
              <li key={row.book.id} className="wc-rank__row" data-me={row.book.id === winner.id || undefined}>
                <span className="wc-rank__no">{row.rank}</span>
                <span className="wc-rank__title">
                  {row.book.title}
                  <span className="wc-rank__author muted">{row.book.author}</span>
                </span>
                <span className="wc-rank__value">{rankValue(row, stats)}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
      {loaded.kind === 'missing' && (
        // 통계만 없는 것입니다. 고른 결과는 그대로입니다.
        <p className="wc-rank__note muted">지금은 전체 순위를 불러오지 못했습니다.</p>
      )}
    </section>
  );
}

function PodiumBook({ theme, book, label }: { theme: Theme; book: Book; label: string }) {
  return (
    <li className="wc-podium__item">
      <span className="wc-podium__book">
        <BookCover themeId={theme.id} book={book} />
      </span>
      <span className="wc-podium__label">{label}</span>
      <span className="wc-podium__title">{book.title}</span>
    </li>
  );
}
