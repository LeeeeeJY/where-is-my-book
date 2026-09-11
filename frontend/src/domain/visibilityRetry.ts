/**
 * 화면을 비운 사이 끊긴 요청을, 돌아왔을 때 **한 번만** 다시 보냅니다.
 *
 * <p>창을 최소화하거나 다른 앱으로 넘어가면 나가 있던 요청이 끊깁니다. 휴대폰은 앱을
 * 바꾸는 순간 페이지가 통째로 얼면서 열려 있던 연결이 닫히고, 데스크톱도 오래 숨겨져
 * 있으면 같은 일이 생깁니다. 우리 요청은 **특히 길어서 이것을 정면으로 맞습니다.**
 * 정보나루가 `srchBooks` 한 번에 3.7초, `libSrchByBook` 한 번에 4.9초를 쓰고 스무 권
 * 확인이 59초까지 갑니다. 그 시간 동안 사람이 가만히 화면을 보고 있을 이유가 없으므로,
 * **기다리다 다른 일을 하러 가는 것이 예외가 아니라 보통입니다.**
 *
 * <p>그렇게 끊긴 것을 그대로 실패로 두면 화면에는 「검색 서버에 연결하지 못했습니다」가
 * 뜹니다. **서버는 멀쩡한데 서버가 죽었다고 말하는 것**이라, 우리가 줄곧 갈라 놓으려는
 * 「서버가 없다」와 「서버는 있는데 물어보지 못했다」가 여기서 뒤바뀝니다. 운영자는 멀쩡한
 * 서버를 다시 띄우고, 사용자는 검색이 고장 났다고 읽습니다. 게다가 오류도 로그도 남지
 * 않고 창을 띄워 둔 채 기다린 사람에게는 멀쩡히 되므로, 재현조차 어렵습니다.
 *
 * <p><b>숨겨진 사이에 곧바로 다시 보내면 안 됩니다.</b> 같은 이유로 또 끊기고 하루 호출
 * 예산만 깎입니다. 화면이 다시 보일 때까지 기다렸다가 그때 보냅니다.
 *
 * <p><b>다시 보내는 것은 한 번뿐이고, 그것도 실패하면 그 오류를 그대로 올립니다.</b>
 * 두 번째는 화면이 보이는 상태에서 나간 것이라 그 결과가 진실입니다. 서버가 정말 죽어
 * 있으면 그때는 「연결하지 못했습니다」가 맞는 답입니다.
 */

/** `document` 가운데 여기서 쓰는 부분만. 테스트가 가짜를 넣을 수 있게 좁혀 둡니다. */
export type VisibilityDoc = {
  readonly visibilityState: string;
  addEventListener(type: 'visibilitychange', listener: () => void): void;
  removeEventListener(type: 'visibilitychange', listener: () => void): void;
};

/**
 * @param send 보낼 요청. 다시 보낼 수 있어야 하므로 함수로 받습니다.
 * @param doc 가시성을 볼 수 있는 곳. 브라우저 밖(테스트 등)에서는 `null` 이고, 그때는
 *   그대로 한 번만 보냅니다.
 * @param retriable 이 오류가 **끊긴 것인지** 판정합니다. 서버가 답을 준 것은 끊긴 것이
 *   아니므로 다시 보내면 안 됩니다. 기본값은 전부 다시 보내는 것입니다.
 */
export async function withVisibilityRetry<T>(
  send: () => Promise<T>,
  doc: VisibilityDoc | null,
  retriable: (error: unknown) => boolean = () => true,
): Promise<T> {
  if (doc === null) return send();

  // **실패한 시점의 `visibilityState` 만 보면 안 됩니다.** 페이지가 얼어 있는 동안에는
  // 자바스크립트가 돌지 않아서, 끊겼다는 사실이 사용자가 **돌아온 뒤에** 보고되는 일이
  // 흔합니다. 그때 다시 보니 화면은 이미 보이는 상태라 「내내 보였다」로 잘못 읽힙니다.
  // 그래서 요청이 나가 있는 동안 한 번이라도 숨겨졌는지를 지켜봅니다.
  let sawHidden = doc.visibilityState === 'hidden';
  const watch = () => {
    if (doc.visibilityState === 'hidden') sawHidden = true;
  };
  doc.addEventListener('visibilitychange', watch);
  try {
    return await send();
  } catch (error) {
    // 화면이 내내 보이는 채로 실패했으면 끊긴 것이 아니라 정말 안 되는 것입니다.
    if (!sawHidden || !retriable(error)) throw error;
  } finally {
    doc.removeEventListener('visibilitychange', watch);
  }

  await whenVisible(doc);
  return send();
}

/** 화면이 다시 보일 때까지 기다립니다. 이미 보이면 곧바로 돌아옵니다. */
function whenVisible(doc: VisibilityDoc): Promise<void> {
  if (doc.visibilityState !== 'hidden') return Promise.resolve();
  return new Promise((resolve) => {
    const wake = () => {
      if (doc.visibilityState === 'hidden') return;
      doc.removeEventListener('visibilitychange', wake);
      resolve();
    };
    doc.addEventListener('visibilitychange', wake);
  });
}
