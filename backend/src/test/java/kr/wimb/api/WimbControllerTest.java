package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.data4library.RegionCode;
import kr.wimb.holdings.CachingHoldingsClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 도서관 마스터를 시도별로 받는 부분입니다.
 *
 * <p>여기서 부분 실패를 전체 실패로 만들면, 목록에 없는 도서관이 사용자에게
 * <b>"그런 도서관이 없다"</b>로 보입니다. 조용히 틀린 답을 주는 자리입니다.
 */
class WimbControllerTest {

    private static String libsOf(String... codes) {
        return libsAt("서울특별시 강남구 어디로 1", codes);
    }

    private static String libsAt(String address, String... codes) {
        var sb = new StringBuilder("<response><libs>");
        for (String code : codes) {
            sb.append("<lib><libCode>").append(code).append("</libCode>")
              .append("<libName><![CDATA[도서관").append(code).append("]]></libName>")
              .append("<address><![CDATA[").append(address).append("]]></address></lib>");
        }
        return sb.append("</libs></response>").toString();
    }

    /** 특정 지역에서만 실패하는 가짜 정보나루입니다. */
    private static final class RegionAware implements Data4LibraryClient.Transport {
        /** region 없이(전국) 부른 호출을 기록할 때 쓰는 표시입니다. */
        static final String ALL = "ALL";
        final List<String> asked = new ArrayList<>();
        java.util.function.Predicate<String> failsFor = region -> false;
        /** 시도별로 다른 주소를 돌려주고 싶을 때. null 이면 서울 주소를 씁니다. */
        java.util.function.Function<String, String> addressFor = region -> null;

        @Override public String get(URI uri) {
            String url = uri.toString();
            if (!url.contains("region=")) {
                // 전국을 한 번에 받는 길입니다. 이 길로는 각 도서관이 어느 시도에 속하는지
                // 알 수 없으므로 컨트롤러가 더는 쓰지 않습니다. 부르면 기록만 남깁니다.
                asked.add(ALL);
                return libsOf(java.util.Arrays.stream(RegionCode.values())
                        .map(r -> r.code() + "0001").toArray(String[]::new));
            }
            String region = url.replaceAll(".*[?&]region=([0-9]+).*", "$1");
            asked.add(region);
            if (failsFor.test(region)) throw new IllegalStateException("이 지역만 실패");
            // 지역마다 도서관 하나씩 돌려줍니다.
            String address = addressFor.apply(region);
            return address == null ? libsOf(region + "0001") : libsAt(address, region + "0001");
        }
    }

    /**
     * {@code bookExist} 만 답하는 가짜 정보나루. 어느 판본을 물었는지 기록합니다.
     */
    private static final class LoanTransport implements Data4LibraryClient.Transport {
        final List<String> asked = new ArrayList<>();
        /** 이 판본만 소장하고 있다고 답합니다. */
        String heldIsbn = null;
        /** 이 판본은 조회 자체가 실패합니다. */
        String failsFor = null;
        boolean loanAvailable = true;

        @Override public String get(URI uri) {
            String url = uri.toString();
            if (!url.contains("bookExist")) return libsOf("110001");
            String isbn = url.replaceAll(".*[?&]isbn13=([0-9]+).*", "$1");
            asked.add(isbn);
            if (isbn.equals(failsFor)) throw new IllegalStateException("이 판본만 조회 실패");
            boolean has = isbn.equals(heldIsbn);
            return "<response><hasBook>" + (has ? "Y" : "N") + "</hasBook>"
                    + "<loanAvailable>" + (has && loanAvailable ? "Y" : "N") + "</loanAvailable>"
                    + "</response>";
        }
    }

    /**
     * 호출 제한은 인터셉터가 보므로 컨트롤러 테스트에서는 걸릴 일이 없습니다. 그래도
     * 생성자가 받으므로 넉넉한 값을 넘깁니다. 한도가 여기 테스트를 흔들면 안 됩니다.
     */
    private static RateLimit unlimited() {
        return new RateLimit(1_000_000, 1_000_000, 0, 100, Clock.systemUTC());
    }

    /** 상세 해석이 필요 없는 테스트용. 실제 HTTP 로 나가면 안 됩니다. */
    private static kr.wimb.opac.DetailResolver noResolver() {
        return new kr.wimb.opac.DetailResolver(url -> {
            throw new java.io.IOException("테스트에서는 OPAC 을 부르지 않습니다");
        }, Clock.systemUTC());
    }

