package kr.wimb.api;

import kr.wimb.data4library.BookInfo;
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

    /** 상·하 두 권뿐인 책. 정보나루가 vol 을 글자로 줄 때의 모양이고, 하권이 더 많이 읽혔습니다. */
    private static final String SANG_HA_ONLY = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[토지]]></bookname>
              <authors><![CDATA[박경리 지음]]></authors>
              <publisher><![CDATA[나남]]></publisher>
              <publication_year>2002</publication_year>
              <isbn13>9788930040037</isbn13>
              <vol>하</vol>
              <loan_count>300</loan_count>
            </doc>
            <doc>
              <bookname><![CDATA[토지]]></bookname>
              <authors><![CDATA[박경리 지음]]></authors>
              <publisher><![CDATA[나남]]></publisher>
              <publication_year>2002</publication_year>
              <isbn13>9788930040013</isbn13>
              <vol>상</vol>
              <loan_count>100</loan_count>
            </doc>
          </docs>
        </response>
        """;

    /**
     * <b>상·하만 있는 책이 「1권」과 「3권」으로 나오고 있었습니다.</b> 상·중·하를 1·2·3 으로
     * 세는 것은 순서를 위해서인데 그 숫자가 화면까지 나갔습니다. 사용자는 없는 2권을 찾게
     * 됩니다. 표기는 받은 대로 두되 순서는 여전히 상이 하보다 앞입니다.
     */
    @Test
    @DisplayName("상·하로 나뉜 책은 1권·3권이 아니라 상권·하권으로, 상권부터 나온다")
    void sangHaKeepsItsMarkAndOrder() {
        var response = service(SANG_HA_ONLY, NEVER_CALLED).search("토지");

        var titles = response.works().stream().map(BookSearchService.WorkResult::title).toList();
        assertEquals(List.of("토지 상권", "토지 하권"), titles,
                "하권이 더 많이 읽혔어도 상권이 먼저이고, 숫자로 바꿔 적으면 안 됩니다");
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
        assertEquals("마의산", BookSearchService.withoutSpaces("마의 산"));
        assertEquals("총균쇠", BookSearchService.withoutSpaces("총 균 쇠"));
        assertNull(BookSearchService.withoutSpaces("코스모스"), "바꿀 것이 없으면 다시 찾지 않습니다");
        assertNull(BookSearchService.withoutSpaces(null));
        assertNull(BookSearchService.withoutSpaces("   "));
    }

    @Test
    @DisplayName("ISBN 이 없어 뺀 자료는 몇 건인지, 무엇인지 밝힌다")
    void reportsWhatItHadToDrop() {
        // 조용히 빼면 사용자는 그것을 「그런 책이 없다」로 읽습니다. 찾던 책이 하필
        // 그 자료였을 때 아무 단서도 없이 사라집니다.
        var response = service(ONE_GOOD_ONE_WITHOUT_ISBN, NEVER_CALLED).search("마의 산");

        assertEquals(1, response.works().size());
        assertEquals(1, response.droppedNoIsbn(), "ISBN 이 없어 뺀 한 건을 밝혀야 합니다");

        // **건수만으로는 사용자가 할 수 있는 일이 없습니다.** 어느 책이 빠졌는지 알아야
        // 도서관에서 직접 찾아보기라도 할 수 있습니다.
        assertEquals(1, response.droppedBooks().size(), "무엇이 빠졌는지도 말해야 합니다");
        var dropped = response.droppedBooks().get(0);
        assertNotNull(dropped.title());
        assertFalse(dropped.title().isBlank(), "표제가 있어야 알아볼 수 있습니다");
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
                    List.of("978898371189" + (i % 10)), List.of(), 0));
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

    /** 「레미제라블」로 찾으면 붙여 쓴 세트 하나만 걸립니다. 실제 응답을 줄인 것입니다. */
    private static final String LESMIS_SET_ONLY = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[레미제라블 세트 - 전5권]]></bookname>
              <authors><![CDATA[빅토르 위고 지음 ; 정기수 옮김]]></authors>
              <publisher><![CDATA[민음사]]></publisher>
              <publication_year>2012</publication_year>
              <isbn13>9788937486104</isbn13>
            </doc>
          </docs>
        </response>
        """;


    /** 붙여 쓴 표기의 책은 있지만 띄어 쓴 판이 없는 응답. 실제 「레미제라블」 검색의 모양입니다. */
    private static final String LESMIS_SAME_TITLE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[레미제라블]]></bookname>
              <authors><![CDATA[빅토르 위고 지음]]></authors>
              <publisher><![CDATA[웅진씽크빅]]></publisher>
              <publication_year>2010</publication_year>
              <isbn13>9788901109954</isbn13>
              <loan_count>500</loan_count>
            </doc>
          </docs>
        </response>
        """;


    /** 띄어 쓴 표기만 있는 응답. 「레 미제라블」로 찾았을 때의 모양입니다. */
    private static final String SPACED_ONLY = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[레 미제라블]]></bookname>
              <authors><![CDATA[빅토르 위고 지음]]></authors>
              <publisher><![CDATA[삼성출판사]]></publisher>
              <publication_year>2015</publication_year>
              <isbn13>9788915030688</isbn13>
              <loan_count>300</loan_count>
            </doc>
          </docs>
        </response>
        """;

    /** 한 건도 없는 응답. */
    private static final String EMPTY_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs></docs>
        </response>
        """;

    /** 저자로 되찾으면 낱권이 나오는데, 같은 저자의 다른 책도 함께 옵니다. */
    private static final String LESMIS_BY_AUTHOR = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[레 미제라블]]></bookname>
              <authors><![CDATA[빅토르 위고 지음 ; 정기수 옮김]]></authors>
              <publisher><![CDATA[민음사]]></publisher>
              <publication_year>2012</publication_year>
              <isbn13>9788937463013</isbn13>
              <vol>1</vol>
              <loan_count>2000</loan_count>
            </doc>
            <doc>
              <bookname><![CDATA[레 미제라블]]></bookname>
              <authors><![CDATA[빅토르 위고 지음 ; 정기수 옮김]]></authors>
              <publisher><![CDATA[민음사]]></publisher>
              <publication_year>2012</publication_year>
              <isbn13>9788937463020</isbn13>
              <vol>2</vol>
              <loan_count>1500</loan_count>
            </doc>
            <doc>
              <bookname><![CDATA[파리의 노트르담]]></bookname>
              <authors><![CDATA[빅토르 위고 지음 ; 정기수 옮김]]></authors>
              <publisher><![CDATA[민음사]]></publisher>
              <publication_year>2005</publication_year>
              <isbn13>9788937462412</isbn13>
            </doc>
          </docs>
        </response>
        """;

    /** 제목으로 찾을 때와 저자로 찾을 때 서로 다른 응답을 주는 정보나루를 흉내 냅니다. */
    private static BookSearchService recoveringService() {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(
                uri -> uri.toString().contains("author=") ? LESMIS_BY_AUTHOR : LESMIS_SET_ONLY,
                "테스트키", budget);
        return new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
    }

    /**
     * 정보나루의 제목 매칭이 어절의 앞에서부터 맞으므로, 「레미제라블」로는 「레 미제라블」
     * 낱권이 한 권도 걸리지 않습니다. 붙여 쓴 세트만 나오고 사용자는 그것을 「낱권이 없다」로
     * 읽습니다. 공백을 어디에 넣을지는 알 수 없지만 세트를 통해 저자는 알게 되므로,
     * 그것으로 되찾습니다.
     */
    @Test
    @DisplayName("제목으로 못 찾은 판을 저자로 되찾는다")
    void recoversMissedEditionsByAuthor() {
        var response = recoveringService().search("레미제라블");

        assertTrue(response.recoveredByAuthor(), "되찾았다는 사실을 화면에 밝혀야 합니다");
        var titles = response.works().stream().map(BookSearchService.WorkResult::title).toList();
        assertTrue(titles.stream().anyMatch(t -> t.contains("1")),
                "낱권이 목록에 들어와야 합니다: " + titles);
        assertTrue(titles.stream().noneMatch(t -> t.contains("노트르담")),
                "표제가 다른 책까지 끌어오면 안 됩니다: " + titles);
    }

    /**
     * <b>못 찾는 것은 양쪽 방향 모두입니다.</b> 「붙여 쓴 질의일 때만」으로 걸었더니
     * 「레미제라블」은 고쳐졌는데 「레 미제라블」로 찾으면 붙여 쓴 판이 한 건도 나오지
     * 않았습니다. 실측에서 저작 155개 가운데 붙여 쓴 표기가 0개였습니다.
     */
    @Test
    @DisplayName("띄어 쓴 질의에서도 붙여 쓴 판을 되찾는다")
    void recoversForSpacedQueryToo() {
        String spacedOnly = SPACED_ONLY;
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(
                uri -> uri.toString().contains("author=") ? LESMIS_SAME_TITLE : spacedOnly,
                "테스트키", budget);
        var service = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));

        var response = service.search("레 미제라블");

        assertTrue(response.recoveredByAuthor(),
                "띄어 쓴 질의로 찾을 때도 붙여 쓴 판을 되찾아야 합니다");
    }

    /**
     * <b>낱권이 순위에서 밀리면 후보 목록에 들지 못합니다.</b> 표제에 권차를 붙이는 것과
     * 제목이 맞는 것을 위로 올리는 것이 부딪쳐, 실제로 「레미제라블」을 찾았을 때 민음사
     * 낱권이 62~67위였습니다. 후보를 스물넷까지 늘려도 들어오지 못합니다.
     */
    @Test
    @DisplayName("권차가 붙은 낱권도 제목이 맞는 것으로 센다")
    void volumesRankAsExactTitleMatch() {
        var one = new BookSearchService.WorkResult(
                1, "레 미제라블 1권", "빅토르 위고", "민음사", null, null, List.of("9788937463013"), List.of(), 0);
        var other = new BookSearchService.WorkResult(
                2, "레 미제라블", "빅토르 위고", "삼성출판사", null, null, List.of("9788915030688"), List.of(), 0);
        var unrelated = new BookSearchService.WorkResult(
                3, "레미제라블 읽기의 즐거움", "윤순식", "살림", null, null, List.of("9788952204134"), List.of(), 0);

        var ranked = BookSearchService.rank("레미제라블", List.of(unrelated, one, other));

        assertEquals(3, ranked.get(2).workId(), "제목이 덜 맞는 것이 뒤로 가야 합니다");
        assertTrue(ranked.get(0).workId() != 3 && ranked.get(1).workId() != 3,
                "낱권과 권차 없는 판이 나란히 앞자리에 와야 합니다");
    }

    private static BookSearchService.WorkResult work(
            int id, String title, String publisher, String isbn, int loans) {
        return new BookSearchService.WorkResult(
                id, title, "빅토르 위고", publisher, null, null, List.of(isbn), List.of(), loans);
    }

    /**
     * <b>대출건수만으로 세우면 3권 다음에 1권이 나옵니다.</b> 낱권은 권마다 대출건수가
     * 제각각이라 그렇습니다. 사용자에게는 순서가 뒤엉킨 목록으로 보이고, 몇 권이 어디까지
     * 있는지 읽어 낼 수 없습니다.
     */
    @Test
    @DisplayName("한 묶음 안의 낱권은 권차 순으로 나온다")
    void volumesOfOneWorkComeOutInOrder() {
        var ranked = BookSearchService.rank("레미제라블", List.of(
                work(3, "레 미제라블 3권", "민음사", "9788937463033", 40),
                work(1, "레 미제라블 1권", "민음사", "9788937463013", 900),
                work(5, "레 미제라블 5권", "민음사", "9788937463055", 10),
                work(2, "레 미제라블 2권", "민음사", "9788937463022", 300),
                work(4, "레 미제라블 4권", "민음사", "9788937463044", 20)));

        assertEquals(List.of(1, 2, 3, 4, 5), ranked.stream().map(w -> w.workId()).toList(),
                "대출건수가 제각각이어도 1권부터 차례로 나와야 합니다");
    }

    /**
     * <b>표제 키만으로 묶으면 출판사가 번갈아 나옵니다.</b> 「레미제라블」에는 민음사 낱권도
     * 있고 열린책들 낱권도 있는데 표제 키가 같습니다. 그대로 권차 순으로 세우면 민음사 1권,
     * 열린책들 1권, 민음사 2권 순이 되어 지금보다 나빠집니다. 번역이 다르면 읽는 사람에게는
     * 다른 책이라는 규칙을 정렬에서도 지켜야 합니다.
     */
    @Test
    @DisplayName("출판사가 다른 낱권이 서로 끼어들지 않는다")
    void publishersDoNotInterleave() {
        var ranked = BookSearchService.rank("레미제라블", List.of(
                work(11, "레 미제라블 1권", "민음사", "9788937463013", 900),
                work(21, "레 미제라블 1권", "열린책들", "9788932917115", 80),
                work(12, "레 미제라블 2권", "민음사", "9788937463022", 300),
                work(22, "레 미제라블 2권", "열린책들", "9788932917122", 70)));

        assertEquals(List.of(11, 12, 21, 22), ranked.stream().map(w -> w.workId()).toList(),
                "민음사 낱권이 먼저 다 나오고 그다음에 열린책들 낱권이 나와야 합니다");
    }

    /**
     * 찾는 사람은 대개 낱권을 원하고, 세트 ISBN 은 도서관이 낱권으로 등록하는 일이 많아
     * 미소장으로 나오기 쉽습니다. 묶음의 첫 줄은 가장 눈에 띄는 자리라 거기에 헛일이 되기
     * 쉬운 것을 두지 않습니다.
     */
    @Test
    @DisplayName("권차 없는 세트와 합본은 낱권 뒤에 온다")
    void theSetComesAfterItsVolumes() {
        var ranked = BookSearchService.rank("레미제라블", List.of(
                work(9, "레 미제라블", "민음사", "9788937400009", 5000),
                work(2, "레 미제라블 2권", "민음사", "9788937463022", 300),
                work(1, "레 미제라블 1권", "민음사", "9788937463013", 900)));

        assertEquals(List.of(1, 2, 9), ranked.stream().map(w -> w.workId()).toList(),
                "세트가 아무리 많이 대출되었어도 낱권 뒤로 가야 합니다");
    }

    /**
     * <b>묶음 안을 권차로 세운다고 해서 인기 있는 책이 아래로 내려가면 안 됩니다.</b>
     * 묶음 사이의 자리는 그 묶음에서 가장 많이 대출된 판이 정합니다. 낱권 하나가 덜 빌린다고
     * 그 묶음 전체가 내려가면 찾던 책이 목록 아래로 사라집니다.
     */
    @Test
    @DisplayName("많이 빌린 낱권이 있는 묶음이 통째로 앞에 온다")
    void theMorePopularSeriesComesFirst() {
        var ranked = BookSearchService.rank("레미제라블", List.of(
                work(21, "레 미제라블 1권", "열린책들", "9788932917115", 80),
                work(12, "레 미제라블 2권", "민음사", "9788937463022", 5),
                work(11, "레 미제라블 1권", "민음사", "9788937463013", 900),
                work(22, "레 미제라블 2권", "열린책들", "9788932917122", 70)));

        assertEquals(List.of(11, 12, 21, 22), ranked.stream().map(w -> w.workId()).toList(),
                "민음사 2권이 5회뿐이어도 민음사 묶음이 통째로 앞에 있어야 합니다");
    }

    /**
     * 「상·중·하」로 나뉜 책도 권차가 1·2·3 이 되므로 같은 규칙으로 세워집니다.
     * 상·중·하가 글자 순으로 나오면 「상, 중, 하」가 아니라 「상, 하, 중」이 됩니다.
     */
    @Test
    @DisplayName("상·중·하로 나뉜 책도 순서대로 나온다")
    void sangJungHaComesOutInOrder() {
        var ranked = BookSearchService.rank("토지", List.of(
                work(3, "토지 하", "나남", "9788930040033", 100),
                work(1, "토지 상", "나남", "9788930040011", 50),
                work(2, "토지 중", "나남", "9788930040022", 70)));

        assertEquals(List.of(1, 2, 3), ranked.stream().map(w -> w.workId()).toList(),
                "상, 중, 하 순이어야 합니다");
    }

    /**
     * <b>운영 서버에서 실제로 받은 순서로 고정합니다</b>(2026-09-09, 「레미제라블」 검색의
     * 앞 열여섯 개). 표제·출판사·대출건수·ISBN 이 모두 그때 받은 값 그대로입니다.
     *
     * <p>그때의 순서는 민음사 낱권이 <b>1권, 5권, 2권, 3권, 4권</b>이었고 그 사이에 비룡소와
     * 대한교과서와 은하수미디어가 끼어 있었습니다. 상서각은 <b>2권이 1권보다 위</b>였습니다.
     * 대출건수만으로 세웠기 때문인데, 사용자에게는 몇 권이 어디까지 있는지 읽어 낼 수 없는
     * 목록으로 보입니다.
     */
    @Test
    @DisplayName("실제로 받은 「레미제라블」 결과에서 낱권이 출판사별로 모여 순서대로 나온다")
    void realLesMiserablesResultGroupsByPublisherAndVolume() {
        var ranked = BookSearchService.rank("레미제라블", List.of(
                work(1, "레 미제라블 1권", "민음사", "9788937444494", 23165),
                work(2, "레 미제라블 5권", "민음사", "9788937463051", 15545),
                work(3, "레 미제라블", "비룡소", "9788949141121", 14760),
                work(4, "레 미제라블", "대한교과서", "9788937840982", 13973),
                work(5, "레 미제라블 2권", "민음사", "9788937444500", 11601),
                work(6, "레 미제라블", "은하수미디어", "9788965794882", 11429),
                work(7, "레 미제라블 3권", "민음사", "9788937463037", 9301),
                work(8, "레 미제라블 4권", "민음사", "9788937463044", 8688),
                work(9, "레 미제라블", "그레이트북스", "9788927173410", 5153),
                work(10, "레 미제라블 1권", "웅진씽크빅", "9788901114583", 3857),
                work(14, "레 미제라블 2권", "상서각 출판사", "9788974314736", 3123),
                work(15, "레 미제라블 1권", "상서각 출판사", "9788974314729", 2612)));

        var order = ranked.stream().map(w -> w.workId()).toList();

        assertEquals(List.of(1, 5, 7, 8, 2), order.subList(0, 5),
                "민음사 낱권이 1권부터 5권까지 붙어서 차례로 나와야 합니다");
        assertEquals(List.of(3, 4, 6, 9, 10), order.subList(5, 10),
                "그다음은 묶음별 대출건수 순이어야 합니다");
        assertEquals(List.of(15, 14), order.subList(10, 12),
                "상서각은 2권이 아니라 1권이 먼저여야 합니다");
    }

    /**
     * <b>여러 권 확인도 같은 되찾기를 거쳐야 합니다.</b> 예전에는 되찾기가 {@code search}
     * 안에만 있고 {@code worksFor} 는 그냥 받아 왔습니다. 그래서 같은 「레미제라블」인데
     * 한 권 검색에서는 민음사 낱권이 나오고 여러 권 확인에서는 한 권도 나오지 않았습니다.
     * 화면에 따라 결과가 달랐던 것입니다.
     */
    @Test
    @DisplayName("여러 권 확인 경로도 저자로 되찾는다")
    void worksForAlsoRecovers() {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(
                uri -> uri.toString().contains("author=") ? LESMIS_BY_AUTHOR : LESMIS_SAME_TITLE,
                "테스트키", budget);
        var service = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));

        var works = service.worksFor(Data4LibraryClient.BookQuery.byTitle("레미제라블"));
        var titles = works.stream().map(BookSearchService.WorkResult::title).toList();

        assertTrue(titles.stream().anyMatch(t -> t.contains("레 미제라블")),
                "여러 권 확인에서도 띄어 쓴 판이 나와야 합니다: " + titles);
    }

    /**
     * <b>저자를 알아내지 못하면 부르지 않습니다.</b> 0건 검색에서까지 호출이 늘면 하루
     * 예산이 그만큼 빨리 사라집니다.
     */
    @Test
    @DisplayName("한 건도 못 찾았으면 저자로 되찾을 것도 없다")
    void doesNotRecoverWithoutAnyResult() {
        String empty = EMPTY_RESPONSE;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(uri -> {
            calls.incrementAndGet();
            return empty;
        }, "테스트키", budget);
        var service = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));

        var response = service.search("없는책");

        assertEquals(1, calls.get(), "저자를 알 수 없으므로 되찾기를 부르지 않습니다");
        assertFalse(response.recoveredByAuthor());
    }

    /**
     * <b>이 검사가 없어서 한 번 헛짚었습니다.</b> 처음에는 「제목이 그대로 맞은 책이 하나도
     * 없을 때」만 되찾았는데, 배포해 놓고 불러 보니 한 번도 발동하지 않았습니다.
     * 「레미제라블」로 찾으면 그 제목의 책이 웅진씽크빅·가나출판사·어문각 등 열다섯 개나
     * 나옵니다. 없는 것은 「그 제목의 책」이 아니라 <b>띄어 쓴 표기의 판</b>이었습니다.
     */
    @Test
    @DisplayName("같은 제목의 다른 책이 이미 있어도 띄어 쓴 판을 되찾는다")
    void recoversEvenWhenSameTitleExists() {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(
                uri -> uri.toString().contains("author=") ? LESMIS_BY_AUTHOR : LESMIS_SAME_TITLE,
                "테스트키", budget);
        var service = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));

        var response = service.search("레미제라블");

        assertTrue(response.recoveredByAuthor(),
                "제목이 맞는 책이 있어도 띄어 쓴 판은 되찾아야 합니다");
    }

    // ── 띄어쓰기 표기 통일 ─────────────────────────────────────────────

    /** 표기마다 다르게 답하는 정보나루. 실제 「레미제라블」 검색의 모양을 줄인 것입니다. */
    private static Data4LibraryClient.Transport spellingAware(List<String> log) {
        return uri -> {
            String q = java.net.URLDecoder.decode(uri.toString(), java.nio.charset.StandardCharsets.UTF_8);
            log.add(q);
            if (q.contains("author=")) return LESMIS_BY_AUTHOR;          // 민음사 낱권 + 노트르담
            if (q.contains("title=레 미제라블")) return SPACED_ONLY;       // 삼성출판사 「레 미제라블」
            if (q.contains("title=레미제라블")) return LESMIS_SAME_TITLE;  // 웅진 「레미제라블」
            return EMPTY_RESPONSE;
        };
    }

    private static BookSearchService serviceWith(Data4LibraryClient.Transport transport) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        return new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
    }

    private static java.util.Set<String> isbnsOf(BookSearchService.SearchResponse response) {
        return response.works().stream()
                .flatMap(w -> w.isbn13List().stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> titlesOf(BookSearchService.SearchResponse response) {
        return response.works().stream().map(BookSearchService.WorkResult::title).toList();
    }

    /**
     * <b>어느 표기로 넣었는지에 따라 목록이 달라지면 안 됩니다.</b> 정보나루는 어절 단위로
     * 그대로 찾으므로 「레미제라블」과 「레 미제라블」이 다른 검색인데, 예전에는 「레미제라블」로는
     * 삼성출판사의 「레 미제라블」이, 「레 미제라블」로는 웅진의 「레미제라블」이 빠졌습니다.
     * 사용자에게는 그것이 「어떤 때는 있고 어떤 때는 없는 책」으로 보입니다.
     */
    @Test
    @DisplayName("띄어 쓴 검색과 붙여 쓴 검색이 같은 책을 같은 순서로 준다")
    void spacingVariantsGiveTheSameWorks() {
        var compact = serviceWith(spellingAware(java.util.Collections.synchronizedList(new java.util.ArrayList<>()))).search("레미제라블");
        var spaced = serviceWith(spellingAware(java.util.Collections.synchronizedList(new java.util.ArrayList<>()))).search("레 미제라블");

        assertEquals(isbnsOf(spaced), isbnsOf(compact), "표기에 따라 책이 달라지면 안 됩니다");
        assertTrue(isbnsOf(compact).containsAll(List.of(
                "9788901109954", "9788915030688", "9788937463013", "9788937463020")),
                "붙여 쓴 판, 띄어 쓴 판, 저자로 되찾은 낱권이 모두 있어야 합니다: " + isbnsOf(compact));
        assertFalse(isbnsOf(compact).contains("9788937462412"), "표제가 다른 노트르담은 들어오면 안 됩니다");
        assertEquals(titlesOf(spaced), titlesOf(compact), "순서도 같아야 합니다");
    }

    @Test
    @DisplayName("함께 찾아본 다른 표기를 화면에 밝힌다")
    void reportsWhichOtherSpellingsWereSearched() {
        var compact = serviceWith(spellingAware(java.util.Collections.synchronizedList(new java.util.ArrayList<>()))).search("레미제라블");
        var spaced = serviceWith(spellingAware(java.util.Collections.synchronizedList(new java.util.ArrayList<>()))).search("레 미제라블");

        assertEquals(List.of("레 미제라블"), compact.alsoSearchedTitles(),
                "되찾기가 보여 준 띄어 쓴 표기로 다시 찾았어야 합니다");
        assertEquals(List.of("레미제라블"), spaced.alsoSearchedTitles(),
                "띄어 쓴 입력은 붙여 쓴 표기로도 찾았어야 합니다");
        assertTrue(compact.recoveredByAuthor());
        assertTrue(spaced.recoveredByAuthor());
    }

    @Test
    @DisplayName("같은 표기는 한 번만 찾고, 다른 표기는 상한 안에서만 더 찾는다")
    void doesNotSearchTheSameSpellingTwice() {
        var log = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        serviceWith(spellingAware(log)).search("레 미제라블");

        long spacedSearches = log.stream().filter(q -> q.contains("title=레 미제라블")).count();
        long compactSearches = log.stream().filter(q -> q.contains("title=레미제라블")).count();
        assertEquals(1, spacedSearches, log.toString());
        assertEquals(1, compactSearches, log.toString());
        assertEquals(3, log.size(), "입력 그대로 + 붙여 쓴 표기 + 저자 되찾기: " + log);
    }

    @Test
    @DisplayName("결과에서 본 표기 가운데 글자가 같고 띄어쓰기만 다른 것만 다시 찾는다")
    void alternateSpellingsAreRespacingsOnly() {
        List<BookInfo> books = List.of(
                book("레 미제라블"), book("레 미제라블"), book("레미제라블 읽기의 즐거움"),
                book("레  미제라블"), book("코스모스"), book("레미제라블 :특별판"));

        var spellings = BookSearchService.alternateSpellings("레미제라블", books, List.of("레미제라블"));

        assertEquals(List.of("레 미제라블"), spellings,
                "두 번 나온 「레 미제라블」만, 공백 묶음은 하나로 보아야 합니다: " + spellings);
        assertTrue(BookSearchService.alternateSpellings(null, books, List.of()).isEmpty());
        assertTrue(BookSearchService.alternateSpellings("레미제라블", books,
                List.of("레미제라블", "레 미제라블")).isEmpty(), "이미 찾은 표기는 다시 찾지 않습니다");
    }

    private static BookInfo book(String title) {
        return BookInfo.from(Map.of("bookname", title, "isbn13", "9788937463013"));
    }

    // ── 둘째 쪽 ───────────────────────────────────────────────────────

    /** 체크디지트가 맞는 ISBN 을 번호로 만듭니다. */
    private static String isbnNumber(int n) {
        String body = "97800000" + "%04d".formatted(n);
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return body + ((10 - sum % 10) % 10);
    }

    private static String cosmosPage(int from, int count) {
        StringBuilder xml = new StringBuilder("<response><docs>");
        for (int i = from; i < from + count; i++) {
            xml.append("<doc><bookname><![CDATA[코스모스]]></bookname>")
               .append("<authors><![CDATA[칼 세이건 지음]]></authors>")
               .append("<publisher><![CDATA[사이언스북스]]></publisher>")
               .append("<isbn13>").append(isbnNumber(i)).append("</isbn13></doc>");
        }
        return xml.append("</docs></response>").toString();
    }

    /** 쪽 번호에 따라 답하는 정보나루. 저자 되찾기에는 아무것도 주지 않습니다. */
    private static Data4LibraryClient.Transport paged(List<String> log, int firstPageSize) {
        return uri -> {
            String q = uri.toString();
            log.add(q);
            if (q.contains("author=")) return EMPTY_RESPONSE;
            if (q.contains("pageNo=1&")) return cosmosPage(1, firstPageSize);
            if (q.contains("pageNo=2&")) return cosmosPage(1001, 1);
            return EMPTY_RESPONSE;
        };
    }

    /**
     * <b>목록에 없는 판은 소장을 물어보지도 못합니다.</b> 첫 쪽이 가득 찼으면 뒤에 판이 더
     * 있을 수 있고, 그 판만 가진 도서관은 「없음」으로 나갑니다.
     */
    @Test
    @DisplayName("첫 쪽이 가득 찼으면 둘째 쪽까지 받는다")
    void fetchesASecondPageWhenTheFirstIsFull() {
        var log = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        var response = serviceWith(paged(log, Data4LibraryClient.PAGE_SIZE)).search("코스모스");

        assertEquals(Data4LibraryClient.PAGE_SIZE + 1, response.foundBooks());
        assertTrue(log.stream().anyMatch(q -> q.contains("pageNo=2&")), log.toString());
        assertTrue(log.stream().noneMatch(q -> q.contains("pageNo=3&")), "둘째 쪽까지만 받습니다");
    }

    @Test
    @DisplayName("첫 쪽이 덜 찼으면 둘째 쪽을 부르지 않는다")
    void doesNotFetchASecondPageWhenTheFirstIsNotFull() {
        var log = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        var response = serviceWith(paged(log, 5)).search("코스모스");

        assertEquals(5, response.foundBooks());
        assertTrue(log.stream().noneMatch(q -> q.contains("pageNo=2&")), "예산을 헛되이 쓰면 안 됩니다: " + log);
    }

    @Test
    @DisplayName("여러 검색에서 같은 자료가 와도 한 번만 센다")
    void countsEachRecordOnce() {
        // 표기를 달리해 여러 번 찾으면 같은 판이 여러 번 옵니다. 그대로 두면 화면이
        // 「정보나루가 600건을 줬다」고 말하고 군집화에도 같은 ISBN 이 두 번 들어갑니다.
        Data4LibraryClient.Transport always = uri -> uri.toString().contains("author=")
                ? EMPTY_RESPONSE : SPACED_ONLY;
        var response = serviceWith(always).search("레 미제라블");   // 입력 그대로 + 붙여 쓴 표기

        assertEquals(1, response.foundBooks());
        assertEquals(1, response.works().size());
    }

    // ── 저자 띄어쓰기 ───────────────────────────────────────────────────

    /** 저자를 붙여 쓴 서지. 정보나루에 「칼세이건」이라고 등록된 판입니다. */
    private static final String COSMOS_COMPACT_AUTHOR = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[코스모스]]></bookname>
              <authors><![CDATA[칼세이건 지음 ; 홍승수 옮김]]></authors>
              <publisher><![CDATA[사이언스북스]]></publisher>
              <publication_year>2004</publication_year>
              <isbn13>9788970000015</isbn13>
              <loan_count>50</loan_count>
            </doc>
          </docs>
        </response>
        """;

    /** 제목만으로 찾으면 오는 것. 찾는 저자의 책과 같은 제목의 다른 책이 섞여 있습니다. */
    private static final String COSMOS_BY_TITLE = """
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
              <bookname><![CDATA[코스모스]]></bookname>
              <authors><![CDATA[김철수 글]]></authors>
              <publisher><![CDATA[삼성당]]></publisher>
              <publication_year>2005</publication_year>
              <isbn13>9788970000022</isbn13>
            </doc>
          </docs>
        </response>
        """;

    private static String decoded(java.net.URI uri) {
        return java.net.URLDecoder.decode(uri.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Data4LibraryClient.BookQuery byAuthor(String author) {
        return new Data4LibraryClient.BookQuery(null, author, null, null, false);
    }

    /**
     * 정보나루는 저자도 어절 단위로 그대로 찾습니다. 「칼 세이건」으로 넣으면 「칼세이건」이라고
     * 등록된 판은 걸리지 않습니다. 제목과 같은 병이라 같은 약을 씁니다.
     */
    @Test
    @DisplayName("띄어 쓴 저자로 찾으면 붙여 쓴 저자 표기로도 함께 찾는다")
    void searchesCompactAuthorToo() {
        List<String> log = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Data4LibraryClient.Transport transport = uri -> {
            String q = decoded(uri);
            log.add(q);
            if (q.contains("author=칼세이건")) return COSMOS_COMPACT_AUTHOR;
            if (q.contains("author=칼 세이건")) return TWO_BOOKS;
            return EMPTY_RESPONSE;
        };

        var response = serviceWith(transport).search(byAuthor("칼 세이건"));

        assertTrue(isbnsOf(response).contains("9788970000015"), "붙여 쓴 판도 들어와야 합니다");
        assertTrue(isbnsOf(response).contains("9788983711892"), "입력 그대로의 결과는 그대로입니다");
        assertEquals(List.of("칼세이건"), response.alsoSearchedAuthors(),
                "함께 찾은 저자 표기를 화면에 밝혀야 합니다");
        assertTrue(log.stream().noneMatch(q -> q.contains("author=칼세 이건")),
                "띄어 쓴 이름은 자리를 옮겨 보지 않습니다: " + log);
    }

    /**
     * <b>저자만 붙여 쓰면 결과가 아예 없었습니다.</b> 「칼세이건」은 정보나루의 어느 어절과도
     * 맞지 않고, 제목이 없으니 되찾을 실마리도 없습니다. 공백을 어디에 넣어야 하는지는 알 수
     * 없지만 자리는 이름 길이만큼뿐이라 전부 물어보고, 저자가 실제로 맞는 것만 더합니다.
     */
    @Test
    @DisplayName("붙여 쓴 저자만으로 찾으면 띄어쓰기 자리를 옮겨 가며 찾는다")
    void respacesCompactAuthorWhenSearchingByAuthorOnly() {
        List<String> log = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Data4LibraryClient.Transport transport = uri -> {
            String q = decoded(uri);
            log.add(q);
            return q.contains("author=칼 세이건") ? TWO_BOOKS : EMPTY_RESPONSE;
        };

        var response = serviceWith(transport).search(byAuthor("칼세이건"));

        assertTrue(isbnsOf(response).contains("9788983711892"),
                "띄어 쓴 표기로 등록된 책이 나와야 합니다");
        assertEquals(List.of("칼 세이건"), response.alsoSearchedAuthors(),
                "책을 데려온 표기만 밝힙니다. 아무것도 못 찾은 「칼세 이건」은 싣지 않습니다");
        assertTrue(log.stream().anyMatch(q -> q.contains("author=칼세 이건"))
                && log.stream().anyMatch(q -> q.contains("author=칼세이 건")),
                "어디서 띄는지 모르므로 자리를 전부 물어봅니다: " + log);
        assertTrue(log.stream().noneMatch(q -> q.contains("title=")),
                "제목이 없으니 제목으로 찾을 것은 없습니다: " + log);
    }

    /**
     * 제목과 저자를 함께 넣었으면 <b>제목이 실마리입니다.</b> 저자 없이 제목만으로 찾아,
     * 저자 표기의 공백과 구두점을 지운 것이 맞는 책만 더합니다. 그렇게 알게 된 「칼 세이건」
     * 표기로 한 번 더 찾아 첫 쪽에 들지 못한 판까지 데려옵니다.
     */
    @Test
    @DisplayName("제목과 붙여 쓴 저자를 함께 넣으면 제목만으로 찾아 저자가 맞는 것만 더한다")
    void recoversByTitleWhenAuthorSpacingDiffers() {
        List<String> log = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Data4LibraryClient.Transport transport = uri -> {
            String q = decoded(uri);
            log.add(q);
            if (q.contains("author=칼 세이건")) return TWO_BOOKS;      // 되찾은 표기로 다시 찾으면 특별판까지
            if (q.contains("author=")) return EMPTY_RESPONSE;         // 「칼세이건」은 어느 어절과도 맞지 않습니다
            if (q.contains("title=코스모스")) return COSMOS_BY_TITLE; // 제목만으로는 김철수의 「코스모스」도 옵니다
            return EMPTY_RESPONSE;
        };

        var response = serviceWith(transport).search(
                new Data4LibraryClient.BookQuery("코스모스", "칼세이건", null, null, false));

        var isbns = isbnsOf(response);
        assertTrue(isbns.contains("9788983711892"), "제목으로 되찾은 칼 세이건의 책이 있어야 합니다");
        assertFalse(isbns.contains("9788970000022"), "저자가 다른 「코스모스」를 끌어오면 안 됩니다");
        assertTrue(isbns.contains("9791158510015"),
                "되찾은 「칼 세이건」 표기로 다시 찾아 특별판까지 데려와야 합니다");
        assertEquals(List.of("칼 세이건"), response.alsoSearchedAuthors());
        assertTrue(log.stream().noneMatch(q -> q.contains("author=칼세 이건")),
                "제목이 있으면 자리를 옮겨 볼 필요가 없습니다: " + log);
    }

    @Test
    @DisplayName("여러 권 확인 경로도 저자 표기가 달라도 같은 책을 찾는다")
    void worksForToleratesAuthorSpacing() {
        Data4LibraryClient.Transport transport = uri -> {
            String q = decoded(uri);
            if (q.contains("author=")) return EMPTY_RESPONSE;
            return q.contains("title=코스모스") ? COSMOS_BY_TITLE : EMPTY_RESPONSE;
        };

        var works = serviceWith(transport).worksFor(
                new Data4LibraryClient.BookQuery("코스모스", "칼세이건", null, null, false));

        assertEquals(1, works.size(), "칼 세이건의 「코스모스」 하나만 나와야 합니다: " + works);
        assertEquals(List.of("9788983711892"), works.get(0).isbn13List());
    }

    @Test
    @DisplayName("띄어쓰기 자리는 붙여 쓴 네 음절 이상의 한글 이름에서만 옮겨 본다")
    void respacesOnlyCompactHangulNames() {
        assertEquals(List.of("칼 세이건", "칼세 이건", "칼세이 건"),
                BookSearchService.spacedAuthorGuesses("칼세이건"));
        assertEquals(List.of(), BookSearchService.spacedAuthorGuesses("김영하"),
                "세 음절은 대개 한국 이름이라 옮길 자리가 없습니다");
        assertEquals(List.of(), BookSearchService.spacedAuthorGuesses("칼 세이건"),
                "이미 띄어 쓴 이름은 그대로 찾습니다");
        assertEquals(List.of(), BookSearchService.spacedAuthorGuesses("J.K.롤링"),
                "로마자가 섞이면 어디서 띄는지 이름마다 달라 옮겨 보지 않습니다");
        assertEquals(List.of(), BookSearchService.spacedAuthorGuesses("알렉산드르솔제니친전집"),
                "여덟 음절을 넘으면 세 어절이라 한 자리만 옮겨서는 맞지 않습니다");
        assertEquals(List.of(), BookSearchService.spacedAuthorGuesses(null));
    }

    @Test
    @DisplayName("결과에서 본 저자 표기 가운데 글자가 같고 띄어쓰기만 다른 것만 다시 찾는다")
    void picksAlternateAuthorSpellingsFromResults() {
        var sagan = BookInfo.from(Map.of("bookname", "코스모스", "authors", "칼 세이건 지음 ; 홍승수 옮김"));
        var inverted = BookInfo.from(Map.of("bookname", "콘택트", "authors", "세이건, 칼 지음"));

        assertEquals(List.of("칼 세이건"),
                BookSearchService.alternateAuthorSpellings("칼세이건", List.of(sagan, inverted), List.of("칼세이건")),
                "성과 이름을 뒤집은 표기는 같은 글자가 아니므로 다시 찾지 않습니다");
        assertEquals(List.of(),
                BookSearchService.alternateAuthorSpellings("칼 세이건", List.of(sagan), List.of("칼 세이건", "칼세이건")),
                "이미 찾아본 표기는 다시 찾지 않습니다");
        assertEquals(List.of(), BookSearchService.alternateAuthorSpellings(null, List.of(sagan), List.of()));
    }

    // ── 소장 조회의 상한과 기준 날짜 ─────────────────────────────────────

    @Test
    @DisplayName("판본이 상한을 넘으면 앞의 것만 묻고 빠짐없이 확인했다고 하지 않는다")
    void tooManyEditionsAreTruncatedNotRefused() {
        // 예전에는 400 으로 거절했고, 화면은 그것을 「확인 불가」로 그렸습니다. 판본이 많은
        // 책일수록 아무것도 알려 주지 못한 셈입니다.
        var asked = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var service = service((isbn, region) -> {
            asked.add(isbn);
            return isbn.equals("E01") ? List.of("111001") : List.of();
        });
        List<String> editions = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> "E%02d".formatted(i)).toList();

        var holdings = service.holdingsOf(editions, List.of("11"), List.of("111001"));

        assertEquals(BookSearchService.MAX_EDITIONS_PER_LOOKUP, asked.size());
        assertEquals(List.of("111001"), holdings.libCodes(), "물어본 판본에서 찾았으면 있음입니다");
        assertFalse(holdings.complete(), "묻지 못한 판본이 남았으므로 빠짐없이 확인한 것이 아닙니다");
        assertFalse(holdings.unreadable());
    }

    @Test
    @DisplayName("소장 답의 기준 날짜는 답을 실제로 받은 날이다")
    void asOfComesFromWhenTheAnswerWasFetched() {
        // 캐시에서 나온 답이면 캐시된 날짜입니다. 오늘로 말하면 옛 답이 새 답처럼 읽힙니다.
        var fetched = Instant.parse("2026-09-07T20:00:00Z");   // 한국 시각으로는 9월 8일 새벽
        HoldingsLookup.HoldingsClient client = new HoldingsLookup.HoldingsClient() {
            @Override public List<String> libCodesFor(String isbn13, String regionCode) {
                return List.of("111001");
            }
            @Override public Answer answerFor(String isbn13, String regionCode) {
                return new Answer(List.of("111001"), fetched);
            }
        };
        var service = service(client);

        var holdings = service.holdingsOf(cosmosEditions(service), List.of("11"), List.of("111001"));

        assertEquals(java.time.LocalDate.of(2026, 9, 8), holdings.asOf());
    }

    @Test
    @DisplayName("답을 하나도 못 받았으면 기준 날짜도 없다")
    void noAnswerMeansNoAsOf() {
        var service = service((isbn, region) -> {
            throw new IllegalStateException("정보나루가 응답하지 않습니다");
        });
        var holdings = service.holdingsOf(cosmosEditions(service), List.of("11"), List.of("111001"));

        assertTrue(holdings.unreadable());
        assertNull(holdings.asOf());
    }
}
