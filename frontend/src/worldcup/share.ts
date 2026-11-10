/**
 * 결과를 밖으로 내보내는 세 가지 길. 공유 창, 그림 저장, 링크 복사입니다.
 *
 * <p><b>공유 창이 없는 환경이 흔합니다.</b> 데스크톱 브라우저 다수가 그렇고, 광고에서
 * 들어온 사람이 쓰는 인스타그램·카카오톡 안의 브라우저도 그림 파일을 공유 창으로 넘기지
 * 못하는 경우가 많습니다. 그래서 되는지 먼저 물어보고, 안 되면 화면이 저장과 복사 단추를
 * 내놓습니다.
 */
export function canShareFile(file: File): boolean {
  try {
    return typeof navigator.share === 'function' && navigator.canShare?.({ files: [file] }) === true;
  } catch {
    return false;
  }
}

export type ShareOutcome = 'shared' | 'cancelled' | 'failed';

export async function shareWithFile(data: {
  file: File;
  title: string;
  text: string;
  url: string;
}): Promise<ShareOutcome> {
  try {
    await navigator.share({ files: [data.file], title: data.title, text: data.text, url: data.url });
    return 'shared';
  } catch (error) {
    // 사람이 공유 창을 닫은 것은 실패가 아닙니다. 그때 저장 단추를 내밀면 누른 적 없는
    // 화면이 열립니다.
    return (error as { name?: string }).name === 'AbortError' ? 'cancelled' : 'failed';
  }
}

/** 주소를 복사합니다. 권한이 없는 브라우저에서는 예전 방식으로 한 번 더 해 봅니다. */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      const done = document.execCommand('copy');
      area.remove();
      return done;
    } catch {
      return false;
    }
  }
}
