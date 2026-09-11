package kr.wimb.opac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 도서관별 OPAC 주소 규칙.
 *
 * <p>여기서 틀린 규칙을 통과시키면 그 도서관은 <b>HTTP 200 을 주면서 결과만 0건</b>이 되고,
 * 사용자에게는 「소장한다더니 그 책이 없네」로 보입니다. 링크가 깨지는 것보다 나쁩니다.
 * 깨진 링크는 눈에 보이지만 이것은 보이지 않습니다.
 */
class OpacTemplatesTest {

    private static OpacTemplates of(String... lines) {
        return OpacTemplates.parse(List.of(lines));
    }

    @Test
    @DisplayName("규칙이 없는 도서관은 비어 있는 값을 준다")
    void noTemplateMeansNoLink() {
        var templates = of("# 주석뿐입니다");
        assertTrue(templates.bestFor("111111", "9788983711892", "코스모스").isEmpty());
        assertEquals(OpacLink.Kind.HOMEPAGE, templates.kindFor("111111"));
    }

    @Test
    @DisplayName("ISBN 검색 규칙이 있으면 그 주소로 보낸다")
    void buildsIsbnSearchUrl() {
        var templates = of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}");

        var link = templates.bestFor("111111", "9788983711892", "코스모스").orElseThrow();

