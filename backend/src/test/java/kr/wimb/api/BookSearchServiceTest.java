package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.ApiBudget;
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

    private static Data4LibraryClient client() {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        return new Data4LibraryClient(uri -> TWO_BOOKS, "테스트키", budget);
    }

    private static BookSearchService service(HoldingsLookup.HoldingsClient holdings) {
        return new BookSearchService(client(), new HoldingsLookup(
                holdings, HoldingsLookup.RegionModeStore.documented()));
    }

    /** 불리면 실패하는 조회. 조회가 일어나지 않아야 하는 경우를 검사할 때 씁니다. */
    private static final HoldingsLookup.HoldingsClient NEVER_CALLED = (isbn, region) -> {
        throw new AssertionError("조회하지 않아야 하는데 불렸습니다: " + isbn + " / " + region);
    };

    @Test
    @DisplayName("고른 도서관이 없으면 조회하지 않고, 미소장이라고도 하지 않는다")
    void noSelectionIsNotAbsence() {
        var response = service(NEVER_CALLED).search("코스모스", List.of(), List.of());

        assertFalse(response.works().isEmpty());
        for (var work : response.works()) {
            assertTrue(work.holdingLibCodes().isEmpty());
            assertTrue(work.holdingsComplete(), "물어볼 필요가 없었으므로 미확인이 아닙니다");
            assertFalse(work.holdingsUnreadable());
        }
    }

    @Test
    @DisplayName("고른 도서관의 지역을 하나도 모르면 미소장이 아니라 확인 불가다")
    void unknownRegionIsNotAbsence() {
        // libSrchByBook 은 region 이 필수라 지역을 모르면 조회를 시작할 수조차 없습니다.
        // 이때 빈 결과를 돌려주면 화면이 "고른 도서관에는 없습니다"로 그립니다.
        var response = service(NEVER_CALLED).search("코스모스", List.of(), List.of("111001"));

        assertFalse(response.allHoldingsChecked(), "확인하지 못했다는 것이 응답에 드러나야 합니다");
        for (var work : response.works()) {
            assertTrue(work.holdingsUnreadable(), "확인 불가로 표시해야 합니다");
            assertFalse(work.holdingsComplete());
            assertTrue(work.holdingLibCodes().isEmpty());
        }
    }

    @Test
    @DisplayName("조회가 실패하면 미소장이 아니라 확인 불가다")
    void lookupFailureIsNotAbsence() {
        var response = service((isbn, region) -> {
            throw new IllegalStateException("정보나루가 응답하지 않습니다");
        }).search("코스모스", List.of("11"), List.of("111001"));

        assertFalse(response.allHoldingsChecked());
        for (var work : response.works()) {
            assertTrue(work.holdingsUnreadable());
            assertTrue(work.holdingLibCodes().isEmpty());
        }
    }

    @Test
    @DisplayName("빠짐없이 확인했는데 없으면 그때는 미소장이다")
    void emptyResultAfterFullCheckIsAbsence() {
        var response = service((isbn, region) -> List.of("999999"))
                .search("코스모스", List.of("11"), List.of("111001"));

        assertTrue(response.allHoldingsChecked());
        for (var work : response.works()) {
            assertFalse(work.holdingsUnreadable(), "확인은 했으므로 확인 불가가 아닙니다");
            assertTrue(work.holdingsComplete());
            assertTrue(work.holdingLibCodes().isEmpty(), "고른 도서관에는 없습니다");
        }
    }

    @Test
    @DisplayName("고른 도서관이 소장하면 그 부호만 돌려준다")
    void returnsOnlySelectedLibraries() {
        var response = service((isbn, region) -> List.of("111001", "141053"))
                .search("코스모스", List.of("11"), List.of("111001"));

        assertTrue(response.allHoldingsChecked());
        // 고르지 않은 141053 은 결과에 나오면 안 됩니다.
        for (var work : response.works()) {
            assertEquals(List.of("111001"), work.holdingLibCodes());
        }
    }

    @Test
    @DisplayName("저작에 묶인 판본 전체를 조회한다")
    void queriesEveryEditionOfTheWork() {
        // 판본 하나만 조회하면 도서관이 다른 판을 가지고 있어도 미소장으로 나옵니다.
        var asked = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var response = service((isbn, region) -> {
            asked.add(isbn);
            // 특별판만 소장한 도서관입니다. 초판만 물었다면 놓쳤을 것입니다.
            return isbn.equals("9791158510015") ? List.of("111001") : List.of();
        }).search("코스모스", List.of("11"), List.of("111001"));

        var work = response.works().get(0);
        assertEquals(2, work.isbn13List().size(), "「코스모스」와 「코스모스 (특별판)」은 한 저작입니다");
        assertTrue(asked.containsAll(work.isbn13List()), "묶인 ISBN 을 모두 물었어야 합니다: " + asked);
        assertEquals(List.of("111001"), work.holdingLibCodes());
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

        var response = service.search("코스모스", List.of("11"), List.of("111001"));

        assertFalse(response.works().isEmpty(), "책 정보까지 없애 버리면 안 됩니다");
        for (var work : response.works()) {
            assertTrue(work.holdingsUnreadable());
        }
    }
}
