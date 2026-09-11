import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * **방문자 수 세기와 개인정보처리방침이 함께 움직이는지 봅니다.**
 *
 * <p>`siteUrl.test.ts` 와 같은 종류의 시험입니다. 값이 무엇인지는 보지 않고 **서로 어긋나지
 * 않는지**만 봅니다. 세는 도구를 붙이거나 떼는 것은 정상적인 일이라 막을 이유가 없고,
 * 막아야 하는 것은 코드만 바뀌고 문서가 그대로 남는 것입니다.
 *
 * <p>어긋나도 **화면에는 아무 이상이 없어 보입니다.** 문서는 그대로 열리고 글자도 멀쩡한데
 * 적힌 내용만 사실이 아니게 되므로, 눌러 보거나 읽어 봐서 잡을 수 있는 자리가 아닙니다.
 */
const read = (name: string) =>
  readFileSync(fileURLToPath(new URL(`../../${name}`, import.meta.url)), 'utf8');

const mainTsx = read('src/main.tsx');
const privacyHtml = read('public/privacy.html');
const packageJson = JSON.parse(read('package.json')) as {
  dependencies?: Record<string, string>;
};

/**
 * 실제로 켜져 있는지는 진입점이 정합니다. 의존성 목록에 있는 것만으로는 화면에 붙었는지
 * 알 수 없습니다.
 */
const counting = mainTsx.includes('@vercel/analytics');

describe('방문자 수 세기', () => {
  it('의존성과 진입점이 따로 놀지 않는다', () => {
    // 한쪽만 지우면 빌드는 그대로 통과합니다. 의존성만 남으면 번들만 무거워지고,
    // 진입점만 남으면 배포할 때에야 깨집니다.
    expect('@vercel/analytics' in (packageJson.dependencies ?? {})).toBe(counting);
  });

  it.skipIf(!counting)('세고 있으면 개인정보처리방침이 없다고 말하지 않는다', () => {
    // 붙이기 전의 문구입니다. 이것이 남아 있으면 문서가 그 자리에서 거짓말이 됩니다.
    expect(privacyHtml).not.toMatch(/분석하는 도구도 넣지 않았습니다/);
  });

  it.skipIf(!counting)('세고 있으면 무엇이 오가는지 개인정보처리방침에 적혀 있다', () => {
    expect(privacyHtml).toMatch(/다녀간 수를 세는 것/);
    expect(privacyHtml).toMatch(/Vercel/);
  });

  it.skipIf(!counting)('고른 도서관이 주소에 실린 채 나가지 않는다', () => {
    /*
     * 주소의 `?libs=` 에 고른 도서관이 들어 있고(`domain/selectionUrl.ts`), 선택이 바뀔
     * 때마다 `App` 이 `replaceState` 로 주소를 고쳐 씁니다. 떼지 않으면 체크 한 번에 한
     * 줄씩 넘어가는데, 오류도 경고도 나지 않아 새는 것을 알 방법이 없습니다.
     */
    expect(mainTsx).toMatch(/beforeSend=/);
    expect(mainTsx).toMatch(/\.search = ''/);
  });
});
