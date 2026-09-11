import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiUnavailable, ApiUnreadable, fetchHoldings } from '../api';

/**
 * **검색을 눌러 놓고 창을 최소화하거나 다른 앱으로 넘어갔을 때** 어떻게 되는지 봅니다.
 *
 * <p>그 순간 브라우저가 나가 있던 연결을 끊습니다. 우리 요청은 정보나루 때문에 한 번에
 * 수 초에서 수십 초가 걸리므로, 기다리다 다른 일을 하러 가는 것이 예외가 아니라 보통입니다.
 * 끊긴 것을 그대로 실패로 두면 화면에 「검색 서버에 연결하지 못했습니다」가 떠서
 * **멀쩡한 서버를 죽었다고 말하게 됩니다.**
 *
 * <p>보호가 {@link api.ts} 의 통로 하나에 있으므로 **그 통로를 지나지 않는 호출이 생기면
 * 그 화면만 조용히 예전 상태로 돌아갑니다.** 오류도 경고도 나지 않고, 창을 띄워 둔 채
 * 기다린 사람에게는 멀쩡히 되기 때문에 눈에 띄지도 않습니다.
 */

/** 화면 가시성을 손으로 바꿀 수 있는 가짜 `document`. */
function fakePage() {
  let state = 'visible';
  const listeners = new Set<() => void>();
  const fire = () => {
    for (const listener of [...listeners]) listener();
  };
  return {
    doc: {
      get visibilityState() {
        return state;
      },
      addEventListener: (_type: string, listener: () => void) => {
        listeners.add(listener);
      },
      removeEventListener: (_type: string, listener: () => void) => {
        listeners.delete(listener);
      },
    },
    hide: () => {
      state = 'hidden';
      fire();
    },
    show: () => {
      state = 'visible';
      fire();
    },
  };
}

type Pending = { resolve: (response: Response) => void; reject: (error: unknown) => void };

/** 나간 요청을 붙잡아 두고 손으로 성공·실패시킵니다. */
function stubFetch(): Pending[] {
  const calls: Pending[] = [];
  globalThis.fetch = (() =>
    new Promise<Response>((resolve, reject) => {
      calls.push({ resolve, reject });
    })) as typeof fetch;
  return calls;
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

/** 밀린 마이크로태스크를 전부 흘려보냅니다. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

const CUT = new TypeError('Failed to fetch');
const HOLDINGS = { libCodes: ['111001'], complete: true, unreadable: false, asOf: '2026-09-09' };

const realFetch = globalThis.fetch;
let page: ReturnType<typeof fakePage>;

beforeEach(() => {
  page = fakePage();
  (globalThis as { document?: unknown }).document = page.doc;
});

afterEach(() => {
  delete (globalThis as { document?: unknown }).document;
  globalThis.fetch = realFetch;
});

describe('화면을 비운 사이 끊긴 요청', () => {
  it('돌아왔을 때 다시 보내고, 사용자는 오류를 보지 않는다', async () => {
    const calls = stubFetch();
    const result = fetchHoldings(['9788983711892'], ['111001']);
    await settle();
    expect(calls).toHaveLength(1);

    // 다른 앱으로 넘어가 연결이 끊깁니다.
    page.hide();
    calls[0].reject(CUT);
    await settle();

    // **숨겨진 채로는 다시 보내지 않습니다.** 같은 이유로 또 끊기고 예산만 깎입니다.
    expect(calls).toHaveLength(1);

    page.show();
    await settle();
    expect(calls).toHaveLength(2);

    calls[1].resolve(json(HOLDINGS));
    await expect(result).resolves.toMatchObject({ complete: true, unreadable: false });
  });

  it('서버가 답을 준 것은 숨겨졌어도 다시 보내지 않는다', async () => {
    // 429 를 다시 두드리면 남은 몫만 깎이고 답은 같습니다. **끊긴 것과 답을 받은 것은
    // 다릅니다.** 이 판정이 빠지면 호출 제한에 걸린 사람이 두 배로 깎입니다.
    const calls = stubFetch();
    const result = fetchHoldings(['9788983711892'], ['111001']);
    const caught = result.catch((error) => error);
    await settle();

    page.hide();
    calls[0].resolve(json({ code: 'RATE_LIMITED', reason: '잠시 뒤 다시 해 주세요.' }, 429));
    await settle();
    page.show();
    await settle();

    expect(calls).toHaveLength(1);
    const error = await caught;
    expect(error).toBeInstanceOf(ApiUnreadable);
    expect((error as ApiUnreadable).code).toBe('RATE_LIMITED');
  });

  it('화면이 내내 보이는 채로 끊겼으면 그대로 실패로 답한다', async () => {
    // 이때는 정말 서버가 안 되는 것이므로 「연결하지 못했습니다」가 맞는 답입니다.
    const calls = stubFetch();
    const result = fetchHoldings(['9788983711892'], ['111001']);
    const caught = result.catch((error) => error);
    await settle();

    calls[0].reject(CUT);
    await settle();

    expect(calls).toHaveLength(1);
    expect(await caught).toBeInstanceOf(ApiUnavailable);
  });

  it('다시 보낸 것도 실패하면 거기서 멈춘다', async () => {
    // **세 번째는 없습니다.** 두 번째는 화면이 보이는 상태에서 나간 것이라 그 결과가
    // 진실입니다.
    const calls = stubFetch();
    const result = fetchHoldings(['9788983711892'], ['111001']);
    const caught = result.catch((error) => error);
    await settle();

    page.hide();
    calls[0].reject(CUT);
    await settle();
    page.show();
    await settle();

    calls[1].reject(CUT);
    await settle();

    expect(calls).toHaveLength(2);
    expect(await caught).toBeInstanceOf(ApiUnavailable);
  });
});

/**
 * `siteUrl.test.ts` 와 같은 종류라 **무엇이 어떻게 구현되었는지는 보지 않고 서로 어긋나는지만**
 * 봅니다. 통로를 고치는 것은 정상적인 일이고, 막아야 하는 것은 그 통로를 비켜 가는 호출이
 * 생기는 것입니다.
 */
describe('API 호출 통로', () => {
  const read = (name: string) =>
    readFileSync(fileURLToPath(new URL(`../${name}`, import.meta.url)), 'utf8');

  it('fetch 를 부르는 곳이 api.ts 안에 한 곳뿐이다', () => {
    // 두 곳이 되는 순간 한쪽에만 보호가 붙습니다. 실제로 예전에는 GET 과 POST 가 같은
    // 코드를 한 벌씩 들고 있었고, 그래서 오류 이유를 꺼내는 것도 두 곳에 따로 있었습니다.
    expect(read('api.ts').match(/\bfetch\(/g)).toHaveLength(1);
  });

  it('화면이 fetch 를 직접 부르지 않는다', () => {
    // 화면에서 직접 부르면 이 보호도, 서버가 실어 보낸 오류 이유를 꺼내는 것도 함께
    // 건너뜁니다.
    const dir = fileURLToPath(new URL('../components', import.meta.url));
    const sources = [
      ['App.tsx', read('App.tsx')] as const,
      ...readdirSync(dir)
        .filter((name) => name.endsWith('.tsx'))
        .map((name) => [name, readFileSync(`${dir}/${name}`, 'utf8')] as const),
    ];

    const offenders = sources
      .filter(([, source]) => /\bfetch\(/.test(source))
      .map(([name]) => name);

    expect(offenders).toEqual([]);
  });
});
