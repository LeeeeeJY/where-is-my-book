package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.CachingHoldingsClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.data4library.RegionCode;
import kr.wimb.ingest.DailyCallLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>하루 한도를 넘긴 날 화면까지 무엇이 나가는지</b>를 주소째로 봅니다.
 *
 * <p>예전에는 우리 쪽에서 먼저 멈추는 선이 둘 있었습니다(하루 예산 25,000건과 그 80%).
 * 2026-11-12 에 둘 다 없앴고, 그 뒤로 한도에 닿았다는 것을 아는 통로는 정보나루가 주는
 * {@code outOflimit} 하나입니다. 그래서 그 오류가 왔을 때의 동작을 여기서 고정합니다.
 *
 * <ul>
 *   <li>검색은 500 이 아니라 503 이고, 방문자용 문구({@code reason})와 운영자용 내용
 *       ({@code detail})이 갈려 나갑니다. 화면은 {@code code} 를 보고 한도 안내 페이지로
 *       넘어갑니다.</li>
 *   <li>소장 조회는 「미소장」이 아니라 {@code unreadable}(확인 불가)입니다.</li>
 *   <li>그 뒤 5분 동안은 정보나루를 부르지 않습니다.</li>
 *   <li>우리가 센 건수가 한도 숫자를 넘겨도 도서관 목록은 받습니다.</li>
 * </ul>
 *
 * <p>컨트롤러와 {@link ApiErrorAdvice} 를 실제 클라이언트·실제 소장 캐시와 함께 엮어 봅니다.
 * 가짜는 정보나루의 응답뿐입니다. 오류 문장은 2026-09-08 에 실제로 받은 그대로입니다.
 */
class DailyLimitHandlingTest {

    private static final String OUT_OF_LIMIT_MESSAGE =
            "1일 500건 이상 요청 시 IP 등록이 필요합니다. 등록 된 IP를 확인하시기 바랍니다.";

    private static final String OUT_OF_LIMIT_JSON =
            "{\"response\":{\"errCode\":\"outOflimit\",\"error\":\"" + OUT_OF_LIMIT_MESSAGE + "\"}}";

    private static final String OUT_OF_LIMIT_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><response><errCode>"
                    + "            outOflimit</errCode><error>" + OUT_OF_LIMIT_MESSAGE
                    + "</error></response>";

