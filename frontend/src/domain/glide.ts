/**
 * 서가를 <b>미끄러지듯 가로지릅니다.</b>
 *
 * <h2>왜 브라우저에 맡기지 않나</h2>
 *
 * <p>`scrollTo({ behavior: 'smooth' })` 는 거리와 상관없이 정해진 시간에 끝냅니다.
 * 스무 줄을 내려가는 것과 <b>4만 픽셀을 가로지르는 것이 같은 시간</b>이라, 먼 거리는
 * 화면이 한 번 번쩍하고 끝난 것처럼 보입니다. 서가를 걸어가는 화면에서 그것은 도착한
 * 느낌이 아니라 <b>잘라 붙인 느낌</b>입니다.
 *
 * <p>그래서 거리에 따라 시간을 정하고, 떠날 때는 천천히 붙었다가 가운데서 달리고
 * 끝에서 길게 내려앉게 합니다.
 *
 * <h2>도착 자리를 매 프레임 다시 셉니다</h2>
 *
 * <p>가는 동안 <b>머리말이 접혀 문서가 87px 짧아집니다.</b> 목표를 처음 한 번만 정해
 * 두면 그 순간 발밑이 그만큼 움직여 화면이 덜컥 튑니다. 매 프레임 다시 세면 그 변화가
 * 나누어 흡수되어 눈에 띄지 않습니다.
 *
 * <h2>사람이 손대면 즉시 멈춥니다</h2>
 *
 * <p>가는 도중에 사용자가 스크롤하면 <b>화면이 손과 싸웁니다.</b> 밀어 내린 만큼 다시
 * 끌려 올라가는데, 그건 고장으로 읽힙니다. 손이 닿는 순간 그대로 놓습니다.
 */

/** 움직임을 줄여 달라고 한 사람에게는 애니메이션을 걸지 않습니다. */
export function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    // 오래된 브라우저나 시험 환경에서는 물어볼 수 없습니다. 그때는 그냥 움직입니다.
    return false;
  }
}

/**
 * 그 거리를 가는 데 쓸 시간(ms).
 *
 * <p>거리에 정비례시키면 먼 거리가 몇 초씩 걸려 갇힌 느낌이 됩니다. 제곱근으로 눌러
 * <b>가까운 곳은 빠르게, 먼 곳은 「이동했다」가 느껴질 만큼만</b> 씁니다.
 */
export function glideDuration(distance: number): number {
  const far = Math.abs(distance);
  return Math.min(1100, Math.max(280, Math.round(260 + 3.6 * Math.sqrt(far))));
}

/**
 * 떠나고 도착하는 곡선(easeInOutQuart).
 *
 * <p>가운데에서 값과 기울기가 모두 이어지므로 <b>속도가 한 번도 튀지 않습니다.</b>
 * 네 제곱이라 3제곱보다 가운데가 빠르고 끝이 길어서, 먼 거리를 갈 때 「달리다가 사뿐히
 * 내려앉는」 느낌이 납니다.
 */
export function easeGlide(t: number): number {
  const x = Math.min(1, Math.max(0, t));
  return x < 0.5 ? 8 * x * x * x * x : 1 - Math.pow(-2 * x + 2, 4) / 2;
}

/**
 * 그 자리로 미끄러져 갑니다. <b>돌려주는 함수를 부르면 그 자리에서 멈춥니다.</b>
 *
 * @param targetOf 도착할 스크롤 자리. **매 프레임 다시 부릅니다.** 가는 동안 머리말이
 *   접혀 문서가 짧아지므로, 한 번 정해 둔 값을 쓰면 발밑이 움직여 화면이 튑니다
 */
export function glideTo(targetOf: () => number): () => void {
  const from = window.scrollY;
  if (prefersReducedMotion()) {
    window.scrollTo(0, targetOf());
    return () => {};
  }

  const duration = glideDuration(targetOf() - from);
  const startedAt = performance.now();
  let frame = 0;
  let done = false;

  const stop = () => {
    if (done) return;
    done = true;
    cancelAnimationFrame(frame);
    for (const name of HANDS_ON) window.removeEventListener(name, stop);
  };

  // 손이 닿으면 그대로 놓습니다. `passive` 로 듣는 것은 막을 생각이 없기 때문입니다.
  for (const name of HANDS_ON) window.addEventListener(name, stop, { passive: true });

  const step = (now: number) => {
    if (done) return;
    const t = Math.min(1, (now - startedAt) / duration);
    // 도착 자리는 매번 다시 셉니다. 출발 자리만 붙들어 두면 그 사이의 변화가 나뉘어
    // 흡수됩니다.
    window.scrollTo(0, Math.round(from + (targetOf() - from) * easeGlide(t)));
    if (t >= 1) stop();
    else frame = requestAnimationFrame(step);
  };
  frame = requestAnimationFrame(step);
  return stop;
}

/** 이 가운데 하나라도 오면 사람이 손댄 것입니다. */
const HANDS_ON = ['wheel', 'touchstart', 'keydown', 'pointerdown'] as const;
