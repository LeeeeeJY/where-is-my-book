import { useEffect, useRef, useState } from 'react';
import { findOnShelf, type ShelfFound, type ShelfMeta, type ShelfRoom } from '../api';

/**
 * 이 서가 안에서 <b>그 책이 선 자리로 가는</b> 길 찾기.
 *
 * <h2>검색 탭과 다른 일입니다</h2>
 *
 * <p>서가 화면에는 검색이 없습니다. 찾는 책이 있는 사람은 검색 탭으로 갑니다. 그런데
 * <b>서가 앞에 선 사람에게도 「그 자리로 가고 싶다」가 있습니다.</b> 수천 권짜리 서가를
 * 손가락으로 밀어 가로지를 수는 없고, 오른쪽 초성 색인은 도서기호 첫 글자까지만
 * 데려다줍니다. 여기서 돌려받는 것은 <b>「이 서가의 몇 번째 자리」</b>뿐이고, 화면은
 * 그 자리로 옮겨 갑니다.
 *
 * <h2>못 찾은 것은 「그런 책이 없다」가 아닙니다</h2>
 *
 * <p>보고 있는 것은 한 도서관의 한 갈래, 그중 한 자료실입니다. 그 책이 <b>다른
 * 자료실이나 다른 갈래에 서 있을 수 있고</b>, 다른 도서관은 아예 보지도 않았습니다.
 * 「이 서가에는 없습니다」라고 적는 이유가 이것이고, 이 저장소가 「미소장」과 「확인
 * 불가」를 갈라 두는 것과 같은 자리입니다.
 *
 * <h2>호출은 서버 파일 한 번 읽기입니다</h2>
 *
 * <p>찾기 색인은 서가를 세울 때 함께 적어 둔 것이라 <b>정보나루를 한 번도 부르지
 * 않습니다.</b> 그래도 글자를 칠 때마다 묻지는 않습니다. 한글은 조합하는 동안 낱자가
 * 오가서 <b>「ㅌ」, 「토」, 「톶」처럼 아직 말이 아닌 것으로도 묻게 되는데</b>, 답은
 * 쓸모가 없고 요청만 늘어납니다. 사람이 다 적고 누를 때 한 번 묻습니다.
 */
