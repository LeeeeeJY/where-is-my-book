import { useEffect, useState } from 'react';
import { ApiUnavailable, fetchLibraries } from './api';
import { BookSearch } from './components/BookSearch';
import { BrandMark } from './components/Brand';
import { MultiCheck } from './components/MultiCheck';
import { LibraryPicker } from './components/LibraryPicker';
import { SAMPLE_LIBRARIES } from './data/sampleLibraries';
import { loadSelection, saveSelection } from './domain/selectionStorage';
import { paramsToSelection, selectionToParams } from './domain/selectionUrl';
import type { Library } from './domain/types';

/**
 * 주소를 쓰기 전에 한 번만 붙잡아 둡니다.
 *
 * 도서관 목록이 늦게 도착하는데 그 사이에 주소를 손대면, 공유받은 링크의 선택이
 * 복원되기도 전에 지워집니다.
 */
const INITIAL_SEARCH = window.location.search;

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
  const [mode, setMode] = useState<'single' | 'multi'>('single');

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

    const fromUrl = paramsToSelection(new URLSearchParams(INITIAL_SEARCH), libraries);
    if (fromUrl.size > 0) {
      setSelected(fromUrl);
    } else {
      const stored = loadSelection();
      const known = new Set(libraries.map((l) => l.libCode));
      // 목록에서 사라진 도서관은 조용히 버립니다.
      if (stored) setSelected(new Set(stored.filter((code) => known.has(code))));
    }
    setRestored(true);
  }, [libraries, restored]);

  // 선택이 바뀔 때마다 브라우저에 저장하고 주소에도 반영합니다.
  useEffect(() => {
    if (!restored) return;
    saveSelection([...selected]);

    const url = new URL(window.location.href);
    url.searchParams.delete('libs');
    url.searchParams.delete('libsb');
    const params = selectionToParams(selected, libraries);
    if (params) url.searchParams.set(params.key, params.value);
    window.history.replaceState(null, '', url);
  }, [selected, libraries, restored]);

  return (
    <div className="app">
      <header className="app__head">
        <div className="brand">
          <BrandMark />
          {/* 약어만 두면 무엇을 하는 곳인지 알 수 없으므로 풀이를 같은 제목 안에 둡니다. */}
          <h1 className="brand__title">
            <span className="brand__mark">WIMB</span>
            <span className="brand__name">Where Is My Book</span>
          </h1>
        </div>
        <p className="muted">
          자주 가는 도서관을 골라 두면, 읽고 싶은 책이 그 중 어디에 있는지 알려 줍니다.
        </p>
      </header>

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

      {catalog.kind === 'loading' ? (
        <p className="muted">도서관 목록을 받는 중입니다.</p>
      ) : (
        <main className="layout">
          <LibraryPicker libraries={libraries} selected={selected} onChange={setSelected} />

          <div className="results-column">
            <nav className="tabs tabs--mode" role="tablist">
              {(
                [
                  ['single', '한 권 검색'],
                  ['multi', '여러 권 확인'],
                ] as const
              ).map(([key, label]) => (
                <button
                  key={key}
                  role="tab"
                  aria-selected={mode === key}
                  className={mode === key ? 'tab tab--active' : 'tab'}
                  onClick={() => setMode(key)}
                >
                  {label}
                </button>
              ))}
            </nav>

            {mode === 'single' ? (
              <BookSearch libraries={libraries} selected={selected} />
            ) : (
              <MultiCheck libraries={libraries} selected={selected} />
            )}

          </div>
        </main>
      )}

      {/*
        정보나루는 국립중앙도서관이 운영하는 서비스입니다. 가운뎃점으로 이으면 서로 다른 두
        곳에서 데이터를 받아 오는 것처럼 읽히는데, 지금 쓰는 출처는 정보나루 하나뿐입니다.
        국립중앙도서관 ISBN 서지정보 API 는 별도 서비스이고 아직 붙이지 않았습니다.
        그것을 붙이면 그때 출처를 하나 더 적습니다.
      */}
      <footer className="app__foot muted">
        출처:{' '}
        <a href="https://www.data4library.kr" target="_blank" rel="noreferrer noopener">
          도서관 정보나루
        </a>{' '}
        (국립중앙도서관)
      </footer>
    </div>
  );
}
