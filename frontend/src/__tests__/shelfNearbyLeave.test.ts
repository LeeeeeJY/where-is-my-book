import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * <b>덮개가 나갈 때도 들어올 때처럼 층을 갖추는지</b> 봅니다.
 *
 * <p>여기서 지키는 것은 전부 <b>어겨도 화면이 멀쩡해 보이는</b> 것들입니다. 층 하나가
 * 나가는 목록에서 빠지면 그 층만 어둠과 같은 속도로 사라져 서가 위에 비치는데, 그것은
 * 300ms 동안 한 번 스쳐 지나갈 뿐이라 눌러 본 사람도 알아채기 어렵습니다. 들어올 때
 * 같은 일을 겪고 「직계 자식은 하나도 빠짐없이 목록에 있어야 한다」를 적어 둔 자리가
 * 바로 그것입니다(`CLAUDE.md`).
 *
 * <p>`siteUrl.test.ts` 와 같은 종류라 <b>값이 무엇인지가 아니라 서로 어긋나는지</b>만
 * 봅니다. 차례를 바꾸거나 길이를 고치는 것은 정상적인 일이므로 막지 않습니다.
 */
const read = (name: string) =>
  readFileSync(fileURLToPath(new URL(`../${name}`, import.meta.url)), 'utf8');

/** 주석에도 `.nearby__…` 이 적혀 있으므로(「무대에는 걸지 마세요」) 먼저 걷어냅니다. */
const css = read('styles.css').replace(/\/\*[\s\S]*?\*\//g, '');
const source = read('components/ShelfNearby.tsx');

/** `animation-name` 을 받는 규칙에서 `.nearby__…` 이름만 거둡니다. */
const layersOf = (leaving: boolean) => {
  const found = new Set<string>();
  for (const rule of css.split('}')) {
    if (!rule.includes('animation-name:') && !rule.includes('animation:')) continue;
    if (rule.includes('--leaving') !== leaving) continue;
    for (const [, name] of rule.matchAll(/\.nearby__([a-z]+)/g)) found.add(name);
  }
  return found;
};

describe('덮개가 나가는 차례', () => {
  it('들어오는 층은 모두 나가는 층에도 있다', () => {
    // 빠진 층은 「층이 없는 것」이 아니라 **어둠과 같은 속도로** 사라집니다. 어둠이
    // 0.6 인 순간 그 층도 0.6 이라 서가 위에 그대로 비칩니다.
    const missing = [...layersOf(false)].filter((one) => !layersOf(true).has(one));
    expect(missing).toEqual([]);
  });

  it('나갈 때 무대 자체에는 걸지 않는다', () => {
    // 옆으로 넘길 때 줄을 미끄러뜨리는 걸음이 `element.animate` 로 `.nearby__stage` 를
    // 잡고 있는데, 대본으로 건 것이 CSS 애니메이션보다 뒤에 놓여 이깁니다. 넘기자마자
    // 닫으면 무대만 남아 서가 위에 비칩니다.
    expect(layersOf(true).has('stage')).toBe(false);
  });

  it('나가는 길이 전부 한 곳을 거친다', () => {
    // 뒤로 가기, Escape, 「서가에서 보기」 셋입니다. 하나라도 바로 부르면 그 길로
    // 나갈 때만 덮개가 툭 사라지는데, 나머지 둘은 멀쩡해서 고장으로 보이지 않습니다.
    expect(source).not.toMatch(/onClick=\{onClose\}/);
    expect(source).toMatch(/leave\(onClose\)/);
    expect(source).toMatch(/leave\(\(\) => onShowOnShelf\(index\)\)/);
  });

  it('어둠이 다 걷힌 뒤에 내보낸다', () => {
    // 길이를 대본에 또 적으면 차례를 고칠 때 두 곳이 갈립니다. 끝났다는 말을 듣되,
    // 탭이 숨겨져 있으면 그 말이 오지 않으므로 넉넉한 상한을 함께 둡니다.
    expect(source).toMatch(/animationName === 'nearby-shut'/);
    expect(css).toMatch(/@keyframes nearby-shut/);
    expect(source).toMatch(/LEAVE_GIVE_UP_MS/);
  });
});
