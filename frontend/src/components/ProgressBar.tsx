/**
 * 소장 확인이 어디까지 왔는지 보여 주는 막대.
 *
 * **목록을 확인이 끝난 뒤에 한 번에 그리기로 했으므로, 그동안 무언가 진행되고 있다는 것을
 * 이것이 말해야 합니다.** 스무 권을 도서관 두 곳에서 확인하면 정보나루 호출이 수십 번이고
 * 요청 사이에 간격을 두므로 몇 초가 걸립니다. 그동안 아무것도 움직이지 않으면 사용자는
 * 검색이 멈췄다고 읽고 다시 누릅니다.
 */
export function ProgressBar({
  done,
  total,
  label,
}: {
  done: number;
  total: number;
  label: string;
}) {
  const percent = total === 0 ? 0 : Math.round((Math.min(done, total) / total) * 100);
  return (
    <div
      className="progress"
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={total}
      aria-valuenow={Math.min(done, total)}
      aria-label={label}
    >
      <div className="progress__track">
        <div className="progress__fill" style={{ width: `${percent}%` }} />
      </div>
      <p className="progress__label muted">
        {label} · {Math.min(done, total)}/{total}권
      </p>
    </div>
  );
}
