import { useEffect, useRef, useState } from 'react';
import { ApiUnavailable, fetchLibraries } from './api';
import { BookSearch } from './components/BookSearch';
import { Shelf } from './components/Shelf';
import { MultiCheck } from './components/MultiCheck';
import { LibraryPicker } from './components/LibraryPicker';
import { ModeTabs, SiteFoot, SiteHead } from './components/SiteChrome';
import { SAMPLE_LIBRARIES } from './data/sampleLibraries';
import { isMode, type Mode } from './domain/modes';
import { navigate, paths } from './domain/route';
import { loadSelection, saveSelection } from './domain/selectionStorage';
import { paramsToSelection, selectionToParams } from './domain/selectionUrl';
import type { Library, UserPosition } from './domain/types';

/**
 * 다른 화면(월드컵)에서 넘어올 때 열어 둘 탭. 주소가 아니라 이동할 때 함께 넘긴 값에서
 * 읽습니다. 탭은 주소에 없는 상태라, 주소로 넘기면 그때부터 탭마다 주소가 생깁니다.
 */
function arrivingMode(): Mode {
  const state = window.history.state as { mode?: unknown } | null;
  return isMode(state?.mode) ? state.mode : 'single';
}

type Catalog =
  | { kind: 'loading' }
  /** API 서버에서 받은 실제 도서관 목록. */
  | { kind: 'api'; libraries: Library[] }
  /**
   * 실제 목록을 받지 못해 샘플로 화면만 확인하는 상태. 반드시 화면에 밝힙니다.
   *
   * `reason` 을 두는 이유는 사람이 할 일이 다르기 때문입니다. `unreachable` 은 서버를
   * 띄우거나 주소를 고쳐야 하고, `upstream` 은 서버는 멀쩡하니 정보나루 인증키를 봐야
   * 합니다. 둘을 같은 문구로 말하면 멀쩡한 서버를 다시 띄우게 됩니다.
   */
  | { kind: 'sample'; libraries: Library[]; reason: 'unreachable' | 'upstream' };

