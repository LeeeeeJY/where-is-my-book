package kr.wimb.opac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @frame:} 의 받기 방식 둘이 <b>범위를 지키는지</b>. 목록을 POST 로 받는 것({@code POST})과 응답 머리로 온
 * 쿠키를 들고 한 번 더 받는 것({@code COOKIE})입니다.
 *
 * <p>어떤 요청으로 바뀌고 어느 줄을 고르는지는 {@link SharedPickCasesTest} 가 조사 도구와 같은 사례로 봅니다.
 * 여기 있는 것은 서버에만 있는 약속입니다. 운영자가 쿠키를 드는 것을 대전 서구에 한해 허용하면서 건 조건이
 * 여기서 틀리면, 쿠키가 다른 호스트로 실려 가거나 누름 한 번에 나가는 요청이 조용히 늘어납니다. 어느 쪽도
 * 화면에서는 보이지 않습니다.
 *
 * <ul>
 *   <li>쿠키를 얻으려고 다시 받는 것은 <b>누름 한 번에 한 번</b>이고, 그 뒤의 받기는 처음부터 그 쿠키를 듭니다.</li>
 *   <li>통은 누름 한 번의 몫과 함께 버려집니다. 다음 누름은 쿠키 없이 시작합니다.</li>
 *   <li>쿠키는 받은 출처로만 갑니다. 같은 기관의 다른 호스트로도, 같은 호스트의 다른 포트로도 가지 않습니다.</li>
 *   <li>다시 받는 것도 누름 한 번의 시간 상한에 듭니다.</li>
 *   <li>스크립트만 있는 화면은 「받지 못함」이고 기억하지 않으며 그 호스트의 빗장에 세지 않습니다.</li>
 *   <li>실제 HTTP 에서 쿠키를 드는 요청은 넘겨 주는 응답(3xx)을 따라가지 않습니다.</li>
 * </ul>
 * 가짜 사이트의 HTML 은 지어낸 것입니다. 실제 화면으로 보는 것은 {@link SharedPickCasesTest} 의 {@code sites} 입니다.
 */
class FrameFetchTest {

    private static final String HOST = "https://lib.seogu.go.kr";
    private static final String FRAME = HOST + "/ilus/index.php?act=list&srchText={title}&thisLib=H1";
    private static final DetailRule RULE =
            DetailRule.parse("@frame:COOKIE " + FRAME + " => @verify:href=\"(/ilus/detail\\.php\\?c=\\d+)\"");
    private static final String STUB = "<script>location.reload();</script>";
    private static final String SESSION = "PHPSESSID=0fake0session0";
    private static final String OURS = "9788937473135";
    private static final String OTHER = "9788996991342";

    /** 쿠키가 있어야 화면을 주는 가짜 사이트. 받은 요청을 한 줄씩 적고, 받을 때마다 가짜 시계를 돌립니다. */
    private static class Site implements DetailResolver.Fetcher {
        final List<String> log = new ArrayList<>();
        final List<Request> requests = new ArrayList<>();
        final Map<String, String> pages = new HashMap<>();
        final Map<String, Duration> costs = new HashMap<>();
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        /** 이 쿠키를 실은 요청에만 화면을 줍니다. 없으면 쿠키 없이 줍니다. */
        String need = SESSION;
        /** 쿠키 없는 요청에 응답 머리로 주는 쿠키. */
        List<String> set = List.of(SESSION + "; path=/", "skin=default");
        /** 쿠키가 있어야 하는 출처. 다른 출처의 화면은 쿠키 없이 줍니다. */
        String guarded = HOST;

        void page(String url, String body) {
            pages.put(url, body);
        }

        void list(String title, int... rows) {
            StringBuilder html = new StringBuilder("<ol>");
            for (int n : rows) html.append("<li><a href=\"/ilus/detail.php?c=").append(n).append("\">책 ").append(n).append("</a></li>");
            page(frameUrl(title), html.append("</ol>").toString());
        }

        void detail(int n, String isbn) {
            page(HOST + "/ilus/detail.php?c=" + n, "<dl><dt>ISBN</dt><dd>" + isbn + "</dd></dl>");
        }

        @Override public DetailResolver.Page fetch(URI url) throws IOException {
            return fetch(Request.get(url));
        }

