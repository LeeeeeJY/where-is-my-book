package kr.wimb.index;

/**
 * 정규화한 문자열을 2글자 조각으로 자릅니다.
 *
 * <p>PGroonga 를 쓸 수 없게 되었을 때의 대체 경로입니다. Neon 의 pg_search 가 예고 후
 * 여섯 달 만에 제거되는 것을 보면 관리형 서비스의 확장 지원은 영구적이지 않으므로,
 * 토큰화를 확장에 맡기지 않고 우리 코드에 둡니다.
 *
 * <p>색인 시점에 이 값을 함께 저장해 두면 전환이 색인 교체만으로 끝나고 재적재가
 * 필요 없습니다.
 */
public final class Bigrams {

    private Bigrams() {}

    /**
     * {@code "해리포터"} 를 {@code "해리 리포 포터"} 로 만듭니다.
     *
     * <p>질의 「포터」는 조각 하나로 걸리고, 질의 「해리포터」는 세 조각을 모두 요구하므로
     * 정확합니다. 입력은 이미 공백이 제거된 정규화 문자열이어야 합니다.
     */
    public static String of(String normalized) {
        if (normalized == null || normalized.isEmpty()) return "";
        if (normalized.length() == 1) return normalized;

        StringBuilder out = new StringBuilder(normalized.length() * 3);
        for (int i = 0; i + 1 < normalized.length(); i++) {
            if (!out.isEmpty()) out.append(' ');
            out.append(normalized, i, i + 2);
        }
        return out.toString();
    }
}
