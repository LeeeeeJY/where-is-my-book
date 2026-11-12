package kr.wimb.opac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 결과를 <b>다른 주소의 프레임</b>으로 보여 주는 OPAC 을 위한 {@code @frame:} 규칙.
 *
 * <p>충북교육청 계열 14곳이 이 모양입니다. 검색 화면({@code /srch/smart2.do?…})의 HTML 에는 결과가
 * 없고, 결과는 같은 호스트의 다른 포트({@code :7443})에 있는 프레임에 있습니다.
 *
 * <h2>무엇이 실제이고 무엇이 지어낸 것인가</h2>
 *
 * <ul>
 *   <li><b>프레임 주소의 모양</b>은 2026-11-11 에 국내 회선에서 본 것입니다(운영자의 기록).</li>
 *   <li><b>{@code :7443} 의 robots.txt</b> 는 2026-11-12 에 운영 VM 에서 받은 본문 그대로입니다.</li>
 *   <li><b>프레임 안의 HTML 은 지어낸 것입니다.</b> 목록에 ISBN 이 적혀 있다는 것만 알고(단양교육도서관에서
 *       한 번 봤습니다), 줄의 마크업과 상세 링크의 모양은 모릅니다. 그래서 여기서 고정하는 것은
 *       「그 사이트에서 된다」가 아니라 <b>규칙 형식이 하는 일</b>입니다. 실제 규칙 줄은 국내 회선에서
 *       확인한 뒤에 넣습니다.</li>
 * </ul>
 */
class FrameRuleTest {

    private static final String SEARCH =
            "https://www.cbelib.go.kr/srch/smart2.do?cmd=SEARCH&menuId=MNU_0000000000000024&searchKeyword=";

    private static final String FRAME_TEMPLATE =
            "https://www.cbelib.go.kr:7443/dls_le/index.php?mod=wdDataSearch&act=searchIList"
                    + "&word={title}&searchType=&manageCode=MA&siteId=SITE_000000000000003";

    /** 지어낸 안쪽 규칙. 실제 프레임의 링크 모양은 아직 모릅니다. */
    private static final String INNER = "@anchor:href=\"(index\\.php\\?mod=wdDataSearch&amp;act=dataView[^\"]*)\"";

    private static final String RULE = "@frame:" + FRAME_TEMPLATE + " => " + INNER;

    /** 운영 VM 에서 2026-11-12 에 받은 {@code https://www.cbelib.go.kr:7443/robots.txt} 그대로입니다. */
    private static final String REAL_FRAME_ROBOTS = """
            User-agent: *
            Allow: /

            User-agent: Googlebot
            Disallow: /schoolibook/wdWebop/
            Disallow: /readathon/webop/
            Disallow: /schoolibook/index.php?g_page=dataspace
            Disallow: /readathon/front/index.php?mod=board
            """;

    /** 지어낸 프레임 본문. 같은 제목의 판 둘이 줄마다 ISBN 과 함께 있습니다. */
    private static final String TWO_EDITIONS = """
            <ul class="result">
              <li><a href="index.php?mod=wdDataSearch&amp;act=dataView&amp;key=111&amp;manageCode=MA">이방인 (큰글자책)</a>
                  <span class="isbn">ISBN 9788937437021</span></li>
              <li><a href="index.php?mod=wdDataSearch&amp;act=dataView&amp;key=222&amp;manageCode=MA">이방인</a>
                  <span class="isbn">ISBN 9788937462665</span></li>
            </ul>
            """;

    private static final class FakeSite implements DetailResolver.Fetcher {
        final List<String> asked = new ArrayList<>();
        final Map<String, String> pages = new HashMap<>();
        boolean down = false;

        @Override public DetailResolver.Page fetch(URI url) throws IOException {
            asked.add(url.toString());
            if (down && !url.getPath().equals("/robots.txt")) throw new IOException("connect timed out");
            String body = pages.get(url.toString());
            return body == null ? new DetailResolver.Page(404, "", url) : new DetailResolver.Page(200, body, url);
        }
    }