        @Override public DetailResolver.Page fetch(Request request) throws IOException {
            String url = request.url().toString();
            if (url.endsWith("/robots.txt")) return new DetailResolver.Page(404, "", request.url());
            requests.add(request);
            log.add(request.method() + " " + url + (request.body() == null ? "" : " body=" + request.body())
                    + (request.cookie() == null ? "" : " cookie=" + request.cookie()));
            nanos.addAndGet(costs.getOrDefault(url, Duration.ZERO).toNanos());
            boolean carried = request.cookie() != null && List.of(request.cookie().split("; ")).contains(need);
            if (need != null && url.startsWith(guarded + "/") && !carried) {
                // 실제 받기처럼 쿠키를 드는 규칙의 요청에만 응답의 쿠키를 돌려줍니다.
                return new DetailResolver.Page(200, STUB, request.url(), request.session() ? set : List.of());
            }
            String body = pages.get(url);
            return body == null ? new DetailResolver.Page(404, "", request.url())
                    : new DetailResolver.Page(200, body, request.url());
        }

        DetailResolver resolver() {
            Clock clock = Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneId.of("UTC"));
            return new DetailResolver(this, clock, nanos::get);
        }
    }

    private static String frameUrl(String title) {
        return FRAME.replace("{title}", java.net.URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private static String search(String title) {
        return "https://www.seogu.go.kr/library/search?loca=H1&searchWrd=" + java.net.URLEncoder.encode(title, StandardCharsets.UTF_8);
    }

    private static DetailResolver.Context book(String title, String... isbns) {
        return new DetailResolver.Context(isbns[0], "125010", List.of(isbns)).withTitle(title);
    }

    private static String detail(int n) {
        return HOST + "/ilus/detail.php?c=" + n;
    }

    private static final String COOKIE = " cookie=" + SESSION + "; skin=default";

    private static Map<String, Object> tally(DetailResolver resolver, String host) {
        return resolver.hostTally().snapshot().stream().filter(row -> host.equals(row.get("host"))).findFirst()
                .orElseThrow(() -> new AssertionError(host + " 를 센 적이 없습니다"));
    }

    // ── 누름 한 번 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("쿠키를 얻으려고 다시 받는 것은 누름 한 번에 한 번이고, 그 뒤의 받기는 처음부터 그 쿠키를 든다")
    void refetchesOncePerPress() {
        var site = new Site();
        site.list("토지 1", 1, 2);
        site.list("토지", 3, 4);
        site.detail(1, OTHER);
        site.detail(2, OTHER);
        site.detail(3, OTHER);
        site.detail(4, OURS);
        var resolver = site.resolver();
        var budget = resolver.newBudget();

        // 화면이 누름 한 번에서 하는 일입니다. 권차를 붙인 표제로 찾고, 못 찾으면 뗀 표제로 한 번 더 찾습니다.
        var first = resolver.resolve(search("토지 1"), RULE, book("토지 1", OURS), budget);
        var second = resolver.resolve(search("토지"), RULE, book("토지", OURS), budget);

        assertEquals(DetailResolver.Status.NO_MATCH, first.status(), first.note());
        assertTrue(first.note().contains("쿠키를 들고 한 번 다시 받았습니다"), first.note());
        // 둘째 찾기는 다시 받지 않았으므로 그 말이 붙지 않습니다.
        assertFalse(second.note().contains("다시 받았습니다"), second.note());
        assertEquals(List.of(
                "GET " + frameUrl("토지 1"),                    // 쿠키 없이. 스크립트만 있는 화면과 Set-Cookie 가 옵니다
                "GET " + frameUrl("토지 1") + COOKIE,           // 그 쿠키를 들고 한 번 다시
                "GET " + detail(1) + COOKIE,
                "GET " + detail(2) + COOKIE,
                "GET " + frameUrl("토지") + COOKIE,             // 둘째 검색 화면은 처음부터 쿠키를 듭니다
                "GET " + detail(3) + COOKIE), site.log);
        // 넷째 후보가 그 책이지만 보지 않습니다. 후보 상세는 누름 한 번에 셋까지입니다.
        assertEquals(DetailResolver.Status.NO_MATCH, second.status(), second.note());
        // 검색 화면 둘, 쿠키를 들고 다시 받는 한 번, 후보 상세 셋. 한 번 누를 때 그 호스트로 나가는 요청의 상한입니다.
        assertEquals(2 + DetailResolver.MAX_COOKIE_REFETCHES + DetailResolver.MAX_VERIFY_DETAILS, site.log.size());
        assertEquals(6, site.log.size());
        assertEquals(1, site.log.stream().filter(line -> !line.contains(" cookie=")).count(), "쿠키 없이 나간 요청은 처음 한 번");
    }

    @Test
    @DisplayName("통은 누름 한 번의 몫과 함께 버린다. 다음 누름은 쿠키 없이 시작한다")
    void aNewPressStartsWithoutCookies() {
        var site = new Site();
        site.list("데미안", 1);
        site.list("이방인", 2);
        site.detail(1, OURS);
        site.detail(2, OTHER);
        var resolver = site.resolver();

        var one = resolver.resolve(search("데미안"), RULE, book("데미안", OURS));
        var two = resolver.resolve(search("이방인"), RULE, book("이방인", OTHER));

        assertEquals(DetailResolver.Status.RESOLVED, one.status(), one.note());
        assertEquals(DetailResolver.Status.RESOLVED, two.status(), two.note());
        assertEquals(List.of(
                "GET " + frameUrl("데미안"), "GET " + frameUrl("데미안") + COOKIE, "GET " + detail(1) + COOKIE,
                "GET " + frameUrl("이방인"), "GET " + frameUrl("이방인") + COOKIE, "GET " + detail(2) + COOKIE), site.log);
    }

    @Test
    @DisplayName("찾은 답은 기억한다. 같은 책을 다시 누르면 그 호스트로 아무것도 나가지 않는다")
    void resolvedAnswersAreRemembered() {
        var site = new Site();
        site.list("데미안", 1);
        site.detail(1, OURS);
        var resolver = site.resolver();

        resolver.resolve(search("데미안"), RULE, book("데미안", OURS));
        int before = site.log.size();
        var again = resolver.resolve(search("데미안"), RULE, book("데미안", OURS));

        assertTrue(again.fromCache());
        assertEquals(detail(1), again.detailUrl());
        assertEquals(before, site.log.size());
    }

    // ── 쿠키가 가는 곳 ──────────────────────────────────────────────────

    @Test
    @DisplayName("쿠키는 받은 출처로만 간다. 같은 기관의 다른 호스트로도, 같은 호스트의 다른 포트로도 가지 않는다")
    void cookiesStayOnTheirOrigin() {
        var site = new Site();
        // 후보의 상세가 다른 출처에 있는 목록입니다. 하나는 같은 기관의 다른 호스트, 하나는 같은 호스트의 다른 포트입니다.
        site.page(frameUrl("데미안"), "<ol><li><a href=\"https://www.seogu.go.kr/detail?c=1\">1</a></li>"
                + "<li><a href=\"https://lib.seogu.go.kr:8443/detail?c=2\">2</a></li>"
                + "<li><a href=\"http://lib.seogu.go.kr/detail?c=3\">3</a></li></ol>");
        site.page("https://www.seogu.go.kr/detail?c=1", "<p>ISBN " + OTHER + "</p>");
        site.page("https://lib.seogu.go.kr:8443/detail?c=2", "<p>ISBN " + OTHER + "</p>");
        site.page("http://lib.seogu.go.kr/detail?c=3", "<p>ISBN " + OURS + "</p>");
        var rule = DetailRule.parse("@frame:COOKIE " + FRAME + " => @verify:href=\"(https?://[^\"]+)\"");

        var outcome = site.resolver().resolve(search("데미안"), rule, book("데미안", OURS));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertEquals(List.of(
                "GET " + frameUrl("데미안"), "GET " + frameUrl("데미안") + COOKIE,
                "GET https://www.seogu.go.kr/detail?c=1",
                "GET https://lib.seogu.go.kr:8443/detail?c=2",
                "GET http://lib.seogu.go.kr/detail?c=3"), site.log);
        // 다른 출처로 가는 요청은 쿠키를 드는 요청이 아닙니다. 응답의 쿠키를 읽지도 않습니다.
        for (var request : site.requests.subList(2, 5)) {
            assertFalse(request.session(), request.url().toString());
            assertNull(request.cookie(), request.url().toString());
        }
        assertTrue(site.requests.get(0).session() && site.requests.get(1).session());
    }

    @Test
    @DisplayName("COOKIE 를 적지 않은 규칙의 요청은 쿠키를 드는 요청이 아니다")
    void rulesWithoutTheWordNeverCarryCookies() {
        var site = new Site();
        site.need = null;                                    // 쿠키 없이 화면을 주는 사이트
        site.list("데미안", 1);
        site.detail(1, OURS);
        var rule = DetailRule.parse("@frame:" + FRAME + " => @verify:href=\"(/ilus/detail\\.php\\?c=\\d+)\"");

        var outcome = site.resolver().resolve(search("데미안"), rule, book("데미안", OURS));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertEquals(2, site.requests.size());
        for (var request : site.requests) {
            assertFalse(request.session(), request.url().toString());
            assertNull(request.cookie());
        }
    }

    @Test
    @DisplayName("쿠키를 드는 호스트는 cookie-hosts.txt 의 것뿐이고, 지금은 대전 서구 하나다")
    void onlyTheApprovedHostCarriesCookies() throws IOException {
        // 호스트를 더하는 것은 운영자가 정할 일입니다. 이 줄이 실패하면 그 결정이 있었는지부터 보세요.
        assertEquals(Set.of("lib.seogu.go.kr"), SessionCookies.hosts());
        assertTrue(SessionCookies.isCookieHost("LIB.SEOGU.GO.KR"));
        assertFalse(SessionCookies.isCookieHost("www.seogu.go.kr"));
        assertFalse(SessionCookies.isCookieHost(null));

        var refused = assertThrows(IllegalArgumentException.class, () -> DetailRule.parse(
                "@frame:COOKIE https://lib.yuseong.go.kr/list?t={title} => @verify:href=\"(/d[^\"]+)\""));
        assertTrue(refused.getMessage().contains("cookie-hosts.txt"), refused.getMessage());
        assertTrue(refused.getMessage().contains("운영자"), refused.getMessage());

        // 규칙 표에서 COOKIE 를 적은 줄은 전부 그 호스트의 것입니다(아니면 서버가 뜨지 않습니다).
        for (String line : Files.readAllLines(Path.of("src/main/resources/opac/detail-patterns.csv"))) {
            if (line.startsWith("#") || !line.contains(",@frame:")) continue;
            var rule = DetailRule.parse(line.substring(line.indexOf(',') + 1));
            if (rule instanceof DetailRule.Frame frame && frame.carriesCookies()) {
                assertTrue(frame.urlTemplate().startsWith("https://lib.seogu.go.kr/"), line);
            }
        }
    }

    @Test
    @DisplayName("cookie-hosts.txt 의 틀린 줄은 서버가 뜰 때 막는다")
    void rejectsMalformedHostLines() {
        assertEquals(Set.of("a.example.kr", "b.example.kr"),
                SessionCookies.parseHosts("# 주석\n\na.example.kr\nb.example.kr   # 뒤의 주석\n"));
        for (String bad : List.of("Lib.Example.kr", "https://lib.example.kr", "lib.example.kr:8443", "lib.example.kr/ilus", "localhost")) {
            assertThrows(IllegalStateException.class, () -> SessionCookies.parseHosts(bad), bad);
        }
    }

    // ── 스크립트만 있는 화면 ────────────────────────────────────────────

    @Test
    @DisplayName("다시 받고도 스크립트만 있는 화면이면 받지 못한 것이다. 기억하지 않고, 그 호스트의 빗장에 세지 않는다")
    void scriptOnlyScreensAreFailuresNotMisses() {
        var site = new Site();
        site.need = "PHPSESSID=never";                       // 준 쿠키를 받아 주지 않는 사이트
        site.list("데미안", 1);
        var resolver = site.resolver();

        List<DetailResolver.Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < DetailResolver.DOWN_AFTER_FAILURES + 1; i++) {
            outcomes.add(resolver.resolve(search("데미안"), RULE, book("데미안", OURS)));
        }

        for (var outcome : outcomes) {
            assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
            assertFalse(outcome.fromCache(), "받지 못한 것은 기억하지 않습니다");
            assertTrue(outcome.note().contains("스크립트만 있는 화면"), outcome.note());
            assertFalse(outcome.note().contains("0fake0session0"), "사유에 쿠키의 값을 싣지 않습니다: " + outcome.note());
        }
        // 누를 때마다 두 번씩(쿠키 없이 한 번, 들고 한 번) 나갑니다. 빗장이 걸렸다면 넷째 누름에서는 나가지 않습니다.
        assertEquals(2 * (DetailResolver.DOWN_AFTER_FAILURES + 1), site.log.size());
        var row = tally(resolver, "lib.seogu.go.kr");
        assertEquals((long) DetailResolver.DOWN_AFTER_FAILURES + 1, row.get("otherFailure"));
        assertEquals(0L, row.get("skippedDown"));
        assertEquals("스크립트만 있는 화면", row.get("lastFailure"));
        assertEquals(0L, row.get("noMatch"), "「결과 없음」으로 세지 않습니다");
    }

    @Test
    @DisplayName("스크립트만 있는 화면이 응답 머리로 쿠키를 주지 않으면 다시 받지 않는다 (스크립트가 심는 쿠키는 얻지 않는다)")
    void doesNotRefetchWithoutHeaderCookies() {
        var site = new Site();
        site.set = List.of();
        site.list("데미안", 1);

        var outcome = site.resolver().resolve(search("데미안"), RULE, book("데미안", OURS));

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
        assertTrue(outcome.note().contains("응답 머리로 온 새 쿠키가 없습니다"), outcome.note());
        assertEquals(List.of("GET " + frameUrl("데미안")), site.log);
    }

    @Test
    @DisplayName("후보의 상세가 스크립트만 있는 화면이면 거기서 그만둔다. 다시 받는 한 번은 이미 썼다")
    void aScriptOnlyDetailStopsTheSearch() {
        var site = new Site() {
            @Override public DetailResolver.Page fetch(Request request) throws IOException {
                // 목록은 쿠키를 받아 주는데 상세는 받아 주지 않는 사이트
                if (request.url().toString().contains("detail.php")) {
                    requests.add(request);
                    log.add("GET " + request.url() + (request.cookie() == null ? "" : " cookie=" + request.cookie()));
                    return new DetailResolver.Page(200, STUB, request.url(), request.session() ? List.of("other=1") : List.of());
                }
                return super.fetch(request);
            }
        };
        site.list("데미안", 1, 2);

        var outcome = site.resolver().resolve(search("데미안"), RULE, book("데미안", OURS));

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
        assertTrue(outcome.note().contains("후보의 상세 화면을 받지 못했습니다"), outcome.note());
        // 첫 후보에서 그만둡니다. 둘째 후보를 또 두드리지 않고, 상세를 쿠키 때문에 다시 받지도 않습니다.
        assertEquals(List.of("GET " + frameUrl("데미안"), "GET " + frameUrl("데미안") + COOKIE, "GET " + detail(1) + COOKIE),
                site.log);
    }

    // ── 시간 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("쿠키를 들고 다시 받는 것도 누름 한 번의 시간 상한에 든다")
    void theRefetchCountsAgainstTheTimeLimit() {
        var site = new Site();
        site.list("데미안", 1);
        site.detail(1, OURS);
        // 그 주소는 받을 때마다 4.5초가 걸립니다. 첫 응답(스크립트만 있는 화면)은 받기 제한(5초) 안이지만, 다시 받을
        // 때는 누름 한 번의 상한(8초)에서 3.5초만 남아 있습니다. 5초를 새로 주지 않습니다.
        site.costs.put(frameUrl("데미안"), Duration.ofMillis(4_500));
        var resolver = site.resolver();

        var outcome = resolver.resolve(search("데미안"), RULE, book("데미안", OURS));

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
        assertTrue(outcome.note().contains("시간 상한"), outcome.note());
        assertEquals(List.of("GET " + frameUrl("데미안"), "GET " + frameUrl("데미안") + COOKIE), site.log);
        // 다시 받는 요청에 준 시간은 남은 시간입니다.
        assertEquals(Duration.ofMillis(3_500), site.requests.get(1).within());
        var row = tally(resolver, "lib.seogu.go.kr");
        assertEquals(1L, row.get("timeLimit"));
        // 그 호스트가 답하지 않은 것이 아니라 우리가 그만둔 것입니다.
        assertEquals(0L, row.get("responseTimeout"));
    }

    @Test
    @DisplayName("안쪽이 @verify: 가 아니어도 쿠키를 드는 규칙의 받기는 시간 상한에 묶는다")
    void cookieRulesAreAlwaysTimed() {
        var site = new Site();
        site.page(frameUrl("데미안"), "<ol><li><a href=\"/ilus/detail.php?c=7\">데미안</a> ISBN " + OURS + "</li></ol>");
        var rule = DetailRule.parse("@frame:COOKIE " + FRAME + " => @anchor:href=\"(/ilus/detail\\.php\\?c=\\d+)\"");

        var outcome = site.resolver().resolve(search("데미안"), rule, book("데미안", OURS));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertEquals(detail(7), outcome.detailUrl());
        assertEquals(List.of("GET " + frameUrl("데미안"), "GET " + frameUrl("데미안") + COOKIE), site.log);
        for (var request : site.requests) {
            assertTrue(request.within() != null && request.within().compareTo(DetailResolver.FETCH_TIMEOUT) <= 0,
                    "본문까지 묶는 받기여야 합니다: " + request.within());
        }
    }

    // ── POST ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("@frame:POST 는 본문틀을 채워 폼으로 보내고, 후보의 상세는 GET 으로 받는다")
    void postsTheFilledBody() {
        var site = new Site();
        site.need = null;
        String list = "https://home.pen.go.kr/siminlib/library/searchListAjax.do";
        site.page(list, "<ul><li><a onclick=\"go('11')\">가</a></li><li><a onclick=\"go('22')\">나</a></li></ul>");
        site.page("https://home.pen.go.kr/siminlib/library/view.do?key=11", "<p>ISBN : " + OTHER + "</p>");
        site.page("https://home.pen.go.kr/siminlib/library/view.do?key=22", "<p>ISBN : " + OURS + "</p>");
        var rule = DetailRule.parse("@frame:POST " + list + " manageCode=AG&searchText={title}&display=10 => "
                + "@verify:@template:onclick=\"go\\('(\\d+)'\\)\" => /siminlib/library/view.do?key={1}");

        var outcome = site.resolver().resolve("https://home.pen.go.kr/siminlib/library/searchList.do?searchText=x", rule,
                book("마의 산", OURS));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertEquals("https://home.pen.go.kr/siminlib/library/view.do?key=22", outcome.detailUrl());
        assertEquals(List.of(
                "POST " + list + " body=manageCode=AG&searchText=%EB%A7%88%EC%9D%98%20%EC%82%B0&display=10",
                "GET https://home.pen.go.kr/siminlib/library/view.do?key=11",
                "GET https://home.pen.go.kr/siminlib/library/view.do?key=22"), site.log);
        var post = site.requests.get(0);
        assertEquals("application/x-www-form-urlencoded", post.contentType());
        assertFalse(post.wantsJson(), "받는 것은 HTML 입니다");
        assertFalse(post.session());
    }

    @Test
    @DisplayName("@frame:POST 의 답도 (검색 주소, ISBN)으로 기억한다")
    void postAnswersAreRemembered() {
        var site = new Site();
        site.need = null;
        String list = "https://home.pen.go.kr/siminlib/library/searchListAjax.do";
        site.page(list, "<ul><li><a href=\"/siminlib/view?key=1\">가</a><img src=\"/cover/" + OURS + "\"></li></ul>");
        var rule = DetailRule.parse("@frame:POST " + list + " isbn={isbn13} => @anchor:href=\"(/siminlib/view\\?key=\\d+)\"");
        var resolver = site.resolver();
        String searchUrl = "https://home.pen.go.kr/siminlib/library/searchList.do?searchText=x";

        var first = resolver.resolve(searchUrl, rule, book("가", OURS));
        var again = resolver.resolve(searchUrl, rule, book("가", OURS));

        assertEquals("https://home.pen.go.kr/siminlib/view?key=1", first.detailUrl(), first.note());
        assertTrue(again.fromCache());
        assertEquals(List.of("POST " + list + " body=isbn=" + OURS), site.log);
    }

    // ── 실제 HTTP ───────────────────────────────────────────────────────

    /** 이 기계에 띄운 작은 서버. 받은 요청을 적어 두고, 경로마다 정해 둔 답을 줍니다. */
    private static final class LocalServer implements AutoCloseable {
        final HttpServer server;
        final List<String> seen = new CopyOnWriteArrayList<>();

        LocalServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var headers = exchange.getRequestHeaders();
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " | type=" + headers.getFirst("Content-Type") + " | accept=" + headers.getFirst("Accept")
                    + " | cookie=" + headers.getFirst("Cookie") + " | body=" + body);
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/redirect")) {
                exchange.getResponseHeaders().add("Set-Cookie", "moved=1; Path=/");
                exchange.getResponseHeaders().add("Location", "/elsewhere");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Set-Cookie", "PHPSESSID=local1; path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "skin=default");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            byte[] page = ("<p>" + path + "</p>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("실제 받기: POST 는 폼 본문과 HTML 을 받겠다는 머리로 나가고, 쿠키는 싣지 않는다")
    void realFetcherPostsAForm() throws Exception {
        try (var local = new LocalServer()) {
            var fetcher = DetailResolver.httpFetcher();

            var page = fetcher.fetch(DetailResolver.Fetcher.Request.post(URI.create(local.url("/list.do")),
                    "manageCode=AG&searchIsbnIssn=" + OURS, "application/x-www-form-urlencoded"));

            assertEquals(200, page.status());
            assertEquals("<p>/list.do</p>", page.body());
            assertEquals(List.of("POST /list.do | type=application/x-www-form-urlencoded | accept=text/html,application/xhtml+xml"
                    + " | cookie=null | body=manageCode=AG&searchIsbnIssn=" + OURS), local.seen);
            // 쿠키를 드는 규칙의 요청이 아니면 응답의 쿠키를 읽지 않습니다.
            assertEquals(List.of(), page.setCookies());
        }
    }

    @Test
    @DisplayName("실제 받기: 쿠키를 드는 요청은 Cookie 머리를 싣고 응답의 Set-Cookie 를 돌려주며, 본문까지의 시간 제한과 함께 쓸 수 있다")
    void realFetcherCarriesAndReturnsCookies() throws Exception {
        try (var local = new LocalServer()) {
            var fetcher = DetailResolver.httpFetcher();
            var plain = DetailResolver.Fetcher.Request.get(URI.create(local.url("/frame"))).within(Duration.ofSeconds(3));

            var first = fetcher.fetch(plain.carrying(null));
            var second = fetcher.fetch(plain.carrying("PHPSESSID=local1; skin=default"));

            assertEquals(List.of("PHPSESSID=local1; path=/", "skin=default"), first.setCookies());
            assertEquals(200, second.status());
            assertEquals(List.of(
                    "GET /frame | type=null | accept=text/html,application/xhtml+xml | cookie=null | body=",
                    "GET /frame | type=null | accept=text/html,application/xhtml+xml | cookie=PHPSESSID=local1; skin=default | body="),
                    local.seen);
        }
    }

    @Test
    @DisplayName("실제 받기: 쿠키를 드는 요청은 넘겨 주는 응답(3xx)을 따라가지 않는다. 나머지 요청은 예전처럼 따라간다")
    void realFetcherDoesNotFollowRedirectsWithCookies() throws Exception {
        try (var local = new LocalServer()) {
            var fetcher = DetailResolver.httpFetcher();
            URI redirect = URI.create(local.url("/redirect"));

            var stayed = fetcher.fetch(DetailResolver.Fetcher.Request.get(redirect).carrying("PHPSESSID=local1"));

            // 따라가면 실은 쿠키가 넘겨받은 곳으로 그대로 실려 갑니다. 해석기는 이것을 「HTTP 302」로 끝냅니다.
            assertEquals(302, stayed.status());
            assertEquals(List.of("GET /redirect | type=null | accept=text/html,application/xhtml+xml | cookie=PHPSESSID=local1 | body="),
                    local.seen);

            var followed = fetcher.fetch(redirect);

            assertEquals(200, followed.status());
            assertEquals("<p>/elsewhere</p>", followed.body());
            assertEquals(List.of(), followed.setCookies(), "쿠키를 드는 요청이 아니면 응답의 쿠키를 읽지 않습니다");
            assertEquals(3, local.seen.size());
            assertTrue(local.seen.get(2).startsWith("GET /elsewhere | "), local.seen.get(2));
            assertTrue(local.seen.get(1).contains("cookie=null") && local.seen.get(2).contains("cookie=null"),
                    "쿠키를 드는 요청이 아니면 쿠키를 싣지 않습니다");
        }
    }
}