    private static WimbController controllerWith(Data4LibraryClient.Transport transport) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 100_000),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        var search = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
        return new WimbController(client, search, new MultiCheckService(search), budget,
                kr.wimb.opac.OpacTemplates.load(), noResolver(),
                new CachingHoldingsClient((isbn, region) -> List.of(), Duration.ofHours(6), 100,
                        Clock.systemUTC()),
                unlimited(), new BrowseService(client),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
    }

    private static WimbController controller(RegionAware transport) {
        return controller(transport, new AtomicReference<>(Instant.parse("2026-09-06T00:00:00Z")));
    }

    /** 소장 조회가 어느 지역을 물었는지 보려면 가짜 소장 클라이언트를 끼워야 합니다. */
    private static WimbController controller(RegionAware transport,
                                             HoldingsLookup.HoldingsClient holdings) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 100_000),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        var search = new BookSearchService(client, new HoldingsLookup(
                holdings, HoldingsLookup.RegionModeStore.documented()));
        return new WimbController(client, search, new MultiCheckService(search), budget,
                kr.wimb.opac.OpacTemplates.load(), noResolver(),
                new CachingHoldingsClient((isbn, region) -> List.of(), Duration.ofHours(6), 100,
                        Clock.systemUTC()),
                unlimited(), new BrowseService(client),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
    }

    /** 재시도 시각을 넘겨 보려면 시계를 움직일 수 있어야 합니다. */
    private static WimbController controller(RegionAware transport, AtomicReference<Instant> now) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 100_000),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        var search = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
        Clock moving = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        return new WimbController(client, search, new MultiCheckService(search), budget,
                kr.wimb.opac.OpacTemplates.load(), noResolver(), new CachingHoldingsClient((isbn, region) -> List.of(), Duration.ofHours(6), 100,
                        Clock.systemUTC()), unlimited(), new BrowseService(client), moving);
    }

    // ── 도서관 링크 (/api/go) ──────────────────────────────────────────────

    /** 주소마다 정해 둔 HTML 을 주는 가짜 OPAC. 무엇을 받았는지 기록합니다. */
    private static final class FakeOpac implements kr.wimb.opac.DetailResolver.Fetcher {
        final List<String> asked = new ArrayList<>();
        final Map<String, String> pages = new java.util.HashMap<>();
        boolean down = false;

        @Override public kr.wimb.opac.DetailResolver.Page fetch(URI url) throws java.io.IOException {
            asked.add(url.toString());
            if (url.getPath().equals("/robots.txt")) return new kr.wimb.opac.DetailResolver.Page(404, "", url);
            if (down) throw new java.io.IOException("connect timed out");
            String body = pages.getOrDefault(url.toString(), "<p>검색결과가 없습니다</p>");
            return new kr.wimb.opac.DetailResolver.Page(200, body, url);
        }
    }

    private static final List<String> EXAMPLE_RULE = List.of(
            "110001,ISBN_SEARCH,UTF-8,https://lib.example.kr/search?q={isbn13}");
    private static final List<String> EXAMPLE_PATTERN = List.of(
            "lib.example.kr,href=\"(/book/[^\"]+)\"");

    private static WimbController linkController(FakeOpac opac, List<String> patterns) {
        var transport = new RegionAware();
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 100_000),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        var search = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
        var controller = new WimbController(client, search, new MultiCheckService(search), budget,
                kr.wimb.opac.OpacTemplates.of(EXAMPLE_RULE, patterns),
                new kr.wimb.opac.DetailResolver(opac, Clock.systemUTC()),
                new CachingHoldingsClient((isbn, region) -> List.of(), Duration.ofHours(6), 100,
                        Clock.systemUTC()),
                unlimited(), new BrowseService(client),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
        controller.libraries();   // 도서관 마스터를 채워야 /api/go 가 그 도서관을 압니다.
        return controller;
    }

    private static String sentTo(WimbController controller, String... isbns) {
        return controller.go("110001", List.of(isbns), "코스모스").getHeaders().getLocation().toString();
    }

    @Test
    @DisplayName("상세 패턴이 있으면 검색 결과를 받아 그 책의 상세로 보낸다")
    void goResolvesDetailPage() {
        var opac = new FakeOpac();
        opac.pages.put("https://lib.example.kr/search?q=9788983711892", "<a href=\"/book/150671418\">코스모스</a>");

        assertEquals("https://lib.example.kr/book/150671418",
                sentTo(linkController(opac, EXAMPLE_PATTERN), "9788983711892"));
        assertEquals(kr.wimb.opac.OpacLink.Kind.DETAIL_LOOKUP,
                kr.wimb.opac.OpacTemplates.of(EXAMPLE_RULE, EXAMPLE_PATTERN).kindFor("110001"),
                "화면에도 상세 조회 단계라고 미리 말해야 합니다");
    }

    @Test
    @DisplayName("상세 링크를 못 찾으면 검색 결과로 내려간다")
    void goFallsBackToSearchResults() {
        var opac = new FakeOpac();   // 아무 페이지도 등록하지 않아 링크가 없습니다.

        assertEquals("https://lib.example.kr/search?q=9788983711892",
                sentTo(linkController(opac, EXAMPLE_PATTERN), "9788983711892"));
    }

    @Test
    @DisplayName("첫 판에 링크가 없으면 다음 판으로 한 번 더 찾는다")
    void goTriesTheNextEditionAfterAMiss() {
        // 정보나루는 특별판을 가졌다고 했는데 OPAC 은 초판으로 등록해 둔 경우입니다.
        var opac = new FakeOpac();
        opac.pages.put("https://lib.example.kr/search?q=B", "<a href=\"/book/2\">코스모스</a>");

        assertEquals("https://lib.example.kr/book/2", sentTo(linkController(opac, EXAMPLE_PATTERN), "A", "B", "C"));
        assertEquals(2, opac.asked.stream().filter(u -> u.contains("/search")).count(),
                "셋째 판까지 두드리지 않습니다. 한 번에 몇 초씩입니다: " + opac.asked);
    }

    @Test
    @DisplayName("페이지를 아예 못 받았으면 다른 판으로 또 두드리지 않는다")
    void goDoesNotRetryAnUnreachableHost() {
        var opac = new FakeOpac();
        opac.down = true;

        assertEquals("https://lib.example.kr/search?q=A", sentTo(linkController(opac, EXAMPLE_PATTERN), "A", "B"));
        assertEquals(1, opac.asked.stream().filter(u -> u.contains("/search")).count(),
                "닿지 않는 호스트를 두 번 기다리게 하면 안 됩니다: " + opac.asked);
    }

    @Test
    @DisplayName("패턴이 없는 OPAC 은 아무것도 받지 않고 검색 결과로 보낸다")
    void goWithoutPatternDoesNotFetch() {
        var opac = new FakeOpac();

        assertEquals("https://lib.example.kr/search?q=A", sentTo(linkController(opac, List.of()), "A"));
        assertTrue(opac.asked.isEmpty(), "남의 서버를 이유 없이 두드리지 않습니다");
    }

    @Test
    @DisplayName("시도별로 받아 각 도서관의 소속을 정보나루 기준으로 기억한다")
    void loadsPerRegionSoEveryLibraryHasASourceRegion() {
        // 전국을 한 번에 받으면 호출은 줄지만 각 도서관이 정보나루 기준으로 어느 시도인지
        // 알 수 없습니다. 소장 조회의 region 은 그 소속으로 걸러지므로, 주소를 우리가
        // 읽어 낸 값과 어긋나는 도서관은 물어보지도 않은 채 「없음」이 됩니다.
        var transport = new RegionAware();
        var libraries = controller(transport).libraries();

        assertEquals(RegionCode.values().length, libraries.size());
        assertFalse(transport.asked.contains(RegionAware.ALL),
                "전국 한 번에 받기로는 소속을 알 수 없습니다: " + transport.asked);
        assertEquals(RegionCode.values().length, transport.asked.size(),
                "시도마다 한 번씩만 불러야 합니다: " + transport.asked);
    }

    @Test
    @DisplayName("주소에서 시도를 못 읽어도 정보나루가 알려 준 소속으로 물어본다")
    void asksWithTheRegionData4LibraryAssigned() {
        // 「고양시립백석도서관」은 주소가 「고양시 …」로 시작해 시도를 뽑지 못했고, 그래서
        // 확인 불가로만 답할 수 있었습니다. 시도별 목록에 들어온 시도가 곧 소속이므로
        // 이제는 경기도로 물어볼 수 있고, 지역 트리에도 들어갑니다.
        var transport = new RegionAware();
        transport.addressFor = region -> region.equals("31") ? "고양시 일산동구 중앙로 1" : null;
        var askedRegions = new ArrayList<String>();
        var controller = controller(transport, (isbn, region) -> {
            askedRegions.add(region);
            return List.of("310001");
        });

        var goyang = controller.libraries().stream()
                .filter(l -> l.libCode().equals("310001")).findFirst().orElseThrow();
        assertEquals("경기도", goyang.sido(), "소속을 알았으면 지역 트리에도 들어가야 합니다");
        assertEquals("고양시", goyang.sigungu());

        var holdings = controller.holdings(new WimbController.HoldingsRequest(
                List.of("9788983711892"), List.of("310001")));

        assertEquals(List.of("31"), askedRegions, "정보나루가 알려 준 소속으로 물어야 합니다");
        assertTrue(holdings.complete(), "물어볼 수 있었으므로 빠짐없이 확인한 것입니다");
        assertEquals(List.of("310001"), holdings.libCodes());
        assertEquals(Map.of("310001", List.of("9788983711892")), holdings.heldIsbns(),
                "화면이 링크에 넣을 수 있게 어느 판을 가졌는지도 함께 나가야 합니다");
    }

    @Test
    @DisplayName("주소는 서울인데 정보나루가 경기로 분류한 도서관은 경기로 물어본다")
    void sourceRegionWinsOverTheAddress() {
        // 둘이 어긋나면 소장 조회가 걸러지는 기준은 정보나루 쪽입니다. 주소로 물으면
        // 그 도서관은 응답에 없고, 화면에는 「없음」으로 나갑니다.
        var transport = new RegionAware();
        transport.addressFor = region -> region.equals("31") ? "서울특별시 강남구 어디로 1" : null;
        var askedRegions = new ArrayList<String>();
        var controller = controller(transport, (isbn, region) -> {
            askedRegions.add(region);
            return List.of();
        });
        controller.libraries();

        controller.holdings(new WimbController.HoldingsRequest(
                List.of("9788983711892"), List.of("310001")));

        assertEquals(List.of("31"), askedRegions);
    }

    @Test
    @DisplayName("시도 하나가 실패해도 나머지 도서관은 보여 준다")
    void oneFailingRegionDoesNotSinkTheList() {
        var transport = new RegionAware();
        transport.failsFor = region -> region.equals("11");   // 서울만 실패

        var libraries = controller(transport).libraries();

        assertEquals(16, libraries.size(), "17개 시도 가운데 16곳은 받았어야 합니다");
        assertTrue(libraries.stream().noneMatch(l -> l.libCode().startsWith("11")));
    }

    @Test
    @DisplayName("실패한 시도를 성공으로 기억하지 않는다")
    void failedRegionIsRetriedLater() {
        // 기억해 버리면 그 지역 도서관이 영영 목록에 나타나지 않고,
        // 사용자에게는 "그런 도서관이 없다"로 보입니다.
        var transport = new RegionAware();
        var seoulBroken = new AtomicReference<>(true);
        transport.failsFor = region -> region.equals("11") && seoulBroken.get();

        var now = new AtomicReference<>(Instant.parse("2026-09-06T00:00:00Z"));
        var controller = controller(transport, now);
        assertEquals(16, controller.libraries().size());

        // 서울이 돌아왔고, 재시도 간격도 지났습니다.
        seoulBroken.set(false);
        now.set(Instant.parse("2026-09-06T00:06:00Z"));
        long seoulAsked = transport.asked.stream().filter("11"::equals).count();

        var again = controller.libraries();
        assertEquals(17, again.size(), "돌아온 지역을 다시 받아야 합니다");
        assertTrue(transport.asked.stream().filter("11"::equals).count() > seoulAsked,
                "서울을 다시 물어봤어야 합니다");
    }

    @Test
    @DisplayName("계속 실패하는 지역을 매 요청마다 두드리지 않는다")
    void failingRegionIsNotHammered() {
        // 남의 서버에 대한 예의입니다. 5분 안에는 다시 부르지 않습니다.
        var transport = new RegionAware();
        transport.failsFor = region -> region.equals("11");
        var controller = controller(transport);

        controller.libraries();
        long first = transport.asked.stream().filter("11"::equals).count();
        controller.libraries();
        controller.libraries();

        assertEquals(first, transport.asked.stream().filter("11"::equals).count());
    }

    @Test
    @DisplayName("성공한 시도는 다시 부르지 않는다")
    void successfulRegionsAreNotRefetched() {
        var transport = new RegionAware();
        var controller = controller(transport);

        controller.libraries();
        int firstRound = transport.asked.size();
        controller.libraries();

        assertEquals(firstRound, transport.asked.size(), "예산을 두 번 쓰면 안 됩니다");
    }

    @Test
    @DisplayName("한 곳도 못 받으면 빈 목록 대신 503 을 낸다")
    void totalFailureIsNotAnEmptyList() {
        // 빈 목록을 정상인 척 돌려주면 화면이 "도서관이 하나도 없다"로 그립니다.
        var transport = new RegionAware();
        transport.failsFor = region -> true;

        var thrown = assertThrows(ResponseStatusException.class, () -> controller(transport).libraries());
        assertEquals(503, thrown.getStatusCode().value());
    }

    @Test
    @DisplayName("도서관이 다른 판본을 가지고 있어도 찾아낸다")
    void loanCheckTriesEveryEdition() {
        // 소장 조회는 저작에 묶인 판본 전체를 봅니다. 대출 조회만 대표 판본 하나를 물으면,
        // 도서관이 2판을 가지고 있을 때 「이 도서관에는 없다」고 답하게 됩니다.
        // 소장한다고 표시해 놓고 누르면 없다고 하는 셈이라 소장 정보를 믿지 못하게 됩니다.
        var transport = new LoanTransport();
        transport.heldIsbn = "9791158510015";   // 2판만 소장

        var loan = controllerWith(transport)
                .loan("110001", List.of("9788983711892", "9791158510015"));

        assertTrue(loan.hasBook(), "묶인 판본을 다 물었으면 찾았어야 합니다: " + transport.asked);
        assertTrue(loan.loanAvailable());
        assertEquals(List.of("9788983711892", "9791158510015"), transport.asked);
    }

    @Test
    @DisplayName("찾으면 나머지 판본은 물어보지 않는다")
    void loanCheckStopsAtTheFirstHit() {
        // bookExist 는 (도서관 × ISBN)이라 부르는 만큼 예산이 깎입니다.
        var transport = new LoanTransport();
        transport.heldIsbn = "9788983711892";   // 첫 판본을 소장

        controllerWith(transport).loan("110001", List.of("9788983711892", "9791158510015"));

        assertEquals(List.of("9788983711892"), transport.asked);
    }

    @Test
    @DisplayName("한 판본이라도 못 물어봤으면 없다고 답하지 않는다")
    void loanCheckDoesNotClaimAbsenceAfterAFailure() {
        // 못 물어본 것을 「없다」로 답하면, 실제로 있는 책을 없다고 말하게 됩니다.
        // 소장 여부에서 지키는 구분과 같습니다. 503 으로 답해 화면이 확인 불가로 그립니다.
        var transport = new LoanTransport();
        transport.failsFor = "9791158510015";

        var controller = controllerWith(transport);
        var thrown = assertThrows(ResponseStatusException.class,
                () -> controller.loan("110001", List.of("9788983711892", "9791158510015")));

        assertEquals(503, thrown.getStatusCode().value());
    }

    @Test
    @DisplayName("전부 물어봤는데 없으면 그때는 없다고 답한다")
    void loanCheckReportsAbsenceOnlyAfterCheckingEverything() {
        var transport = new LoanTransport();   // 어느 판본도 소장하지 않음

        var loan = controllerWith(transport)
                .loan("110001", List.of("9788983711892", "9791158510015"));

        assertFalse(loan.hasBook());
        assertEquals(2, transport.asked.size(), "전부 물어봤어야 합니다");
    }

    @Test
    @DisplayName("대출 상태의 기준 날짜는 어제다")
    void loanStatusIsDatedYesterday() {
        // 정보나루가 주는 값은 조회일 기준 전날의 것입니다(매뉴얼 11절).
        // 화면이 이 날짜를 그대로 보여 주어야 사용자가 언제 기준인지 알고 판단합니다.
        var transport = new LoanTransport();
        transport.heldIsbn = "9788983711892";

        var loan = controllerWith(transport).loan("110001", List.of("9788983711892"));

        assertEquals(java.time.LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString(),
                loan.asOf());
    }
}