    private static DetailResolver resolver(FakeSite site) {
        return new DetailResolver(site, Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneId.of("UTC")));
    }

    private static String enc(String text, Charset charset) {
        return java.net.URLEncoder.encode(text, charset).replace("+", "%20");
    }

    private static String frameUrl(String title) {
        return FRAME_TEMPLATE.replace("{title}", enc(title, StandardCharsets.UTF_8));
    }

    private static DetailResolver.Context book(String isbn, String title) {
        return new DetailResolver.Context(isbn, "143013").withTitle(title);
    }

    // ── 형식 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("@frame: 은 프레임 주소틀과 안쪽 규칙을 읽고, 적은 모양 그대로 되돌려 말한다")
    void parsesTheFrameTemplateAndTheInnerRule() {
        var rule = (DetailRule.Frame) DetailRule.parse(RULE);

        assertEquals(FRAME_TEMPLATE, rule.urlTemplate());
        assertEquals(StandardCharsets.UTF_8, rule.encoding());
        assertInstanceOf(DetailRule.Anchored.class, rule.inner());
        assertTrue(rule.anchored(), "안쪽이 닻 규칙이면 제목 검색에서 상세로 갈 수 있는 규칙입니다");
        assertEquals(RULE, rule.describe());
    }

    @Test
    @DisplayName("인코딩을 앞에 적으면 그 글자 집합으로 표제를 보낸다")
    void readsAnExplicitEncoding() {
        var rule = (DetailRule.Frame) DetailRule.parse("@frame:EUC-KR " + FRAME_TEMPLATE + " => " + INNER);

        assertEquals(Charset.forName("EUC-KR"), rule.encoding());
        assertEquals("@frame:EUC-KR " + FRAME_TEMPLATE + " => " + INNER, rule.describe());
        // 「마의 산」의 EUC-KR 바이트입니다. 공백은 %20 입니다.
        assertTrue(DetailResolver.frameUrlFor(rule, book("9788937460883", "마의 산"))
                .contains("word=%B8%B6%C0%C7%20%BB%EA&"));
    }

    @Test
    @DisplayName("안쪽에 @template: 이 와도 첫 「=>」에서만 가른다")
    void splitsOnlyAtTheFirstArrow() {
        var rule = (DetailRule.Frame) DetailRule.parse("@frame:" + FRAME_TEMPLATE
                + " => @anchor:@template:data-key=\"(\\d+)\" => index.php?act=dataView&key={1}");

        assertEquals(FRAME_TEMPLATE, rule.urlTemplate());
        var inner = (DetailRule.Anchored) rule.inner();
        assertInstanceOf(DetailRule.Template.class, inner.inner());
    }

    @Test
    @DisplayName("안쪽이 닻 규칙이 아니면 제목 검색에서 쓸 수 있는 규칙이 아니다")
    void plainInnerRuleIsNotAnchored() {
        var rule = DetailRule.parse("@frame:https://lib.example.kr:7443/list?isbn={isbn13} => href=\"(/view[^\"]+)\"");

        assertFalse(rule.anchored(), "제목 검색 결과의 첫 줄은 그 책이 아닙니다");
    }

    @Test
    @DisplayName("틀린 @frame: 줄은 서버가 뜰 때 막는다")
    void rejectsMalformedFrameRules() {
        // 조용히 빠진 줄은 그 도서관을 검색 결과로만 보내는데, 「패턴이 없어서」인지 「줄이 틀려서」인지
        // 아무도 모르게 됩니다.
        String noPlaceholder = "@frame:https://lib.example.kr:7443/list?word=fixed => " + INNER;
        String unknownPlaceholder = "@frame:https://lib.example.kr:7443/list?word={title}&m={manageCode} => " + INNER;
        String unknownCharset = "@frame:KOREAN-9 https://lib.example.kr:7443/list?word={title} => " + INNER;
        String noArrow = "@frame:https://lib.example.kr:7443/list?word={title} " + INNER;
        String notHttp = "@frame:/dls_le/index.php?word={title} => " + INNER;
        String jsonInside = "@frame:https://lib.example.kr:7443/list?word={title} => "
                + "@json:GET https://lib.example.kr/api?q={isbn13} => list => /d/{id}";
        String frameInside = "@frame:https://lib.example.kr:7443/list?word={title} => " + RULE;
        String badInner = "@frame:https://lib.example.kr:7443/list?word={title} => href=\"(";

        for (String bad : List.of(noPlaceholder, unknownPlaceholder, unknownCharset, noArrow, notHttp,
                jsonInside, frameInside, badInner)) {
            assertThrows(IllegalArgumentException.class, () -> DetailRule.parse(bad), bad);
        }
        // 닻 규칙의 안쪽에 프레임을 넣는 것도 안 됩니다. 프레임이 바깥입니다.
        assertThrows(IllegalArgumentException.class, () -> DetailRule.parse("@anchor:" + RULE));
    }

    // ── 받는 주소 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("검색 화면이 아니라 프레임을 받아, 그 ISBN 이 적힌 줄의 상세로 보낸다")
    void fetchesTheFrameNotTheSearchPage() {
        var site = new FakeSite();
        site.pages.put("https://www.cbelib.go.kr:7443/robots.txt", REAL_FRAME_ROBOTS);
        site.pages.put(frameUrl("이방인"), TWO_EDITIONS);
        String searchUrl = SEARCH + enc("이방인", StandardCharsets.UTF_8);

        var outcome = resolver(site).resolve(searchUrl, DetailRule.parse(RULE), book("9788937462665", "이방인"));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        // 첫 줄(큰글자책)이 아니라 그 ISBN 의 줄입니다. 상대 링크는 프레임 주소 기준으로 풀어 포트가 남습니다.
        assertEquals("https://www.cbelib.go.kr:7443/dls_le/index.php?mod=wdDataSearch&act=dataView&key=222&manageCode=MA",
                outcome.detailUrl());
        // 검색 화면도, 검색 화면이 있는 출처의 robots.txt 도 받지 않습니다. 받는 것은 프레임의 출처뿐입니다.
        assertEquals(List.of("https://www.cbelib.go.kr:7443/robots.txt", frameUrl("이방인")), site.asked);
    }

    @Test
    @DisplayName("표제의 공백은 %20 으로 보낸다")
    void sendsSpacesAsPercentTwenty() {
        var site = new FakeSite();
        site.pages.put(frameUrl("마의 산"), "<li><a href=\"index.php?mod=wdDataSearch&amp;act=dataView&amp;key=9\">마의 산</a> 9788937460883</li>");

        var outcome = resolver(site).resolve(SEARCH + "x", DetailRule.parse(RULE), book("9788937460883", "마의 산"));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertTrue(site.asked.stream().anyMatch(u -> u.contains("word=%EB%A7%88%EC%9D%98%20%EC%82%B0&")), site.asked.toString());
        assertTrue(site.asked.stream().noneMatch(u -> u.contains("+")), "더하기 부호를 공백으로 읽어 주는 것은 서버 마음입니다");
    }

    @Test
    @DisplayName("조사 도구와 같은 글자로 주소를 채운다")
    void fillsTheTemplateWithTheSameBytesAsTheProbeTool() {
        // scripts/test-opac-title-probe.py 가 같은 표제에 같은 글자를 바랍니다. 자바의 URLEncoder 와
        // 파이썬의 quote 는 그대로 두는 글자가 달라서(「~」), 한쪽만 고치면 도구가 확인한 주소와 서버가
        // 내보내는 주소가 갈립니다.
        var rule = (DetailRule.Frame) DetailRule.parse("@frame:https://x.kr/{title} => " + INNER);

        assertEquals("https://x.kr/C%2B%2B%20%EC%9E%85%EB%AC%B8%20%28%EA%B0%9C%EC%A0%95%ED%8C%90%29%20a_b-c.d*e%7Ef",
                DetailResolver.frameUrlFor(rule, book("9788937462665", "C++ 입문 (개정판) a_b-c.d*e~f")));
    }

    // ── robots.txt ──────────────────────────────────────────────────────

    @Test
    @DisplayName("실제로 받은 :7443 의 robots.txt 는 프레임 경로를 막지 않는다")
    void theRealRobotsFileAllowsTheFramePath() {
        // 모든 UA 에 Allow: / 이고, Disallow 줄은 Googlebot 의 것입니다. 우리 UA 에는 걸리지 않습니다.
        assertEquals(List.of(), DetailResolver.parseDisallows(REAL_FRAME_ROBOTS));
    }

    @Test
    @DisplayName("프레임의 출처가 그 경로를 막으면 받지 않는다. 검색 화면의 출처가 열려 있어도 마찬가지다")
    void honoursTheRobotsFileOfTheFrameOrigin() {
        var site = new FakeSite();
        site.pages.put("https://www.cbelib.go.kr/robots.txt", "User-agent: *\nAllow: /\n");
        site.pages.put("https://www.cbelib.go.kr:7443/robots.txt", "User-agent: *\nDisallow: /dls_le/\n");
        site.pages.put(frameUrl("이방인"), TWO_EDITIONS);

        var outcome = resolver(site).resolve(SEARCH + "x", DetailRule.parse(RULE), book("9788937462665", "이방인"));

        assertEquals(DetailResolver.Status.BLOCKED_BY_ROBOTS, outcome.status(), outcome.note());
        assertFalse(site.asked.contains(frameUrl("이방인")), "막힌 경로로 요청이 나갔습니다: " + site.asked);
    }

    @Test
    @DisplayName("포트가 다르면 다른 출처라 robots.txt 를 따로 받는다")
    void portsAreSeparateOrigins() {
        var site = new FakeSite();
        // 검색 화면의 출처는 전부 막고 있습니다. 서버가 받는 것은 그 출처가 아니므로 상관이 없습니다.
        site.pages.put("https://www.cbelib.go.kr/robots.txt", "User-agent: *\nDisallow: /\n");
        site.pages.put("https://www.cbelib.go.kr:7443/robots.txt", REAL_FRAME_ROBOTS);
        site.pages.put(frameUrl("이방인"), TWO_EDITIONS);

        var outcome = resolver(site).resolve(SEARCH + "x", DetailRule.parse(RULE), book("9788937462665", "이방인"));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertFalse(site.asked.contains("https://www.cbelib.go.kr/robots.txt"), site.asked.toString());
    }

    // ── 못 찾았을 때 ────────────────────────────────────────────────────

    @Test
    @DisplayName("프레임에 그 ISBN 이 없으면 첫 줄로 내려가지 않는다")
    void doesNotFallBackToTheFirstRow() {
        var site = new FakeSite();
        site.pages.put(frameUrl("이방인"), TWO_EDITIONS);

        var outcome = resolver(site).resolve(SEARCH + "x", DetailRule.parse(RULE), book("9788954672214", "이방인"));

        // 제목이 같은 다른 판으로 보내느니 검색 화면으로 보냅니다. 거기에는 프레임 안에 결과가 보입니다.
        assertEquals(DetailResolver.Status.NO_MATCH, outcome.status(), outcome.note());
        assertNull(outcome.detailUrl());
    }

    @Test
    @DisplayName("검색에 넣은 표제를 모르면 남의 서버를 두드리지 않고 끝낸다")
    void needsTheSearchedTitle() {
        var site = new FakeSite();

        var outcome = resolver(site).resolve(SEARCH + "x", DetailRule.parse(RULE),
                new DetailResolver.Context("9788937462665", "143013"));

        assertEquals(DetailResolver.Status.NO_MATCH, outcome.status());
        assertTrue(site.asked.isEmpty(), "빈 검색어로 프레임을 받았습니다: " + site.asked);
    }

    @Test
    @DisplayName("닻으로 삼을 ISBN 이 없으면 프레임을 받지 않는다")
    void needsAnIsbnToAnchorOn() {
        var site = new FakeSite();

        var outcome = resolver(site).resolve(SEARCH + "x", DetailRule.parse(RULE),
                new DetailResolver.Context(null, "143013", List.of()).withTitle("이방인"));

        assertEquals(DetailResolver.Status.NO_MATCH, outcome.status());
        assertTrue(site.asked.isEmpty(), site.asked.toString());
    }

    @Test
    @DisplayName("프레임에 닿지 못하면 실패로 답한다. 링크 없음과 다르다")
    void unreachableFrameIsAFetchFailure() {
        var site = new FakeSite();
        site.down = true;

        var outcome = resolver(site).resolve(SEARCH + "x", DetailRule.parse(RULE), book("9788937462665", "이방인"));

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
    }

    // ── 기억 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 검색과 같은 책은 한 번만 받고, 표제가 다른 검색은 따로 받는다")
    void answersAreRememberedPerSearchAndBook() {
        var site = new FakeSite();
        site.pages.put(frameUrl("이방인"), TWO_EDITIONS);
        site.pages.put(frameUrl("이방인 큰글자책"), TWO_EDITIONS);
        var resolver = resolver(site);
        var rule = DetailRule.parse(RULE);
        String searchA = SEARCH + enc("이방인", StandardCharsets.UTF_8);
        String searchB = SEARCH + enc("이방인 큰글자책", StandardCharsets.UTF_8);

        resolver.resolve(searchA, rule, book("9788937462665", "이방인"));
        var again = resolver.resolve(searchA, rule, book("9788937462665", "이방인"));
        assertTrue(again.fromCache(), "같은 검색과 같은 책을 또 받았습니다");
        assertEquals(1, site.asked.stream().filter(u -> u.equals(frameUrl("이방인"))).count());

        // 같은 검색이라도 다른 판을 누른 사람의 답은 따로입니다.
        var otherEdition = resolver.resolve(searchA, rule, book("9788937437021", "이방인"));
        assertTrue(otherEdition.detailUrl().contains("key=111"), otherEdition.detailUrl());

        resolver.resolve(searchB, rule, book("9788937462665", "이방인 큰글자책"));
        assertTrue(site.asked.contains(frameUrl("이방인 큰글자책")), site.asked.toString());
    }
}
