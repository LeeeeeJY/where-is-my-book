import { MODES, WORLDCUP_TAB_LABEL, type Mode } from '../domain/modes';
import { BrandMark } from './Brand';

/**
 * 머리말·탭·꼬리말. **첫 화면과 월드컵 화면이 함께 씁니다.**
 *
 * <p>월드컵은 주소가 따로 있는 화면이라 `App` 밖에서 그려집니다. 그렇다고 머리말과 탭을
 * 한 벌 더 적어 두면 한쪽만 고쳐지는 날이 오고, 그때 두 화면은 다른 사이트처럼 보입니다.
 */
export function SiteHead({ tagline = true }: { tagline?: boolean }) {
  return (
    <header className="app__head">
      <div className="brand">
        <BrandMark />
        {/* 약어만 두면 무엇을 하는 곳인지 알 수 없으므로 풀이를 같은 제목 안에 둡니다. */}
        <h1 className="brand__title">
          <span className="brand__mark">WIMB</span>
          <span className="brand__name">Where Is My Book</span>
        </h1>
      </div>
      {tagline && (
        <p className="muted">
          자주 가는 도서관을 골라 두면, 읽고 싶은 책이 그 중 어디에 있는지 알려 줍니다.
        </p>
      )}
    </header>
  );
}

/**
 * 화면을 고르는 탭 줄.
 *
 * <p>앞의 셋은 첫 화면 안에서 내용만 바뀌고, 「월드컵」은 주소가 바뀝니다. 누르는 사람에게
 * 그 차이는 보이지 않아야 하므로 생김새는 넷이 같습니다.
 */
export function ModeTabs({
  active,
  onMode,
  onWorldcup,
}: {
  active: Mode | 'worldcup';
  onMode: (mode: Mode) => void;
  onWorldcup: () => void;
}) {
  return (
    <nav className="tabs tabs--mode" role="tablist">
      {MODES.map(([key, label]) => (
        <button
          key={key}
          role="tab"
          aria-selected={active === key}
          className={active === key ? 'tab tab--active' : 'tab'}
          onClick={() => onMode(key)}
        >
          {label}
        </button>
      ))}
      <button
        role="tab"
        aria-selected={active === 'worldcup'}
        className={active === 'worldcup' ? 'tab tab--active' : 'tab'}
        onClick={onWorldcup}
      >
        {WORLDCUP_TAB_LABEL}
      </button>
    </nav>
  );
}

/*
  정보나루는 국립중앙도서관이 운영하는 서비스입니다. 가운뎃점으로 이으면 서로 다른 두
  곳에서 데이터를 받아 오는 것처럼 읽히는데, 지금 쓰는 출처는 정보나루 하나뿐입니다.
  국립중앙도서관 ISBN 서지정보 API 는 별도 서비스이고 아직 붙이지 않았습니다.
  그것을 붙이면 그때 출처를 하나 더 적습니다.
*/
export function SiteFoot() {
  return (
    <footer className="app__foot muted">
      <div>
        출처:{' '}
        <a href="https://www.data4library.kr" target="_blank" rel="noreferrer noopener">
          도서관 정보나루
        </a>{' '}
        (국립중앙도서관)
      </div>
      {/*
        **개인정보처리방침을 출처와 한 줄에 두지 마세요.** 가운뎃점으로 이어 붙였더니
        출처가 두 곳인 것처럼, 「개인정보처리방침」까지 데이터를 받아 오는 곳 이름으로
        읽혔습니다. 바로 위에서 정보나루와 국립중앙도서관을 가운뎃점으로 잇지 않는
        것과 같은 이유입니다. 성격이 다른 것은 줄을 나눕니다.

        **그리고 앱 밖의 정적 페이지라 리액트 라우팅을 타지 않습니다.** 자바스크립트가
        돌지 않아도, API 서버가 죽어 있어도 열려야 하기 때문입니다. 그래서 `<a>` 로
        그냥 넘깁니다. 주소 규칙은 `vercel.json` 에 있습니다.
      */}
      <div>
        <a href="/privacy">개인정보처리방침</a>
      </div>
    </footer>
  );
}
