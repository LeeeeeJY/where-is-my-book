package kr.wimb.data4library;

import kr.wimb.ingest.ApiBudget;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 응답 예시는 Open API Manual v20260210 의 응답 명세대로 만들었습니다.
 * 매뉴얼이 항목 이름과 중첩 구조는 밝히지만 최상위 요소 이름은 적어 두지 않아,
 * 루트가 무엇이든 읽히는지도 함께 확인합니다.
 */
class Data4LibraryClientTest {

    private static final String LIB_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <request><pageNo>1</pageNo></request>
          <numFound>2</numFound>
          <resultNum>2</resultNum>
          <libs>
            <lib>
              <libCode>111001</libCode>
              <libName><![CDATA[강남도서관]]></libName>
              <address><![CDATA[서울특별시 강남구 선릉로 6]]></address>
              <tel>02-000-0000</tel>
              <fax>02-000-0001</fax>
              <latitude>37.5150</latitude>
              <longitude>127.0470</longitude>
              <homepage><![CDATA[https://library.gangnam.go.kr]]></homepage>
              <closed><![CDATA[매주 월요일]]></closed>
              <operatingTime><![CDATA[09:00~18:00]]></operatingTime>
              <BookCount>120,000</BookCount>
            </lib>
            <lib>
              <libCode>141053</libCode>
              <libName><![CDATA[성남시중원도서관]]></libName>
              <address><![CDATA[경기도 성남시 중원구 광명로 6]]></address>
              <latitude>37.4300</latitude>
              <longitude>127.1500</longitude>
            </lib>
          </libs>
        </response>
        """;

    private static final String BOOK_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <numFound>1</numFound>
          <docs>
            <doc>
              <bookname><![CDATA[코스모스]]></bookname>
              <authors><![CDATA[칼 세이건 지음 ; 홍승수 옮김]]></authors>
              <publisher><![CDATA[사이언스북스]]></publisher>
              <publication_year>2006</publication_year>
              <isbn13>9788983711892</isbn13>
              <addition_symbol>03400</addition_symbol>
              <vol>1</vol>
              <class_no>440</class_no>
              <class_nm><![CDATA[천문학]]></class_nm>
              <bookImageURL><![CDATA[https://image.example/cosmos.jpg]]></bookImageURL>
              <bookDtlUrl><![CDATA[https://data4library.kr/bookV?seq=123]]></bookDtlUrl>
              <loan_count>1,234</loan_count>
            </doc>
          </docs>
        </response>
        """;

    private static final class RecordingTransport implements Data4LibraryClient.Transport {
        final List<URI> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        String response = "<response><libs/></response>";
        /** 비어 있지 않으면 호출마다 앞에서 하나씩 꺼내 씁니다. 쪽 넘김을 시험할 때 씁니다. */
        final List<String> pages = new ArrayList<>();
        /** 쪽 번호마다 다른 답. 둘째 쪽부터는 동시에 오므로 순서에 기대면 안 됩니다. */
        final java.util.Map<Integer, String> byPage = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public synchronized String get(URI uri) {
            requests.add(uri);
            if (!byPage.isEmpty()) {
                int page = Integer.parseInt(uri.toString().replaceAll(".*[?&]pageNo=([0-9]+).*", "$1"));
                return byPage.getOrDefault(page, "<response><libs/></response>");
            }
            if (pages.isEmpty()) return response;
            return pages.remove(0);
        }
    }

    /** 소장 도서관 한 곳이 든 한 쪽. */
    private static String holdingPage(int numFound, String... libCodes) {
        StringBuilder xml = new StringBuilder("<response><numFound>")
                .append(numFound).append("</numFound><libs>");
        for (String code : libCodes) {
            xml.append("<lib><libCode>").append(code)
                    .append("</libCode><libName>도서관").append(code).append("</libName></lib>");
        }
        return xml.append("</libs></response>").toString();
    }

    private static ApiBudget budget() {
        return new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
    }

    private static Data4LibraryClient client(RecordingTransport transport) {
        return new Data4LibraryClient(transport, "테스트키", budget());
    }

    @Test
    @DisplayName("도서관 목록에서 위경도까지 읽는다")
    void parsesLibrariesIncludingCoordinates() {
        // 위경도가 여기 있어서 공공데이터포털의 표준데이터를 이름과 주소로 대조할 필요가 없습니다.
        var transport = new RecordingTransport();
        transport.response = LIB_XML;

        var libraries = client(transport).libraries(null, ApiBudget.Priority.BACKGROUND);

        assertEquals(2, libraries.size());
        var gangnam = libraries.get(0);
        assertEquals("111001", gangnam.libCode());
        assertEquals("강남도서관", gangnam.libName());
        assertEquals(37.5150, gangnam.latitude());
        assertEquals(127.0470, gangnam.longitude());
        assertEquals("매주 월요일", gangnam.closed());
        assertEquals(120_000, gangnam.bookCount(), "천 단위 쉼표를 처리해야 합니다");
    }

    @Test
    @DisplayName("주소에서 지역 코드를 뽑는다")
    void derivesRegionFromAddress() {
        var transport = new RecordingTransport();
        transport.response = LIB_XML;

        var libraries = client(transport).libraries(null, ApiBudget.Priority.BACKGROUND);

        assertEquals(RegionCode.SEOUL, libraries.get(0).region().orElseThrow());
        assertEquals(RegionCode.GYEONGGI, libraries.get(1).region().orElseThrow());
    }

    @Test
    @DisplayName("빠진 항목이 있어도 도서관을 버리지 않는다")
    void toleratesMissingFields() {
        var transport = new RecordingTransport();
        transport.response = LIB_XML;

        var seongnam = client(transport).libraries(null, ApiBudget.Priority.BACKGROUND).get(1);

        assertEquals("성남시중원도서관", seongnam.libName());
        assertNull(seongnam.tel());
        assertNull(seongnam.bookCount());
    }

    @Test
    @DisplayName("서지에서 표지와 대출건수까지 읽는다")
    void parsesBooks() {
        var transport = new RecordingTransport();
        transport.response = BOOK_XML;

        var books = client(transport).searchBooks(
                Data4LibraryClient.BookQuery.byTitle("코스모스"), 1, ApiBudget.Priority.USER);

        assertEquals(1, books.size());
        var cosmos = books.get(0);
        assertEquals("9788983711892", cosmos.canonicalIsbn13().orElseThrow());
        assertEquals("03400", cosmos.additionSymbol(), "부가기호는 ISBN 과 따로 옵니다");
        assertEquals(1, cosmos.volumeNumber().orElseThrow(), "권차도 따로 옵니다");
        assertEquals("https://image.example/cosmos.jpg", cosmos.bookImageUrl());
        assertEquals(1234, cosmos.loanCount());
    }

    @Test
    @DisplayName("최상위 요소 이름이 무엇이든 읽는다")
    void parsingIsRootAgnostic() {
        // 매뉴얼이 루트 이름을 적어 두지 않아 가정하지 않습니다.
        String different = LIB_XML.replace("<response>", "<result>").replace("</response>", "</result>");
        var transport = new RecordingTransport();
        transport.response = different;

        assertEquals(2, client(transport).libraries(null, ApiBudget.Priority.BACKGROUND).size());
    }

    @Test
    @DisplayName("소장 조회는 region 없이 부르지 못하게 막는다")
    void holdingsRequiresRegion() {
        // 매뉴얼 13절에 필수로 명시되어 있습니다. 빼고 부르면 조용히 빈 결과가 올 수 있으므로
        // 호출 자체를 막아 예산만 쓰고 아무것도 못 얻는 일을 없앱니다.
        var transport = new RecordingTransport();
        var client = client(transport);

        assertThrows(IllegalArgumentException.class,
                () -> client.librariesHolding("9788983711892", null, ApiBudget.Priority.USER));
        assertTrue(transport.requests.isEmpty(), "막혔으면 호출하지 않아야 합니다");
    }

    @Test
    @DisplayName("요청 주소를 매뉴얼대로 만든다")
    void buildsDocumentedUrl() {
        var transport = new RecordingTransport();
        transport.response = LIB_XML;

        client(transport).librariesHolding("9788983711892", "11", ApiBudget.Priority.USER);

        String url = transport.requests.get(0).toString();
        assertTrue(url.startsWith("https://data4library.kr/api/libSrchByBook?authKey="), url);
        assertTrue(url.contains("&isbn=9788983711892"), url);
        assertTrue(url.contains("&region=11"), url);
    }

    @Test
    @DisplayName("소장 도서관이 한 쪽에 안 들어가면 끝까지 넘겨 받는다")
    void pagesThroughEveryHoldingLibrary() {
        // 첫 쪽만 보고 끝내면 뒤쪽 도서관이 통째로 빠진 채 답이 나갑니다. 빠진 도서관은
        // 「그 도서관에는 없다」로, 그것도 「빠짐없이 확인했다」는 표시를 달고 나갑니다.
        // 소장 도서관이 많은 책일수록 심해지므로, 가장 자주 찾는 책에서 가장 자주 틀립니다.
        var transport = new RecordingTransport();
        transport.pages.add(holdingPage(3, "111001", "111002"));
        transport.pages.add(holdingPage(3, "111003"));

        var libs = client(transport).librariesHolding("9788983711892", "11",
                ApiBudget.Priority.USER);

        assertEquals(List.of("111001", "111002", "111003"),
                libs.stream().map(LibraryInfo::libCode).toList());
        assertEquals(2, transport.requests.size(), "두 쪽을 받아야 합니다");
        assertTrue(transport.requests.get(1).toString().contains("pageNo=2"),
                transport.requests.get(1).toString());
    }

    @Test
    @DisplayName("셋째 쪽 뒤까지 있어도 전부 받고 쪽 순서를 지킨다")
    void fetchesLaterPagesTogetherInOrder() {
        // 한 쪽에 4~5초가 걸려 둘째 쪽부터는 동시에 받습니다. 그래도 빠지는 쪽이 없어야 하고
        // 순서도 쪽 순서 그대로여야 합니다.
        var transport = new RecordingTransport();
        transport.byPage.put(1, holdingPage(7, "111001", "111002", "111003"));
        transport.byPage.put(2, holdingPage(7, "111004", "111005", "111006"));
        transport.byPage.put(3, holdingPage(7, "111007"));

        var libs = client(transport).librariesHolding("9788983711892", "11",
                ApiBudget.Priority.USER);

        assertEquals(List.of("111001", "111002", "111003", "111004", "111005", "111006", "111007"),
                libs.stream().map(LibraryInfo::libCode).toList());
        assertEquals(3, transport.requests.size(), "세 쪽을 한 번씩만 받아야 합니다");
        assertTrue(transport.requests.stream().noneMatch(u -> u.toString().contains("pageNo=4")));
    }

    @Test
    @DisplayName("한 쪽에 다 들어오면 더 부르지 않는다")
    void stopsAfterOnePageWhenEverythingFits() {
        // 쪽 넘김이 예산을 쓸데없이 갉아먹으면 안 됩니다.
        var transport = new RecordingTransport();
        transport.pages.add(holdingPage(2, "111001", "111002"));

        var libs = client(transport).librariesHolding("9788983711892", "11",
                ApiBudget.Priority.USER);

        assertEquals(2, libs.size());
        assertEquals(1, transport.requests.size(), "한 번만 불러야 합니다");
    }

    @Test
    @DisplayName("제목의 공백을 + 가 아니라 %20 으로 보낸다")
    void sendsSpacesAsPercentTwenty() {
        // URLEncoder 는 공백을 + 로 바꿉니다. 그것은 HTML 폼 본문의 규칙이고, 질의
        // 문자열에서 + 를 공백으로 되돌려 주는 것은 서버 마음입니다. 되돌리지 않는
        // 서버에서는 「마의+산」이라는 글자를 그대로 찾는 검색이 되어 0건이 나오고,
        // 사용자에게는 「그런 책이 없다」로 보입니다. 오류도 로그도 남지 않고,
        // 제목에 공백이 없는 책은 멀쩡히 나오기 때문에 의심하기도 어렵습니다.
        var transport = new RecordingTransport();
        transport.response = BOOK_XML;

        client(transport).searchBooks(
                Data4LibraryClient.BookQuery.byTitle("마의 산"), 1, ApiBudget.Priority.USER);

        String url = transport.requests.get(0).toString();
        assertTrue(url.contains("title=%EB%A7%88%EC%9D%98%20%EC%82%B0"), url);
        assertFalse(url.contains("+"), "질의 문자열에 + 가 남아 있으면 안 됩니다: " + url);
    }

    @Test
    @DisplayName("검색 조건이 하나도 없으면 부르지 않는다")
    void rejectsEmptyQuery() {
        // 조건 없이 부르면 전체 대출데이터를 훑게 되어 예산만 쓰고 쓸모가 없습니다.
        assertThrows(IllegalArgumentException.class,
                () -> new Data4LibraryClient.BookQuery(null, null, null, null, false).toParams());
    }

    @Test
    @DisplayName("예산이 없으면 호출하지 않는다")
    void respectsBudget() {
        var transport = new RecordingTransport();
        var exhausted = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", exhausted);

        // 배경 작업 상한은 1의 80% 이므로 0회입니다.
        assertThrows(Data4LibraryClient.BudgetExhaustedException.class,
                () -> client.libraries(null, ApiBudget.Priority.BACKGROUND));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    @DisplayName("소장 조회를 HoldingsLookup 이 쓰는 형태로 넘긴다")
    void adaptsToHoldingsClient() {
        var transport = new RecordingTransport();
        transport.response = LIB_XML;

        var codes = client(transport)
                .asHoldingsClient(ApiBudget.Priority.USER)
                .libCodesFor("9788983711892", "11");

        assertEquals(List.of("111001", "141053"), codes);
    }

    /**
     * 아래 두 응답은 <b>2026-09-06 에 실제 정보나루에서 받은 본문 그대로입니다.</b>
     * 손으로 지어낸 것이 아닙니다.
     */
    private static final String REAL_NOT_ACTIVATED_XML =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?><response><errCode>            vitalizationErr</errCode><error>API 활성화 상태가아닙니다.</error></response>""";

    private static final String REAL_NOT_ACTIVATED_JSON =
            """
            {"response":{"errCode":"vitalizationErr","error":"API 활성화 상태가아닙니다."}}""";

    private static final String REAL_AUTH_ERROR_XML =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?><response><errCode>            authErr</errCode><error>인증정보가 일치하지 않습니다.</error></response>""";

    @Test
    @DisplayName("오류를 HTTP 200 으로 받아도 빈 결과로 넘기지 않는다")
    void bodyLevelErrorIsNotAnEmptyResult() {
        // 이것이 이 프로젝트에서 가장 위험한 실패입니다. 오류 본문에는 lib 도 doc 도 없어서
        // 그대로 파싱하면 "항목이 하나도 없는 정상 응답"이 되고, 화면은 그것을 미소장으로
        // 그립니다. 멀쩡히 있는 책을 없다고 답하게 됩니다.
        var transport = new RecordingTransport();
        transport.response = REAL_NOT_ACTIVATED_XML;
        var client = client(transport);

        var thrown = assertThrows(Data4LibraryClient.ApiErrorException.class,
                () -> client.libraries("11", ApiBudget.Priority.USER));
        assertTrue(thrown.isNotActivated(), thrown.getMessage());
        assertFalse(thrown.isAuthFailure());
    }

    @Test
    @DisplayName("오류가 JSON 으로 와도 알아본다")
    void jsonShapedErrorIsDetected() {
        // 실제로 libSrch 는 XML, srchBooks 는 JSON 으로 오류를 돌려주었습니다.
        // 한쪽만 보면 다른 쪽이 빈 결과로 새어 나갑니다.
        var transport = new RecordingTransport();
        transport.response = REAL_NOT_ACTIVATED_JSON;
        var client = client(transport);

        assertThrows(Data4LibraryClient.ApiErrorException.class,
                () -> client.searchBooks(
                        Data4LibraryClient.BookQuery.byTitle("코스모스"), 1, ApiBudget.Priority.USER));
    }

    @Test
    @DisplayName("인증 실패와 미활성을 구분한다")
    void distinguishesAuthFailureFromInactiveKey() {
        // 고쳐야 할 것이 다릅니다. 앞은 키가 틀린 것이고 뒤는 승인을 기다리는 것입니다.
        var transport = new RecordingTransport();
        transport.response = REAL_AUTH_ERROR_XML;
        var client = client(transport);

        var thrown = assertThrows(Data4LibraryClient.ApiErrorException.class,
                () -> client.libraries("11", ApiBudget.Priority.USER));
        assertTrue(thrown.isAuthFailure());
        assertFalse(thrown.isNotActivated());
    }

    @Test
    @DisplayName("정상 응답을 오류로 오해하지 않는다")
    void normalResponseIsNotMistakenForAnError() {
        var transport = new RecordingTransport();
        transport.response = LIB_XML;
        assertEquals(2, client(transport).libraries("11", ApiBudget.Priority.USER).size());
    }

    @Test
    @DisplayName("인증 오류를 만나면 한동안 부르지 않는다")
    void stopsCallingAfterAuthClassError() {
        // 키가 없거나 활성화되지 않은 것은 사람이 고쳐야 낫는 상태라 재시도해도
        // 절대 성공하지 않습니다. 여러 권 확인은 한 번에 수십 번을 부르므로,
        // 막지 않으면 실패할 것이 뻔한 요청으로 남의 서버를 수십 번 두드립니다.
        var transport = new RecordingTransport();
        transport.response = REAL_NOT_ACTIVATED_XML;
        var client = client(transport);

        for (int i = 0; i < 10; i++) {
            assertThrows(Data4LibraryClient.ApiErrorException.class,
                    () -> client.libraries("11", ApiBudget.Priority.USER));
        }
        assertEquals(1, transport.requests.size(),
                "첫 한 번만 부르고 나머지는 막아야 합니다: " + transport.requests.size() + "회");
    }

    @Test
    @DisplayName("막힌 동안에는 호출 예산도 쓰지 않는다")
    void blockedCallsDoNotSpendBudget() {
        var transport = new RecordingTransport();
        transport.response = REAL_AUTH_ERROR_XML;
        var budget = budget();
        var client = new Data4LibraryClient(transport, "테스트키", budget);

        for (int i = 0; i < 5; i++) {
            assertThrows(Data4LibraryClient.ApiErrorException.class,
                    () -> client.libraries("11", ApiBudget.Priority.USER));
        }
        // 실제로 나간 요청 한 번만 예산에서 빠져야 합니다.
        assertEquals(1, budget.used(Data4LibraryClient.SOURCE_CODE));
    }

    @Test
    @DisplayName("시간이 지나면 다시 시도한다")
    void retriesAfterTheBackoff() {
        // 승인이 나면 다시 되어야 합니다. 영구히 막으면 사람이 고쳐도 살아나지 않습니다.
        var transport = new RecordingTransport();
        transport.response = REAL_NOT_ACTIVATED_XML;
        var now = new java.util.concurrent.atomic.AtomicReference<>(
                Instant.parse("2026-09-06T00:00:00Z"));
        var moving = Clock.fixed(now.get(), ZoneId.of("UTC"));
        var client = new Data4LibraryClient(transport, "테스트키", budget(), new Clock() {
            public ZoneId getZone() { return moving.getZone(); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        });

        assertThrows(Data4LibraryClient.ApiErrorException.class,
                () -> client.libraries("11", ApiBudget.Priority.USER));
        assertThrows(Data4LibraryClient.ApiErrorException.class,
                () -> client.libraries("11", ApiBudget.Priority.USER));
        assertEquals(1, transport.requests.size(), "막혀 있어야 합니다");

        // 승인이 나고 시간이 지난 뒤입니다.
        now.set(Instant.parse("2026-09-06T00:06:00Z"));
        transport.response = LIB_XML;
        assertEquals(2, client.libraries("11", ApiBudget.Priority.USER).size());
        assertEquals(2, transport.requests.size(), "다시 불러야 합니다");

        // 한 번 성공했으면 이후로는 막지 않습니다.
        assertEquals(2, client.libraries("11", ApiBudget.Priority.USER).size());
        assertEquals(3, transport.requests.size());
    }

    @Test
    @DisplayName("인증 계열이 아닌 오류는 막지 않는다")
    void otherErrorsAreNotLatched() {
        // 일시적인 오류일 수 있으므로 다음 요청까지 막으면 안 됩니다.
        var transport = new RecordingTransport();
        transport.response = """
            <response><errCode>someOtherErr</errCode><error>일시적인 오류</error></response>""";
        var client = client(transport);

        for (int i = 0; i < 3; i++) {
            assertThrows(Data4LibraryClient.ApiErrorException.class,
                    () -> client.libraries("11", ApiBudget.Priority.USER));
        }
        assertEquals(3, transport.requests.size(), "매번 시도해야 합니다");
    }

    /**
     * 매뉴얼 15절의 응답은 <b>여섯 묶음이 전부 {@code book} 이라는 같은 이름</b>을 씁니다.
     * 문서 전체를 훑어 읽으면 120권이 한 덩어리로 나오는데 <b>예외가 나지 않아</b>
     * 눈으로는 알아채기 어렵습니다. 화면에는 「영유아 목록에 성인 책」으로 나갑니다.
     */
    private static final String POPULAR_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <loanBooks>
            <book><no>1</no><ranking>1</ranking><bookname><![CDATA[급류]]></bookname>
              <authors><![CDATA[정대건 지음]]></authors><publisher><![CDATA[민음사]]></publisher>
              <publication_year>2024</publication_year><isbn13>9788937473838</isbn13>
              <class_no>813.7</class_no><class_nm><![CDATA[한국소설]]></class_nm>
              <bookImageURL><![CDATA[https://example.test/1.jpg]]></bookImageURL>
              <bookDtlUrl><![CDATA[https://data4library.kr/bookV?seq=1]]></bookDtlUrl></book>
          </loanBooks>
          <age0Books>
            <book><no>1</no><ranking>1</ranking><bookname><![CDATA[사과가 쿵!]]></bookname>
              <authors><![CDATA[다다 히로시]]></authors><publisher><![CDATA[보림]]></publisher>
              <publication_year>1996</publication_year><isbn13>9788943302016</isbn13></book>
          </age0Books>
          <age6Books></age6Books>
          <age8Books>
            <book><no>1</no><ranking>1</ranking><bookname><![CDATA[흔한남매 14]]></bookname>
              <authors><![CDATA[흔한남매]]></authors><publisher><![CDATA[아이스크림북스]]></publisher>
              <publication_year>2023</publication_year><isbn13>9791165341114</isbn13></book>
            <book><no>2</no><ranking>2</ranking><bookname><![CDATA[전천당 1]]></bookname>
              <authors><![CDATA[히로시마 레이코]]></authors><publisher><![CDATA[길벗스쿨]]></publisher>
              <publication_year>2018</publication_year><isbn13>9791164060207</isbn13></book>
          </age8Books>
          <age14Books>
            <book><no>1</no><ranking>1</ranking><bookname><![CDATA[아몬드]]></bookname>
              <authors><![CDATA[손원평 지음]]></authors><publisher><![CDATA[창비]]></publisher>
              <publication_year>2017</publication_year><isbn13>9788936434267</isbn13></book>
          </age14Books>
          <age20Books>
            <book><no>1</no><ranking>1</ranking><bookname><![CDATA[세이노의 가르침]]></bookname>
              <authors><![CDATA[세이노 지음]]></authors><publisher><![CDATA[데이원]]></publisher>
              <publication_year>2023</publication_year><isbn13>9788901270005</isbn13></book>
          </age20Books>
        </response>""";

    @Test
    @DisplayName("15절의 여섯 묶음이 같은 book 태그를 써도 섞이지 않는다")
    void popularGroupsDoNotMix() {
        var transport = new RecordingTransport();
        transport.response = POPULAR_XML;

        var groups = client(transport).popularByLibrary("111001", ApiBudget.Priority.USER);

        // 문서 전체를 훑어 읽으면 여기가 6이 아니라 전부 같은 목록이 됩니다.
        assertEquals(List.of("급류"), names(groups, Data4LibraryClient.AgeGroup.ALL));
        assertEquals(List.of("사과가 쿵!"), names(groups, Data4LibraryClient.AgeGroup.INFANT));
        assertEquals(List.of("흔한남매 14", "전천당 1"),
                names(groups, Data4LibraryClient.AgeGroup.ELEMENTARY));
        assertEquals(List.of("아몬드"), names(groups, Data4LibraryClient.AgeGroup.TEEN));
        assertEquals(List.of("세이노의 가르침"), names(groups, Data4LibraryClient.AgeGroup.ADULT));
    }

    @Test
    @DisplayName("빈 묶음은 아예 담기지 않는다")
    void emptyGroupIsOmitted() {
        var transport = new RecordingTransport();
        transport.response = POPULAR_XML;

        var groups = client(transport).popularByLibrary("111001", ApiBudget.Priority.USER);

        // 작은 도서관은 유아 목록이 비어서 옵니다. 그대로 그리면 눌렀는데 아무것도
        // 안 나와 고장으로 읽힙니다.
        assertFalse(groups.containsKey(Data4LibraryClient.AgeGroup.TODDLER));
        assertEquals(5, groups.size());
    }

    @Test
    @DisplayName("15절은 대출건수를 주지 않으므로 순위를 들어야 한다")
    void popularCarriesRankingNotLoanCount() {
        var transport = new RecordingTransport();
        transport.response = POPULAR_XML;

        var elementary = client(transport)
                .popularByLibrary("111001", ApiBudget.Priority.USER)
                .get(Data4LibraryClient.AgeGroup.ELEMENTARY);

        assertNull(elementary.get(0).loanCount(), "매뉴얼 15절 응답에 loan_count 가 없습니다");
        assertEquals(1, elementary.get(0).ranking());
        assertEquals(2, elementary.get(1).ranking());
    }

    private static List<String> names(Map<Data4LibraryClient.AgeGroup, List<BookInfo>> groups,
                                      Data4LibraryClient.AgeGroup group) {
        return groups.getOrDefault(group, List.of()).stream().map(BookInfo::bookname).toList();
    }

    @Test
    @DisplayName("지역 코드는 매뉴얼의 17개 시도를 모두 담는다")
    void regionCodesCoverAllSido() {
        assertEquals(17, RegionCode.values().length);
        assertEquals(RegionCode.SEOUL, RegionCode.ofCode("11").orElseThrow());
        assertEquals(RegionCode.JEJU, RegionCode.ofCode("39").orElseThrow());
        assertTrue(RegionCode.ofCode("99").isEmpty());
        // 매뉴얼은 약칭(서울, 경기)을 쓰고 주소는 정식 명칭이라 앞 두 글자로 맞춥니다.
        assertEquals(RegionCode.GYEONGGI, RegionCode.ofSido("경기도").orElseThrow());
        assertEquals(RegionCode.GANGWON, RegionCode.ofSido("강원특별자치도").orElseThrow());
    }
}
