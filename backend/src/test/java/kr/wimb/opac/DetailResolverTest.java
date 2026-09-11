package kr.wimb.opac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 누를 때 검색 결과에서 상세 링크를 뽑는 부분.
 *
 * <p>여기서 틀리면 사용자는 남의 사이트나 엉뚱한 책으로 가거나, 닿지 않는 호스트를 두 번씩
 * 기다립니다. 어느 쪽도 화면에서는 「검색 결과로 갔다」와 구별되지 않습니다.
 */
class DetailResolverTest {

    private static final Pattern BOOK_LINK = Pattern.compile("href=[\"'](/book[^\"']*)[\"']");

    /** 주소마다 정해 둔 답을 주는 가짜 서버. 무엇을 받았는지 기록합니다. */
    private static final class FakeSite implements DetailResolver.Fetcher {
        final List<String> asked = new ArrayList<>();
        final Map<String, DetailResolver.Page> pages = new java.util.HashMap<>();
        boolean down = false;

        void page(String url, String body) {
            pages.put(url, new DetailResolver.Page(200, body, URI.create(url)));
        }

        @Override public DetailResolver.Page fetch(URI url) throws IOException {
            asked.add(url.toString());
            if (down) throw new IOException("connect timed out");
            DetailResolver.Page page = pages.get(url.toString());
            if (page == null) return new DetailResolver.Page(404, "", url);
            return page;
        }
    }

    private static DetailResolver resolver(FakeSite site, AtomicReference<Instant> now) {
        Clock moving = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        return new DetailResolver(site, moving);
    }

    private static DetailResolver resolver(FakeSite site) {
        return resolver(site, new AtomicReference<>(Instant.parse("2026-09-10T00:00:00Z")));
    }

    @Test
    @DisplayName("검색 결과의 상세 링크를 절대 주소로 만들어 돌려준다")
    void resolvesRelativeHrefAgainstThePage() {
        var site = new FakeSite();
        site.page("https://lib.example.kr/search?q=9788983711892",
                "<ul><li><a href='/book?bookkey=150671418&amp;site=main'>코스모스</a></li></ul>");

        var outcome = resolver(site).resolve("https://lib.example.kr/search?q=9788983711892", BOOK_LINK);

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        // &amp; 는 HTML 엔티티라 되돌려야 실제 주소가 됩니다.
        assertEquals("https://lib.example.kr/book?bookkey=150671418&site=main", outcome.detailUrl());
    }

    @Test
    @DisplayName("「?query」만 있는 상대 링크는 브라우저처럼 마지막 경로 조각을 지키며 푼다")
    void resolvesQueryOnlyHrefLikeABrowser() {
        // 자바의 URI.resolve 는 RFC 2396 이라 search00.do?a=1 에 ?b=2 를 풀면 /site/search/?b=2 가
        // 됩니다. 브라우저와 조사 스크립트(urljoin)는 search00.do?b=2 로 풉니다. 실제로 도봉의 상세
        // 링크가 ?manage_code=... 로 시작해서, 그대로 두면 확인한 주소와 다른 주소로 보냅니다.
        var site = new FakeSite();
        site.page("https://lib.example.kr/site/search/search00.do?search_txt=9788983711892",
                "<a href='?manage_code=MD&amp;reckey=105065008'>코스모스</a>");

        var outcome = resolver(site).resolve(
                "https://lib.example.kr/site/search/search00.do?search_txt=9788983711892",
                Pattern.compile("href=[\"'](\\?manage_code=[^\"']*)[\"']"));

        assertEquals(DetailResolver.Status.RESOLVED, outcome.status(), outcome.note());
        assertEquals("https://lib.example.kr/site/search/search00.do?manage_code=MD&reckey=105065008",
                outcome.detailUrl());
    }

    @Test
    @DisplayName("리다이렉트를 따라간 뒤의 주소를 기준으로 상대 링크를 푼다")
    void resolvesAgainstTheFinalUri() throws IOException {
        DetailResolver.Fetcher redirected = url -> new DetailResolver.Page(200,
                "<a href='detail.do?key=1'>코스모스</a>", URI.create("https://lib.example.kr/opac/list.do?q=1"));
        var outcome = new DetailResolver(redirected, Clock.systemUTC())
                .resolve("https://lib.example.kr/search?q=1", Pattern.compile("href='([^']*)'"));

        assertEquals("https://lib.example.kr/opac/detail.do?key=1", outcome.detailUrl());
    }

    @Test
    @DisplayName("찾은 답은 기억해 두고 같은 검색을 다시 받지 않는다")
    void remembersResolvedAnswers() {
        var site = new FakeSite();
        site.page("https://lib.example.kr/search?q=1", "<a href='/book/1'>코스모스</a>");
        var resolver = resolver(site);

        resolver.resolve("https://lib.example.kr/search?q=1", BOOK_LINK);
        var again = resolver.resolve("https://lib.example.kr/search?q=1", BOOK_LINK);

        assertTrue(again.fromCache());
        assertEquals("https://lib.example.kr/book/1", again.detailUrl());
        // robots.txt 한 번, 검색 한 번. 두 번째 호출은 아무것도 받지 않습니다.
        assertEquals(List.of("https://lib.example.kr/robots.txt", "https://lib.example.kr/search?q=1"),
                site.asked);
    }

