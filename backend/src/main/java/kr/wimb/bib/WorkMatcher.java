package kr.wimb.bib;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 두 서지가 같은 저작인지 판정합니다. 계획서 7절 D단계의 규칙 표를 그대로 구현합니다.
 *
 * <p>단일 해시 키로 묶지 않는 이유는, 필드 하나만 달라도 놓치는 데다
 * 왜 그렇게 묶였는지 설명할 수 없기 때문입니다. 판정마다 규칙 코드를 함께 돌려주므로
 * 잘못된 묶음을 만났을 때 어느 규칙이 그랬는지 바로 알 수 있습니다.
 *
 * <p><b>병합 판정만 실제로 합칩니다.</b> 검토 판정은 합치지 않고 사람이 볼 큐에 쌓습니다.
 */
public final class WorkMatcher {

    private WorkMatcher() {}

    public enum Verdict { MERGE, REVIEW, REJECT }

    /**
     * @param ruleCode 어느 규칙이 판정했는지. bib_merge_edge에 그대로 남깁니다.
     */
    public record Decision(Verdict verdict, String ruleCode, double confidence) {
        public boolean isMerge() { return verdict == Verdict.MERGE; }
    }

    /** 판정에 필요한 서지 한 건. */
    public record Candidate(
            String isbn13,
            TitleParts title,
            List<Contributor> contributors,
            String publisherNorm,
            Integer pubYear,
            String language
    ) {
        public static Candidate of(String rawTitle, String rawAuthors) {
            return of(rawTitle, rawAuthors, null, null, null);
        }

        public static Candidate of(String rawTitle, String rawAuthors,
                                   String rawPublisher, Integer pubYear, String isbn13) {
            TitleParts parts = BibNormalizer.parseTitle(rawTitle);
            // 저자 필드가 비어 있으면 표제에 섞여 들어온 책임표시를 씁니다.
            String authors = (rawAuthors == null || rawAuthors.isBlank())
                    ? parts.statementOfResponsibility() : rawAuthors;
            return new Candidate(isbn13, parts,
                    BibNormalizer.parseContributors(authors),
                    BibNormalizer.normalizePublisher(rawPublisher), pubYear, null);
        }

        AuthorTokens authorTokens() {
            Contributor primary = BibNormalizer.primaryAuthor(contributors);
            return AuthorTokens.of(primary == null ? null : primary.name());
        }
    }

    /** 같은 ISBN인데 제목이 이만큼도 닮지 않았다면 데이터 오류를 의심합니다. */
    private static final double ISBN_CONFLICT_THRESHOLD = 0.5;
    /** R7 유사도 문턱. */
    private static final double FUZZY_THRESHOLD = 0.92;

    public static Decision compare(Candidate a, Candidate b) {
        return compare(a, b, AuthorTokens.ASSUME_COMMON, false);
    }

