package kr.wimb.data4library;

import kr.wimb.ingest.ApiBudget;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code itemSrch}(매뉴얼 2절)가 돌려주는 <b>실제</b> 응답 모양을 고정합니다.
 *
 * <h2>여기 적힌 XML 은 지어낸 것이 아닙니다</h2>
 *
 * <p>매뉴얼 6~7쪽의 「결과화면」은 글자가 아니라 그림이라 PDF 에서 글로 뽑히지 않습니다.
 * 그림을 꺼내 눈으로 읽어 옮긴 것이 아래 {@link #MANUAL_ITEM_SRCH_XML} 이고,
 * 부천시립상동도서관(141321)의 첫 항목입니다.
 *
 * <h2>이 시험이 막는 것</h2>
 *
 * <p><b>{@code callNumber} 는 잎이 아니라 상자입니다.</b> 매뉴얼의 응답 명세가
 * 「callNumber 도서 청구기호」라고만 적어 두어 그 자리에 문자열이 올 것처럼 읽히는데,
 * 실제로는 자기 글자가 없고 여섯 조각을 자식으로 답니다.
 *
 * <p>그것을 모른 채 자기 글자만 읽던 코드가 있었고, 상자에는 자기 글자가 없으므로
 * <b>모든 도서관에서 언제나 청구기호가 null 이었습니다.</b> 배포된 서버로 도서관 여섯
 * 곳을 물어 여섯 곳 모두 null 인 것을 확인했습니다(2026-09-20). <b>예외도 로그도 나지
 * 않아</b> 「이 도서관은 청구기호를 안 주는구나」로 읽혔고, 문서에도 그렇게 적혀
 * 있었습니다. 값이 비는 것과 못 읽는 것은 다릅니다.
 */
class CatalogPageTest {

    private static final String MANUAL_ITEM_SRCH_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <request><libCode>141321</libCode><type>all</type><pageNo>1</pageNo></request>
          <libNm>부천시립상동도서관</libNm>
          <pageNo>1</pageNo>
          <pageSize>100</pageSize>
          <numFound>181080</numFound>
          <resultNum>1</resultNum>
          <docs>
            <doc>
              <bookname><![CDATA[중학생 일기]]></bookname>
              <authors><![CDATA[박문정 지음;강정희 지도감수;서영희 지도감수]]></authors>
              <publisher><![CDATA[부크크(bookk)]]></publisher>
              <publication_year>2022</publication_year>
              <isbn13>9791137270053</isbn13>
              <set_isbn13/>
              <bookImageURL><![CDATA[https://image.aladin.co.kr/product/28787/2/cover/k632836581_1.jpg]]></bookImageURL>
              <addition_symbol>0</addition_symbol>
              <vol/>
              <class_no>818</class_no>
              <class_nm><![CDATA[문학 > 한국문학 > 르포르타주 및 기타]]></class_nm>
              <callNumbers>
                <callNumber>
                  <separate_shelf_code/>
                  <separate_shelf_name/>
                  <book_code>박36ㅈ</book_code>
                  <shelf_loc_code>AAJ</shelf_loc_code>
                  <shelf_loc_name>[상동도서관]부천작가(1층)</shelf_loc_name>
                  <copy_code/>
                </callNumber>
              </callNumbers>
              <reg_date>2022-05-07</reg_date>
            </doc>
          </docs>
        </response>""";

    @Test
    @DisplayName("callNumber 는 상자다. 자기 글자가 아니라 자식 조각을 읽어야 한다")
    void readsCallNumberAsABox() {
        BookInfo book = onlyBook(MANUAL_ITEM_SRCH_XML);

        assertEquals(1, book.callNumbers().size(), "복본 하나입니다");
        CallNumber call = book.callNumbers().get(0);

        assertEquals("박36ㅈ", call.bookCode());
        assertEquals("AAJ", call.shelfLocCode());
        assertEquals("[상동도서관]부천작가(1층)", call.shelfLocName());
        // 별치와 복본은 빈 태그로 옵니다. 없는 것이지 못 읽은 것이 아닙니다.
        assertNull(call.separateShelfCode());
        assertNull(call.copyCode());
    }

    @Test
    @DisplayName("청구기호는 조립해야 한다. class_no 와 book_code 가 따로 온다")
    void callNumberMustBeAssembled() {
        BookInfo book = onlyBook(MANUAL_ITEM_SRCH_XML);

        // 서가의 「818 박36ㅈ」이 두 필드에 나뉘어 옵니다. 청구기호 문자열을 주는
        // 항목이 응답 어디에도 없으므로, 우리가 이어 붙이는 수밖에 없습니다.
        assertEquals("818", book.classNo());
        assertEquals("박36ㅈ", book.callNumbers().get(0).bookCode());
    }

    @Test
    @DisplayName("itemSrch 는 등록일자를 준다")
    void carriesRegistrationDate() {
        assertEquals("2022-05-07", onlyBook(MANUAL_ITEM_SRCH_XML).regDate());
    }

    /**
     * 복본이 둘인 책이 섞여 있어도 <b>그 뒤의 책이 밀리지 않아야 합니다.</b> 문서 전체를
     * 훑어 자리로 맞추면 두 번째 책이 첫 책의 둘째 복본을 제 것으로 가져가고, 그러면
     * 서가에서 엉뚱한 자리를 알려 줍니다.
     */
    @Test
    @DisplayName("복본이 둘인 책이 있어도 다음 책의 청구기호가 밀리지 않는다")
    void copiesDoNotShiftTheNextBook() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response><numFound>2</numFound><docs>
              <doc>
                <bookname><![CDATA[토지]]></bookname><isbn13>9788937437267</isbn13>
                <class_no>813.6</class_no>
                <callNumbers>
                  <callNumber><book_code>박14ㅌ</book_code><shelf_loc_name>종합자료실</shelf_loc_name></callNumber>
                  <callNumber><book_code>박14ㅌ</book_code><shelf_loc_name>보존서고</shelf_loc_name><copy_code>c.2</copy_code></callNumber>
                </callNumbers>
              </doc>
              <doc>
                <bookname><![CDATA[아몬드]]></bookname><isbn13>9788936434267</isbn13>
                <class_no>813.7</class_no>
                <callNumbers>
                  <callNumber><book_code>손66ㅇ</book_code><shelf_loc_name>종합자료실</shelf_loc_name></callNumber>
                </callNumbers>
              </doc>
            </docs></response>""";

        List<BookInfo> books = catalogPage(xml);

        assertEquals(2, books.get(0).callNumbers().size());
        assertEquals("c.2", books.get(0).callNumbers().get(1).copyCode());

        assertEquals(1, books.get(1).callNumbers().size(), "둘째 책이 첫 책의 복본을 가져가면 안 됩니다");
        assertEquals("손66ㅇ", books.get(1).callNumbers().get(0).bookCode());
    }

    /**
     * 조각이 하나도 없는 상자는 버립니다. 서가에서 어디에 꽂혔는지 말해 주는 것이
     * 없으면 자리를 정할 수 없어, 목록에 넣어 봐야 쓸 데가 없습니다.
     */
    @Test
    @DisplayName("빈 청구기호 상자는 버린다")
    void dropsEmptyCallNumberBoxes() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response><numFound>1</numFound><docs>
              <doc>
                <bookname><![CDATA[이름 없는 책]]></bookname><isbn13>9788937473838</isbn13>
                <class_no>813.7</class_no>
                <callNumbers>
                  <callNumber><separate_shelf_code/><book_code/><copy_code/></callNumber>
                </callNumbers>
              </doc>
            </docs></response>""";

        assertEquals(List.of(), onlyBook(xml).callNumbers());
    }

    /** {@code srchBooks} 처럼 청구기호가 없는 응답에서는 빈 목록입니다. 예외가 아닙니다. */
    @Test
    @DisplayName("청구기호가 없는 응답에서는 빈 목록이다")
    void noCallNumbersIsEmptyNotNull() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response><numFound>1</numFound><docs>
              <doc><bookname><![CDATA[코스모스]]></bookname><isbn13>9788983711892</isbn13>
                <class_no>443.1</class_no></doc>
            </docs></response>""";

        assertEquals(List.of(), onlyBook(xml).callNumbers());
    }

    // ── 거들기 ────────────────────────────────────────────────────────────

    private static BookInfo onlyBook(String xml) {
        List<BookInfo> books = catalogPage(xml);
        assertEquals(1, books.size());
        return books.get(0);
    }

    private static List<BookInfo> catalogPage(String xml) {
        var transport = new FixedTransport(xml);
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneId.of("UTC")));
        return new Data4LibraryClient(transport, "테스트키", budget)
                .catalogPage("141321", null, 1, 100, ApiBudget.Priority.USER);
    }

    private record FixedTransport(String body) implements Data4LibraryClient.Transport {
        @Override public String get(URI uri) { return body; }
    }
}