    @Test
    @DisplayName("링크가 없던 답은 한 시간만 기억한다")
    void remembersMissesBriefly() {
        var site = new FakeSite();
        site.page("https://lib.example.kr/search?q=1", "<p>검색결과가 없습니다</p>");
        var now = new AtomicReference<>(Instant.parse("2026-09-10T00:00:00Z"));
        var resolver = resolver(site, now);

        assertEquals(DetailResolver.Status.NO_MATCH,
                resolver.resolve("https://lib.example.kr/search?q=1", BOOK_LINK).status());
        assertTrue(resolver.resolve("https://lib.example.kr/search?q=1", BOOK_LINK).fromCache(),
                "한 시간 안에는 다시 받지 않습니다");

        now.set(now.get().plus(Duration.ofHours(1).plusSeconds(1)));
        assertFalse(resolver.resolve("https://lib.example.kr/search?q=1", BOOK_LINK).fromCache(),
                "한 시간이 지나면 다시 받아 봅니다. 그 사이 자료가 들어왔을 수 있습니다");
    }

    @Test
    @DisplayName("페이지를 못 받은 것은 기억하지 않는다")
    void doesNotRememberFailures() {
        var site = new FakeSite();
        site.down = true;
        var resolver = resolver(site);

        assertEquals(DetailResolver.Status.FETCH_FAILED,
                resolver.resolve("https://lib.example.kr/search?q=1", BOOK_LINK).status());
        site.down = false;
        site.page("https://lib.example.kr/search?q=1", "<a href='/book/1'>코스모스</a>");

        var after = resolver.resolve("https://lib.example.kr/search?q=1", BOOK_LINK);
        assertEquals(DetailResolver.Status.RESOLVED, after.status(),
                "잠깐 흔들린 뒤에는 바로 다시 상세로 가야 합니다");
        assertFalse(after.fromCache());
    }

    @Test
    @DisplayName("200 이 아닌 답은 못 받은 것으로 센다")
    void non200IsAFailure() {
        var site = new FakeSite();   // 등록된 페이지가 없어 404 입니다.
        var outcome = resolver(site).resolve("https://lib.example.kr/search?q=1", BOOK_LINK);

        assertEquals(DetailResolver.Status.FETCH_FAILED, outcome.status());
        assertTrue(outcome.note().contains("404"), outcome.note());
    }

    @Test
    @DisplayName("다른 기관으로 가는 링크는 상세로 치지 않는다")
    void rejectsLinksToOtherOrganisations() {
        // 패턴이 배너나 외부 링크를 잡았을 때입니다. 남의 사이트로 보내지 않습니다.
        var site = new FakeSite();
        site.page("https://lib.example.kr/search?q=1",
                "<a href='https://ads.example.com/book/1'>코스모스</a>");

        var outcome = resolver(site).resolve("https://lib.example.kr/search?q=1", BOOK_LINK);

        assertEquals(DetailResolver.Status.NO_MATCH, outcome.status());
        assertNull(outcome.detailUrl());
    }

    @Test
    @DisplayName("같은 기관의 다른 서브도메인은 받아들인다")
    void acceptsSiblingSubdomains() {
        var site = new FakeSite();
        site.page("https://lib.example.go.kr/search?q=1",
                "<a href='https://opac.example.go.kr/book/1'>코스모스</a>");

        var outcome = resolver(site).resolve("https://lib.example.go.kr/search?q=1",
                Pattern.compile("href='([^']*)'"));

        assertEquals("https://opac.example.go.kr/book/1", outcome.detailUrl(), outcome.note());
    }

    @Test
    @DisplayName("검색 결과 자기 주소나 자바스크립트 링크는 상세가 아니다")
    void rejectsNonNavigableLinks() {
        var site = new FakeSite();
        site.page("https://lib.example.kr/book?q=1", "<a href='/book?q=1'>코스모스</a>");
        assertEquals(DetailResolver.Status.NO_MATCH,
                resolver(site).resolve("https://lib.example.kr/book?q=1", BOOK_LINK).status());

        site.page("https://lib.example.kr/search?q=2", "<a href='javascript:open(1)'>코스모스</a>");
        assertEquals(DetailResolver.Status.NO_MATCH, resolver(site)
                .resolve("https://lib.example.kr/search?q=2", Pattern.compile("href='([^']*)'")).status());
    }

    @Test
    @DisplayName("robots.txt 가 막은 경로는 받지 않는다")
    void honoursRobots() {
        var site = new FakeSite();
        site.page("https://lib.example.kr/robots.txt", "User-agent: *\nDisallow: /search\n");
        site.page("https://lib.example.kr/search?q=1", "<a href='/book/1'>코스모스</a>");

        var outcome = resolver(site).resolve("https://lib.example.kr/search?q=1", BOOK_LINK);

        assertEquals(DetailResolver.Status.BLOCKED_BY_ROBOTS, outcome.status());
        assertEquals(List.of("https://lib.example.kr/robots.txt"), site.asked, "검색 페이지는 받지 않습니다");
    }

