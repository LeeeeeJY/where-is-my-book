import { useEffect, useState } from 'react';
import {
  fetchShelfStatus,
  startShelfBuild,
  type ShelfStatus,
  type ShelfSubject,
} from '../api';

/**
 * 서가를 세우는 동안 보여 주는 화면.
 *
 * <h2>왜 기다려야 하나</h2>
 *
 * <p>정보나루는 청구기호로 정렬해 주지도, 그 범위로 걸러 주지도 않습니다. 그래서
 * 「813.6 다음에 무엇이 꽂혀 있나」를 알려면 <b>그 갈래를 통째로 받아 우리가 세워야</b>
 * 합니다. 한 번 세워 두면 그 뒤로는 몇 번을 열어도 즉시 열립니다.
 *
 * <h2>진행률이 없으면 멈춘 것과 구별되지 않습니다</h2>
 *
 * <p>빙글빙글 도는 표시만 두면 사람은 몇십 초를 기다리다 새로 고칩니다. 그러면 화면은
 * 처음부터 다시 시작한 것처럼 보이는데, <b>서버는 이미 돌고 있던 것을 그대로 두므로</b>
 * 실제로는 아무것도 되돌아가지 않습니다. 그래도 사람은 그것을 알 방법이 없습니다.
 * 숫자가 올라가는 것이 보이면 기다립니다.
 */
export function ShelfOpening({
  libCode,
  subject,
  libraryName,
  onReady,
  onGiveUp,
}: {
  libCode: string;
  subject: ShelfSubject;
  libraryName: string;
  onReady: () => void;
  onGiveUp: () => void;
}) {
  const [status, setStatus] = useState<ShelfStatus | 'failed' | null>(null);

  useEffect(() => {
    let stopped = false;
    let timer: number | undefined;

    /*
      **먼저 물어보고, 없을 때만 세우라고 합니다.** 세우는 요청은 정보나루를 수백 번
      부르는 값비싼 통로라 주소별 한도에서도 무겁게 잡혀 있습니다. 이미 세워져 있는데
      부르면 그 무게만 치르고 아무것도 얻지 못합니다.
    */
    const tick = async () => {
      try {
        let now = await fetchShelfStatus(libCode, subject.code);
        if (now.state === 'absent') now = await startShelfBuild(libCode, subject.code);
        if (stopped) return;

        setStatus(now);
        if (now.state === 'ready') {
          onReady();
          return;
        }
        /*
          **2초마다 물어봅니다.** 더 자주 물으면 주소별 한도를 스스로 깎고, 더 뜸하면
          다 세워졌는데도 빈 화면을 몇 초 더 봅니다. 이 호출은 정보나루를 부르지
          않으므로 하루 예산과는 상관이 없습니다.
        */
        timer = window.setTimeout(tick, 2000);
      } catch {
        if (!stopped) setStatus('failed');
      }
    };

    void tick();
    return () => {
      stopped = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [libCode, subject.code, onReady]);

  if (status === 'failed') {
    return (
      <div className="shelf-open">
        <p className="banner banner--info">
          서가를 세우지 못했습니다. 잠시 뒤에 다시 열어 보세요. 검색은 평소대로 됩니다.
        </p>
        <button type="button" className="chip" onClick={onGiveUp}>
          다른 서가 고르기
        </button>
      </div>
    );
  }

  /*
    **하루 몫이 모자라면 서버가 시작하지 않고 「없음」으로 답합니다.** 그것을 계속
    물어보면 영영 세워지지 않는 화면을 보게 되므로, 그 사실을 그대로 말합니다.
  */
  const blocked = status?.state === 'absent';

  return (
    <div className="shelf-open">
      <h2 className="shelf-open__title">
        {libraryName} · {subject.label}
      </h2>

      {blocked ? (
        <p className="banner banner--info">
          오늘 정보나루에 물어볼 수 있는 몫이 얼마 남지 않아 새 서가를 세우지 못합니다.
          내일 다시 열어 보세요. 이미 세워 둔 서가와 검색은 평소대로 됩니다.
        </p>
      ) : (
        <>
          {/*
            **다음부터 빨라진다는 말을 적지 않습니다.** 그것은 우리가 안쪽에서 어떻게 해
            두었는지이지 지금 기다리는 사람이 할 수 있는 일이 아닙니다. 기다리는 화면이
            할 말은 「무엇을 하는 중인가」와 「어디까지 왔는가」 둘뿐입니다.
          */}
          <p className="shelf-open__lead">서가를 둘러보는 중입니다.</p>

          <div
            className="shelf-open__bar"
            role="progressbar"
            aria-valuenow={status?.percent ?? 0}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="서가를 세우는 중"
          >
            <span style={{ width: `${status?.percent ?? 0}%` }} />
          </div>

          <p className="shelf-open__count muted">
            {status && status.books > 0
              ? `${status.books.toLocaleString('ko-KR')}권을 둘러봤습니다`
              : '책을 찾는 중입니다'}
          </p>
        </>
      )}

      <button type="button" className="chip" onClick={onGiveUp}>
        다른 서가 고르기
      </button>

      {!blocked && (
        /*
          **떠나도 세우던 것은 그대로 돕니다.** 그 사실을 말해 두지 않으면 나갔다가
          다시 들어와 처음부터 기다리는 줄 알고, 그때마다 화면을 새로 고칩니다.
        */
        <p className="shelf-open__note muted">
          다른 곳을 보고 와도 됩니다. 세우던 것은 그대로 이어집니다.
        </p>
      )}
    </div>
  );
}
