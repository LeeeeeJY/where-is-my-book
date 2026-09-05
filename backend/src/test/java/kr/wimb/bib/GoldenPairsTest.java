package kr.wimb.bib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * golden-pairs.tsv 가 「같은 책」의 실질적인 명세이고, 이 테스트가 그 명세를 지킵니다.
 *
 * <p>정밀도를 재현율보다 훨씬 높게 잡은 것이 의도적입니다. 누락은 사용자가 도서관
 * 페이지에서 확인하면 바로잡히고 눈에 보이지만, 오병합은 헛걸음을 만들고 두 저작이
 * 이후의 모든 검색에서 계속 붙어 다니는데도 아무도 눈치채지 못합니다.
 */
class GoldenPairsTest {

    private static final double MIN_PRECISION = 0.99;
    private static final double MIN_RECALL = 0.90;

    private record Pair(String verdict, String titleA, String authorA,
                        String titleB, String authorB, String note) {}

    private static List<Pair> load() throws Exception {
        List<Pair> pairs = new ArrayList<>();
        var stream = GoldenPairsTest.class.getResourceAsStream("/bib/golden-pairs.tsv");
        assertNotNull(stream, "golden-pairs.tsv 를 찾지 못했습니다");
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                assertEquals(6, f.length, "열 개수가 맞지 않습니다: " + line);
                pairs.add(new Pair(f[0], f[1], f[2], f[3], f[4], f[5]));
            }
        }
        return pairs;
    }

    @Test
    @DisplayName("정밀도 0.99 이상, 재현율 0.90 이상을 유지한다")
    void meetsQualityBar() throws Exception {
        int truePositive = 0, falsePositive = 0, falseNegative = 0;
        List<String> falseMerges = new ArrayList<>();
        List<String> misses = new ArrayList<>();

        for (Pair p : load()) {
            var decision = WorkMatcher.compare(
                    WorkMatcher.Candidate.of(p.titleA(), p.authorA()),
                    WorkMatcher.Candidate.of(p.titleB(), p.authorB()));
            boolean expectedSame = "SAME".equals(p.verdict());

            if (expectedSame && decision.isMerge()) {
                truePositive++;
            } else if (expectedSame) {
                falseNegative++;
                misses.add("  %s | %s → %s (%s)"
                        .formatted(p.titleA(), p.titleB(), decision.ruleCode(), p.note()));
            } else if (decision.isMerge()) {
                falsePositive++;
                falseMerges.add("  %s | %s → %s (%s)"
                        .formatted(p.titleA(), p.titleB(), decision.ruleCode(), p.note()));
            }
        }

        double precision = truePositive + falsePositive == 0
                ? 1.0 : (double) truePositive / (truePositive + falsePositive);
        double recall = truePositive + falseNegative == 0
                ? 1.0 : (double) truePositive / (truePositive + falseNegative);

        assertTrue(precision >= MIN_PRECISION,
                "정밀도 %.3f 가 기준 %.2f 에 미달합니다. 오병합:%n%s"
                        .formatted(precision, MIN_PRECISION, String.join("\n", falseMerges)));
        assertTrue(recall >= MIN_RECALL,
                "재현율 %.3f 가 기준 %.2f 에 미달합니다. 누락:%n%s"
                        .formatted(recall, MIN_RECALL, String.join("\n", misses)));
    }

    @Test
    @DisplayName("판정이 대칭이다")
    void decisionIsSymmetric() throws Exception {
        for (Pair p : load()) {
            var forward = WorkMatcher.compare(
                    WorkMatcher.Candidate.of(p.titleA(), p.authorA()),
                    WorkMatcher.Candidate.of(p.titleB(), p.authorB()));
            var backward = WorkMatcher.compare(
                    WorkMatcher.Candidate.of(p.titleB(), p.authorB()),
                    WorkMatcher.Candidate.of(p.titleA(), p.authorA()));
            assertEquals(forward.isMerge(), backward.isMerge(),
                    "순서를 바꾸면 판정이 달라집니다: " + p.titleA() + " | " + p.titleB());
        }
    }

    @Test
    @DisplayName("정규화는 여러 번 적용해도 결과가 같다")
    void normalizationIsIdempotent() throws Exception {
        for (Pair p : load()) {
            for (String title : List.of(p.titleA(), p.titleB())) {
                String once = BibNormalizer.normalizeKey(title);
                assertEquals(once, BibNormalizer.normalizeKey(once),
                        "정규화가 멱등이 아닙니다: " + title);
            }
        }
    }
}