    @Test
    @DisplayName("robots.txt 는 우리 UA 이름으로 적은 규칙도 따른다")
    void robotsForOurAgent() {
        assertEquals(List.of("/search"), DetailResolver.parseDisallows(
                "User-agent: Googlebot\nDisallow: /\n\nUser-agent: wimb-opac-check\nDisallow: /search\n"));
        assertEquals(List.of(), DetailResolver.parseDisallows("<html>404</html>"),
                "robots.txt 자리에 HTML 이 오면 제한이 없는 것으로 봅니다");
    }

    @Test
    @DisplayName("한 호스트에 둘이 나가 있으면 기다리지 않고 검색 결과로 보낸다")
    void doesNotQueueBehindABusyHost() throws Exception {
        var gate = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(2);
        DetailResolver.Fetcher slow = url -> {
            if (url.getPath().equals("/robots.txt")) return new DetailResolver.Page(404, "", url);
            started.countDown();
            gate.await();
            return new DetailResolver.Page(200, "<a href='/book/1'>x</a>", url);
        };
        var resolver = new DetailResolver(slow, Clock.systemUTC());
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 2; i++) {
            int n = i;
            var t = new Thread(() -> resolver.resolve("https://lib.example.kr/search?q=" + n, BOOK_LINK));
            t.start();
            threads.add(t);
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));

        var third = resolver.resolve("https://lib.example.kr/search?q=9", BOOK_LINK);

        assertEquals(DetailResolver.Status.BUSY, third.status());
        gate.countDown();
        for (Thread t : threads) t.join();
    }

    @Test
    @DisplayName("EUC-KR 로 온 페이지도 제목이 깨지지 않게 읽는다")
    void decodesEucKr() {
        byte[] euc = "<a href='/book/1'>코스모스</a>".getBytes(Charset.forName("EUC-KR"));
        assertTrue(DetailResolver.decode(euc, "text/html; charset=euc-kr").contains("코스모스"));
        // 선언이 없어도 UTF-8 로 깨지면 EUC-KR 로 다시 읽습니다.
        assertTrue(DetailResolver.decode(euc, "text/html").contains("코스모스"));
        // 선언이 틀린 곳도 있습니다. UTF-8 본문에 euc-kr 이라고 적혀 있으면 깨진 채로 두지 않습니다.
        byte[] utf8 = "<meta charset='euc-kr'><a href='/book/1'>코스모스</a>".getBytes(Charset.forName("UTF-8"));
        assertTrue(DetailResolver.decode(utf8, "text/html").contains("코스모스"));
    }

    @Test
    @DisplayName("서버의 User-Agent 는 조사 스크립트의 것과 같다")
    void userAgentMatchesTheScript() throws IOException {
        // 조사 스크립트는 서버와 같은 방식으로 받아 패턴을 확인합니다. UA 가 다르면 다른 페이지를
        // 받는 OPAC 이 있어, 로컬에서 통과한 패턴이 서버에서는 잡히지 않을 수 있습니다.
        Path script = Path.of("..", "scripts", "opac-discover.py");
        assumeTrue(Files.exists(script), "저장소 안에서 돌릴 때만 봅니다");
        String ua = Files.readAllLines(script).stream()
                .filter(l -> l.startsWith("UA = \""))
                .map(l -> l.substring("UA = \"".length(), l.lastIndexOf('"')))
                .findFirst().orElseThrow();
        assertEquals(ua, DetailResolver.USER_AGENT);
    }

    @Test
    @DisplayName("기억해 둔 답이 상한을 넘으면 오래된 것부터 4분의 1만 덜어 낸다")
    void evictsAQuarterNotEverything() {
        var site = new FakeSite();
        var now = new AtomicReference<>(Instant.parse("2026-09-10T00:00:00Z"));
        var resolver = resolver(site, now);
        for (int i = 0; i <= DetailResolver.MAX_ENTRIES; i++) {
            String url = "https://lib.example.kr/search?q=" + i;
            site.page(url, "<a href='/book/" + i + "'>x</a>");
            now.set(now.get().plusSeconds(1));
            resolver.resolve(url, BOOK_LINK);
        }
        int expected = DetailResolver.MAX_ENTRIES + 1 - (DetailResolver.MAX_ENTRIES + 1) / 4;
        assertEquals(expected, resolver.cacheSize());
        assertFalse(resolver.resolve("https://lib.example.kr/search?q=0", BOOK_LINK).fromCache(),
                "가장 오래된 것은 나갔습니다");
        assertTrue(resolver.resolve("https://lib.example.kr/search?q=" + DetailResolver.MAX_ENTRIES, BOOK_LINK)
                .fromCache(), "가장 새것은 남았습니다");
    }
}
