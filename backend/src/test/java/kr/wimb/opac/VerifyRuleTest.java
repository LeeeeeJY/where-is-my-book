package kr.wimb.opac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @verify:} 규칙이 <b>받는 횟수와 시간</b>을 지키는지, 그리고 무엇을 기억하고 무엇을 세는지.
 *
 * <p>어느 줄을 고르는지는 {@link SharedPickCasesTest} 가 조사 도구와 같은 사례로 봅니다. 여기 있는 것은
 * 서버에만 있는 일입니다. 이 규칙은 누를 때마다 남의 서버를 여러 번 두드리므로, 여기서 틀리면 한 번
 * 누를 때 나가는 요청이 조용히 늘거나 누른 사람이 끝없이 기다립니다. 어느 쪽도 화면에서는 「검색 결과로
 * 갔다」로만 보입니다.
 */
class VerifyRuleTest {

    private static final String SEARCH = "https://lib.example.kr/search?title=x&lib=MA";
    private static final String BROAD = "https://lib.example.kr/search?title=y&lib=MA";
    private static final DetailRule RULE = DetailRule.parse("@verify:href=\"(/detail/\\d+)\"");
    private static final String OURS = "9788937473135";
    private static final String OTHER = "9788996991342";

    /** 주소마다 정해 둔 답을 주고, 받을 때마다 가짜 시계를 정해 둔 만큼 돌리는 서버. */
    private static class Site implements DetailResolver.Fetcher {
        final List<String> asked = new ArrayList<>();
        final Map<String, String> pages = new HashMap<>();
        final Map<String, Duration> costs = new HashMap<>();
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T03:00:00Z"));
        String robots;

        void page(String url, String body) {
            pages.put(url, body);
        }

        /** {@code /detail/n} 의 상세 화면. 글자로 그 ISBN 을 적습니다. */
        void detail(int n, String isbn) {
            page("https://lib.example.kr/detail/" + n, "<dl><dt>ISBN</dt><dd>" + isbn + "</dd></dl>");
        }

        void list(String url, int... rows) {
            StringBuilder html = new StringBuilder("<ul>");
            for (int n : rows) {
                // 줄마다 같은 상세로 가는 링크가 둘입니다(표지와 제목).
                html.append("<li><a href=\"/detail/").append(n).append("\"><img></a><a href=\"/detail/")
                    .append(n).append("\">책 ").append(n).append("</a></li>");
            }
            page(url, html.append("</ul>").toString());
        }

        List<String> askedWithoutRobots() {
            return asked.stream().filter(u -> !u.endsWith("/robots.txt")).toList();
        }

        @Override public DetailResolver.Page fetch(URI url) throws IOException {
            String key = url.toString();
            asked.add(key);
            nanos.addAndGet(costs.getOrDefault(key, Duration.ZERO).toNanos());
            if (key.endsWith("/robots.txt")) {
                return robots == null ? new DetailResolver.Page(404, "", url) : new DetailResolver.Page(200, robots, url);
            }
            String body = pages.get(key);
            return body == null ? new DetailResolver.Page(404, "", url) : new DetailResolver.Page(200, body, url);
        }

