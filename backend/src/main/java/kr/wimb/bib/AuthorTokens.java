package kr.wimb.bib;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 저자 대조는 문자열 비교가 아니라 토큰 집합으로 합니다.
 * 외국인 이름의 한글 음차는 문자열로 맞출 수 없기 때문입니다.
 *
 * <pre>
 *   "조앤 K. 롤링"  → {조앤, k, 롤링}
 *   "J.K.롤링"      → {j, k, 롤링}
 *   "롤링, 조앤 K." → {롤링, 조앤, k}
 * </pre>
 *
 * 집합이므로 도치 표기(성이 앞에 오는 형태)가 저절로 해결됩니다.
 */
public record AuthorTokens(Set<String> tokens, String compact) {

    public enum Compatibility {
        /** 같은 사람으로 볼 수 있습니다. */
        COMPATIBLE,
        /** 다른 사람입니다. 병합하지 않습니다. */
        INCOMPATIBLE,
        /** 한쪽에 저자 정보가 없어 판단할 수 없습니다. 막지 말고 등급을 낮춥니다. */
        UNKNOWN
    }

    /**
     * 토큰의 말뭉치 출현 빈도. {@code 롤링}은 0.0002라 그것 하나로 동일인을 확정할 수 있지만
     * {@code 김}은 0.2라 아무것도 확정하지 못합니다.
     *
     * <p>빈도표는 군집화를 실제로 돌려야 만들어지므로, 그전까지는 {@link #ASSUME_COMMON}을 씁니다.
     * 모든 토큰을 흔하다고 보므로 희소 토큰 지름길이 작동하지 않고 자카드 유사도가 판단합니다.
     * 병합을 덜 하는 방향이라 안전합니다.
     */
    @FunctionalInterface
    public interface DocumentFrequency {
        double ratioOf(String token);
    }

    public static final DocumentFrequency ASSUME_COMMON = token -> 1.0;

    /** 이 값보다 드문 토큰 하나가 공유되면 동일인으로 확정합니다. */
    private static final double RARE_THRESHOLD = 0.001;

    /** 희소 토큰이 없을 때 요구하는 최소 자카드 유사도. */
    private static final double MIN_JACCARD = 0.5;

    public static AuthorTokens of(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null || name.isBlank()) return new AuthorTokens(out, "");

        String s = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase();

        // 한글 연속과 라틴 문자 연속을 각각 하나의 토큰으로 봅니다.
        // 점과 공백은 경계일 뿐이므로 토큰이 되지 않습니다.
        StringBuilder buf = new StringBuilder();
        Character.UnicodeBlock currentBlock = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock block = classify(c);
            if (block == null) {
                flush(buf, out);
                currentBlock = null;
                continue;
            }
            if (currentBlock != null && block != currentBlock) flush(buf, out);
            currentBlock = block;
            buf.append(c);
        }
        flush(buf, out);
        return new AuthorTokens(out, String.join("", out));
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    /** 두 저자 표기가 같은 사람을 가리키는지 판단합니다. */
    public static Compatibility compare(AuthorTokens a, AuthorTokens b, DocumentFrequency df) {
        if (a.isEmpty() || b.isEmpty()) return Compatibility.UNKNOWN;

        // 붙여 쓴 표기와 띄어 쓴 표기는 같은 사람입니다.
        // 「칼 세이건」과 「칼세이건」은 토큰이 하나도 겹치지 않으므로 이 지름길이 없으면 갈립니다.
        if (a.compact().equals(b.compact())) return Compatibility.COMPATIBLE;

        Set<String> shared = new LinkedHashSet<>(a.tokens());
        shared.retainAll(b.tokens());
        if (shared.isEmpty()) return Compatibility.INCOMPATIBLE;

        // 희소한 토큰이 하나라도 공유되면 그것으로 확정됩니다.
        boolean hasRare = shared.stream()
                .filter(t -> t.length() > 1)          // 이니셜 한 글자는 근거가 되지 못합니다
                .anyMatch(t -> df.ratioOf(t) < RARE_THRESHOLD);
        if (hasRare) return Compatibility.COMPATIBLE;

        Set<String> union = new LinkedHashSet<>(a.tokens());
        union.addAll(b.tokens());
        double jaccard = (double) shared.size() / union.size();
        return jaccard >= MIN_JACCARD ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
    }

    private static Character.UnicodeBlock classify(char c) {
        if (c >= '가' && c <= '힣') return Character.UnicodeBlock.HANGUL_SYLLABLES;
        if (c >= 'a' && c <= 'z') return Character.UnicodeBlock.BASIC_LATIN;
        if (c >= '0' && c <= '9') return Character.UnicodeBlock.BASIC_LATIN;
        if (c >= '一' && c <= '鿿') return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
        return null;
    }

    private static void flush(StringBuilder buf, Set<String> out) {
        if (!buf.isEmpty()) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }
}
