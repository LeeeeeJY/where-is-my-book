import { useEffect } from 'react';
import { ModeTabs, SiteFoot, SiteHead } from '../components/SiteChrome';
import type { Mode } from '../domain/modes';
import { navigate, paths, type Route } from '../domain/route';
import { homeMeta, playMeta, resultMeta, SITE_TITLE } from './meta';
import { bookById, themeById } from './themes';
import { rememberUtm } from './track';
import { WorldcupHome } from './WorldcupHome';
import { WorldcupPlay } from './WorldcupPlay';
import { WorldcupResult } from './WorldcupResult';

type WorldcupRoute = Exclude<Route, { kind: 'app' }>;

/** 탭에 보이는 제목. 빌드가 경로마다 적어 둔 것과 같은 함수에서 나옵니다. */
function titleOf(route: WorldcupRoute): string {
  if (route.kind === 'worldcup-home') return homeMeta().title;
  const theme = themeById(route.themeId);
  if (!theme) return homeMeta().title;
  if (route.kind === 'worldcup-play') return playMeta(theme.id, theme).title;
  const book = bookById(theme, route.bookId);
  return book ? resultMeta(theme.id, theme, book).title : homeMeta().title;
}

/**
 * 월드컵 화면의 틀. 머리말과 탭은 첫 화면과 같은 것을 씁니다.
 *
 * <p><b>도서관 목록을 받지 않습니다.</b> 이 화면에서 도서관이 필요한 곳은 없고, 광고에서
 * 들어온 사람에게 300KB 짜리 목록을 먼저 받게 할 이유도 없습니다. 도서관은 「이 책이 어느
 * 도서관에 있는지 찾기」를 눌러 검색 화면으로 간 뒤에 고릅니다.
 */
export function Worldcup({ route }: { route: WorldcupRoute }) {
  // 광고 링크의 유입 표시는 첫 주소에만 있습니다. 화면을 넘기기 전에 적어 둡니다.
  useEffect(() => rememberUtm(), []);

  const title = titleOf(route);
  useEffect(() => {
    document.title = title;
    // 첫 화면으로 돌아가면 제목도 돌려놓습니다. 그대로 두면 검색 화면의 탭에
    // 「월드컵」이라고 적혀 있습니다.
    return () => {
      document.title = SITE_TITLE;
    };
  }, [title]);

  const toMode = (mode: Mode) => navigate('/', { state: { mode } });

  return (
    <div className="app">
      {/* 대결 화면은 두 권이 한 화면에 들어와야 합니다. 머리말의 소개 한 줄을 접습니다. */}
      <SiteHead tagline={route.kind !== 'worldcup-play'} />
      <ModeTabs active="worldcup" onMode={toMode} onWorldcup={() => navigate(paths.worldcup())} />

      <main className="wc">
        {route.kind === 'worldcup-home' && <WorldcupHome />}
        {route.kind === 'worldcup-play' && (
          // 주제가 바뀌면 진행 상태를 통째로 새로 잡습니다.
          <WorldcupPlay key={route.themeId} themeId={route.themeId} />
        )}
        {route.kind === 'worldcup-result' && (
          <WorldcupResult
            key={`${route.themeId}/${route.bookId}`}
            themeId={route.themeId}
            bookId={route.bookId}
          />
        )}
      </main>

      <SiteFoot />
    </div>
  );
}

/** 주소의 주제나 책이 없을 때. 공유된 링크의 책이 나중에 빠졌을 수도 있습니다. */
export function WorldcupMissing({ what }: { what: string }) {
  return (
    <section className="results wc-card">
      <h2 className="wc-title">{what}</h2>
      <p className="muted">주소가 바뀌었거나 잘못 옮겨졌을 수 있습니다.</p>
      <button className="button wc-wide" onClick={() => navigate(paths.worldcup())}>
        월드컵 처음으로
      </button>
    </section>
  );
}