    /**
     * @param enableFuzzy R7(유사도 기반 검토)을 켤지 여부. 기본은 꺼짐입니다.
     */
    public static Decision compare(Candidate a, Candidate b,
                                   AuthorTokens.DocumentFrequency df, boolean enableFuzzy) {

        String keyA = a.title().titleKeyCore();
        String keyB = b.title().titleKeyCore();

        // R0. 같은 ISBN은 같은 판본입니다. 다만 제목이 전혀 다르면 데이터 오류이므로
        //     신뢰도가 높은 소스의 값을 지키기 위해 병합하지 않고 사람에게 넘깁니다.
        if (a.isbn13() != null && a.isbn13().equals(b.isbn13())) {
            if (!volumeCompatible(a, b)) return new Decision(Verdict.REJECT, "R1_VOL_MISMATCH", 0);
            if (trigramSimilarity(keyA, keyB) < ISBN_CONFLICT_THRESHOLD) {
                return new Decision(Verdict.REVIEW, "ISBN_CONFLICT", 0.50);
            }
            return new Decision(Verdict.MERGE, "R0_ISBN_EXACT", 1.00);
        }

        // R1. 권차가 다르면 다른 책입니다. 「토지 1」과 「토지」도 여기서 갈립니다.
        if (!volumeCompatible(a, b)) {
            return new Decision(Verdict.REJECT, "R1_VOL_MISMATCH", 0);
        }

        // R2. 각색은 내용이 다른 별개 저작입니다. 「데미안」과 「데미안(청소년판)」은
        //     정규화하면 키가 같아지므로, 이 규칙이 없으면 반드시 잘못 합쳐집니다.
        if (!sameSet(a.title().adaptationTokens(), b.title().adaptationTokens())) {
            return new Decision(Verdict.REJECT, "R2_ADAPTATION", 0);
        }
        if (a.language() != null && b.language() != null && !a.language().equals(b.language())) {
            return new Decision(Verdict.REJECT, "R2_LANGUAGE", 0);
        }

        AuthorTokens.Compatibility compat =
                AuthorTokens.compare(a.authorTokens(), b.authorTokens(), df);

        // R3. 표제 키와 저자가 맞으면 같은 저작입니다. 역자가 달라도 상관없습니다.
        if (!keyA.isEmpty() && keyA.equals(keyB)) {
            if (compat == AuthorTokens.Compatibility.COMPATIBLE) {
                return new Decision(Verdict.MERGE, "R3_TITLE_AUTHOR", 0.95);
            }
            // R5. 한쪽에 저자가 없을 때만 출판사와 발행연도를 보조 근거로 씁니다.
            if (compat == AuthorTokens.Compatibility.UNKNOWN
                    && !a.publisherNorm().isEmpty()
                    && a.publisherNorm().equals(b.publisherNorm())
                    && yearsClose(a.pubYear(), b.pubYear())) {
                return new Decision(Verdict.MERGE, "R5_TITLE_PUB_YEAR", 0.80);
            }
            // R6. 표제는 같은데 저자가 다르면 동명이서일 수 있습니다. 사람이 봅니다.
            if (compat == AuthorTokens.Compatibility.INCOMPATIBLE) {
                return new Decision(Verdict.REVIEW, "R6_AUTHOR_CLASH", 0.55);
            }
            return new Decision(Verdict.REVIEW, "R6_AUTHOR_UNKNOWN", 0.50);
        }

        // R4. 부표제까지 합친 키가 같고 저자도 맞으면 병합합니다.
        String fullA = a.title().titleKeyFull();
        String fullB = b.title().titleKeyFull();
        if (!fullA.isEmpty() && fullA.equals(fullB)
                && compat == AuthorTokens.Compatibility.COMPATIBLE) {
            return new Decision(Verdict.MERGE, "R4_FULLTITLE_AUTHOR", 0.93);
        }

        // 별칭 키가 겹치고 저자도 맞으면 병합합니다.
        // 「난중일기(亂中日記)」와 「亂中日記」가 여기서 이어집니다.
        if (compat == AuthorTokens.Compatibility.COMPATIBLE && aliasOverlap(a, b)) {
            return new Decision(Verdict.MERGE, "R3_ALIAS_AUTHOR", 0.90);
        }

        // R7. 기본적으로 꺼 둡니다. 켜면 재현율이 오르지만 오병합 위험도 함께 오릅니다.
        if (enableFuzzy && keyA.length() >= 6
                && compat == AuthorTokens.Compatibility.COMPATIBLE
                && trigramSimilarity(keyA, keyB) >= FUZZY_THRESHOLD) {
            return new Decision(Verdict.REVIEW, "R7_FUZZY", 0.60);
        }

        return new Decision(Verdict.REJECT, "NO_RULE", 0);
    }

    /** 양쪽 다 없으면 호환, 한쪽만 있으면 불일치입니다. 「토지 1」과 「토지」를 막습니다. */
    private static boolean volumeCompatible(Candidate a, Candidate b) {
        return Objects.equals(a.title().volNo(), b.title().volNo());
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        return new LinkedHashSet<>(a).equals(new LinkedHashSet<>(b));
    }

    private static boolean yearsClose(Integer a, Integer b) {
        return a != null && b != null && Math.abs(a - b) <= 1;
    }

    private static boolean aliasOverlap(Candidate a, Candidate b) {
        Set<String> keysA = new LinkedHashSet<>(a.title().aliasKeys());
        keysA.add(a.title().titleKeyCore());
        Set<String> keysB = new LinkedHashSet<>(b.title().aliasKeys());
        keysB.add(b.title().titleKeyCore());
        keysA.retainAll(keysB);
        return !keysA.isEmpty();
    }

    /** 3글자 조각의 자카드 유사도. 짧은 문자열은 문자열 자체를 조각으로 씁니다. */
    /** 정규화된 문자열 사이의 3글자 조각 유사도. 후보 점수 계산도 이 함수를 씁니다. */
    public static double trigramSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        Set<String> ga = trigrams(a);
        Set<String> gb = trigrams(b);
        Set<String> shared = new LinkedHashSet<>(ga);
        shared.retainAll(gb);
        Set<String> union = new LinkedHashSet<>(ga);
        union.addAll(gb);
        return union.isEmpty() ? 0 : (double) shared.size() / union.size();
    }

    private static Set<String> trigrams(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s.length() < 3) { out.add(s); return out; }
        for (int i = 0; i + 3 <= s.length(); i++) out.add(s.substring(i, i + 3));
        return out;
    }
}
