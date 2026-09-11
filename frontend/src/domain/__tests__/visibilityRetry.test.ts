import { describe, expect, it } from 'vitest';
import { withVisibilityRetry, type VisibilityDoc } from '../visibilityRetry';

/**
 * 검색을 눌러 놓고 창을 최소화하거나 다른 앱으로 넘어가면 나가 있던 요청이 끊깁니다.
 * 그것을 그대로 실패로 두면 **멀쩡한 서버를 죽었다고 말하게 됩니다.**
 *
 * <p>고쳐야 할 것이 셋이고 셋이 서로 다른 방향으로 틀리기 쉬워서 따로 고정합니다.
 * ① 끊긴 것을 이어 붙이는 것, ② 숨겨진 동안에는 다시 보내지 않는 것(같은 이유로 또
 * 끊기고 호출 예산만 깎입니다), ③ 서버가 답을 준 것은 다시 보내지 않는 것입니다.
 */

/** 화면 가시성을 손으로 바꿀 수 있는 가짜 `document`. */
function fakePage() {
  let state = 'visible';
  const listeners = new Set<() => void>();
  const doc: VisibilityDoc = {
    get visibilityState() {
      return state;
    },
    addEventListener: (_type, listener) => {
      listeners.add(listener);
    },
    removeEventListener: (_type, listener) => {
      listeners.delete(listener);
    },
  };
  const fire = () => {
    for (const listener of [...listeners]) listener();
  };
  return {
    doc,
    hide: () => {
      state = 'hidden';
      fire();
    },
    show: () => {
      state = 'visible';
      fire();
    },
    startHidden: () => {
      state = 'hidden';
    },
    listenerCount: () => listeners.size,
  };
}

type Pending<T> = { resolve: (value: T) => void; reject: (error: unknown) => void };

/** 요청을 손으로 성공·실패시킬 수 있게 붙잡아 둡니다. */
function recorder<T>() {
  const calls: Pending<T>[] = [];
  const send = () =>
    new Promise<T>((resolve, reject) => {
      calls.push({ resolve, reject });
    });
  return { calls, send };
}

/** 밀린 마이크로태스크를 전부 흘려보냅니다. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

const CUT = new TypeError('Failed to fetch');

describe('withVisibilityRetry', () => {
  it('화면을 비운 사이 끊겼으면 돌아왔을 때 다시 보낸다', async () => {
    const page = fakePage();
    const { calls, send } = recorder<string>();

    const result = withVisibilityRetry(send, page.doc);
    await settle();
    expect(calls).toHaveLength(1);

    // 다른 앱으로 넘어가 연결이 끊깁니다.
    page.hide();
    calls[0].reject(CUT);
    await settle();

    // **아직은 다시 보내지 않습니다.** 숨겨진 채로 보내면 같은 이유로 또 끊깁니다.
    expect(calls).toHaveLength(1);

    page.show();
    await settle();
    expect(calls).toHaveLength(2);

    calls[1].resolve('찾았습니다');
    await expect(result).resolves.toBe('찾았습니다');
  });

  it('화면이 내내 보였으면 다시 보내지 않는다', async () => {
    // 이때의 실패는 끊긴 것이 아니라 정말 안 되는 것입니다. 다시 보내 봐야 호출 예산만
    // 깎이고 답은 같습니다.
    const page = fakePage();
    const { calls, send } = recorder<string>();

    const result = withVisibilityRetry(send, page.doc);
    const caught = result.catch((error) => error);
    await settle();

    calls[0].reject(CUT);
    await settle();

    expect(calls).toHaveLength(1);
    expect(await caught).toBe(CUT);
  });

  it('다시 보낸 것도 실패하면 그 오류를 그대로 올린다', async () => {
    // **세 번째는 없습니다.** 두 번째는 화면이 보이는 상태에서 나간 것이라 그 결과가
    // 진실이고, 서버가 정말 죽어 있으면 그때는 「연결하지 못했습니다」가 맞는 답입니다.
    const page = fakePage();
    const { calls, send } = recorder<string>();

    const result = withVisibilityRetry(send, page.doc);
    const caught = result.catch((error) => error);
    await settle();

    page.hide();
    calls[0].reject(CUT);
    await settle();
    page.show();
    await settle();

    const second = new TypeError('again');
    calls[1].reject(second);
    await settle();

    expect(calls).toHaveLength(2);
    expect(await caught).toBe(second);
  });

  it('서버가 답을 준 오류는 숨겨졌어도 다시 보내지 않는다', async () => {
    // 429 를 다시 두드리면 남은 몫만 깎이고 답은 같습니다. 끊긴 것과 답을 받은 것은
    // 다릅니다.
    const page = fakePage();
    const { calls, send } = recorder<string>();
    const answered = new Error('오늘 쓸 수 있는 몫을 다 썼습니다');

    const result = withVisibilityRetry(send, page.doc, (error) => error !== answered);
    const caught = result.catch((error) => error);
    await settle();

    page.hide();
    calls[0].reject(answered);
    await settle();
    page.show();
    await settle();

    expect(calls).toHaveLength(1);
    expect(await caught).toBe(answered);
  });

  it('보내기 전부터 숨겨져 있었어도 끊긴 것으로 본다', async () => {
    // 숨겨진 탭에서 시작한 요청은 `visibilitychange` 가 한 번도 발화하지 않습니다.
    // 시작 시점의 상태를 함께 보지 않으면 이 경우를 통째로 놓칩니다.
    const page = fakePage();
    page.startHidden();
    const { calls, send } = recorder<string>();

    const result = withVisibilityRetry(send, page.doc);
    await settle();
    calls[0].reject(CUT);
    await settle();
    expect(calls).toHaveLength(1);

    page.show();
    await settle();
    calls[1].resolve('찾았습니다');

    await expect(result).resolves.toBe('찾았습니다');
  });

  it('성공하면 그대로 돌려주고 가시성을 더 지켜보지 않는다', async () => {
    const page = fakePage();
    const { calls, send } = recorder<string>();

    const result = withVisibilityRetry(send, page.doc);
    await settle();
    calls[0].resolve('찾았습니다');

    await expect(result).resolves.toBe('찾았습니다');
    expect(calls).toHaveLength(1);
    // 요청마다 리스너가 쌓이면 목록을 확인할 때마다 수십 개가 남습니다.
    expect(page.listenerCount()).toBe(0);
  });

  it('가시성을 볼 수 없는 곳에서는 그대로 한 번만 보낸다', async () => {
    const { calls, send } = recorder<string>();

    const result = withVisibilityRetry(send, null);
    const caught = result.catch((error) => error);
    await settle();

    calls[0].reject(CUT);
    await settle();

    expect(calls).toHaveLength(1);
    expect(await caught).toBe(CUT);
  });
});