export function ShelfSearch({
  meta,
  room,
  open,
  onOpen,
  onGo,
}: {
  meta: ShelfMeta;
  room: ShelfRoom;
  /**
   * 펼쳐져 있는지. <b>이 상태를 부르는 쪽이 들고 있어야 합니다.</b> 펼치면 머리말이
   * 그만큼 높아지는데, 서가는 <b>「페이지를 얼마나 내렸는가 − 서가가 시작하는 자리」</b>
   * 로 몇 번째 줄인지를 셉니다. 그 자리가 움직인 것을 모르면 줄이 어긋나 스크롤할 때
   * 책이 건너뛰거나 겹쳐 보입니다. 머리말을 접을 때 자리를 다시 재는 것과 같은 이유라,
   * 같은 자리에서 함께 다시 잽니다.
   */
  open: boolean;
  onOpen: (open: boolean) => void;
  /** 고른 자리. 서가는 그 줄로 스크롤하고 「주변 서가」는 그 자리로 옮겨 갑니다. */
  onGo: (at: number) => void;
}) {
  const [text, setText] = useState('');
  const [state, setState] = useState<'idle' | 'asking' | 'failed' | ShelfFound>('idle');
  const box = useRef<HTMLInputElement>(null);

  /*
    **자료실을 바꾸면 찾은 것을 버립니다.** 돌려받은 것이 자리 번호인데 그 번호는
    자료실 안에서만 뜻이 있습니다. 그대로 두면 다른 자료실의 엉뚱한 자리로 갑니다.
  */
  useEffect(() => {
    setState('idle');
  }, [room.slug]);

  useEffect(() => {
    if (open) box.current?.focus();
  }, [open]);

  const ask = () => {
    const q = text.trim();
    if (q.length < 2) return;
    setState('asking');
    findOnShelf(meta.libCode, meta.kdc, room.slug, q).then(
      (found) => setState(found),
      /*
        **못 물어본 것과 이 서가에 없는 것은 다릅니다.** 물어보지 못한 것을 「없다」로
        그리면 멀쩡히 꽂혀 있는 책을 없다고 말하게 됩니다.
      */
      () => setState('failed'),
    );
  };

  if (!open) {
    return (
      <button type="button" className="chip chip--sm" onClick={() => onOpen(true)}>
        책 찾기
      </button>
    );
  }

  return (
    <div className="find">
      <form
        className="find__form"
        onSubmit={(event) => {
          event.preventDefault();
          ask();
        }}
      >
        <input
          ref={box}
          className="find__box"
          type="search"
          /*
            **제 몫으로 한 글자만 주장하게 합니다.** 아무것도 적지 않으면 `input` 은
            스무 글자 폭(190px)을 <b>최소 너비로</b> 주장하고, 그러면 320px 화면에서
            머리말이 화면보다 넓어져 페이지가 통째로 가로로 밀립니다. 실측으로 문서가
            359px 이 되어 도서관 선택 칸까지 함께 넘쳤습니다. CSS 의 `min-width: 0` 은
            이 최소 너비를 지우지 못합니다. 실제 폭은 `flex-grow` 가 정합니다.
          */
          size={1}
          value={text}
          placeholder="제목이나 저자"
          aria-label="이 서가에서 찾을 제목이나 저자"
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== 'Escape') return;
            /*
              **여기서 멈춰 세웁니다.** 「이 책 주변 서가」가 Escape 를 듣고 화면을
              통째로 닫는데, 그대로 두면 찾기를 그만두려던 사람이 보던 자리까지 잃습니다.
            */
            event.stopPropagation();
            onOpen(false);
          }}
        />
        {/*
          **글자가 바뀌어도 단추 폭이 변하면 안 됩니다.** 「찾기」가 「찾는 중」으로
          늘면 옆 단추가 밀려 누른 자리가 눈앞에서 움직입니다. 대출 상태 확인과
          「더 보기」에서 이미 겪은 것과 같은 자리라 같은 방법을 씁니다.
        */}
        <button type="submit" className="chip chip--sm" disabled={text.trim().length < 2}>
          <span className="chip__swap">
            <span className={state === 'asking' ? 'chip__swap--off' : undefined}>찾기</span>
            <span className={state === 'asking' ? undefined : 'chip__swap--off'}>찾는 중</span>
          </span>
        </button>
        <button type="button" className="chip chip--sm" onClick={() => onOpen(false)}>
          닫기
        </button>
      </form>

      <Answer
        state={state}
        onPick={(at) => {
          onOpen(false);
          onGo(at);
        }}
      />
    </div>
  );
}

function Answer({
  state,
  onPick,
}: {
  state: 'idle' | 'asking' | 'failed' | ShelfFound;
  onPick: (at: number) => void;
}) {
  if (state === 'idle' || state === 'asking') return null;

  /*
    **못 물어본 것과 이 서가에 없는 것을 갈라 말합니다.** 앞은 잠시 뒤 다시 하면 되고
    뒤는 다른 자료실이나 다른 갈래를 봐야 합니다. 사람이 할 일이 다릅니다.
  */
  if (state === 'failed') {
    return (
      <p className="find__note muted">지금은 찾을 수 없습니다. 잠시 뒤 다시 해 보세요.</p>
    );
  }

  if (state.hits.length === 0) {
    return (
      <p className="find__note muted">
        이 서가에는 없습니다. 다른 자료실이나 다른 갈래에 꽂혀 있을 수 있습니다.
      </p>
    );
  }

  const more = state.total - state.hits.length;
  return (
    <div className="find__answer">
      <ul className="find__hits">
        {state.hits.map((hit) => (
          <li key={hit.at}>
            <button type="button" className="find__hit" onClick={() => onPick(hit.at)}>
              <span className="find__hit-title">{hit.title}</span>
              <span className="find__hit-meta">
                {[hit.author, hit.call].filter(Boolean).join(' · ')}
              </span>
            </button>
          </li>
        ))}
      </ul>
      {more > 0 && (
        <p className="find__note muted">
          그 밖에 {more.toLocaleString('ko-KR')}권이 더 맞습니다. 조금 더 적으면 좁혀집니다.
        </p>
      )}
    </div>
  );
}
