package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 검색 결과의 소장 표시가 <b>없다</b>와 <b>모른다</b>를 섞지 않는지 확인합니다.
 *
 * <p>이 구분이 무너지면 실제로 소장한 책을 미소장으로 답하게 되어 헛걸음을 만듭니다.
 * 이 도구의 존재 이유가 무너지는 지점이라 다른 무엇보다 먼저 지켜야 합니다.
 *
 * <p>소장 판정은 {@code holdingsOf} 가 하고 검색은 그것을 부르지 않습니다. 그래서 아래
 * 검사들도 {@code holdingsOf} 를 직접 부릅니다. 화면에서는 {@code POST /api/holdings} 가
 * 같은 함수를 거칩니다.
 */
class BookSearchServiceTest {

    private static final String TWO_BOOKS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[코스모스]]></bookname>
              <authors><![CDATA[칼 세이건 지음 ; 홍승수 옮김]]></authors>
              <publisher><![CDATA[사이언스북스]]></publisher>
              <publication_year>2006</publication_year>
              <isbn13>9788983711892</isbn13>
            </doc>
            <doc>
              <bookname><![CDATA[코스모스 (특별판)]]></bookname>
              <authors><![CDATA[칼 세이건 지음]]></authors>
              <publisher><![CDATA[사이언스북스]]></publisher>
              <publication_year>2010</publication_year>
              <isbn13>9791158510015</isbn13>
            </doc>
          </docs>
        </response>
        """;

    /** 정보나루가 실제로 돌려준 순서를 흉내 냅니다. 찾는 책이 맨 뒤에 있습니다. */
    private static final String BURIED_MATCH = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[(벨라 바르톡의)미크로코스모스 입문]]></bookname>
              <authors><![CDATA[조치노 지음]]></authors>
              <publisher><![CDATA[음악춘추사]]></publisher>
              <publication_year>2003</publication_year>
              <isbn13>9788913007323</isbn13>
            </doc>
            <doc>
              <bookname><![CDATA[뽐내는 코스모스]]></bookname>
              <authors><![CDATA[김신철 글]]></authors>
              <publisher><![CDATA[삼성당]]></publisher>
              <publication_year>2005</publication_year>
              <isbn13>9788914012562</isbn13>
            </doc>
            <doc>
              <bookname><![CDATA[코스모스]]></bookname>
              <authors><![CDATA[칼 세이건 지음 ; 홍승수 옮김]]></authors>
              <publisher><![CDATA[사이언스북스]]></publisher>
              <publication_year>2006</publication_year>
              <isbn13>9788983711892</isbn13>
            </doc>
          </docs>
        </response>
        """;

    private static Data4LibraryClient client(String payload) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        return new Data4LibraryClient(uri -> payload, "테스트키", budget);
    }

    private static BookSearchService service(HoldingsLookup.HoldingsClient holdings) {
        return service(TWO_BOOKS, holdings);
    }

    private static BookSearchService service(String payload,
                                             HoldingsLookup.HoldingsClient holdings) {
        return new BookSearchService(client(payload), new HoldingsLookup(
                holdings, HoldingsLookup.RegionModeStore.documented()));
    }

    /** 불리면 실패하는 조회. 조회가 일어나지 않아야 하는 경우를 검사할 때 씁니다. */
    private static final HoldingsLookup.HoldingsClient NEVER_CALLED = (isbn, region) -> {
        throw new AssertionError("조회하지 않아야 하는데 불렸습니다: " + isbn + " / " + region);
    };

    private static List<String> cosmosEditions(BookSearchService service) {
        return service.search("코스모스").works().get(0).isbn13List();
    }

    @Test
    @DisplayName("검색은 소장을 조회하지 않는다")
    void searchDoesNotLookUpHoldings() {
        // 예전에는 저작마다 libSrchByBook 을 불렀습니다. 저작이 200개면 호출도 200번이고
        // 요청 간격이 120ms 라 그것만으로 24초가 걸렸습니다. 소장은 화면이 따로 묻습니다.
        var response = service(NEVER_CALLED).search("코스모스");

        assertFalse(response.works().isEmpty(), "책 정보는 그대로 나와야 합니다");
        assertNotNull(response.asOf());
    }

    @Test
    @DisplayName("제목이 그대로 맞는 책을 위로 올린다")
    void exactTitleMatchComesFirst() {
        // 정보나루가 주는 순서를 그대로 쓰면 「코스모스」를 찾았는데 「미크로코스모스 입문」이
        // 1등으로 나옵니다. 찾으려던 책이 안 보이는 것이 이 도구를 버리게 만듭니다.
        var response = service(BURIED_MATCH, NEVER_CALLED).search("코스모스");

        assertEquals("코스모스", response.works().get(0).title());
    }

    @Test
    @DisplayName("띄어쓰기가 달라도 같은 책으로 본다")
    void rankingSharesTheNormalizer() {
        // 적재·군집화와 같은 정규화 함수를 써야 검색에서만 어긋나는 일이 없습니다.
        var response = service(BURIED_MATCH, NEVER_CALLED).search("  코스모스  ");

        assertEquals("코스모스", response.works().get(0).title());
    }

    private static final String ONE_GOOD_ONE_WITHOUT_ISBN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[마의 산. 상]]></bookname>
              <authors><![CDATA[토마스 만 지음 ; 홍성광 옮김]]></authors>
              <publisher><![CDATA[을유문화사]]></publisher>
              <publication_year>2008</publication_year>
              <isbn13>9788932460345</isbn13>
            </doc>
            <doc>
              <bookname><![CDATA[마의 산 (전2권)]]></bookname>
              <authors><![CDATA[토마스 만]]></authors>
              <publisher><![CDATA[을유문화사]]></publisher>
              <publication_year>2008</publication_year>
              <isbn13></isbn13>
            </doc>
          </docs>
        </response>
        """;

    /** 낱권 표제가 모두 같고 권차만 vol 로 따로 오는 실제 모양입니다. */
    private static final String VOLUMES = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[레미제라블]]></bookname>
              <authors><![CDATA[빅토르 위고 지음 ; 정기수 옮김]]></authors>
              <publisher><![CDATA[민음사]]></publisher>
              <publication_year>2012</publication_year>
              <isbn13>9788937462511</isbn13>
              <vol>1</vol>
            </doc>
            <doc>
              <bookname><![CDATA[레미제라블]]></bookname>
              <authors><![CDATA[빅토르 위고 지음 ; 정기수 옮김]]></authors>
              <publisher><![CDATA[민음사]]></publisher>
              <publication_year>2012</publication_year>
              <isbn13>9788937462528</isbn13>
              <vol>2</vol>
            </doc>
          </docs>
        </response>
        """;

    @Test
    @DisplayName("표제가 같아도 권차가 다르면 다른 책으로 둔다")
    void volumesAreDifferentWorks() {
        // 정보나루는 권차를 vol 로 따로 줍니다. 표제에는 안 들어 있는 경우가 많아서,
        // 그 값을 버리면 「레미제라블」 1~5권이 표제가 같아 한 저작으로 합쳐집니다.
        // 화면에는 하나만 나와 낱권을 고를 수 없고, 그 하나의 ISBN 목록에 다섯 권이
        // 다 들어가므로 1권만 있는 도서관이 「레미제라블 있음」으로 나옵니다.
        var response = service(VOLUMES, NEVER_CALLED).search("레미제라블");

        assertEquals(2, response.works().size(), "1권과 2권은 다른 책입니다");
        // 갈라 놓아도 표제가 같으면 화면에서 구별할 수 없습니다.
        var titles = response.works().stream().map(BookSearchService.WorkResult::title).sorted().toList();
        assertEquals(List.of("레미제라블 1권", "레미제라블 2권"), titles);
        // 그리고 소장 조회에 서로의 ISBN 이 섞이면 안 됩니다.
        for (var work : response.works()) {
            assertEquals(1, work.isbn13List().size(), "한 권의 ISBN 만 들고 있어야 합니다");
        }
    }

    @Test
    @DisplayName("물어볼 수 없었던 도서관이 있으면 빠짐없이 확인했다고 하지 않는다")
    void aLibraryWeCouldNotAskIsNotAbsence() {
        // libSrchByBook 은 region 이 필수인데 그 값은 도서관 주소에서 뽑습니다. 주소가
        // 비어 있으면 그 도서관은 조회 대상에서 아예 빠지고, 결과 목록에 있을 수 없으므로
        // 그대로 「그 도서관에는 없다」로 나갑니다. 물어보지 않고 없다고 답하는 것입니다.
        var service = service((isbn, region) -> List.of());
        var holdings = service.holdingsOf(
                cosmosEditions(service), List.of("11"), List.of("111001", "111002"), 1);

        assertTrue(holdings.libCodes().isEmpty());
        assertFalse(holdings.complete(), "한 곳을 물어보지 못했으면 빠짐없이 확인한 것이 아닙니다");
    }

    @Test
    @DisplayName("띄어쓰기만 다른 제목을 우리가 대신 찾아 준다")
    void retriesWithoutSpaces() {
        // 정보나루는 넣은 글자를 그대로 찾습니다. 「마의 산」과 「마의산」이 다른 검색이고,
        // 사용자에게는 그것이 「그런 책이 없다」로 보입니다. 안내만 하고 마는 것은
        // 우리가 할 수 있는 일을 사용자에게 미루는 것입니다.
        assertEquals("마의산", BookSearchService.respacedTitle("마의 산"));
        assertEquals("총균쇠", BookSearchService.respacedTitle("총 균 쇠"));
        assertNull(BookSearchService.respacedTitle("코스모스"), "바꿀 것이 없으면 다시 찾지 않습니다");
        assertNull(BookSearchService.respacedTitle(null));
        assertNull(BookSearchService.respacedTitle("   "));
    }

    @Test
    @DisplayName("ISBN 이 없어 뺀 자료는 몇 건인지 밝힌다")
    void reportsWhatItHadToDrop() {
        // 조용히 빼면 사용자는 그것을 「그런 책이 없다」로 읽습니다. 찾던 책이 하필
        // 그 자료였을 때 아무 단서도 없이 사라집니다.
        var response = service(ONE_GOOD_ONE_WITHOUT_ISBN, NEVER_CALLED).search("마의 산");

        assertEquals(1, response.works().size());
        assertEquals(1, response.droppedNoIsbn(), "ISBN 이 없어 뺀 한 건을 밝혀야 합니다");
    }

    @Test
    @DisplayName("세우기는 자르지 않는다. 전체가 몇 개인지 셀 수 있어야 한다")
    void rankingDoesNotTruncate() {
        // 자르는 것은 부르는 쪽의 몫입니다. 여기서 자르면 화면이 「n개 중 20개」라고
        // 말할 수 없고, 사용자는 지금 보는 것이 전부인지 잘린 것인지 알 수 없습니다.
        assertEquals(150, BookSearchService.rank("코스모스", manyWorks(150)).size());
    }

    @Test
    @DisplayName("제목 없이 저자나 출판사로만 찾으면 정보나루가 준 순서를 흔들지 않는다")
    void withoutATitleTheOrderIsLeftAlone() {
        var works = manyWorks(5);
        assertEquals(works, BookSearchService.rank(null, works));
        assertEquals(works, BookSearchService.rank("   ", works));
    }

    private static List<BookSearchService.WorkResult> manyWorks(int count) {
        List<BookSearchService.WorkResult> many = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            many.add(new BookSearchService.WorkResult(
                    i, "코스모스 " + i, "지은이", "출판사", null, null,
                    List.of("978898371189" + (i % 10)), List.of()));
        }
        return many;
    }

    @Test
    @DisplayName("고른 도서관이 없으면 조회하지 않고, 미소장이라고도 하지 않는다")
    void noSelectionIsNotAbsence() {
        var service = service(NEVER_CALLED);
        var holdings = service.holdingsOf(cosmosEditions(service), List.of(), List.of());

        assertTrue(holdings.libCodes().isEmpty());
        assertTrue(holdings.complete(), "물어볼 필요가 없었으므로 미확인이 아닙니다");
        assertFalse(holdings.unreadable());
    }

    @Test
    @DisplayName("고른 도서관의 지역을 하나도 모르면 미소장이 아니라 확인 불가다")
    void unknownRegionIsNotAbsence() {
        // libSrchByBook 은 region 이 필수라 지역을 모르면 조회를 시작할 수조차 없습니다.
        // 이때 빈 결과를 돌려주면 화면이 "고른 도서관에는 없습니다"로 그립니다.
        var service = service(NEVER_CALLED);
        var holdings = service.holdingsOf(cosmosEditions(service), List.of(), List.of("111001"));

        assertTrue(holdings.unreadable(), "확인 불가로 표시해야 합니다");
        assertFalse(holdings.complete());
        assertTrue(holdings.libCodes().isEmpty());
    }

    @Test
    @DisplayName("조회가 실패하면 미소장이 아니라 확인 불가다")
    void lookupFailureIsNotAbsence() {
        var service = service((isbn, region) -> {
            throw new IllegalStateException("정보나루가 응답하지 않습니다");
        });
        var holdings = service.holdingsOf(cosmosEditions(service), List.of("11"), List.of("111001"));

        assertTrue(holdings.unreadable());
        assertTrue(holdings.libCodes().isEmpty());
    }

    @Test
    @DisplayName("빠짐없이 확인했는데 없으면 그때는 미소장이다")
    void emptyResultAfterFullCheckIsAbsence() {
        var service = service((isbn, region) -> List.of("999999"));
        var holdings = service.holdingsOf(cosmosEditions(service), List.of("11"), List.of("111001"));

        assertFalse(holdings.unreadable(), "확인은 했으므로 확인 불가가 아닙니다");
        assertTrue(holdings.complete());
        assertTrue(holdings.libCodes().isEmpty(), "고른 도서관에는 없습니다");
    }

    @Test
    @DisplayName("고른 도서관이 소장하면 그 부호만 돌려준다")
    void returnsOnlySelectedLibraries() {
        var service = service((isbn, region) -> List.of("111001", "141053"));
        var holdings = service.holdingsOf(cosmosEditions(service), List.of("11"), List.of("111001"));

        assertTrue(holdings.complete());
        // 고르지 않은 141053 은 결과에 나오면 안 됩니다.
        assertEquals(List.of("111001"), holdings.libCodes());
    }

    @Test
    @DisplayName("저작에 묶인 판본 전체를 조회한다")
    void queriesEveryEditionOfTheWork() {
        // 판본 하나만 조회하면 도서관이 다른 판을 가지고 있어도 미소장으로 나옵니다.
        var asked = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var service = service((isbn, region) -> {
            asked.add(isbn);
            // 특별판만 소장한 도서관입니다. 초판만 물었다면 놓쳤을 것입니다.
            return isbn.equals("9791158510015") ? List.of("111001") : List.of();
        });

        var editions = cosmosEditions(service);
        assertEquals(2, editions.size(), "「코스모스」와 「코스모스 (특별판)」은 한 저작입니다");

        var holdings = service.holdingsOf(editions, List.of("11"), List.of("111001"));
        assertTrue(asked.containsAll(editions), "묶인 ISBN 을 모두 물었어야 합니다: " + asked);
        assertEquals(List.of("111001"), holdings.libCodes());
    }

    @Test
    @DisplayName("호출 예산이 소진되어도 오류 대신 확인 불가로 답한다")
    void budgetExhaustionDegradesInsteadOfFailing() {
        var exhausted = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var searchClient = new Data4LibraryClient(uri -> TWO_BOOKS, "테스트키", exhausted);
        var service = new BookSearchService(searchClient, new HoldingsLookup(
                (isbn, region) -> {
                    throw new Data4LibraryClient.BudgetExhaustedException("예산 소진");
                },
                HoldingsLookup.RegionModeStore.documented()));

        var response = service.search("코스모스");
        assertFalse(response.works().isEmpty(), "책 정보까지 없애 버리면 안 됩니다");

        var holdings = service.holdingsOf(response.works().get(0).isbn13List(),
                List.of("11"), List.of("111001"));
        assertTrue(holdings.unreadable());
    }
}
