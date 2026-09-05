import { useCallback, useEffect, useState } from 'react';
import { fetchLibraries } from './api';
import { BookSearch } from './components/BookSearch';
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
  /** 서버가 없어 샘플로 화면만 확인하는 상태. 반드시 화면에 밝힙니다. */
  | { kind: 'sample'; libraries: Library[] };

export default function App() {
  const [catalog, setCatalog] = useState<Catalog>({ kind: 'loading' });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [restored, setRestored] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchLibraries().then(
      (libraries) => {
        if (!cancelled) setCatalog({ kind: 'api', libraries });
      },
      () => {
        // 서버가 없어도 선택 화면은 동작해야 합니다. 다만 샘플이라는 것을 숨기지 않습니다.
        if (!cancelled) setCatalog({ kind: 'sample', libraries: SAMPLE_LIBRARIES });
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

  const share = useCallback(() => {
    navigator.clipboard.writeText(window.location.href).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      },
      () => setCopied(false),
    );
  }, []);

  return (
    <div className="app">
      <header className="app__head">
        <h1>내 책 어디 있지</h1>
        <p className="muted">
          자주 가는 도서관을 골라 두면, 읽고 싶은 책이 그중 어디에 있는지 알려 줍니다.
        </p>
      </header>

      {catalog.kind === 'sample' && (
        <div className="banner banner--warn">
          <strong>API 서버에 연결하지 못했습니다.</strong> 지금 보이는 도서관{' '}
          {catalog.libraries.length}곳은 화면 확인용 샘플이고, 책 검색도 동작하지 않습니다.
          서버를 띄우면 전국 공공도서관 목록이 그 자리에 들어옵니다.
        </div>
      )}

      {catalog.kind === 'loading' ? (
        <p className="muted">도서관 목록을 받는 중입니다.</p>
      ) : (
        <main className="layout">
          <LibraryPicker libraries={libraries} selected={selected} onChange={setSelected} />

          <div className="results-column">
            <BookSearch libraries={libraries} selected={selected} />

            {selected.size > 0 && (
              <div className="share">
                <button className="button" onClick={share}>
                  {copied ? '주소를 복사했습니다' : '선택 상태 공유하기'}
                </button>
                <p className="muted">
                  주소에 선택이 담겨 있어 다른 기기에서 열어도 그대로 복원됩니다.
                </p>
              </div>
            )}
          </div>
        </main>
      )}

      <footer className="app__foot muted">출처: 도서관 정보나루 · 국립중앙도서관</footer>
    </div>
  );
}
