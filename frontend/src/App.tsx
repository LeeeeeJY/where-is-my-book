import { useCallback, useEffect, useState } from 'react';
import { LibraryPicker } from './components/LibraryPicker';
import { SAMPLE_LIBRARIES } from './data/sampleLibraries';
import { loadSelection, saveSelection } from './domain/selectionStorage';
import { paramsToSelection, selectionToParams } from './domain/selectionUrl';

const LIBRARIES = SAMPLE_LIBRARIES;

/**
 * 처음 화면을 그릴 때의 선택 상태를 정합니다.
 *
 * **URL 이 있으면 URL 이 이깁니다.** 공유받은 링크를 열었을 때 내 기존 선택으로 덮이면
 * 공유가 의미를 잃기 때문입니다.
 */
function initialSelection(): Set<string> {
  const fromUrl = paramsToSelection(new URLSearchParams(window.location.search), LIBRARIES);
  if (fromUrl.size > 0) return fromUrl;

  const stored = loadSelection();
  if (!stored) return new Set();
  // 목록에서 사라진 도서관은 조용히 버립니다.
  const known = new Set(LIBRARIES.map((l) => l.libCode));
  return new Set(stored.filter((code) => known.has(code)));
}

export default function App() {
  const [selected, setSelected] = useState<Set<string>>(initialSelection);
  const [copied, setCopied] = useState(false);

  // 선택이 바뀔 때마다 브라우저에 저장하고 주소에도 반영합니다.
  useEffect(() => {
    saveSelection([...selected]);

    const url = new URL(window.location.href);
    url.searchParams.delete('libs');
    url.searchParams.delete('libsb');
    const params = selectionToParams(selected, LIBRARIES);
    if (params) url.searchParams.set(params.key, params.value);
    window.history.replaceState(null, '', url);
  }, [selected]);

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

      <div className="banner banner--warn">
        <strong>개발 중입니다.</strong> 지금 보이는 도서관 {LIBRARIES.length}곳은 화면 확인용
        샘플입니다. 실제 목록(전국 1,604개관)과 책 검색은 공공데이터포털과 국립중앙도서관
        인증키를 받은 뒤에 연결됩니다.
      </div>

      <main className="layout">
        <LibraryPicker libraries={LIBRARIES} selected={selected} onChange={setSelected} />

        <section className="results">
          <header className="picker__head">
            <h2>책 검색</h2>
          </header>

          <div className="placeholder">
            <p>아직 검색할 수 있는 서지 데이터가 없습니다.</p>
            <p className="muted">
              국립중앙도서관 서지정보 API 로 2000년 이후 서지를 적재하면 이 자리에 검색창이
              들어옵니다. 소장 여부는 검색 시점에 도서관 정보나루에 물어보고, 받은 결과를
              캐시에 쌓아 둡니다.
            </p>
            <p className="muted">
              대출 가능 여부는 표시하지 않습니다. 제공되는 값이 전날 기준이라 그것을 믿고
              헛걸음하는 것이 이 도구를 못 쓰게 만드는 가장 큰 요인이기 때문입니다.
              소장 여부까지만 보여 주고 대출은 도서관 페이지에서 확인하도록 넘깁니다.
            </p>
          </div>

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
        </section>
      </main>

      <footer className="app__foot muted">
        출처: 도서관 정보나루 · 국립중앙도서관 · 공공데이터포털
      </footer>
    </div>
  );
}
