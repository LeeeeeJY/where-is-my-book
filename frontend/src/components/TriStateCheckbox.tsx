import { useEffect, useRef } from 'react';
import type { CheckState } from '../domain/types';

/**
 * 전체 선택, 부분 선택, 미선택 세 상태를 가지는 체크박스.
 *
 * `indeterminate` 는 HTML 속성이 아니라 DOM 프로퍼티라서 JSX 로 직접 지정할 수 없습니다.
 * `ref` 를 통해 설정해야 합니다. 이걸 모르고 `indeterminate={...}` 를 쓰면
 * 오류 없이 그냥 무시되어 부분 선택이 영영 표시되지 않습니다.
 */
export function TriStateCheckbox({
  state,
  onChange,
  label,
}: {
  state: CheckState;
  onChange: () => void;
  label: string;
}) {
  const ref = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === 'some';
  }, [state]);

  return (
    <input
      ref={ref}
      type="checkbox"
      checked={state === 'all'}
      onChange={onChange}
      aria-label={label}
    />
  );
}