    private static final String ONE_BOOK = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response><docs><doc>
          <bookname><![CDATA[코스모스]]></bookname>
          <authors><![CDATA[칼 세이건 지음 ; 홍승수 옮김]]></authors>
          <publisher><![CDATA[사이언스북스]]></publisher>
          <publication_year>2006</publication_year>
          <isbn13>9788983711892</isbn13>
          <loan_count>1,234</loan_count>
        </doc></docs></response>
        """;

    /** 방문자가 읽을 자리에 나오면 안 되는 말. {@code ApiErrorAdviceTest} 와 같은 목록입니다. */
    private static final List<String> OPERATOR_WORDS =
            List.of("인증키", "IP", "errCode", "캐시", "authKey", "outOflimit", "정보나루");

    /** 한도를 넘기기 전에는 정상으로 답하고, {@link #overLimit} 을 켜면 전부 한도 오류입니다. */
    private static final class Data4Library implements Data4LibraryClient.Transport {
        volatile boolean overLimit = false;
        final AtomicInteger sent = new AtomicInteger();

        @Override public String get(URI uri) {
            sent.incrementAndGet();
            String url = uri.toString();
            if (overLimit) {
                // 오류 형식이 엔드포인트마다 다릅니다. 실제로 libSrch 는 XML, srchBooks 는 JSON
                // 으로 왔습니다.
                return url.contains("/srchBooks") ? OUT_OF_LIMIT_JSON : OUT_OF_LIMIT_XML;
            }
            if (url.contains("/srchBooks")) return ONE_BOOK;
            if (url.contains("/libSrchByBook")) {
                return "<response><numFound>1</numFound><libs><lib><libCode>110001</libCode>"
                        + "<libName>도서관110001</libName></lib></libs></response>";
            }
            // 도서관 마스터. 시도마다 한 곳씩 줍니다.
            String region = url.replaceAll(".*[?&]region=([0-9]+).*", "$1");
            return "<response><libs><lib><libCode>" + region + "0001</libCode>"
                    + "<libName><![CDATA[도서관" + region + "]]></libName>"
                    + "<address><![CDATA[서울특별시 강남구 어디로 1]]></address></lib></libs></response>";
        }
    }

    private final Data4Library data4Library = new Data4Library();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-12T03:00:00Z"));
    private DailyCallLedger ledger;
    private WimbController controller;
    private MockMvc mvc;

    @BeforeEach
    void wire() {
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        // 한도 숫자를 일부러 작게 둡니다. 우리가 센 건수가 이 숫자를 넘겨도 아무것도 막히지
        // 않아야 합니다. 막는 것은 정보나루의 답뿐입니다.
        ledger = new DailyCallLedger(Map.of(Data4LibraryClient.SOURCE_CODE, 10), clock);
        var client = new Data4LibraryClient(data4Library, "테스트키", ledger, clock);
        var cache = new CachingHoldingsClient(client.asHoldingsClient(),
                Duration.ofHours(6), 100, clock);
        var search = new BookSearchService(client,
                new HoldingsLookup(cache, HoldingsLookup.RegionModeStore.documented()));
        controller = new WimbController(client, search, new MultiCheckService(search), ledger,
                kr.wimb.opac.OpacTemplates.load(),
                new kr.wimb.opac.DetailResolver(url -> {
                    throw new java.io.IOException("테스트에서는 OPAC 을 부르지 않습니다");
                }, clock),
                cache, new RateLimit(1_000_000, 1_000_000, 0, 100, clock),
                new kr.wimb.shelf.ShelfStore(java.nio.file.Path.of("build/tmp/no-shelf")), true, clock);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorAdvice())
                .build();
    }

    @Test
    @DisplayName("우리가 센 건수가 한도 숫자를 넘겨도 도서관 목록을 받는다")
    void catalogLoadsEvenAfterOurCountPassesTheLimit() throws Exception {
        // 80% 선이 있던 때에는 도서관 목록 받기가 배경 등급이라, 그 선을 넘긴 날 서버가 새로
        // 뜨면 목록을 받지 못했습니다. 목록이 없으면 화면은 아무것도 고를 수 없습니다.
        for (int i = 0; i < 9; i++) ledger.record(Data4LibraryClient.SOURCE_CODE);   // 한도 10 의 90%

        mvc.perform(get("/api/libraries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(RegionCode.values().length)));

        assertEquals(9 + RegionCode.values().length, ledger.used(Data4LibraryClient.SOURCE_CODE));
        assertEquals(0, ledger.remaining(Data4LibraryClient.SOURCE_CODE));

        // 한도 숫자를 한참 넘긴 뒤의 검색도 그대로 나갑니다.
        mvc.perform(get("/api/search").param("q", "코스모스"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.works[0].title").value("코스모스"));
    }

    @Test
    @DisplayName("검색이 한도에 걸리면 500 이 아니라 503 이고, 방문자용 이유와 운영자용 내용이 갈려 나간다")
    void searchAnswers503WithVisitorReasonAndOperatorDetail() throws Exception {
        data4Library.overLimit = true;

        var result = mvc.perform(get("/api/search").param("q", "코스모스"))
                .andExpect(status().isServiceUnavailable())
                // 화면이 이 코드를 보고 한도 안내 페이지로 넘어갑니다.
                .andExpect(jsonPath("$.code").value("outOflimit"))
                .andExpect(jsonPath("$.reason", containsString("내일")))
                .andExpect(jsonPath("$.reason", containsString("책이 없다는 뜻이 아니")))
                // 운영자가 볼 것은 detail 에만 있습니다. 정보나루의 원문과 무엇을 봐야 하는지입니다.
                .andExpect(jsonPath("$.detail", containsString(OUT_OF_LIMIT_MESSAGE)))
                .andExpect(jsonPath("$.detail", containsString("/api/diagnose")))
                .andExpect(jsonPath("$.reason", not(containsString(OUT_OF_LIMIT_MESSAGE))))
                .andReturn();

        String reason = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$.reason");
        for (String word : OPERATOR_WORDS) {
            assertFalse(reason.contains(word),
                    "방문자 문구에 운영자용 낱말 「" + word + "」가 들어 있습니다: " + reason);
        }
        // 인증키는 어느 칸에도 실리면 안 됩니다.
        assertFalse(result.getResponse().getContentAsString().contains("테스트키"));
    }

    @Test
    @DisplayName("소장 조회가 한도에 걸리면 미소장이 아니라 확인 불가로 나간다")
    void holdingsAnswerUnreadableNotAbsent() throws Exception {
        // 도서관 목록은 한도를 넘기기 전에 받아 둔 상태입니다.
        mvc.perform(get("/api/libraries")).andExpect(status().isOk());
        data4Library.overLimit = true;

        mvc.perform(post("/api/holdings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn13List\":[\"9788983711892\"],\"libs\":[\"110001\"]}"))
                // 소장 조회는 200 으로 답하되 「물어보지 못했다」를 값으로 말합니다.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadable").value(true))
                // 빠짐없이 확인했다고 말하면 화면이 「고른 도서관에는 없습니다」로 그립니다.
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.libCodes", hasSize(0)));
    }

    @Test
    @DisplayName("한도를 넘기기 전에 받아 둔 소장은 한도를 넘긴 뒤에도 답한다")
    void cachedHoldingsStillAnswerAfterTheLimit() throws Exception {
        String body = "{\"isbn13List\":[\"9788983711892\"],\"libs\":[\"110001\"]}";
        mvc.perform(get("/api/libraries")).andExpect(status().isOk());
        mvc.perform(post("/api/holdings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.libCodes[0]").value("110001"));

        data4Library.overLimit = true;
        int sent = data4Library.sent.get();

        // 실패는 기억하지 않고 받은 답은 기억합니다. 그래서 이미 본 책은 계속 답할 수 있습니다.
        mvc.perform(post("/api/holdings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.unreadable").value(false))
                .andExpect(jsonPath("$.libCodes[0]").value("110001"));
        assertEquals(sent, data4Library.sent.get(), "캐시가 답했으면 정보나루로 나가지 않습니다");
    }

    @Test
    @DisplayName("여러 권 검색의 목록 확인이 한도에 걸리면 줄마다 실패로 내리지 않고 503 과 같은 코드로 답한다")
    void resolveAnswers503WithTheSameCode() throws Exception {
        data4Library.overLimit = true;

        mvc.perform(post("/api/check/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[\"코스모스\",\"마의 산 - 토마스 만\",\"9788983711892\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("outOflimit"))
                .andExpect(jsonPath("$.reason", containsString("내일")));
    }

    @Test
    @DisplayName("한도 오류가 아닌 실패는 여전히 그 줄만의 실패다")
    void otherFailuresStayPerLine() throws Exception {
        // 한도 오류만 요청 전체를 끝냅니다. 그 밖의 오류까지 올리면 줄 하나가 흔들릴 때
        // 서른 줄을 통째로 잃습니다.
        var flaky = new Data4LibraryClient(uri -> """
                <response><errCode>someOtherErr</errCode><error>일시적인 오류</error></response>""",
                "테스트키", ledger);
        var search = new BookSearchService(flaky, new HoldingsLookup(
                flaky.asHoldingsClient(), HoldingsLookup.RegionModeStore.documented()));

        var response = new MultiCheckService(search).resolve(List.of("코스모스"));

        assertEquals(MultiCheckService.LineStatus.LOOKUP_FAILED, response.lines().get(0).status());
    }

    @Test
    @DisplayName("한도 오류를 받은 뒤 5분 동안은 어느 요청도 정보나루로 나가지 않는다")
    void nothingGoesOutForFiveMinutesAfterTheLimit() throws Exception {
        mvc.perform(get("/api/libraries")).andExpect(status().isOk());
        data4Library.overLimit = true;

        mvc.perform(get("/api/search").param("q", "코스모스"))
                .andExpect(status().isServiceUnavailable());
        // 검색 한 번이 여러 표기를 동시에 물어보므로, 빗장이 걸리기 전에 나간 것까지 셉니다.
        int afterFirst = data4Library.sent.get();
        int counted = ledger.used(Data4LibraryClient.SOURCE_CODE);

        String holdings = "{\"isbn13List\":[\"9788983711892\"],\"libs\":[\"110001\"]}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/search").param("q", "마의 산"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("outOflimit"));
            mvc.perform(post("/api/holdings").contentType(MediaType.APPLICATION_JSON).content(holdings))
                    .andExpect(jsonPath("$.unreadable").value(true));
            mvc.perform(get("/api/loan").param("lib", "110001").param("isbn", "9788983711892"))
                    .andExpect(status().isServiceUnavailable());
        }
        assertEquals(afterFirst, data4Library.sent.get(), "5분 안에는 한 건도 나가면 안 됩니다");
        assertEquals(counted, ledger.used(Data4LibraryClient.SOURCE_CODE),
                "나가지 않은 호출은 원장에 적지 않습니다");

        // 5분이 지나면 다시 물어봅니다. 날이 바뀌어 정보나루가 답하면 그대로 살아납니다.
        now.set(now.get().plus(Duration.ofMinutes(5)));
        data4Library.overLimit = false;
        mvc.perform(get("/api/search").param("q", "코스모스"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.works[0].title").value("코스모스"));
        assertTrue(data4Library.sent.get() > afterFirst);
    }
}