        DetailResolver resolver() {
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneId.of("UTC"); }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return now.get(); }
            };
            return new DetailResolver(this, clock, nanos::get);
        }
    }

    private static DetailResolver.Context book(String... isbns) {
        return new DetailResolver.Context(isbns[0], "111111", List.of(isbns));
    }

    private static Map<String, Object> tally(DetailResolver resolver, String host) {
        return resolver.hostTally().snapshot().stream().filter(row -> host.equals(row.get("host"))).findFirst()
                .orElseThrow(() -> new AssertionError(host + " 를 센 적이 없습니다"));
    }

    @Test
    @DisplayName("한 번 찾는 데 나가는 요청은 검색 화면 하나와 후보 상세 셋까지다")
    void fetchesAtMostThreeDetails() {
        var site = new Site();
        site.list(SEARCH, 1, 2, 3, 4, 5);
        for (int n = 1; n <= 5; n++) site.detail(n, n == 5 ? OURS : OTHER);

        var outcome = site.resolver().resolve(SEARCH, RULE, book(OURS));

        // 다섯째 줄이 그 책이지만 보지 않습니다. 첫 줄로 내려가지도 않습니다.
        assertEquals(DetailResolver.Status.NO_MATCH, outcome.status(), outcome.note());
        assertNull(outcome.detailUrl());
        assertEquals(List.of(SEARCH, "https://lib.example.kr/detail/1", "https://lib.example.kr/detail/2",
                "https://lib.example.kr/detail/3"), site.askedWithoutRobots());
        assertEquals(1 + DetailResolver.MAX_VERIFY_DETAILS, site.askedWithoutRobots().size());
    }

    @Test
    @DisplayName("찾은 답은 이레, 못 찾은 답은 한 시간 기억하고 그동안 다시 받지 않는다")
    void remembersBothAnswers() {
        var site = new Site();
        site.list(SEARCH, 1, 2);
        site.detail(1, OTHER);
        site.detail(2, OURS);
        var resolver = site.resolver();

        assertEquals("https://lib.example.kr/detail/2", resolver.resolve(SEARCH, RULE, book(OURS)).detailUrl());
        int afterFirst = site.asked.size();
        var again = resolver.resolve(SEARCH, RULE, book(OURS));
        assertTrue(again.fromCache());
        assertEquals("https://lib.example.kr/detail/2", again.detailUrl());
        assertEquals(afterFirst, site.asked.size(), "기억해 둔 답에는 요청이 나가지 않습니다");

        // 같은 검색 주소라도 다른 책이면 다른 답입니다. 제목 검색은 주소에 책이 적혀 있지 않습니다.
        var miss = resolver.resolve(SEARCH, RULE, book("9788936433598"));
        assertEquals(DetailResolver.Status.NO_MATCH, miss.status(), miss.note());
        int afterMiss = site.asked.size();
        assertTrue(resolver.resolve(SEARCH, RULE, book("9788936433598")).fromCache());
        assertEquals(afterMiss, site.asked.size());

        site.now.set(site.now.get().plus(DetailResolver.NO_MATCH_TTL).plusSeconds(1));
        assertFalse(resolver.resolve(SEARCH, RULE, book("9788936433598")).fromCache(), "한 시간이 지나면 다시 받아 봅니다");
    }

    @Test
    @DisplayName("후보의 상세 화면을 받지 못하면 「못 찾음」이 아니고, 기억하지 않는다")
    void anUnreadDetailIsNotAMiss() {
        var site = new Site();
        site.list(SEARCH, 1, 2, 3);
        site.detail(1, OTHER);                       // 2번은 404 를 줍니다
        site.detail(3, OURS);
        var resolver = site.resolver();

        var outcome = resolver.resolve(SEARCH, RULE, book(OURS));

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
        assertTrue(outcome.note().contains("받지 못했습니다"), outcome.note());
        // 받지 못한 후보 뒤의 후보는 두드리지 않습니다. 닿지 않는 호스트를 후보마다 다시 기다리게 됩니다.
        assertFalse(site.asked.contains("https://lib.example.kr/detail/3"));
        assertEquals(1L, resolver.stats().get("opacDetailFailed"), "찾기 한 번을 받지 못한 것으로 한 번 셉니다");
        assertEquals(0L, resolver.stats().get("opacDetailMissed"));
        // 받지 못한 후보가 그 책이었을 수 있으므로 한 시간 동안 「없다」로 답하지 않습니다.
        assertFalse(resolver.resolve(SEARCH, RULE, book(OURS)).fromCache());
    }

    @Test
    @DisplayName("큰글자책 줄을 본 뒤 다음 후보를 받지 못하면 본 줄로 보내되 그 답을 기억하지 않는다")
    void aLargePrintRowSeenBeforeAFailureIsNotRemembered() {
        var site = new Site();
        // 첫 줄이 같은 ISBN 으로 등록된 큰글자책이고 둘째 줄이 보통 판입니다. 둘째 줄의 상세는 404 를 줍니다.
        site.page(SEARCH, "<ul><li><a href=\"/detail/1\">책 [큰글자책]</a></li><li><a href=\"/detail/2\">책</a></li></ul>");
        site.detail(1, OURS);
        var resolver = site.resolver();
        var rule = DetailRule.parse("@verify:@plain:href=\"(/detail/\\d+)\"");

        var outcome = resolver.resolve(SEARCH, rule, book(OURS));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertEquals("https://lib.example.kr/detail/1", outcome.detailUrl());
        assertTrue(site.asked.contains("https://lib.example.kr/detail/2"), "큰글자책 줄에서 멈추지 않고 다음 줄을 봅니다");
        // 둘째 줄이 살아나면 보통 판으로 갑니다. 큰글자책으로 간 답을 이레 동안 되풀이하지 않습니다.
        site.detail(2, OURS);
        var again = resolver.resolve(SEARCH, rule, book(OURS));
        assertFalse(again.fromCache());
        assertEquals("https://lib.example.kr/detail/2", again.detailUrl());
        assertTrue(again.note().contains("빼고 골랐습니다"), again.note());
        assertTrue(resolver.resolve(SEARCH, rule, book(OURS)).fromCache(), "다 보고 고른 답은 기억합니다");
    }

    @Test
    @DisplayName("한 번 누를 때 받아 보는 후보 상세는 검색을 두 번 해도 셋까지다")
    void theShareIsPerClick() {
        var site = new Site();
        site.list(SEARCH, 1, 2);                     // 권차를 붙인 표제로 찾은 결과
        site.list(BROAD, 1, 2, 3, 4);                // 권차를 뗀 표제로 찾은 결과. 앞의 두 줄이 같습니다
        for (int n = 1; n <= 4; n++) site.detail(n, n == 4 ? OURS : OTHER);
        var resolver = site.resolver();

        var budget = resolver.newBudget();
        var first = resolver.resolve(SEARCH, RULE, book(OURS), budget);
        var second = resolver.resolve(BROAD, RULE, book(OURS), budget);

        assertEquals(DetailResolver.Status.NO_MATCH, first.status(), first.note());
        // 두 번째 검색의 앞의 두 줄은 이미 받아 본 화면이라 다시 받지 않고, 남은 몫 하나로 셋째 줄을 봅니다.
        // 넷째 줄(그 책)은 보지 못합니다. 앞의 셋까지만 봅니다.
        assertEquals(DetailResolver.Status.NO_MATCH, second.status(), second.note());
        assertEquals(List.of(SEARCH, "https://lib.example.kr/detail/1", "https://lib.example.kr/detail/2",
                BROAD, "https://lib.example.kr/detail/3"), site.askedWithoutRobots());
    }

    @Test
    @DisplayName("몫을 다 쓴 누름에서는 검색 화면도 받지 않는다")
    void anEmptyShareFetchesNothing() {
        var site = new Site();
        site.list(SEARCH, 1, 2, 3);
        site.list(BROAD, 4);
        for (int n = 1; n <= 3; n++) site.detail(n, OTHER);
        site.detail(4, OURS);
        var resolver = site.resolver();

        var budget = resolver.newBudget();
        resolver.resolve(SEARCH, RULE, book(OURS), budget);
        int before = site.asked.size();
        var second = resolver.resolve(BROAD, RULE, book(OURS), budget);

        assertEquals(DetailResolver.Status.NO_MATCH, second.status(), second.note());
        assertEquals(before, site.asked.size(), "후보 상세를 볼 몫이 없으면 검색 화면만 받고 버리게 됩니다");
        // 받아 본 것이 없는 답이라 기억하지 않습니다. 다음 누름에서는 새 몫으로 받아 봅니다.
        var next = resolver.resolve(BROAD, RULE, book(OURS));
        assertEquals("https://lib.example.kr/detail/4", next.detailUrl(), next.note());
    }

    @Test
    @DisplayName("몫이 모자라 다 보지 못한 답은 기억하지 않는다")
    void aPartialLookIsNotRemembered() {
        var site = new Site();
        site.list(SEARCH, 1, 2);
        site.list(BROAD, 3, 4);
        site.detail(1, OTHER);
        site.detail(2, OTHER);
        site.detail(3, OTHER);
        site.detail(4, OURS);
        var resolver = site.resolver();

        var budget = resolver.newBudget();
        resolver.resolve(SEARCH, RULE, book(OURS), budget);
        var cut = resolver.resolve(BROAD, RULE, book(OURS), budget);
        assertEquals(DetailResolver.Status.NO_MATCH, cut.status(), cut.note());
        assertTrue(cut.note().contains("보지 못했습니다"), cut.note());

        // 다음 누름: 첫 검색은 기억해 둔 「못 찾음」이라 몫을 쓰지 않고, 두 번째 검색을 온전히 봅니다.
        var fresh = resolver.newBudget();
        assertTrue(resolver.resolve(SEARCH, RULE, book(OURS), fresh).fromCache());
        var found = resolver.resolve(BROAD, RULE, book(OURS), fresh);
        assertEquals("https://lib.example.kr/detail/4", found.detailUrl(), found.note());
    }

    @Test
    @DisplayName("검색 화면과 후보 상세를 합쳐 시간 상한에 닿으면 받던 것을 버리고 그만둔다")
    void stopsAtTheTimeLimit() {
        var site = new Site();
        site.list(SEARCH, 1, 2, 3);
        site.detail(1, OTHER);
        site.detail(2, OTHER);
        site.detail(3, OURS);
        // 하나하나는 받기 제한(5초) 안이지만 합치면 상한(8초)을 넘습니다.
        site.costs.put(SEARCH, Duration.ofSeconds(3));
        site.costs.put("https://lib.example.kr/detail/1", Duration.ofSeconds(3));
        site.costs.put("https://lib.example.kr/detail/2", Duration.ofSeconds(3));
        var resolver = site.resolver();

        var outcome = resolver.resolve(SEARCH, RULE, book(OURS));

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
        assertTrue(outcome.note().contains("시간 상한"), outcome.note());
        assertFalse(site.asked.contains("https://lib.example.kr/detail/3"), "상한을 넘긴 뒤에는 받지 않습니다");
        var row = tally(resolver, "lib.example.kr");
        assertEquals(1L, row.get("timeLimit"));
        assertEquals(1L, row.get("attempts"), "끝은 찾기 한 번에 한 번 적습니다");
        // 그 호스트의 탓으로 적지 않습니다. 받기 하나하나는 제한 안이었습니다.
        assertEquals(0L, row.get("responseTimeout"));
        assertNull(row.get("lastFailure"));
        // 기억하지 않습니다. 다음에는 빨리 답할 수 있습니다.
        site.costs.clear();
        assertEquals("https://lib.example.kr/detail/3", resolver.resolve(SEARCH, RULE, book(OURS)).detailUrl());
    }

    @Test
    @DisplayName("앞선 찾기가 시간을 다 썼으면 두 번째 찾기는 받아 보지 않는다")
    void theClockIsPerClickToo() {
        var site = new Site();
        site.list(SEARCH, 1);
        site.list(BROAD, 2);
        site.detail(1, OTHER);
        site.detail(2, OURS);
        site.costs.put(SEARCH, Duration.ofSeconds(4));
        site.costs.put("https://lib.example.kr/detail/1", Duration.ofMillis(4_500));
        var resolver = site.resolver();

        var budget = resolver.newBudget();
        var first = resolver.resolve(SEARCH, RULE, book(OURS), budget);
        int before = site.asked.size();
        var second = resolver.resolve(BROAD, RULE, book(OURS), budget);

        assertEquals(DetailResolver.Status.FETCH_FAILED, first.status(), first.note());
        assertEquals(DetailResolver.Status.FETCH_FAILED, second.status(), second.note());
        assertEquals(before, site.asked.size());
    }

    @Test
    @DisplayName("받기 제한을 다 주고도 답이 없는 후보 상세는 그 호스트가 느린 것으로 적는다")
    void aSlowDetailCountsAgainstTheHost() {
        var site = new Site();
        site.list(SEARCH, 1, 2);
        site.detail(1, OURS);
        site.costs.put("https://lib.example.kr/detail/1", Duration.ofSeconds(6));
        var resolver = site.resolver();

        var outcome = resolver.resolve(SEARCH, RULE, book(OURS));

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status(), outcome.note());
        var row = tally(resolver, "lib.example.kr");
        assertEquals(1L, row.get("responseTimeout"));
        assertEquals(0L, row.get("timeLimit"));
    }

    @Test
    @DisplayName("상세로 간 찾기는 한 번으로 세고, 걸린 시간은 검색 화면과 후보 상세를 합친 것이다")
    void talliesTheWholeWaitOnce() {
        var site = new Site();
        site.list(SEARCH, 1, 2);
        site.detail(1, OTHER);
        site.detail(2, OURS);
        site.costs.put(SEARCH, Duration.ofSeconds(1));
        site.costs.put("https://lib.example.kr/detail/1", Duration.ofSeconds(2));
        site.costs.put("https://lib.example.kr/detail/2", Duration.ofSeconds(2));
        var resolver = site.resolver();

        var outcome = resolver.resolve(SEARCH, RULE, book(OURS));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        var row = tally(resolver, "lib.example.kr");
        assertEquals(1L, row.get("attempts"));
        assertEquals(1L, row.get("resolved"));
        assertEquals(5000L, row.get("maxMillis"), "누른 사람이 기다린 시간입니다");
        assertEquals(1L, row.get("slowAnswers"), "하나하나는 5초 안이어도 합쳐서 5초를 넘긴 것을 셉니다");
        assertEquals(1L, resolver.stats().get("opacDetailResolved"));
    }

    @Test
    @DisplayName("진단으로 잰 것은 후보 상세까지 호스트별로 세지 않는다")
    void probesAreNotTallied() {
        var site = new Site();
        site.list(SEARCH, 1, 2);
        site.detail(2, OURS);                        // 1번은 404
        var resolver = site.resolver();

        resolver.probe(SEARCH, RULE, book(OURS));

        assertTrue(resolver.hostTally().snapshot().isEmpty());
    }

    @Test
    @DisplayName("robots.txt 가 상세 화면의 경로를 막으면 받지 않고, 누를 때마다 검색 화면을 받지도 않는다")
    void honoursRobotsOnTheDetailPath() {
        var site = new Site();
        site.robots = "User-agent: *\nDisallow: /detail/\n";
        site.list(SEARCH, 1, 2);
        site.detail(1, OURS);
        var resolver = site.resolver();

        var outcome = resolver.resolve(SEARCH, RULE, book(OURS));

        assertEquals(DetailResolver.Status.BLOCKED_BY_ROBOTS, outcome.status(), outcome.note());
        assertFalse(site.asked.contains("https://lib.example.kr/detail/1"));
        assertEquals(1L, tally(resolver, "lib.example.kr").get("blockedByRobots"));
        int before = site.asked.size();
        assertTrue(resolver.resolve(SEARCH, RULE, book(OURS)).fromCache());
        assertEquals(before, site.asked.size());
    }

    @Test
    @DisplayName("후보 상세도 그 호스트의 빗장을 지난다")
    void detailsPassTheBreaker() {
        var site = new Site() {
            @Override public DetailResolver.Page fetch(URI url) throws IOException {
                if (url.getHost().equals("opac.example.kr") && !url.getPath().equals("/robots.txt")) {
                    asked.add(url.toString());
                    throw new IOException("connect timed out");
                }
                return super.fetch(url);
            }
        };
        // 상세 화면이 같은 기관의 다른 호스트에 있습니다.
        site.page(SEARCH, "<a href=\"https://opac.example.kr/detail/1\">책</a>");
        var resolver = site.resolver();
        var rule = DetailRule.parse("@verify:href=\"(https://opac\\.example\\.kr/detail/\\d+)\"");

        for (int i = 0; i < DetailResolver.DOWN_AFTER_FAILURES; i++) resolver.resolve(SEARCH, rule, book(OURS));
        long before = site.asked.stream().filter(u -> u.contains("opac.example.kr/detail")).count();
        var skipped = resolver.resolve(SEARCH, rule, book(OURS));

        assertEquals(DetailResolver.Status.FETCH_FAILED, skipped.status(), skipped.note());
        assertEquals(before, site.asked.stream().filter(u -> u.contains("opac.example.kr/detail")).count(),
                "연속으로 닿지 않은 호스트는 한동안 받아 보지 않습니다");
        var row = tally(resolver, "opac.example.kr");
        assertEquals((long) DetailResolver.DOWN_AFTER_FAILURES, row.get("otherFailure"));
        assertEquals(1L, row.get("skippedDown"));
        // 검색 화면의 호스트에는 받지 못한 것으로 적지 않습니다. 그 호스트는 답했습니다.
        assertEquals(List.of("opac.example.kr"),
                resolver.hostTally().snapshot().stream().map(r -> r.get("host")).toList());
    }

    @Test
    @DisplayName("어느 책인지 모르면 받아 보지 않는다")
    void needsAnIsbn() {
        var site = new Site();
        site.list(SEARCH, 1);

        var outcome = site.resolver().resolve(SEARCH, RULE, DetailResolver.Context.NONE);

        assertEquals(DetailResolver.Status.NO_MATCH, outcome.status());
        assertTrue(site.asked.isEmpty(), "확인할 ISBN 이 없으면 남의 서버만 두드리게 됩니다");
    }

    @Test
    @DisplayName("ISBN 으로 검색해 들어간 상세 화면이 검색어를 되돌려 적어도 속지 않는다")
    void isNotFooledByTheEchoedQuery() {
        var site = new Site();
        String search = "https://lib.example.kr/search?isbn=" + OURS;
        site.list(search, 1, 2);
        // 두 상세 화면 모두 숨은 칸에 검색한 ISBN 을 되돌려 적습니다. 글자로 적힌 것은 2번뿐입니다.
        site.page("https://lib.example.kr/detail/1", "<input type=\"hidden\" name=\"isbn\" value=\"" + OURS
                + "\"><a href=\"/search?isbn=" + OURS + "\">목록</a><dl><dt>ISBN</dt><dd>" + OTHER + "</dd></dl>");
        site.page("https://lib.example.kr/detail/2", "<input type=\"hidden\" name=\"isbn\" value=\"" + OURS
                + "\"><dl><dt>ISBN</dt><dd>" + OURS + "</dd></dl>");

        var outcome = site.resolver().resolve(search, RULE, book(OURS));

        assertEquals("https://lib.example.kr/detail/2", outcome.detailUrl(), outcome.note());
    }

    @Test
    @DisplayName("프레임을 받아 그 안의 줄을 상세 화면으로 확인할 수 있다")
    void worksInsideAFrame() {
        var site = new Site();
        site.list("https://lib.example.kr/frame?word=%EB%8D%B0%EB%AF%B8%EC%95%88&lib=MA", 1, 2);
        site.detail(1, OTHER);
        site.detail(2, OURS);
        var rule = DetailRule.parse("@frame:https://lib.example.kr/frame?word={title}&lib=MA => @verify:href=\"(/detail/\\d+)\"");

        var outcome = site.resolver().resolve("https://www.example.kr/search?title=x", rule,
                book(OURS).withTitle("데미안"));

        assertEquals("https://lib.example.kr/detail/2", outcome.detailUrl(), outcome.note());
        assertTrue(rule.anchored(), "제목으로 찾는 프레임에 걸 수 있는 규칙입니다");
    }

    @Test
    @DisplayName("규칙의 문법: 감쌀 수 있는 것과 없는 것")
    void parsesAndRejects() {
        assertEquals("@verify:@onclick:fnDetail", DetailRule.parse("@verify:@onclick:fnDetail").describe());
        assertEquals("@verify:@plain:@onclick:fnDetail", DetailRule.parse("@verify:@plain:@onclick:fnDetail").describe());
        assertEquals("@plain:@anchor-after:@onclick:f", DetailRule.parse("@plain:@anchor-after:@onclick:f").describe());
        assertEquals("@plain:href=\"(/v/[^\"]+)\"", DetailRule.parse("@plain:href=\"(/v/[^\"]+)\"").describe());
        String framed = "@frame:https://x.kr/l?isbn={isbn13} => @plain:@anchor:href=\"(/v/[^\"]+)\"";
        assertEquals(framed, DetailRule.parse(framed).describe());

        // 제목 검색에 걸 수 있는가. 그 ISBN 으로 줄을 고르는 규칙만입니다.
        assertTrue(DetailRule.parse("@verify:@onclick:fnDetail").anchored());
        assertTrue(DetailRule.parse("@plain:@anchor:href=\"(/v/[^\"]+)\"").anchored());
        assertFalse(DetailRule.parse("@plain:href=\"(/v/[^\"]+)\"").anchored(),
                "닻 없는 @plain: 은 화면의 줄 전부를 그 책의 줄로 봅니다. ISBN 으로 찾은 결과에만 씁니다");

        for (String bad : List.of(
                "@verify:@anchor:@onclick:f",        // 닻은 목록의 ISBN 으로 이미 한 줄을 골랐습니다
                "@verify:@plain:@anchor:@onclick:f", "@verify:@plain:@plain:@onclick:f", "@verify:@verify:@onclick:f",
                "@verify:@json:GET https://x.kr/api?q={isbn13} => list => /d/{id}",
                "@plain:@verify:@onclick:f", "@plain:@plain:@onclick:f",
                "@plain:@frame:https://x.kr/l?isbn={isbn13} => @onclick:f",
                "@anchor:@plain:@onclick:f", "@anchor:@verify:@onclick:f",
                "@verify:", "@plain:")) {
            assertThrows(IllegalArgumentException.class, () -> DetailRule.parse(bad), bad);
        }
    }

    @Test
    @DisplayName("받아 본 후보 가운데 고르는 차례")
    void picksInIsbnOrderThenDocumentOrder() {
        String ours = "<p>ISBN " + OURS + "</p>";
        String other = "<p>ISBN " + OTHER + "</p>";
        var seen = List.of(new IsbnVerify.Seen<>("a", other), new IsbnVerify.Seen<>("b", ours));

        assertEquals("b", IsbnVerify.pick(seen, List.of(OURS)).payload());
        // 앞의 ISBN 이 그 도서관이 가진 판입니다. 앞선 후보에 뒤의 ISBN 이 있어도 가진 판의 후보가 먼저입니다.
        assertEquals("b", IsbnVerify.pick(seen, List.of(OURS, OTHER)).payload());
        assertEquals("a", IsbnVerify.pick(seen, List.of("9788936433598", OTHER)).payload());
        assertNull(IsbnVerify.pick(seen, List.of("9788936433598")).payload(), "첫 후보로 내려가지 않습니다");
        assertNull(IsbnVerify.pick(seen, List.of()).payload());
        // 받지 못한 후보는 「없다」로도 「있다」로도 치지 않습니다.
        var unread = new ArrayList<IsbnVerify.Seen<String>>();
        unread.add(new IsbnVerify.Seen<>("a", null));
        unread.add(new IsbnVerify.Seen<>("b", ours));
        assertEquals("b", IsbnVerify.pick(unread, List.of(OURS)).payload());

        // 같은 ISBN 이 적힌 후보가 여럿이면 큰글자책·점자책 표기가 없는 줄이 먼저입니다(@verify:@plain:).
        var mixed = List.of(new IsbnVerify.Seen<>("a", ours, true), new IsbnVerify.Seen<>("b", other, false),
                new IsbnVerify.Seen<>("c", ours, false));
        assertEquals("c", IsbnVerify.pick(mixed, List.of(OURS)).payload());
        // 전부 표기 있는 줄이면(큰글자판을 누른 경우) 앞선 후보이고, 표기 없는 줄에 그 ISBN 이 없으면 표기 있는
        // 줄이라도 그 책입니다.
        assertEquals("a", IsbnVerify.pick(List.of(new IsbnVerify.Seen<>("a", ours, true),
                new IsbnVerify.Seen<>("b", ours, true)), List.of(OURS)).payload());
        assertEquals("a", IsbnVerify.pick(List.of(new IsbnVerify.Seen<>("a", ours, true),
                new IsbnVerify.Seen<>("b", other, false)), List.of(OURS)).payload());
    }
}
