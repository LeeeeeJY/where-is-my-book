package kr.wimb.opac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    // ── 확인한 시스템만 켜는 허용 목록 ────────────────────────────────────

    private static final List<String> TWO_HOSTS = List.of(
            "111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}",
            "222222,ISBN_SEARCH,UTF-8,https://Other.example.kr/main/search?q={isbn13}",
            "333333,ISBN_SEARCH,UTF-8,https://other.example.kr/english/search?q={isbn13}");

    @Test
    @DisplayName("허용 목록이 비어 있으면 전부 켠다")
    void emptyAllowlistMeansAll() {
        var all = OpacTemplates.of(TWO_HOSTS, List.of(), true, Set.of());
        assertEquals(OpacLink.Kind.ISBN_SEARCH, all.kindFor("111111"));
        assertEquals(OpacLink.Kind.ISBN_SEARCH, all.kindFor("222222"));
        assertTrue(all.enabledHosts().isEmpty());
    }

    @Test
    @DisplayName("허용 목록에 적힌 호스트의 규칙만 링크로 쓴다")
    void allowlistLimitsToListedHosts() {
        // 스위치 하나로는 「확인한 만큼씩 켜라」를 지킬 수 없습니다. 전부 켜 놓으면 어느 규칙이
        // 틀렸는지 알 방법이 없고, 확인이 끝난 시스템이 있어도 나머지 때문에 켤 수 없습니다.
        var some = OpacTemplates.of(TWO_HOSTS, List.of(), true,
                OpacTemplates.parseEnabledHosts("lib.example.kr"));

        assertEquals(OpacLink.Kind.ISBN_SEARCH, some.kindFor("111111"));
        assertEquals("https://lib.example.kr/search?q=9788983711892",
                some.bestFor("111111", "9788983711892", null).orElseThrow().url());
        // 목록 밖은 **규칙이 없는 도서관과 똑같이** 보여야 합니다. 그래야 부르는 쪽이 이미
        // 가지고 있는 홈페이지 폴백을 그대로 탑니다.
        assertEquals(OpacLink.Kind.HOMEPAGE, some.kindFor("222222"));
        assertTrue(some.bestFor("222222", "9788983711892", "코스모스").isEmpty());
        // 규칙 수는 그대로입니다. 목록으로 줄인 것과 파일을 잃은 것이 구별되어야 합니다.
        assertEquals(3, some.size());
    }

    @Test
    @DisplayName("허용 목록의 열쇠는 상세 패턴과 같은 모양이라 경로로 좁힐 수 있다")
    void allowlistKeyMayNarrowByPath() {
        // 한 호스트에 시스템이 둘인 곳(의정부 /main 과 /english)에서 확인한 쪽만 켭니다.
        // 대소문자와 끝의 / 는 가리지 않습니다.
        var some = OpacTemplates.of(TWO_HOSTS, List.of(), true,
                OpacTemplates.parseEnabledHosts(" Other.example.kr/main/ , "));

        assertEquals(Set.of("other.example.kr/main"), some.enabledHosts());
        assertEquals(OpacLink.Kind.ISBN_SEARCH, some.kindFor("222222"));
        assertEquals(OpacLink.Kind.HOMEPAGE, some.kindFor("333333"));
        assertEquals(OpacLink.Kind.HOMEPAGE, some.kindFor("111111"));
        // 「other.example.kr/mainx」처럼 글자만 이어지는 것은 덮지 않습니다.
        var prefixOnly = OpacTemplates.of(
                List.of("444444,ISBN_SEARCH,UTF-8,https://other.example.kr/mainx/s?q={isbn13}"),
                List.of(), true, OpacTemplates.parseEnabledHosts("other.example.kr/main"));
        assertEquals(OpacLink.Kind.HOMEPAGE, prefixOnly.kindFor("444444"));
    }

    @Test
    @DisplayName("규칙 주소에 포트가 붙어 있어도 허용 목록의 호스트 이름이 덮는다")
    void allowlistIgnoresPortInRuleUrl() {
        // 김해(libbook.gimhae.go.kr:8080)와 남해(lib.namhae.go.kr:8443)가 그렇습니다. 포트를 글자
        // 그대로 견주면 목록에 호스트를 적어도 덮이지 않아, 켰는데도 홈페이지로 갑니다.
        // 실제로 그렇게 40곳이 빠져 있었습니다. 열쇠는 호스트 이름으로만 적습니다.
        var ported = OpacTemplates.of(
                List.of("555555,TITLE_SEARCH,UTF-8,http://libbook.example.kr:8080/search/list.do?sstring={title}",
                        "666666,ISBN_SEARCH,UTF-8,https://lib.example.kr:8443/kolas3_01/search?v={isbn13}"),
                List.of(), true, OpacTemplates.parseEnabledHosts("libbook.example.kr,lib.example.kr"));

        assertEquals(OpacLink.Kind.TITLE_SEARCH, ported.kindFor("555555"));
        assertEquals(OpacLink.Kind.ISBN_SEARCH, ported.kindFor("666666"));
        // 만든 주소에는 포트가 그대로 남습니다. 견줄 때만 떼는 것입니다.
        assertEquals("https://lib.example.kr:8443/kolas3_01/search?v=9788983711892",
                ported.bestFor("666666", "9788983711892", null).orElseThrow().url());
    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 허용 목록이 무엇이든 전부 꺼진다")
    void switchOffBeatsAllowlist() {
        var off = OpacTemplates.of(TWO_HOSTS, List.of(), false,
                OpacTemplates.parseEnabledHosts("lib.example.kr"));
        assertEquals(OpacLink.Kind.HOMEPAGE, off.kindFor("111111"));
        assertTrue(off.bestFor("111111", "9788983711892", null).isEmpty());
    }

    @Test
    @DisplayName("진단은 허용 목록도 건너뛴다")
    void diagnosisIgnoresAllowlist() {
        // 목록에 넣기 전에 배포된 서버에서 그 호스트를 시험해 봐야 넣을 수 있습니다.
        var some = OpacTemplates.of(TWO_HOSTS, List.of(), true,
                OpacTemplates.parseEnabledHosts("lib.example.kr"));
        assertEquals(OpacLink.Kind.ISBN_SEARCH, some.asIfEnabled().kindFor("222222"));
        // 원본은 그대로입니다.
        assertEquals(OpacLink.Kind.HOMEPAGE, some.kindFor("222222"));
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
    @DisplayName("제목 검색 규칙은 @anchor: 패턴이 붙었을 때만 상세 조회 단계가 된다")
    void titleSearchNeedsAnAnchoredPattern() {
        List<String> titleOnly = List.of(
                "111111,TITLE_SEARCH,UTF-8,https://www.l4d.or.kr/info/plusSearchResultList.do?searchKeyword={title}");

        var anchored = with(titleOnly, "www.l4d.or.kr,@anchor:@onclick:fnSearchResultDetail");
        assertEquals(OpacLink.Kind.DETAIL_LOOKUP, anchored.kindFor("111111"));
        // 링크 자체는 여전히 제목 검색 주소입니다. 상세는 누를 때 그 결과에서 ISBN 의 줄을 골라 뽑습니다.
        assertEquals(OpacLink.Kind.TITLE_SEARCH, anchored.bestFor("111111", "9788996991342", "미움받을 용기").orElseThrow().kind());

        // 닻 없는 규칙은 결과의 첫 줄을 씁니다. 제목 검색의 첫 줄은 같은 제목의 아무 판이나입니다.
        var plain = with(titleOnly, "www.l4d.or.kr,@onclick:fnSearchResultDetail");
        assertEquals(OpacLink.Kind.TITLE_SEARCH, plain.kindFor("111111"));

        // ISBN 검색 규칙에는 어느 쪽 규칙이든 상세 조회입니다.
        var isbn = with(List.of("111111,ISBN_SEARCH,UTF-8,https://www.l4d.or.kr/s?q={isbn13}"),
                "www.l4d.or.kr,@anchor:@onclick:fnSearchResultDetail");
        assertEquals(OpacLink.Kind.DETAIL_LOOKUP, isbn.kindFor("111111"));
    }

    @Test
    @DisplayName("열쇠에 ?칸이름 이 붙으면 그 칸이 값과 함께 있는 검색 주소에만 걸린다")
    void keyWithRequiredParamOnlyCoversNarrowedSearches() {
        // 파주에서 실제로 걸린 모양입니다(2026-11-10). 분관 사이트마다 주소가 따로라 「한 도서관만 쓰는
        // 주소」인데, 다섯 곳은 searchLibrary=ALL 이라 결과에 다른 분관의 소장본이 섞여 있었습니다.
        var templates = with(List.of(
                "111111,TITLE_SEARCH,UTF-8,https://lib.paju.go.kr/grlib/plusSearchResultList.do?searchLibraryArr=MM&searchKeyword={title}",
                "222222,TITLE_SEARCH,UTF-8,https://lib.paju.go.kr/gclib/plusSearchResultList.do?searchLibrary=ALL&searchKeyword={title}",
                "333333,TITLE_SEARCH,UTF-8,https://lib.paju.go.kr/small/plusSearchResultList.do?searchLibraryArr=&searchKeyword={title}"),
                "lib.paju.go.kr?searchLibraryArr,@anchor:@onclick:fnSearchResultDetail");

        assertEquals(OpacLink.Kind.DETAIL_LOOKUP, templates.kindFor("111111"));
        assertEquals("lib.paju.go.kr?searchLibraryArr", templates.titleDetailPatternFor("111111").orElseThrow().key());
        // 주소는 하나뿐이지만 분관으로 좁히지 않은 검색입니다. 닻만으로는 누른 분관의 줄을 가를 수 없습니다.
        assertEquals(OpacLink.Kind.TITLE_SEARCH, templates.kindFor("222222"));
        // 칸은 있는데 값이 비어 있는 것도 좁힌 것이 아닙니다(마포 작은도서관의 통합 검색이 이 모양입니다).
        assertEquals(OpacLink.Kind.TITLE_SEARCH, templates.kindFor("333333"));

        // 채운 주소(누를 때 서버가 받는 것)로 물어도 같습니다.
        assertTrue(templates.detailPatternFor(
                "https://lib.paju.go.kr/grlib/plusSearchResultList.do?searchLibraryArr=MM&searchKeyword=%EB%8D%B0").isPresent());
        assertTrue(templates.detailPatternFor(
                "https://lib.paju.go.kr/gclib/plusSearchResultList.do?searchLibrary=ALL&searchKeyword=%EB%8D%B0").isEmpty());
    }

    @Test
    @DisplayName("분관을 여럿 늘어놓은 검색은 칸이 있어도 좁힌 것이 아니다")
    void severalBranchesAreNotNarrowed() {
        // 연천·광산은 칸을 여러 번, 사하는 쉼표로 분관을 전부 늘어놓습니다. 통합 검색과 같습니다.
        assertTrue(OpacTemplates.hasParam("https://x.kr/s?searchLibraryArr=MA&q={title}", "searchLibraryArr"));
        assertFalse(OpacTemplates.hasParam("https://x.kr/s?searchLibraryArr=MA&searchLibraryArr=BR&q={title}", "searchLibraryArr"));
        assertFalse(OpacTemplates.hasParam("https://x.kr/s?manage_code=BC%2CCE%2CGS&q={title}", "manage_code"));
        assertFalse(OpacTemplates.hasParam("https://x.kr/s?manage_code=BC,CE&q={title}", "manage_code"));
        assertFalse(OpacTemplates.hasParam("https://x.kr/s?searchLibraryArr=&q={title}", "searchLibraryArr"));
        assertFalse(OpacTemplates.hasParam("https://x.kr/s?q={title}", "searchLibraryArr"));
        // 이름이 다른 칸에 같은 글자가 들어 있어도 그 칸이 아닙니다.
        assertFalse(OpacTemplates.hasParam("https://x.kr/s?xsearchLibraryArr=MA", "searchLibraryArr"));
        // 조각(#) 뒤는 질의가 아닙니다.
        assertFalse(OpacTemplates.hasParam("https://x.kr/s?q=1#searchLibraryArr=MA", "searchLibraryArr"));
    }

    @Test
    @DisplayName("칸 이름은 대소문자를 가리고, 같은 경로면 칸 조건이 붙은 열쇠가 이긴다")
    void requiredParamIsCaseSensitiveAndMoreSpecific() {
        var templates = with(List.of(
                "111111,ISBN_SEARCH,UTF-8,https://Lib.Example.kr/s?manage_code=MA&q={isbn13}",
                "222222,ISBN_SEARCH,UTF-8,https://lib.example.kr/s?q={isbn13}"),
                "LIB.example.kr/?manage_code,href=\"(/narrowed/[^\"]+)\"",
                "lib.example.kr,href=\"(/any/[^\"]+)\"");

        // 호스트는 대소문자를 가리지 않지만 칸 이름은 가립니다. 소문자로 바꿔 버리면 searchLibraryArr 같은
        // 이름이 어느 주소에도 걸리지 않습니다.
        assertEquals("lib.example.kr?manage_code",
                templates.detailPatternFor("https://lib.example.kr/s?manage_code=MA&q=1").orElseThrow().key());
        assertEquals("lib.example.kr",
                templates.detailPatternFor("https://lib.example.kr/s?q=1").orElseThrow().key());
        assertEquals("lib.example.kr",
                templates.detailPatternFor("https://lib.example.kr/s?MANAGE_CODE=MA&q=1").orElseThrow().key());
    }

    @Test
    @DisplayName("열쇠의 ? 뒤에 칸 이름 하나가 아닌 것이 오면 뜰 때 걸린다")
    void rejectsMalformedRequiredParam() {
        for (String key : List.of("lib.example.kr?a=1", "lib.example.kr?a&b", "lib.example.kr?")) {
            assertThrows(IllegalStateException.class, () -> with(NOWON_LIKE, key + ",href=\"(/d/[^\"]+)\""), key);
        }
    }

    @Test
    @DisplayName("여러 도서관이 한 제목 검색 주소를 함께 쓰면 닻 규칙이 있어도 상세로 보내지 않는다")
    void sharedTitleSearchIsNotAnchored() {
        // 작은도서관 둘이 통합 검색 주소 하나를 함께 쓰고, 셋째 도서관은 분관으로 좁힌 주소를 씁니다.
        // 통합 검색 결과에는 같은 ISBN 의 줄이 분관마다 따로 있어서, 닻만으로는 어느 분관 것인지 못 가릅니다.
        var templates = with(List.of(
                "111111,TITLE_SEARCH,UTF-8,https://www.l4d.or.kr/small/plusSearchResultList.do?searchLibrary=ALL&searchKeyword={title}",
                "222222,TITLE_SEARCH,UTF-8,https://www.l4d.or.kr/small/plusSearchResultList.do?searchLibrary=ALL&searchKeyword={title}",
                "333333,TITLE_SEARCH,UTF-8,https://www.l4d.or.kr/small/plusSearchResultList.do?searchLibraryArr=SN&searchKeyword={title}"),
                "www.l4d.or.kr,@anchor:@onclick:fnSearchResultDetail");

        assertEquals(OpacLink.Kind.TITLE_SEARCH, templates.kindFor("111111"));
        assertEquals(OpacLink.Kind.TITLE_SEARCH, templates.kindFor("222222"));
        assertTrue(templates.titleDetailPatternFor("111111").isEmpty());
        assertEquals(2, templates.titleSearchSharedBy("111111"));

        // 같은 경로라도 분관 부호가 붙어 주소가 다르면 그 도서관만의 검색입니다.
        assertEquals(OpacLink.Kind.DETAIL_LOOKUP, templates.kindFor("333333"));
        assertTrue(templates.titleDetailPatternFor("333333").isPresent());
        assertEquals(1, templates.titleSearchSharedBy("333333"));
        assertEquals(0, templates.titleSearchSharedBy("999999"));
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

    @Test
    @DisplayName("상세 패턴 줄은 정규식, @onclick:함수, @template:정규식 => 주소틀 세 종류를 받는다")
    void parsesThreeKindsOfDetailRules() {
        var templates = OpacTemplates.of(
                List.of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}"),
                List.of("lib.example.kr,href=\"(/book/[^\"]+)\"",
                        "lib.two.kr,@onclick:fnDetail",
                        "lib.three.kr,@template:regNo=\"([^\"]+)\" => detail.do?regNo={1}"),
                true);
        assertEquals(3, templates.patternCount());
        assertInstanceOf(DetailRule.OnclickForm.class, templates.detailPatternFor("https://lib.two.kr/s?q=1").orElseThrow().rule());
        assertInstanceOf(DetailRule.Template.class, templates.detailPatternFor("https://lib.three.kr/s?q=1").orElseThrow().rule());
    }

    @Test
    @DisplayName("잘못된 상세 규칙 줄은 서버가 뜨지 않게 막는다")
    void rejectsMalformedDetailRules() {
        var tmpl = List.of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@onclick:"), true));
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@onclick:fn Detail"), true));
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@template:regNo=\"([^\"]+)\""), true),
                "주소틀이 없으면 안 됩니다");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@template:regNo=\"([^\"]+)\" => detail.do?a={2}"), true),
                "없는 그룹을 가리키는 자리표");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@template:regNo=\"([^\"]+)\" => detail.do"), true),
                "자리표가 하나도 없는 주소틀");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@template:(x*) => d?a={1}"), true),
                "빈 문자열에 맞는 정규식");
    }

    @Test
    @DisplayName("@json: 규칙 줄은 요청·목록경로·상세주소틀 셋이 다 있어야 하고 METHOD 와 자리표를 검사한다")
    void parsesAndRejectsJsonRules() {
        var tmpl = List.of("111111,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}");
        var ok = OpacTemplates.of(tmpl, List.of(
                "lib.example.kr,@json:POST https://lib.example.kr/api/search {\"k\":\"{isbn13}\"} => contents.bookList => /detail/{bookKey}"), true);
        assertInstanceOf(DetailRule.Json.class, ok.detailPatternFor("https://lib.example.kr/search?q=1").orElseThrow().rule());
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@json:POST https://x.kr/a {\"k\":\"{isbn13}\"} => list"), true), "셋이 아님");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@json:PUT https://x.kr/a {} => list => /d/{id}"), true), "METHOD");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@json:GET https://x.kr/a?q=1 => list => /d/{id}"), true), "{isbn13} 없음");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@json:GET https://x.kr/a?q={isbn13} => list => /d/none"), true), "상세 자리표 없음");
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(tmpl, List.of("lib.example.kr,@json:POST https://x.kr/a => list => /d/{id}"), true), "POST 본문 없음");
    }

    // ── 결과를 프레임으로 보여 주는 OPAC (@frame:) ─────────────────────────

    private static final List<String> FRAMED_TITLE_RULE = List.of(
            "143013,TITLE_SEARCH,UTF-8,https://www.cbelib.go.kr/srch/smart2.do?cmd=SEARCH&menuId=MNU_0000000000000024&searchKeyword={title}");
    private static final String FRAME =
            "https://www.cbelib.go.kr:7443/dls_le/index.php?mod=wdDataSearch&act=searchIList&word={title}&manageCode=MA";

    @Test
    @DisplayName("프레임을 받아 닻으로 고르는 규칙이 있으면 제목 검색 도서관도 상세를 찾아 본다")
    void framedAnchorRuleMakesATitleSearchADetailLookup() {
        var templates = OpacTemplates.of(FRAMED_TITLE_RULE,
                List.of("www.cbelib.go.kr,@frame:" + FRAME + " => @anchor:href=\"(index\\.php\\?act=dataView[^\"]*)\""), true);

        assertEquals(OpacLink.Kind.DETAIL_LOOKUP, templates.kindFor("143013"));
        var pattern = templates.titleDetailPatternFor("143013").orElseThrow();
        assertInstanceOf(DetailRule.Frame.class, pattern.rule());
        // 화면이 여는 검색 링크는 그대로 검색 화면입니다. 프레임 주소는 서버만 받습니다.
        assertTrue(templates.bestFor("143013", "9788937462665", "이방인").orElseThrow().url()
                .startsWith("https://www.cbelib.go.kr/srch/smart2.do?"));
    }

    @Test
    @DisplayName("프레임 안쪽이 닻 규칙이 아니면 제목 검색에서는 쓰지 않는다")
    void framedRuleWithoutAnAnchorIsNotUsedForTitleSearch() {
        // 제목 검색 결과의 첫 줄은 그 책이 아닙니다. 프레임을 받는다고 그 사정이 달라지지 않습니다.
        var templates = OpacTemplates.of(FRAMED_TITLE_RULE,
                List.of("www.cbelib.go.kr,@frame:" + FRAME + " => href=\"(index\\.php\\?act=dataView[^\"]*)\""), true);

        assertEquals(OpacLink.Kind.TITLE_SEARCH, templates.kindFor("143013"));
        assertTrue(templates.titleDetailPatternFor("143013").isEmpty());
    }

    @Test
    @DisplayName("틀린 @frame: 줄이 있으면 서버가 뜨지 않는다")
    void malformedFrameRuleStopsTheServer() {
        assertThrows(IllegalStateException.class, () -> OpacTemplates.of(FRAMED_TITLE_RULE,
                List.of("www.cbelib.go.kr,@frame:https://www.cbelib.go.kr:7443/dls_le/index.php?word=fixed => @anchor:href=\"(x[^\"]*)\""), true));
    }
}
