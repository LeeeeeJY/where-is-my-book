package kr.wimb.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexVerifierTest {

    private static SearchDoc doc(int workId, String title, String isbn, String searchText) {
        return new SearchDoc(workId, title, "칼 세이건", "사이언스북스", null, null,
                isbn == null ? List.of() : List.of(isbn), List.of(),
                searchText, Bigrams.of(searchText));
    }

    private static final SearchDoc COSMOS =
            doc(1, "코스모스", "9788983711892", "코스모스칼세이건사이언스북스");
    private static final SearchDoc DEMIAN =
            doc(2, "데미안", "9788937460449", "데미안헤르만헤세");

    @Test
    @DisplayName("정상 색인은 통과한다")
    void passesHealthyIndex() {
        var result = IndexVerifier.verify(List.of(COSMOS, DEMIAN),
                IndexVerifier.Expectations.firstBuild(List.of(
                        IndexVerifier.Canary.text("코스모스", "코스모스"),
                        IndexVerifier.Canary.isbn("9788983711892"))));

        assertTrue(result.passed(), () -> String.join(", ", result.failures()));
    }

    @Test
    @DisplayName("문서가 하나도 없으면 실패한다")
    void failsOnEmptyIndex() {
        var result = IndexVerifier.verify(List.of(),
                IndexVerifier.Expectations.firstBuild(List.of()));

        assertFalse(result.passed());
        assertTrue(result.failures().get(0).contains("하나도 없습니다"));
    }

    @Test
    @DisplayName("문서 수가 급감하면 실패한다")
    void failsWhenDocCountDrops() {
        // 적재가 절반쯤에서 멈춘 경우입니다. 그대로 전환하면 검색되던 책이 사라집니다.
        var result = IndexVerifier.verify(List.of(COSMOS, DEMIAN),
                new IndexVerifier.Expectations(100, 0.05, List.of()));

        assertFalse(result.passed());
        assertTrue(result.failures().get(0).contains("문서 수가 예상 범위를 벗어났습니다"));
    }

    @Test
    @DisplayName("허용 편차 안의 변동은 통과한다")
    void allowsSmallVariation() {
        var docs = List.of(COSMOS, DEMIAN);
        var result = IndexVerifier.verify(docs,
                new IndexVerifier.Expectations(2, 0.05, List.of()));

        assertTrue(result.passed());
    }

    @Test
    @DisplayName("ISBN 이 없는 문서를 잡아낸다")
    void catchesDocsWithoutIsbn() {
        // 검색은 되는데 소장 조회가 언제나 비어 있는 문서입니다. 조용히 실패하는 형태라
        // 사용자가 알려 줄 때까지 아무도 모릅니다.
        var broken = doc(3, "이상한 책", null, "이상한책");
        var result = IndexVerifier.verify(List.of(COSMOS, broken),
                IndexVerifier.Expectations.firstBuild(List.of()));

        assertFalse(result.passed());
        assertTrue(result.failures().stream()
                .anyMatch(f -> f.contains("소장 조회가 불가능한")));
    }

    @Test
    @DisplayName("검색 대상 텍스트가 빈 문서를 잡아낸다")
    void catchesDocsWithoutSearchText() {
        var broken = doc(3, "이상한 책", "9788937460456", "");
        var result = IndexVerifier.verify(List.of(COSMOS, broken),
                IndexVerifier.Expectations.firstBuild(List.of()));

        assertFalse(result.passed());
        assertTrue(result.failures().stream()
                .anyMatch(f -> f.contains("영원히 검색되지 않는")));
    }

    @Test
    @DisplayName("저작 번호가 중복된 문서를 잡아낸다")
    void catchesDuplicateWorkIds() {
        var duplicate = doc(1, "다른 책", "9788937460456", "다른책");
        var result = IndexVerifier.verify(List.of(COSMOS, duplicate),
                IndexVerifier.Expectations.firstBuild(List.of()));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("중복")));
    }

    @Test
    @DisplayName("고정 질의가 걸리지 않으면 실패한다")
    void failsWhenCanaryDoesNotMatch() {
        var result = IndexVerifier.verify(List.of(COSMOS),
                IndexVerifier.Expectations.firstBuild(List.of(
                        IndexVerifier.Canary.text("사피엔스", "사피엔스"))));

        assertFalse(result.passed());
        assertTrue(result.failures().get(0).contains("고정 질의 실패"));
    }

    @Test
    @DisplayName("질의도 색인과 같은 정규화를 거친다")
    void canaryUsesSameNormalization() {
        // 띄어쓰기가 다른 질의로도 걸려야 합니다. 다른 정규화를 쓰면 여기서 통과해도
        // 실제 검색에서는 걸리지 않습니다.
        var result = IndexVerifier.verify(List.of(COSMOS),
                IndexVerifier.Expectations.firstBuild(List.of(
                        IndexVerifier.Canary.text("코스 모스", "코스모스"))));

        assertTrue(result.passed(), () -> String.join(", ", result.failures()));
    }

    @Test
    @DisplayName("한 ISBN 이 여러 저작으로 갈리면 실패한다")
    void failsWhenIsbnResolvesToMultipleWorks() {
        // 군집화가 같은 판본을 두 저작으로 갈라 놓은 경우입니다.
        var split = doc(3, "코스모스", "9788983711892", "코스모스");
        var result = IndexVerifier.verify(List.of(COSMOS, split),
                IndexVerifier.Expectations.firstBuild(List.of(
                        IndexVerifier.Canary.isbn("9788983711892"))));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("2 개 저작으로")));
    }

    @Test
    @DisplayName("실패를 무시하고 넘어갈 수 없다")
    void throwsOnFailure() {
        var result = IndexVerifier.verify(List.of(),
                IndexVerifier.Expectations.firstBuild(List.of()));

        var e = assertThrows(IllegalStateException.class, result::throwIfFailed);
        assertTrue(e.getMessage().contains("전환하지 않습니다"));
    }
}
