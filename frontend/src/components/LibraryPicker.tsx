import { useMemo, useState } from 'react';
import type { Library } from '../domain/types';
import {
  buildRegionTree,
  byDistanceFrom,
  groupState,
  haversineKm,
  toggleGroup,
  toggleOne,
} from '../domain/selection';
import { TriStateCheckbox } from './TriStateCheckbox';

type Tab = 'nearby' | 'search' | 'region';

export function LibraryPicker({
  libraries,
  selected,
  onChange,
}: {
  libraries: Library[];
  selected: Set<string>;
  onChange: (next: Set<string>) => void;
}) {
  const [tab, setTab] = useState<Tab>('region');

  return (
    <section className="picker">
      <header className="picker__head">
        <h2>도서관 선택</h2>
        <span className="picker__count">{selected.size}곳 선택됨</span>
      </header>

      <nav className="tabs" role="tablist">
        {(
          [
            ['region', '지역'],
            ['search', '이름 검색'],
            ['nearby', '내 주변'],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={tab === key}
            className={tab === key ? 'tab tab--active' : 'tab'}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </nav>

      {tab === 'region' && (
        <RegionTab libraries={libraries} selected={selected} onChange={onChange} />
      )}
      {tab === 'search' && (
        <SearchTab libraries={libraries} selected={selected} onChange={onChange} />
      )}
      {tab === 'nearby' && (
        <NearbyTab libraries={libraries} selected={selected} onChange={onChange} />
      )}

      {selected.size > 0 && (
        <>
          <p className="picker__note muted">
            선택은 이 브라우저에 저장되고 주소에도 담깁니다. 주소를 그대로 보내면 상대방
            화면에서도 같은 선택으로 열립니다.
          </p>
          <button className="link-button" onClick={() => onChange(new Set())}>
            선택 전체 해제
          </button>
        </>
      )}
    </section>
  );
}

/** 시도 > 시군구 > 도서관 3단계로 접었다 폅니다. */
function RegionTab({
  libraries,
  selected,
  onChange,
}: {
  libraries: Library[];
  selected: Set<string>;
  onChange: (next: Set<string>) => void;
}) {
  const tree = useMemo(() => buildRegionTree(libraries), [libraries]);
  const [openSido, setOpenSido] = useState<Set<string>>(new Set());
  // 시군구는 (시도, 시군구) 쌍으로 구분합니다. 「중구」처럼 여러 시도에 같은 이름이
  // 있으므로, 시군구 이름만 열쇠로 쓰면 서울 중구를 펴면 부산 중구도 함께 펴집니다.
  const [openSigungu, setOpenSigungu] = useState<Set<string>>(new Set());
  const toggle = (set: Set<string>, key: string) => {
    const next = new Set(set);
    if (!next.delete(key)) next.add(key);
    return next;
  };

  return (
    <ul className="tree">
      {tree.map((sidoNode) => {
        const members = sidoNode.sigungus.flatMap((s) => s.libraries);
        const open = openSido.has(sidoNode.sido);
        return (
          <li key={sidoNode.sido}>
            <div className="tree__row tree__row--sido">
              <TriStateCheckbox
                state={groupState(selected, members)}
                onChange={() => onChange(toggleGroup(selected, members))}
                label={`${sidoNode.sido} 전체`}
              />
              <button
                className="tree__toggle"
                aria-expanded={open}
                onClick={() => setOpenSido((prev) => toggle(prev, sidoNode.sido))}
              >
                <span className={open ? 'caret caret--open' : 'caret'} aria-hidden>
                  ▸
                </span>
                {sidoNode.sido}
                <span className="muted"> {sidoNode.libraryCount}곳</span>
              </button>
            </div>

            {open && (
              <ul className="tree tree--nested">
                {sidoNode.sigungus.map((sigunguNode) => {
                  const key = `${sidoNode.sido}/${sigunguNode.sigungu}`;
                  const sigunguOpen = openSigungu.has(key);
                  return (
                    <li key={key}>
                      <div className="tree__row">
                        <TriStateCheckbox
                          state={groupState(selected, sigunguNode.libraries)}
                          onChange={() => onChange(toggleGroup(selected, sigunguNode.libraries))}
                          label={`${sigunguNode.sigungu} 전체`}
                        />
                        <button
                          className="tree__toggle"
                          aria-expanded={sigunguOpen}
                          onClick={() => setOpenSigungu((prev) => toggle(prev, key))}
                        >
                          <span className={sigunguOpen ? 'caret caret--open' : 'caret'} aria-hidden>
                            ▸
                          </span>
                          {sigunguNode.sigungu}
                          <span className="muted"> {sigunguNode.libraries.length}곳</span>
                        </button>
                      </div>

                      {sigunguOpen && (
                        <ul className="tree tree--nested">
                          {sigunguNode.libraries.map((library) => (
                            <li key={library.libCode} className="tree__row">
                              <input
                                type="checkbox"
                                checked={selected.has(library.libCode)}
                                onChange={() => onChange(toggleOne(selected, library.libCode))}
                                id={`lib-${library.libCode}`}
                              />
                              <label htmlFor={`lib-${library.libCode}`}>{library.name}</label>
                            </li>
                          ))}
                        </ul>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function SearchTab({
  libraries,
  selected,
  onChange,
}: {
  libraries: Library[];
  selected: Set<string>;
  onChange: (next: Set<string>) => void;
}) {
  const [query, setQuery] = useState('');
  // 1,604건뿐이라 전부 내려받아 클라이언트에서 거릅니다. 응답이 즉각적이라 사용감이 좋습니다.
  const hits = useMemo(() => {
    const needle = query.replace(/\s+/g, '');
    if (!needle) return [];
    return libraries
      .filter((l) => l.name.replace(/\s+/g, '').includes(needle))
      .slice(0, 50);
  }, [libraries, query]);

  return (
    <div className="search-tab">
      <input
        className="text-input"
        type="search"
        value={query}
        placeholder="도서관 이름"
        onChange={(e) => setQuery(e.target.value)}
      />
      {query && hits.length === 0 && <p className="muted">찾는 도서관이 없습니다.</p>}
      <ul className="flat-list">
        {hits.map((library) => (
          <li key={library.libCode} className="tree__row">
            <input
              type="checkbox"
              checked={selected.has(library.libCode)}
              onChange={() => onChange(toggleOne(selected, library.libCode))}
              id={`search-${library.libCode}`}
            />
            <label htmlFor={`search-${library.libCode}`}>
              {library.name}
              <span className="muted">
                {' '}
                {library.sido} {library.sigungu}
              </span>
            </label>
          </li>
        ))}
      </ul>
    </div>
  );
}

function NearbyTab({
  libraries,
  selected,
  onChange,
}: {
  libraries: Library[];
  selected: Set<string>;
  onChange: (next: Set<string>) => void;
}) {
  const [position, setPosition] = useState<{ lat: number; lon: number } | null>(null);
  const [status, setStatus] = useState<'idle' | 'asking' | 'denied'>('idle');

  const nearby = useMemo(() => {
    if (!position) return [];
    // 위경도가 없는 도서관은 거리를 잴 수 없어 목록 끝으로 갑니다.
    // 표준데이터 대조에 실패한 경우이고, 이름 검색과 지역 계층에서는 정상적으로 찾힙니다.
    return [...libraries]
      .filter((l) => l.latitude !== null)
      .sort(byDistanceFrom(position.lat, position.lon))
      .slice(0, 20);
  }, [libraries, position]);

  if (!position) {
    return (
      <div className="search-tab">
        <button
          className="button"
          disabled={status === 'asking'}
          onClick={() => {
            setStatus('asking');
            navigator.geolocation.getCurrentPosition(
              (p) => {
                setPosition({ lat: p.coords.latitude, lon: p.coords.longitude });
                setStatus('idle');
              },
              () => setStatus('denied'),
              { timeout: 10_000 },
            );
          }}
        >
          현재 위치로 가까운 도서관 찾기
        </button>
        {status === 'denied' && (
          <p className="muted">
            위치를 사용할 수 없습니다. 지역 탭이나 이름 검색으로 골라 주세요.
          </p>
        )}
      </div>
    );
  }

  return (
    <ul className="flat-list">
      {nearby.map((library) => {
        const km = haversineKm(position.lat, position.lon, library);
        return (
          <li key={library.libCode} className="tree__row">
            <input
              type="checkbox"
              checked={selected.has(library.libCode)}
              onChange={() => onChange(toggleOne(selected, library.libCode))}
              id={`near-${library.libCode}`}
            />
            <label htmlFor={`near-${library.libCode}`}>
              {library.name}
              <span className="muted"> {km === null ? '' : `${km.toFixed(1)}km`}</span>
            </label>
          </li>
        );
      })}
    </ul>
  );
}
