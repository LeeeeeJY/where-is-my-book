package kr.wimb.index;

import kr.wimb.bib.BibNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 색인을 서비스에 올리기 전에 통과해야 하는 관문입니다.
 *
 * <p><b>검증하지 않은 색인은 절대 전환하지 않습니다.</b> 자동 배치가 조용히 망가진 색인으로
 * 갈아 끼우는 것이 이 구조에서 가장 위험한 실패입니다. 검색이 안 되는 것을 사용자가
 * 알려 줄 때까지 아무도 모르게 됩니다.
 *
 * <p>실패하면 새 테이블을 버리고 기존 뷰를 그대로 둡니다. 오래된 색인이 서비스되는 것이
 * 망가진 색인이 서비스되는 것보다 낫습니다.
 */
public final class IndexVerifier {

    private IndexVerifier() {}

    /** 색인을 다시 만들 때마다 통과해야 하는 고정 질의. */
    public record Canary(Kind kind, String query, String expect) {
        public enum Kind { TEXT, ISBN }

        /** 이 질의로 검색하면 제목에 expect 가 들어간 문서가 나와야 합니다. */
        public static Canary text(String query, String expectedTitleContains) {
            return new Canary(Kind.TEXT, query, expectedTitleContains);
        }

        /** 이 ISBN 은 정확히 한 저작으로 해석되어야 합니다. */
        public static Canary isbn(String isbn13) {
            return new Canary(Kind.ISBN, isbn13, null);
        }
    }

    /**
     * @param expectedDocCount 직전 세대의 문서 수. 처음 만들 때는 0 을 주어 건너뜁니다.
     * @param tolerance        허용 편차. 0.05 면 ±5% 입니다.
     */
    public record Expectations(int expectedDocCount, double tolerance, List<Canary> canaries) {
        public static Expectations firstBuild(List<Canary> canaries) {
            return new Expectations(0, 0, canaries);
        }
    }

    public record Result(boolean passed, List<String> failures) {
        public void throwIfFailed() {
            if (!passed) {
                throw new IllegalStateException(
                        "색인 검증에 실패해 전환하지 않습니다:\n  " + String.join("\n  ", failures));
            }
        }
    }

    public static Result verify(List<SearchDoc> docs, Expectations expectations) {
        List<String> failures = new ArrayList<>();

        checkDocCount(docs, expectations, failures);
        checkStructure(docs, failures);
        for (Canary canary : expectations.canaries()) {
            checkCanary(docs, canary, failures);
        }
        return new Result(failures.isEmpty(), List.copyOf(failures));
    }

    /** 문서가 갑자기 줄었다면 적재가 절반쯤에서 멈춘 것입니다. */
    private static void checkDocCount(List<SearchDoc> docs, Expectations expectations,
                                      List<String> failures) {
        if (docs.isEmpty()) {
            failures.add("문서가 하나도 없습니다.");
            return;
        }
        if (expectations.expectedDocCount() <= 0) return;

        double allowed = expectations.expectedDocCount() * expectations.tolerance();
        double diff = Math.abs(docs.size() - expectations.expectedDocCount());
        if (diff > allowed) {
            failures.add("문서 수가 예상 범위를 벗어났습니다: %d 건 (예상 %d 건, 허용 ±%.0f)"
                    .formatted(docs.size(), expectations.expectedDocCount(), allowed));
        }
    }

    /**
     * 구조가 깨진 문서를 찾습니다. 이런 문서는 검색은 되는데 소장 조회가 안 되거나,
     * 아예 검색되지 않는 형태로 조용히 실패합니다.
     */
    private static void checkStructure(List<SearchDoc> docs, List<String> failures) {
        Set<Integer> seenWorkIds = new HashSet<>();
        List<Integer> duplicates = new ArrayList<>();
        List<Integer> withoutIsbn = new ArrayList<>();
        List<Integer> withoutSearchText = new ArrayList<>();

        for (SearchDoc doc : docs) {
            if (!seenWorkIds.add(doc.workId())) duplicates.add(doc.workId());
            if (doc.isbn13List().isEmpty()) withoutIsbn.add(doc.workId());
            if (doc.searchText() == null || doc.searchText().isBlank()) {
                withoutSearchText.add(doc.workId());
            }
        }

        if (!duplicates.isEmpty()) {
            failures.add("저작 번호가 중복된 문서가 있습니다: " + sample(duplicates));
        }
        if (!withoutIsbn.isEmpty()) {
            // ISBN 이 없으면 소장 조회를 할 수 없으므로 검색은 되는데 결과가 언제나 비어 있습니다.
            failures.add("ISBN 이 없어 소장 조회가 불가능한 문서가 있습니다: " + sample(withoutIsbn));
        }
        if (!withoutSearchText.isEmpty()) {
            failures.add("검색 대상 텍스트가 비어 영원히 검색되지 않는 문서가 있습니다: "
                    + sample(withoutSearchText));
        }
    }

    private static void checkCanary(List<SearchDoc> docs, Canary canary, List<String> failures) {
        if (canary.kind() == Canary.Kind.ISBN) {
            long hits = docs.stream().filter(d -> d.isbn13List().contains(canary.query())).count();
            if (hits != 1) {
                failures.add("고정 질의 실패: ISBN %s 가 %d 개 저작으로 해석됩니다 (1개여야 함)"
                        .formatted(canary.query(), hits));
            }
            return;
        }

        // 질의도 색인과 같은 정규화 함수를 씁니다. 다른 정규화를 쓰면 여기서 통과해도
        // 실제 검색에서는 걸리지 않습니다.
        String normalized = BibNormalizer.normalizeKey(canary.query());
        boolean matched = docs.stream()
                .filter(d -> d.searchText().contains(normalized))
                .anyMatch(d -> d.titleDisplay() != null
                        && d.titleDisplay().contains(canary.expect()));
        if (!matched) {
            failures.add("고정 질의 실패: 「%s」로 검색했을 때 「%s」가 나오지 않습니다"
                    .formatted(canary.query(), canary.expect()));
        }
    }

    private static String sample(List<Integer> workIds) {
        List<Integer> head = workIds.stream().limit(5).toList();
        return head + (workIds.size() > head.size() ? " 외 " + (workIds.size() - head.size()) + "건" : "");
    }
}
