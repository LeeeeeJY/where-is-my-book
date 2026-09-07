/**
 * 로고 심볼. 꽂혀 있는 책등 셋 가운데 하나만 강조색입니다.
 *
 * 「여러 권 가운데 어느 것이 어디에 있는지 짚어 준다」는 이 도구가 하는 일과 그림이
 * 겹칩니다. 그리고 형태가 단순해서 파비콘 크기로 줄여도 알아볼 수 있습니다.
 *
 * <p><b>세 번째 책등을 기울여 둔 것이 중요합니다.</b> 곧게 선 막대 셋에 밑줄을 그으면
 * 30px 에서 책장이 아니라 막대그래프로 읽힙니다. 막대그래프에는 기운 막대가 없으므로
 * 이 기울기 하나가 둘을 갈라 줍니다.
 *
 * <p>색은 CSS 에 두고 여기서는 클래스만 붙입니다. 강조색을 바꿀 일이 생겼을 때 고칠 곳이
 * `styles.css` 한 군데로 모입니다. 도형은 `frontend/public/favicon.svg` 와 같은 좌표를
 * 쓰므로, 한쪽을 고치면 다른 쪽도 같이 고쳐야 합니다.
 */
export function BrandMark({ size = 34 }: { size?: number }) {
  return (
    <svg
      className="brand__symbol"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      <rect className="brand__spine" x="4.4" y="8.6" width="4.6" height="10.8" rx="1" />
      <rect
        className="brand__spine brand__spine--found"
        x="9.7"
        y="5.4"
        width="4.6"
        height="14"
        rx="1"
      />
      <rect
        className="brand__spine"
        x="15.2"
        y="7.6"
        width="4.6"
        height="11.8"
        rx="1"
        transform="rotate(13 17.5 19.4)"
      />
      <rect className="brand__shelf" x="2.4" y="19.4" width="19.2" height="2" rx="1" />
    </svg>
  );
}
