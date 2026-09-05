package kr.wimb.bib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static kr.wimb.bib.WorkMatcher.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkMatcherTest {

    private static WorkMatcher.Decision decide(String ta, String aa, String tb, String ab) {
        return WorkMatcher.compare(WorkMatcher.Candidate.of(ta, aa),
                                   WorkMatcher.Candidate.of(tb, ab));
    }

    @Test
    @DisplayName("역자가 달라도 같은 저작으로 병합한다")
    void mergesAcrossTranslators() {
        var d = decide("페스트", "알베르 카뮈 지음, 김화영 옮김",
                       "페스트", "알베르 카뮈 지음 ; 유호식 옮김");
        assertEquals(MERGE, d.verdict());
        assertEquals("R3_TITLE_AUTHOR", d.ruleCode());
    }

    @Test
    @DisplayName("권차가 다르면 거부한다")
    void rejectsVolumeMismatch() {
        assertEquals(REJECT, decide("미움받을 용기", "기시미 이치로",
                                    "미움받을 용기 2", "기시미 이치로").verdict());
        // 한쪽만 권차가 있는 경우도 거부한다.
        assertEquals(REJECT, decide("토지", "박경리", "토지 1", "박경리").verdict());
    }

    @Test
    @DisplayName("각색본은 키가 같아져도 거부한다")
    void rejectsAdaptations() {
        var d = decide("데미안", "헤르만 헤세", "데미안 (청소년판)", "헤르만 헤세");
        assertEquals(REJECT, d.verdict());
        assertEquals("R2_ADAPTATION", d.ruleCode());
    }

    @Test
    @DisplayName("표제가 같고 저자가 다르면 병합하지 않고 사람에게 넘긴다")
    void reviewsAuthorClash() {
        var d = decide("코스모스", "칼 세이건", "코스모스", "베르나르 베르베르");
        assertEquals(REVIEW, d.verdict());
        assertFalse(d.isMerge());
    }

    @Test
    @DisplayName("같은 ISBN이라도 제목이 전혀 다르면 병합하지 않는다")
    void reviewsIsbnConflict() {
        var a = WorkMatcher.Candidate.of("코스모스", "칼 세이건", null, null, "9788983920775");
        var b = WorkMatcher.Candidate.of("자바의 정석", "남궁성", null, null, "9788983920775");
        var d = WorkMatcher.compare(a, b);
        assertEquals("ISBN_CONFLICT", d.ruleCode());
        assertFalse(d.isMerge());
    }

    @Test
    @DisplayName("저자가 없으면 출판사와 발행연도를 보조 근거로 쓴다")
    void usesPublisherWhenAuthorMissing() {
        var a = WorkMatcher.Candidate.of("조선왕조실록", "", "민음사", 2015, null);
        var b = WorkMatcher.Candidate.of("조선왕조실록", "", "(주)민음사", 2016, null);
        var d = WorkMatcher.compare(a, b);
        assertEquals(MERGE, d.verdict());
        assertEquals("R5_TITLE_PUB_YEAR", d.ruleCode());
    }

    @Test
    @DisplayName("한자 별칭으로 이어진 표제를 병합한다")
    void mergesViaHanjaAlias() {
        var d = decide("난중일기(亂中日記)", "이순신", "亂中日記", "이순신");
        assertEquals(MERGE, d.verdict());
    }
}
