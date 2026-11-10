import { useState } from 'react';
import { navigate, paths } from '../domain/route';
import { fitsTheme, roundLabel, standing } from './bracket';
import { BookCover } from './BookCover';
import { WORLDCUP_NAME } from './meta';
import { clearProgress, loadProgress } from './storage';
import { THEMES, type Theme } from './themes';

/**
 * 주제 목록이자 시작 화면.
 *
 * <p><b>설명을 읽지 않아도 시작할 수 있어야 합니다.</b> 광고에서 처음 들어온 사람이 보는
 * 화면이라, 무엇을 하는 곳인지는 한 줄로 말하고 누를 것은 「시작하기」 하나를 가장 크게
 * 둡니다. 규칙은 첫 대결 화면이 그 자리에서 말해 줍니다.
 */
export function WorldcupHome() {
  return (
    <section className="results wc-card">
      <h2 className="wc-title">{WORLDCUP_NAME}</h2>
      <p className="wc-lede">둘 중 더 마음이 가는 책을 고르면, 마지막에 한 권이 남습니다.</p>
      <div className="wc-themes">
        {THEMES.map((theme) => (
          <ThemeCard key={theme.id} theme={theme} />
        ))}
      </div>
    </section>
  );
}

/** 하다 만 대진이 있으면 어디까지 왔는지. 없거나 끝났으면 null. */
function unfinished(theme: Theme): string | null {
  const saved = loadProgress(theme.id);
  if (!saved || !fitsTheme(saved, theme.books.map((b) => b.id))) return null;
  const now = standing(saved);
  if (!now || now.finish || saved.picks.length === 0) return null;
  return `${roundLabel(now.roundSize)} ${now.matchIndex + 1}/${now.matchCount}`;
}

function ThemeCard({ theme }: { theme: Theme }) {
  // 처음 그릴 때 한 번만 읽습니다. 이 화면에 머무는 동안 바뀔 일이 없습니다.
  const [resume] = useState(() => unfinished(theme));
  // 선반에 세워 보이는 몇 권. 무엇을 고르게 되는지를 글 대신 표지로 말합니다.
  const sample = theme.books.slice(0, 6);

  const start = (fresh: boolean) => {
    if (fresh) clearProgress(theme.id);
    navigate(paths.play(theme.id));
  };

  return (
    <article className="wc-theme">
      <div className="wc-shelf" aria-hidden>
        <div className="wc-shelf__books">
          {sample.map((book) => (
            <BookCover key={book.id} themeId={theme.id} book={book} eager />
          ))}
        </div>
        <div className="wc-plank" />
      </div>
      <h3 className="wc-theme__title">{theme.title}</h3>
      <p className="wc-theme__meta muted">
        책 {theme.books.length}권 · 고르는 횟수 {theme.books.length - 1}번
      </p>
      {resume ? (
        <>
          <button className="button wc-wide" onClick={() => start(false)}>
            이어서 하기 ({resume})
          </button>
          <button className="button button--quiet wc-wide" onClick={() => start(true)}>
            처음부터 다시 하기
          </button>
        </>
      ) : (
        <button className="button wc-wide" onClick={() => start(true)}>
          시작하기
        </button>
      )}
    </article>
  );
}
