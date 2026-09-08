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

    /**
     * 실제로 정보나루에서 받은 값입니다. 이 둘을 합치면 화면에는 대표 하나만 남고,
     * 을유문화사 판을 찾는 사람에게는 「그 책이 없다」로 보입니다. 소장 조회 쪽 피해가
     * 더 큰데, 묶인 ISBN 전체로 물어보므로 <b>범우사 판만 있는 도서관이 「열린책들
     * 마의 산 있음」으로 나가</b> 헛걸음을 만듭니다.
     */
    @Test
    @DisplayName("출판사가 다른 번역본은 병합하지 않는다")
    void rejectsDifferentPublishers() {
        var a = WorkMatcher.Candidate.of("마의 산", "토마스 만", "열린책들", 2009, "9788932916453");
        var b = WorkMatcher.Candidate.of("마의 산", "토마스 만", "을유문화사", 2008, "9788932403311");
        var d = WorkMatcher.compare(a, b);
        assertEquals(REJECT, d.verdict());
        assertEquals("R2_PUBLISHER_DIFF", d.ruleCode());
    }

    /**
     * <b>모르는 것을 근거로 쪼개면 안 됩니다.</b> 출판사를 알 수 없는 쪽까지 갈라 놓으면
     * 같은 책이 흩어지고, 소장 조회가 물어보는 ISBN 이 그만큼 줄어 실제로 소장한 책을
     * 놓치게 됩니다.
     */
    @Test
    @DisplayName("한쪽 출판사를 모르면 갈라 놓지 않는다")
    void keepsMergingWhenPublisherUnknown() {
        var a = WorkMatcher.Candidate.of("마의 산", "토마스 만", "열린책들", 2009, "9788932916453");
        var b = WorkMatcher.Candidate.of("마의 산", "토마스 만", null, 2008, "9788932403311");
        assertEquals(MERGE, WorkMatcher.compare(a, b).verdict());
    }

    /** 같은 ISBN 은 같은 판본입니다. 출판사 표기가 흔들려도 R0 이 먼저 병합합니다. */
    @Test
    @DisplayName("같은 ISBN 이면 출판사 표기가 달라도 병합한다")
    void isbnWinsOverPublisher() {
        var a = WorkMatcher.Candidate.of("마의 산", "토마스 만", "을유문화사", 2008, "9788932403311");
        var b = WorkMatcher.Candidate.of("마의 산", "토마스 만", "을유문화사 출판부", 2008, "9788932403311");
        var d = WorkMatcher.compare(a, b);
        assertEquals(MERGE, d.verdict());
        assertEquals("R0_ISBN_EXACT", d.ruleCode());
    }

    /** 법인격 표기는 출판사가 다르다는 근거가 못 됩니다. */
    @Test
    @DisplayName("(주) 가 붙고 안 붙고는 같은 출판사로 본다")
    void legalFormIsNotADifference() {
        var a = WorkMatcher.Candidate.of("페스트", "알베르 카뮈", "민음사", 2011, "9788937460777");
        var b = WorkMatcher.Candidate.of("페스트", "알베르 카뮈", "(주)민음사", 2015, "9788937473364");
        assertEquals(MERGE, WorkMatcher.compare(a, b).verdict());
    }

    /**
     * 권차가 다르면 출판사 검사까지 가기 전에 갈립니다. 순서가 뒤바뀌면 같은 출판사의
     * 낱권들이 다시 한 저작으로 합쳐지므로, 규칙 순서를 여기서 고정합니다.
     */
    @Test
    @DisplayName("같은 출판사라도 권차가 다르면 거부한다")
    void volumeStillSplitsWithinPublisher() {
        var a = WorkMatcher.Candidate.of("레 미제라블", "빅토르 위고", "민음사", 2012, "9788937463013", 1);
        var b = WorkMatcher.Candidate.of("레 미제라블", "빅토르 위고", "민음사", 2012, "9788937463020", 2);
        var d = WorkMatcher.compare(a, b);
        assertEquals(REJECT, d.verdict());
        assertEquals("R1_VOL_MISMATCH", d.ruleCode());
    }
}
