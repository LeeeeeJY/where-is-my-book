import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Library } from '../domain/types';
import {
  buildRegionTree,
  groupState,
  haversineKm,
  nearbyLibraries,
  NEARBY_RADIUS_KM,
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

      {/*
        **목록만 스크롤합니다.** 시도를 여럿 펼치면 목록이 화면보다 길어지는데, 카드 전체가
        늘어나면 선택 개수와 탭이 위로 밀려 올라가 보이지 않습니다. 어느 탭에 있고 몇 곳을
        골랐는지는 고르는 내내 보여야 합니다.
      */}
      <div className="picker__body">
        {tab === 'region' && (
          <RegionTab libraries={libraries} selected={selected} onChange={onChange} />
        )}
        {tab === 'search' && (
          <SearchTab libraries={libraries} selected={selected} onChange={onChange} />
        )}
        {tab === 'nearby' && (
          <NearbyTab libraries={libraries} selected={selected} onChange={onChange} />
        )}
      </div>

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
                {[library.sido, library.sigungu].filter(Boolean).join(' ')}
              </span>
            </label>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * 위치를 잡는 동안의 상태.
 *
 * <p>예전에는 {@code 'denied'} 하나로 뭉뚱그렸는데, 실패 이유마다 사용자가 할 일이
 * 다릅니다. 권한이 막힌 것은 브라우저 설정을 바꿔야 낫고, 기기가 못 잡은 것은 잠시 뒤
 * 다시 누르면 됩니다. 같은 문구로 말하면 고칠 수 있는 것을 못 고칩니다.
 */
type LocateStatus =
  | { kind: 'idle' }
  | { kind: 'asking' }
  | { kind: 'failed'; reason: string }
  | { kind: 'same' };

/** 브라우저가 아무 답도 주지 않을 때 우리가 포기하는 시각. */
const LOCATE_GIVE_UP_MS = 12_000;

function reasonOf(e: GeolocationPositionError): string {
  if (e.code === e.PERMISSION_DENIED) {
    return '브라우저가 위치 사용을 막고 있습니다. 주소창의 위치 아이콘에서 허용해 주세요.';
  }
  if (e.code === e.POSITION_UNAVAILABLE) {
    return '기기가 위치를 알아내지 못했습니다. 잠시 뒤 다시 눌러 보세요.';
  }
  return '시간 안에 잡지 못했습니다. 잠시 뒤 다시 눌러 보세요.';
}

/** 다시 잡은 값이 앞과 사실상 같은지. 1e-6도는 10cm 남짓이라 같은 자리로 봅니다. */
function samePlace(
  before: { lat: number; lon: number; accuracyM: number } | null,
  after: { lat: number; lon: number; accuracyM: number },
): boolean {
  if (before === null) return false;
  return (
    Math.abs(before.lat - after.lat) < 1e-6 &&
    Math.abs(before.lon - after.lon) < 1e-6 &&
    Math.abs(before.accuracyM - after.accuracyM) < 1
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
  const [position, setPosition] = useState<{ lat: number; lon: number; accuracyM: number } | null>(
    null,
  );
  const [status, setStatus] = useState<LocateStatus>({ kind: 'idle' });
  const timerRef = useRef<number | null>(null);

  // 탭을 옮기면 이 컴포넌트가 사라지는데, 시계가 남아 있으면 없어진 것에 상태를 씁니다.
  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
  }, []);

  /**
   * 위치를 잡습니다. **처음 잡을 때와 다시 잡을 때가 같은 코드입니다.**
   *
   * 갈라 놓으면 한쪽에만 옵션을 주게 되어, 다시 잡을 때 오히려 더 거친 값이 들어오는
   * 일이 생깁니다. 옵션이 특히 중요한데, 그냥 부르면 브라우저가 가장 싸고 거친 방법을
   * 쓰고 캐시된 예전 위치를 그대로 주기도 합니다. 회사에서 눌렀는데 집 근처 도서관이
   * 나오는 것이 그것입니다.
   *
   * <b>브라우저에 준 {@code timeout} 만 믿으면 안 됩니다.</b> 그 시계는 권한을 얻은 뒤에야
   * 돌기 시작해서, 권한이 막혀 있거나 사용자가 물음에 답하지 않으면 <b>성공도 실패도
   * 돌아오지 않습니다.</b> 실제로 눌러 보니 「다시 잡는 중입니다」에서 15초가 지나도 그대로였고,
   * 버튼이 잠긴 채라 다시 누를 수도 없었습니다. 사용자에게는 그것이 「아무리 눌러도 안 잡힌다」로
   * 보입니다. 그래서 <b>우리 쪽에서도 시계를 겁니다.</b>
   */
  const locate = useCallback(() => {
    setStatus({ kind: 'asking' });
    let settled = false;
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      if (settled) return;
      settled = true;
      setStatus({
        kind: 'failed',
        reason: '브라우저가 응답하지 않습니다. 주소창의 위치 아이콘을 확인해 주세요.',
      });
    }, LOCATE_GIVE_UP_MS);

    navigator.geolocation.getCurrentPosition(
      (p) => {
        if (timerRef.current !== null) window.clearTimeout(timerRef.current);
        const next = {
          lat: p.coords.latitude,
          lon: p.coords.longitude,
          // 브라우저가 알려 주는 오차 반경(미터). 이걸 화면에 밝히지 않으면
          // 20km 떨어진 곳을 「가까운 도서관」이라고 내놓게 됩니다.
          accuracyM: p.coords.accuracy,
        };
        // 포기한 뒤에 늦게 도착해도 위치 자체는 쓸모가 있으므로 받아 둡니다.
        setPosition(next);
        settled = true;
        // **값이 그대로면 그 사실을 말해야 합니다.** 아무 표시도 없으면 사용자는 자기가
        // 누른 것이 먹히지 않았다고 읽고 같은 자리를 계속 누릅니다.
        setStatus(samePlace(position, next) ? { kind: 'same' } : { kind: 'idle' });
      },
      (e) => {
        if (timerRef.current !== null) window.clearTimeout(timerRef.current);
        // 이미 포기했으면 덧쓰지 않습니다. 안내가 두 번 바뀌면 더 헷갈립니다.
        if (settled) return;
        settled = true;
        setStatus({ kind: 'failed', reason: reasonOf(e) });
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 10_000 },
    );
  }, [position]);

  const asking = status.kind === 'asking';

  // 위경도가 없는 도서관은 거리를 잴 수 없어 여기에 나오지 않습니다.
  // 이름 검색과 지역 계층에서는 정상적으로 찾히므로 사라지는 것이 아닙니다.
  const nearby = useMemo(
    () =>
      position === null
        ? null
        : nearbyLibraries(libraries, position.lat, position.lon),
    [libraries, position],
  );

  if (!position) {
    return (
      <div className="search-tab">
        <button className="button" disabled={asking} onClick={locate}>
          {asking ? '위치를 잡는 중입니다' : '현재 위치로 가까운 도서관 찾기'}
        </button>
        {status.kind === 'failed' && (
          <p className="muted">{status.reason}</p>
        )}
      </div>
    );
  }

  return (
    <>
      {/*
        위치 정확도를 밝힙니다. 노트북은 주변 Wi-Fi 로, 유선 데스크톱은 IP 로 위치를 잡는데
        후자는 수 킬로미터가 어긋납니다. 그 사실을 감추면 사용자는 엉뚱한 목록을 보고
        「이 도구가 틀렸다」고 생각합니다.
      */}
      {position.accuracyM > 2000 ? (
        <p className="muted">
          위치 오차 <strong>약 {Math.round(position.accuracyM / 1000)}km</strong>. 아래 순서가
          실제와 다를 수 있습니다.{' '}
          {/*
            **오차를 알려 주면 다시 잡을 방법도 함께 주어야 합니다.** 예전에는 사실만 말하고
            끝내서, 사용자가 할 수 있는 일이 탭을 바꾸는 것뿐이었습니다. 유선 데스크톱은 IP 로
            위치를 잡아 수 킬로미터가 어긋나는데, 한 번 더 부르면 Wi-Fi 나 GPS 로 잡혀 훨씬
            좁혀지는 경우가 많습니다.
          */}
          <button className="link-button" onClick={locate} disabled={asking}>
            {asking ? '다시 잡는 중입니다' : '내 위치 다시 잡기'}
          </button>
        </p>
      ) : (
        <p className="muted nearby__accuracy">
          위치 오차 약 {Math.round(position.accuracyM)}m.{' '}
          <button className="link-button" onClick={locate} disabled={asking}>
            {asking ? '다시 잡는 중입니다' : '내 위치 다시 잡기'}
          </button>
        </p>
      )}
      {/*
        **다시 잡다가 실패한 것을 조용히 넘기지 마세요.** 앞의 위치가 그대로 남아 있어서
        새로 잡힌 것처럼 보이는데, 실제로는 예전 값입니다.
      */}
      {status.kind === 'failed' && (
        <p className="muted">{status.reason} 아래는 <strong>앞서 잡은 위치</strong> 기준입니다.</p>
      )}
      {/*
        **같은 값이 다시 온 이유는 오차를 봐야 알 수 있습니다.** 수십 미터로 잡혀 있는데
        「Wi-Fi 나 GPS 를 쓸 수 없다」고 말하면 틀린 안내입니다. 이미 정확한 것이고 달라질
        것이 없어서 같은 값이 온 것입니다. 반대로 수 킬로미터로 잡혀 있으면 기기가 IP
        주소로만 위치를 짐작하고 있다는 뜻이고, **같은 IP 면 늘 같은 답이 나오므로 몇 번을
        눌러도 달라지지 않습니다.** 사용자가 할 일이 서로 다르므로 갈라 말합니다.
      */}
      {status.kind === 'same' &&
        (position.accuracyM > 2000 ? (
          <p className="muted">
            같은 위치입니다. <strong>IP 주소로만 잡고 있어</strong> 다시 눌러도 달라지지
            않습니다. 유선 데스크톱에서 흔합니다. 지역 탭이 빠릅니다.
          </p>
        ) : (
          <p className="muted">같은 위치입니다. 이미 정확해 더 좁힐 것이 없습니다.</p>
        ))}
      {/*
        반경을 넘겨 보여 주는 중이면 반드시 밝힙니다. 잠자코 넓히면 사용자는 30km 떨어진
        곳을 「내 주변」으로 읽고, 다녀와서야 멀다는 것을 압니다.
      */}
      {nearby !== null && nearby.widened && (
        <p className="muted">
          {nearby.withinRadius === 0
            ? `반경 ${NEARBY_RADIUS_KM}km 안에는 공공도서관이 없어 가까운 순으로 보여 줍니다.`
            : `반경 ${NEARBY_RADIUS_KM}km 안에는 ${nearby.withinRadius}곳뿐이라 더 먼 곳까지 보여 줍니다.`}
        </p>
      )}
      <ul className="flat-list">
      {(nearby?.libraries ?? []).map((library) => {
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
    </>
  );
}