        assertEquals("https://lib.example.kr/search?q=9788983711892", link.url());
        assertEquals(OpacLink.Kind.ISBN_SEARCH, link.kind());
        assertEquals(OpacLink.Kind.ISBN_SEARCH, templates.kindFor("111111"));
    }

    @Test
    @DisplayName("상세 규칙이 있으면 검색보다 상세를 고른다")
    void prefersDetailOverSearch() {
        var templates = of(
                "111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}",
                "111111,ISBN_DETAIL,UTF-8,https://lib.example.kr/book/{isbn13}");

        assertEquals(OpacLink.Kind.ISBN_DETAIL,
                templates.bestFor("111111", "9788983711892", "코스모스").orElseThrow().kind());
    }

    @Test
    @DisplayName("ISBN 이 없으면 제목 검색으로 내려간다")
    void fallsBackToTitleWhenIsbnMissing() {
        var templates = of(
                "111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}",
                "111111,TITLE_SEARCH,UTF-8,https://lib.example.kr/search?q={title}");

        var link = templates.bestFor("111111", null, "코스모스").orElseThrow();

        assertEquals(OpacLink.Kind.TITLE_SEARCH, link.kind());
        assertTrue(link.url().endsWith("q=%EC%BD%94%EC%8A%A4%EB%AA%A8%EC%8A%A4"), link.url());
    }

    @Test
    @DisplayName("제목 규칙만 있는데 제목도 없으면 비어 있는 값을 준다")
    void noValueMeansNoLink() {
        var templates = of("111111,TITLE_SEARCH,UTF-8,https://lib.example.kr/search?q={title}");
        assertTrue(templates.bestFor("111111", "9788983711892", null).isEmpty());
    }

    @Test
    @DisplayName("EUC-KR 로 적힌 OPAC 은 그 인코딩으로 질의어를 만든다")
    void honoursEucKr() {
        // UTF-8 로 보내면 200 이 오면서 결과만 0건이 되는 OPAC 이 아직 있습니다.
        var templates = of("111111,TITLE_SEARCH,EUC-KR,https://lib.example.kr/s?q={title}");

        var link = templates.bestFor("111111", null, "코스모스").orElseThrow();

        assertTrue(link.url().endsWith("q=%C4%DA%BD%BA%B8%F0%BD%BA"), link.url());
    }

    @Test
    @DisplayName("자리표가 없는 규칙은 실행을 실패시킨다")
    void placeholderIsRequired() {
        // 자리표가 없으면 어느 책을 눌러도 같은 페이지가 나옵니다. 조용히 엉뚱한 곳으로
        // 보내느니 뜰 때 실패하는 편이 낫습니다.
        var thrown = assertThrows(IllegalStateException.class,
                () -> of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search"));
        assertTrue(thrown.getMessage().contains("{isbn13}"), thrown.getMessage());
    }

    @Test
    @DisplayName("HOMEPAGE 를 규칙으로 적으면 실패시킨다")
    void homepageIsNotATemplate() {
        assertThrows(IllegalStateException.class,
                () -> of("111111,HOMEPAGE,UTF-8,https://lib.example.kr"));
    }

    @Test
    @DisplayName("칸이 모자라거나 모르는 값이면 실패시킨다")
    void malformedLinesFail() {
        assertThrows(IllegalStateException.class, () -> of("111111,ISBN_SEARCH,UTF-8"));
        assertThrows(IllegalStateException.class,
                () -> of("111111,ISBN_XXX,UTF-8,https://x.kr/{isbn13}"));
        assertThrows(IllegalStateException.class,
                () -> of("111111,ISBN_SEARCH,모르는인코딩,https://x.kr/{isbn13}"));
    }

    @Test
    @DisplayName("주소에 쉼표가 있어도 규칙이 깨지지 않는다")
    void urlMayContainCommas() {
        var templates = of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/s?q={isbn13}&f=a,b,c");

        assertEquals("https://lib.example.kr/s?q=9788983711892&f=a,b,c",
                templates.bestFor("111111", "9788983711892", null).orElseThrow().url());
    }

    @Test
    @DisplayName("저장소에 든 실제 규칙 파일이 읽힌다")
    void shippedFileParses() {
        // 규칙을 한 줄 잘못 적으면 서버가 뜨지 않습니다. 그 사실을 배포가 아니라 여기서 압니다.
        assertDoesNotThrow(() -> OpacTemplates.load());
    }

    // ── 규칙을 통째로 끄는 스위치 ──────────────────────────────────────────

    private static final List<String> ONE_RULE =
            List.of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}");
    private static final List<String> ONE_PATTERN =
            List.of("lib.example.kr,href=\"(/book/[^\"]+)\"");

    @Test
    @DisplayName("꺼 두면 규칙이 있어도 링크를 만들지 않는다")
    void disabledMeansNoLink() {
        // 규칙 307줄이 실제 OPAC 에서 검증되지 않아 지금은 꺼 두었습니다. 틀린 규칙은 HTTP 200
        // 을 주면서 결과만 0건이라 「소장한다더니 그 책이 없네」로 보이고, 그것이 소장 정보
        // 자체를 믿지 못하게 만듭니다. 검증하지 않은 색인을 전환하지 않는 것과 같은 판단입니다.
        var off = OpacTemplates.of(ONE_RULE, ONE_PATTERN, false);

        // **규칙이 없는 도서관과 똑같이 보입니다.** 그래야 부르는 쪽이 이미 가지고 있는
        // 홈페이지 폴백을 그대로 타고, 내려앉는 길을 새로 만들지 않아도 됩니다.
        assertTrue(off.bestFor("111111", "9788983711892", "코스모스").isEmpty());
        assertEquals(OpacLink.Kind.HOMEPAGE, off.kindFor("111111"));
        assertFalse(off.linksEnabled());
    }

    @Test
    @DisplayName("꺼 두어도 규칙 수는 그대로라 파일을 잃은 것과 구별된다")
    void disabledStillCountsRules() {
        // /api/status 의 opacRuleLibraries 가 0이 되면 「꺼서 0」인지 「규칙 파일을 잃어서 0」
        // 인지 구별할 수 없습니다. 예전에 .gitignore 가 CSV 를 삼킨 적이 있어서 그쪽을 먼저
        // 의심하게 되고, 없는 원인을 찾게 됩니다.
        var off = OpacTemplates.of(ONE_RULE, ONE_PATTERN, false);

        assertEquals(1, off.size());
        assertEquals(1, off.patternCount());
    }

    @Test
    @DisplayName("진단은 스위치를 건너뛰어 규칙 그대로 시험한다")
    void diagnosisSeesTheRules() {
        // 꺼 둔 채로 규칙을 보완하려면 배포된 서버에서 한 줄씩 시험할 수 있어야 합니다.
        // 서버가 그 OPAC 에 닿는지는 나가는 IP 에 달려 있어 로컬에서는 알 수 없습니다.
        var off = OpacTemplates.of(ONE_RULE, ONE_PATTERN, false);

        assertEquals(OpacLink.Kind.DETAIL_LOOKUP, off.asIfEnabled().kindFor("111111"));
        assertEquals("https://lib.example.kr/search?q=9788983711892",
                off.asIfEnabled().bestFor("111111", "9788983711892", null).orElseThrow().url());
        // 원래 것은 그대로 꺼져 있어야 합니다. 사본이 원본을 바꾸면 스위치가 새어 나갑니다.
        assertTrue(off.bestFor("111111", "9788983711892", null).isEmpty());
    }

    @Test
    @DisplayName("꺼 두어도 잘못된 줄은 뜰 때 걸린다")
    void disabledStillRejectsBadRules() {
        // 지금 쓰지 않는다고 통과시키면 그 줄이 남아 있다가 켜는 날 서버가 뜨지 않습니다.
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(
                List.of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q=고정"),
                List.of(), false));
    }

    // ── 상세 패턴 ──────────────────────────────────────────────────────────

    private static OpacTemplates with(List<String> templates, String... patterns) {
        return OpacTemplates.of(templates, List.of(patterns));
    }

    private static final List<String> NOWON_LIKE = List.of(
            "111111,ISBN_SEARCH,UTF-8,https://www.nowonlib.kr/KeywordSearchResult/{isbn13}");

    @Test
    @DisplayName("ISBN 검색 규칙에 상세 패턴이 붙으면 그 도서관은 상세 조회 단계가 된다")
    void patternUpgradesSearchToDetailLookup() {
        var templates = with(NOWON_LIKE, "www.nowonlib.kr,href=\"(/bookDetail/[^\"]+)\"");

        assertEquals(OpacLink.Kind.DETAIL_LOOKUP, templates.kindFor("111111"));
        // 링크 자체는 여전히 ISBN 검색 주소입니다. 상세는 누를 때 그 결과에서 뽑습니다.
        var link = templates.bestFor("111111", "9788983711892", null).orElseThrow();
        assertEquals(OpacLink.Kind.ISBN_SEARCH, link.kind());
        assertTrue(templates.detailPatternFor(link.url()).isPresent());
        assertEquals(1, templates.patternCount());
    }

    @Test
    @DisplayName("패턴이 없는 호스트의 도서관은 그대로 검색 단계다")
    void noPatternMeansSearch() {
        var templates = with(NOWON_LIKE, "public.seocholib.or.kr,href=\"(/bookDetail/[^\"]+)\"");
        assertEquals(OpacLink.Kind.ISBN_SEARCH, templates.kindFor("111111"));
        assertTrue(templates.detailPatternFor("https://www.nowonlib.kr/KeywordSearchResult/1").isEmpty());
    }

    @Test
    @DisplayName("열쇠는 호스트만이 아니라 경로 조각으로도 좁힐 수 있고, 긴 것이 이긴다")
    void longestKeyWins() {
        var templates = with(List.of(),
                "library.daegu.go.kr,href=\"(/a/[^\"]+)\"",
                "library.daegu.go.kr/dalseolib,href=\"(/b/[^\"]+)\"");

        assertEquals("library.daegu.go.kr/dalseolib",
                templates.detailPatternFor("https://library.daegu.go.kr/dalseolib/search?q={isbn13}")
                        .orElseThrow().key());
        assertEquals("library.daegu.go.kr",
                templates.detailPatternFor("https://library.daegu.go.kr/junggu/search?q=1")
                        .orElseThrow().key());
        // 「library.daegu.go.kr」가 「library.daegu.go.kr2」에 붙으면 안 됩니다.
        assertTrue(templates.detailPatternFor("https://library.daegu.go.kr2/search").isEmpty());
    }

    @Test
    @DisplayName("정규식이 잘못되었거나 그룹이 하나가 아니면 실행을 실패시킨다")
    void badPatternsFailStartup() {
        assertThrows(IllegalStateException.class, () -> with(List.of(), "lib.example.kr,href=\"([^\"]+\""));
        assertThrows(IllegalStateException.class, () -> with(List.of(), "lib.example.kr,href=\"[^\"]+\""),
                "그룹이 없으면 무엇이 주소인지 모릅니다");
        assertThrows(IllegalStateException.class, () -> with(List.of(), "lib.example.kr,(href)=\"([^\"]+)\""),
                "그룹이 둘이면 어느 것이 주소인지 모릅니다");
        assertThrows(IllegalStateException.class, () -> with(List.of(), "lib.example.kr,(.*)"),
                "빈 문자열에도 맞는 정규식은 아무 페이지에서나 찾았다가 됩니다");
        assertThrows(IllegalStateException.class, () -> with(List.of(), "lib.example.kr"),
                "정규식 칸이 없습니다");
        assertThrows(IllegalStateException.class,
                () -> with(List.of(), "lib.example.kr,(/a.*)", "LIB.example.kr/,(/b.*)"),
                "대소문자와 끝의 슬래시만 다른 열쇠는 같은 열쇠입니다");
    }

    @Test
    @DisplayName("정규식에 쉼표가 있어도 첫 쉼표에서만 나눈다")
    void patternMayContainCommas() {
        var templates = with(List.of(), "lib.example.kr,href=\"(/book/[0-9]{1,3})\"");
        assertEquals(1, templates.patternCount());
    }

    @Test
    @DisplayName("DETAIL_LOOKUP 을 규칙 표에 적으면 실패시킨다")
    void detailLookupIsNotATemplate() {
        // 검색 규칙과 상세 패턴이 함께 있을 때 저절로 되는 것이라 적는 것이 아닙니다.
        assertThrows(IllegalStateException.class,
                () -> of("111111,DETAIL_LOOKUP,UTF-8,https://lib.example.kr/s?q={isbn13}"));
    }
}