export default function App() {
  const [catalog, setCatalog] = useState<Catalog>({ kind: 'loading' });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [restored, setRestored] = useState(false);
  /*
   * **서가를 셋째 탭에 둡니다.** 검색하러 온 사람에게는 검색 칸만 보이는 편이 낫고,
   * 서가는 애초에 다른 행동이라 들어오는 문을 따로 두는 것이 맞습니다.
   */
  const [mode, setMode] = useState<Mode>(arrivingMode);
  /**
   * 주소를 쓰기 전에 한 번만 붙잡아 둡니다.
   *
   * 도서관 목록이 늦게 도착하는데 그 사이에 주소를 손대면, 공유받은 링크의 선택이
   * 복원되기도 전에 지워집니다.
   *
   * **이 화면이 그려질 때의 주소입니다. 페이지를 처음 받았을 때의 주소가 아닙니다.**
   * 월드컵 화면에 다녀오면 이 화면이 새로 그려지는데, 처음 받은 주소를 계속 들고 있으면
   * 그 사이에 바꾼 선택이 공유받은 링크의 선택으로 되돌아갑니다.
   */
  const [initialSearch] = useState(() => window.location.search);
  /**
   * 검색어를 채운 채 들어온 경우(`/?q=제목`). 월드컵의 「이 책이 어느 도서관에 있는지
   * 찾기」가 이 길로 옵니다. 한 번 읽고 주소에서는 지웁니다. 남겨 두면 다른 책을 찾은
   * 뒤에도 주소가 예전 제목을 말합니다.
   */
  const [arrivedWith] = useState(
    () => new URLSearchParams(window.location.search).get('q')?.trim() ?? '',
  );
  /**
   * 「내 주변」에서 잡은 위치. 도서관 선택 칸이 잡고 여러 권 검색이 거리를 재는 데 씁니다.
   * 한쪽 컴포넌트 안에 두면 다른 쪽이 볼 수 없어 여기에 올려 둡니다.
   */
  const [position, setPosition] = useState<UserPosition | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchLibraries().then(
      (libraries) => {
        if (!cancelled) setCatalog({ kind: 'api', libraries });
      },
      (error) => {
        // 서버가 없어도 선택 화면은 동작해야 합니다. 다만 샘플이라는 것을 숨기지 않습니다.
        if (cancelled) return;
        setCatalog({
          kind: 'sample',
          libraries: SAMPLE_LIBRARIES,
          reason: error instanceof ApiUnavailable ? 'unreachable' : 'upstream',
        });
      },
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const libraries = catalog.kind === 'loading' ? [] : catalog.libraries;

  // 목록이 도착한 뒤에야 번호를 도서관부호로 되돌릴 수 있습니다.
  // **URL 이 있으면 URL 이 이깁니다.** 공유받은 링크가 내 기존 선택으로 덮이면
  // 공유가 의미를 잃기 때문입니다.
  useEffect(() => {
    if (restored || libraries.length === 0) return;

    const fromUrl = paramsToSelection(new URLSearchParams(initialSearch), libraries);
    if (fromUrl.size > 0) {
      setSelected(fromUrl);
    } else {
      const stored = loadSelection();
      const known = new Set(libraries.map((l) => l.libCode));
      // 목록에서 사라진 도서관은 조용히 버립니다.
      if (stored) setSelected(new Set(stored.filter((code) => known.has(code))));
    }
    setRestored(true);
  }, [libraries, restored, initialSearch]);

  /*
   * 책을 찾아 달라는 채로 들어왔고 고른 도서관이 있으면 결과가 보이는 자리로 내립니다.
   * 좁은 화면에서는 도서관 선택 칸이 결과보다 위에 있어서, 그대로 두면 방금 누른 「찾기」의
   * 답이 화면 밖에 있습니다. 고른 도서관이 없으면 내리지 않습니다. 그때 먼저 할 일은
   * 맨 위의 도서관 선택입니다.
   */
  const resultsColumn = useRef<HTMLDivElement>(null);
  const hadLibraries = restored && selected.size > 0;
  useEffect(() => {
    if (arrivedWith && hadLibraries) resultsColumn.current?.scrollIntoView({ block: 'start' });
    // 되살린 직후 한 번만 봅니다. 그 뒤에 도서관을 고를 때마다 화면을 옮기면 안 됩니다.
  }, [restored]); // eslint-disable-line react-hooks/exhaustive-deps

  // 선택이 바뀔 때마다 브라우저에 저장하고 주소에도 반영합니다.
  useEffect(() => {
    if (!restored) return;
    saveSelection([...selected]);

    const url = new URL(window.location.href);
    url.searchParams.delete('libs');
    url.searchParams.delete('libsb');
    url.searchParams.delete('q');
    const params = selectionToParams(selected, libraries);
    if (params) url.searchParams.set(params.key, params.value);
    window.history.replaceState(null, '', url);
  }, [selected, libraries, restored]);

  return (
    <div className="app">
      <SiteHead />

      {catalog.kind === 'sample' && (
        <div className="banner banner--warn">
          {catalog.reason === 'unreachable' ? (
            <>
              <strong>API 서버에 연결하지 못했습니다.</strong> 지금 보이는 도서관{' '}
              {catalog.libraries.length}곳은 화면 확인용 샘플이고, 책 검색도 동작하지 않습니다.
              서버를 띄우면 전국 공공도서관 목록이 그 자리에 들어옵니다.
            </>
          ) : (
            <>
              <strong>도서관 목록을 받지 못했습니다.</strong> 서버는 응답했지만 정보나루에서
              목록을 가져오지 못했습니다. 인증키가 아직 활성화되지 않았을 수 있습니다. 지금
              보이는 도서관 {catalog.libraries.length}곳은 화면 확인용 샘플이고, 책 검색도
              동작하지 않습니다.
            </>
          )}
        </div>
      )}

      {/*
        책을 찾아 달라는 채로 들어왔는데 고른 도서관이 없는 경우입니다. 검색은 이미 해
        두었지만 어디에 있는지는 도서관을 골라야 답할 수 있으므로, 다음에 할 일을 한 번
        말해 줍니다. 안내라서 노란 상자를 쓰지 않습니다.
      */}
      {arrivedWith && restored && selected.size === 0 && (
        <div className="banner banner--info">
          <strong>《{arrivedWith}》 검색을 해 두었습니다.</strong> 「도서관 선택」에서 자주 가는
          곳을 고른 뒤 검색 결과 위의 확인 단추를 누르면, 그중 어디에 있는지 알려 드립니다.
        </div>
      )}

      {catalog.kind === 'loading' ? (
        <p className="muted">도서관 목록을 받는 중입니다.</p>
      ) : (
        <main className="layout">
          <LibraryPicker
            libraries={libraries}
            selected={selected}
            onChange={setSelected}
            position={position}
            onPosition={setPosition}
          />

          {/*
            **탭은 오른쪽 칸 안이 아니라 격자의 한 칸입니다.** 오른쪽 칸 안에 두면 넓은
            화면에서 왼쪽 카드의 윗선이 탭과 나란해지고 오른쪽 카드는 탭 높이만큼 내려가,
            두 카드의 윗선이 어긋났습니다. 격자의 첫 줄에 탭만 두고 둘째 줄에 카드 둘을 두면
            윗선이 맞습니다. 좁은 화면에서는 DOM 순서 그대로 선택 → 탭 → 내용으로 쌓입니다.
          */}
          <ModeTabs
            active={mode}
            onMode={setMode}
            onWorldcup={() => navigate(paths.worldcup())}
          />

          <div className="results-column" ref={resultsColumn}>
            {mode === 'single' && (
              <BookSearch
                libraries={libraries}
                selected={selected}
                // 고른 도서관을 되살린 뒤에 넘깁니다. 먼저 넘기면 도서관 없이 검색이
                // 나가고, 되살아난 선택으로는 사람이 「확인」을 한 번 더 눌러야 합니다.
                initialTitle={restored ? arrivedWith : ''}
              />
            )}
            {mode === 'multi' && (
              <MultiCheck libraries={libraries} selected={selected} position={position} />
            )}
            {/*
              **서가도 다른 탭과 같은 자리에 그립니다.** 한때 화면을 통째로 덮고 아래
              탭 바를 쓰게 했는데, 탭을 누르는 순간 예고 없이 배치가 바뀌어 다른 데로
              온 것처럼 보였습니다. 탭이 제자리에 있으면 이동이 이동으로 읽힙니다.

              **넓은 화면에서도 좁지 않습니다.** 1,100px 에서 도서관 선택 칸을 빼도
              오른쪽이 780px 이라 한 줄에 네 권이면 표지가 180px 입니다. 휴대폰의
              81px 보다 두 배 넓습니다.
            */}
            {mode === 'shelf' && <Shelf libraries={libraries} selected={selected} />}
          </div>
        </main>
      )}

      <SiteFoot />
    </div>
  );
}
