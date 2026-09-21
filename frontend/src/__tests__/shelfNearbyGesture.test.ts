import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * <b>「이 책 주변 서가」에서 끌고 나서 누르는 첫 탭이 사라지지 않게</b> 붙들어 둡니다.
 *
 * <p>`analyticsPrivacy.test.ts` 와 같은 종류라 <b>값이 무엇인지가 아니라 서로 어긋나는지</b>를
 * 봅니다. 여기서 지키는 둘은 되돌려 놓아도 **빌드가 통과하고 화면에도 경고에도 아무것도
 * 남지 않습니다.** 손가락으로 끌어 보고 곧바로 눌러 본 사람만 알 수 있는데, 그것도 관성이
 * 도는 200ms 안에 눌러야 드러납니다.
 *
 * <p>헤드리스 브라우저로는 잡히지 않습니다. 손짓 인식기와 관성이 거기서는 돌지 않아
 * 합성 터치를 아무리 넣어도 늘 통과합니다. 실측 경위는 `docs/가정과-검증상태.md` 2-25 에
 * 있습니다.
 */
const read = (name: string) =>
  readFileSync(fileURLToPath(new URL(`../${name}`, import.meta.url)), 'utf8');

/** 주석 안에도 중괄호가 있으므로(`tabIndex={-1}`) 먼저 걷어내고 봅니다. */
const css = read('styles.css').replace(/\/\*[\s\S]*?\*\//g, '');
const nearbyRule = /^\.nearby\s*\{([^}]*)\}/m.exec(css)?.[1] ?? '';

const source = read('components/ShelfNearby.tsx');

describe('덮개의 손짓', () => {
  it('.nearby 가 스크롤을 통째로 잠근다', () => {
    // `pan-y` 는 세로 스크롤을 잠그는 것이 아니라 허용하는 것이라, 덮개 위에서 세로로
    // 끌면 뒤에 가려진 서가가 밀려 올라갑니다. 덮개가 불투명해서 그때는 보이지 않고
    // 닫고 나서야 보던 줄이 아닌 데 서 있게 됩니다.
    expect(nearbyRule).toMatch(/touch-action:\s*none/);
  });

  it('touchmove 를 passive 가 아닌 자리에서 직접 막는다', () => {
    // React 는 `touchmove` 를 passive 로 답니다. `onTouchMove` 로 옮기면 코드는 그대로인데
    // `preventDefault()` 가 아무 일도 하지 않아, 관성이 남고 첫 탭이 다시 사라집니다.
    expect(source).not.toMatch(/onTouchMove=/);
    expect(source).toMatch(/addEventListener\(\s*'touchmove'[\s\S]{0,80}passive:\s*false/);
  });

  it('막는 것은 우리가 가져간 손짓뿐이다', () => {
    // 시트와 머리 줄에서 시작한 것까지 막으면 청구기호를 손가락으로 골라 복사하는 것이
    // 함께 막힙니다. 가르는 값은 `onTouchStart` 가 적어 두는 것 하나뿐입니다.
    expect(source).toMatch(/touchFrom\.current !== null[\s\S]{0,40}preventDefault\(\)/);
  });
});
