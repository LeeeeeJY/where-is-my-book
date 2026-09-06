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
        final List<URI> requests = new ArrayList<>();
        String response = "<response><libs/></response>";

        @Override public String get(URI uri) {
            requests.add(uri);
            return response;
        }
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
