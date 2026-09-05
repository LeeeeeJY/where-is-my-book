package kr.wimb.bib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WorkClustererTest {

    private static IntSupplier ids() {
        AtomicInteger next = new AtomicInteger(1000);
        return next::incrementAndGet;
    }

    private static WorkClusterer.Input rec(String id, String title, String author) {
        return WorkClusterer.Input.of(id, title, author);
    }

    private static WorkClusterer.Input rec(String id, String title, String author, Integer previousWorkId) {
        return new WorkClusterer.Input(id, WorkMatcher.Candidate.of(title, author), previousWorkId);
    }

    @Test
    @DisplayName("판본과 표기가 달라도 하나의 저작으로 모인다")
    void mergesTransitively() {
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "코스모스", "칼 세이건"),
                rec("r2", "코스모스 (특별판)", "칼세이건"),
                rec("r3", "코스모스", "칼 세이건 지음")
        ), List.of(), ids());

        assertEquals(1, Set.copyOf(result.workIdByRecord().values()).size(),
                "세 레코드가 한 저작으로 모여야 합니다");
    }

    @Test
    @DisplayName("각색본은 키가 같아져도 갈라진다")
    void keepsAdaptationsApart() {
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "데미안", "헤르만 헤세"),
                rec("r2", "데미안 (청소년판)", "헤르만 헤세")
        ), List.of(), ids());

        assertNotEquals(result.workIdByRecord().get("r1"), result.workIdByRecord().get("r2"));
    }

    @Test
    @DisplayName("권차가 다르면 갈라진다")
    void keepsVolumesApart() {
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "레미제라블 1", "빅토르 위고"),
                rec("r2", "레미제라블 2", "빅토르 위고"),
                rec("r3", "레·미제라블 Ⅰ", "빅토르 위고")
        ), List.of(), ids());

        assertEquals(result.workIdByRecord().get("r1"), result.workIdByRecord().get("r3"),
                "표기만 다른 1권은 같은 저작입니다");
        assertNotEquals(result.workIdByRecord().get("r1"), result.workIdByRecord().get("r2"));
    }

    @Test
    @DisplayName("표제가 같고 저자가 다르면 합치지 않고 검토 큐에 쌓는다")
    void queuesAuthorClashForReview() {
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "코스모스", "칼 세이건"),
                rec("r2", "코스모스", "베르나르 베르베르")
        ), List.of(), ids());

        assertNotEquals(result.workIdByRecord().get("r1"), result.workIdByRecord().get("r2"));
        assertEquals(1, result.reviewQueue().size());
        assertEquals("R6_AUTHOR_CLASH", result.reviewQueue().get(0).ruleCode());
    }

    @Test
    @DisplayName("이전 번호를 최빈값으로 물려받는다")
    void inheritsWorkIdByMajority() {
        // 공유된 URL이 깨지지 않으려면 재군집화 때 번호가 유지되어야 합니다.
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "코스모스", "칼 세이건", 100),
                rec("r2", "코스모스 (특별판)", "칼 세이건", 100),
                rec("r3", "코스모스", "칼세이건", 200)
        ), List.of(), ids());

        assertEquals(100, result.workIdByRecord().get("r1"));
        assertEquals(100, result.workIdByRecord().get("r3"));
        assertEquals(100, result.workIdAliases().get(200),
                "흡수된 번호는 별칭으로 남아 301 전달에 쓰입니다");
    }

    @Test
    @DisplayName("이전 번호가 없는 저작에만 새 번호를 발급한다")
    void mintsOnlyForNewWorks() {
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "코스모스", "칼 세이건", 100),
                rec("r2", "데미안", "헤르만 헤세", null)
        ), List.of(), ids());

        assertEquals(100, result.workIdByRecord().get("r1"));
        assertTrue(result.workIdByRecord().get("r2") > 1000);
    }

    @Test
    @DisplayName("분리 지정은 제3의 레코드를 거친 연결까지 끊는다")
    void forceSplitBreaksTransitiveChain() {
        // 직접 간선만 지우면 A-B-C 경로로 다시 이어지므로 경로에서 가장 약한 간선을 끊어야 합니다.
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "코스모스", "칼 세이건"),
                rec("r2", "코스모스", "칼세이건"),
                rec("r3", "코스모스 (특별판)", "칼 세이건")
        ), List.of(new WorkClusterer.Override("r1", "r3", WorkClusterer.OverrideKind.FORCE_SPLIT)),
                ids());

        assertNotEquals(result.workIdByRecord().get("r1"), result.workIdByRecord().get("r3"));
        assertTrue(result.violations().isEmpty(), "분리 지정을 지키지 못한 쌍이 없어야 합니다");
    }

    @Test
    @DisplayName("병합 지정은 규칙이 거부한 쌍도 합친다")
    void forceMergeOverridesRules() {
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "코스모스", "칼 세이건"),
                rec("r2", "COSMOS", "Carl Sagan")
        ), List.of(new WorkClusterer.Override("r1", "r2", WorkClusterer.OverrideKind.FORCE_MERGE)),
                ids());

        assertEquals(result.workIdByRecord().get("r1"), result.workIdByRecord().get("r2"));
    }

    @Test
    @DisplayName("판정 근거를 간선으로 남긴다")
    void recordsRuleCodes() {
        var result = WorkClusterer.cluster(List.of(
                rec("r1", "페스트", "알베르 카뮈 지음, 김화영 옮김"),
                rec("r2", "페스트", "알베르 카뮈 지음 ; 유호식 옮김")
        ), List.of(), ids());

        assertEquals(1, result.mergeEdges().size());
        assertEquals("R3_TITLE_AUTHOR", result.mergeEdges().get(0).ruleCode());
    }

    @Test
    @DisplayName("같은 입력에 같은 결과를 낸다")
    void isDeterministic() {
        List<WorkClusterer.Input> inputs = List.of(
                rec("r3", "코스모스", "칼세이건", 200),
                rec("r1", "코스모스", "칼 세이건", 100),
                rec("r2", "데미안", "헤르만 헤세", null)
        );
        var first = WorkClusterer.cluster(inputs, List.of(), ids());
        var second = WorkClusterer.cluster(inputs, List.of(), ids());
        assertEquals(first.workIdByRecord(), second.workIdByRecord());
        assertEquals(first.workIdAliases(), second.workIdAliases());
    }

    @Test
    @DisplayName("말뭉치가 충분히 크면 희소 토큰으로 동일인을 확정한다")
    void documentFrequencyResolvesRareNames() {
        // 빈도표가 없으면 「J.D. 샐린저」와 「제롬 데이비드 샐린저」는 공유 토큰이 하나뿐이라
        // 자카드 유사도가 낮아 갈립니다. 실제 규모의 말뭉치에서는 이 문제가 사라집니다.
        List<AuthorTokens> corpus = new ArrayList<>();
        for (int i = 0; i < 2000; i++) corpus.add(AuthorTokens.of("김작가" + i));
        corpus.add(AuthorTokens.of("J.D. 샐린저"));

        var df = AuthorDocumentFrequency.build(corpus);
        assertTrue(df.ratioOf("샐린저") < 0.001, "희소 토큰으로 판정되어야 합니다");

        assertEquals(AuthorTokens.Compatibility.INCOMPATIBLE,
                AuthorTokens.compare(AuthorTokens.of("J.D. 샐린저"),
                        AuthorTokens.of("제롬 데이비드 샐린저"), AuthorTokens.ASSUME_COMMON));
        assertEquals(AuthorTokens.Compatibility.COMPATIBLE,
                AuthorTokens.compare(AuthorTokens.of("J.D. 샐린저"),
                        AuthorTokens.of("제롬 데이비드 샐린저"), df));
    }

    @Test
    @DisplayName("저자가 없는 자료는 빈도표의 분모에서 뺀다")
    void excludesEmptyAuthorsFromCorpus() {
        var df = AuthorDocumentFrequency.build(List.of(
                AuthorTokens.of("칼 세이건"),
                AuthorTokens.of(""),
                AuthorTokens.of(null)));
        assertEquals(1, df.documentCount());
    }
}
