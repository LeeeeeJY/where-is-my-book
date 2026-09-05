package kr.wimb.bib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static kr.wimb.bib.AuthorTokens.Compatibility.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorTokensTest {

    private static AuthorTokens.Compatibility cmp(String a, String b) {
        return AuthorTokens.compare(AuthorTokens.of(a), AuthorTokens.of(b),
                AuthorTokens.ASSUME_COMMON);
    }

    @Test
    @DisplayName("한글 연속과 라틴 연속을 각각 토큰으로 나눈다")
    void tokenizes() {
        assertEquals(java.util.List.of("조앤", "k", "롤링"),
                java.util.List.copyOf(AuthorTokens.of("조앤 K. 롤링").tokens()));
        assertEquals(java.util.List.of("j", "k", "롤링"),
                java.util.List.copyOf(AuthorTokens.of("J.K.롤링").tokens()));
    }

    @Test
    @DisplayName("음차 표기와 이니셜 표기가 같은 사람으로 판정된다")
    void matchesTransliterations() {
        assertEquals(COMPATIBLE, cmp("조앤 K. 롤링", "J.K.롤링"));
    }

    @Test
    @DisplayName("성을 앞세운 도치 표기도 같은 사람이다")
    void handlesInvertedNames() {
        assertEquals(COMPATIBLE, cmp("조앤 K. 롤링", "롤링, 조앤 K."));
    }

    @Test
    @DisplayName("붙여 쓴 표기와 띄어 쓴 표기는 같은 사람이다")
    void handlesSpacing() {
        assertEquals(COMPATIBLE, cmp("칼 세이건", "칼세이건"));
        assertEquals(COMPATIBLE, cmp("기시미 이치로", "기시미이치로"));
    }

    @Test
    @DisplayName("미들네임이 추가되어도 같은 사람이다")
    void handlesMiddleNames() {
        assertEquals(COMPATIBLE, cmp("유발 하라리", "유발 노아 하라리"));
    }

    @Test
    @DisplayName("다른 사람은 비호환으로 판정한다")
    void rejectsDifferentPeople() {
        assertEquals(INCOMPATIBLE, cmp("칼 세이건", "앤 드루얀"));
        assertEquals(INCOMPATIBLE, cmp("김영하", "김애란"));
    }

    @Test
    @DisplayName("한쪽에 저자가 없으면 막지 않고 판단을 보류한다")
    void unknownWhenEmpty() {
        assertEquals(UNKNOWN, cmp("칼 세이건", ""));
        assertEquals(UNKNOWN, cmp("", ""));
    }

    @Test
    @DisplayName("희소 토큰이 공유되면 그것만으로 동일인을 확정한다")
    void rareTokenIsDecisive() {
        // 말뭉치 빈도표가 있어야 통과하는 사례. ASSUME_COMMON 으로는 갈린다.
        AuthorTokens.DocumentFrequency df =
                token -> token.equals("샐린저") || token.equals("생텍쥐페리") ? 0.0002 : 1.0;

        assertEquals(INCOMPATIBLE, cmp("J.D. 샐린저", "제롬 데이비드 샐린저"));
        assertEquals(COMPATIBLE, AuthorTokens.compare(
                AuthorTokens.of("J.D. 샐린저"), AuthorTokens.of("제롬 데이비드 샐린저"), df));

        assertEquals(INCOMPATIBLE, cmp("앙투안 드 생텍쥐페리", "생텍쥐페리"));
        assertEquals(COMPATIBLE, AuthorTokens.compare(
                AuthorTokens.of("앙투안 드 생텍쥐페리"), AuthorTokens.of("생텍쥐페리"), df));
    }
}
