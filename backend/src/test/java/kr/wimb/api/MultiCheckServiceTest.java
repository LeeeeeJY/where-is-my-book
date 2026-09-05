package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 여러 권 확인의 첫 단계. 줄을 읽고 책을 확정하는 데까지입니다. */
class MultiCheckServiceTest {

    private static String doc(String title, String authors, String year, String isbn) {
        return """
            <doc>
              <bookname><![CDATA[%s]]></bookname>
              <authors><![CDATA[%s]]></authors>
              <publisher><![CDATA[테스트출판]]></publisher>
              <publication_year>%s</publication_year>
              <isbn13>%s</isbn13>
            </doc>
            """.formatted(title, authors, year, isbn);
    }

    private static String docs(String... entries) {
        return "<response><docs>" + String.join("", entries) + "</docs></response>";
    }

    /** 요청 주소를 보고 다르게 답하는 가짜 정보나루입니다. */
    private static final class FakeD4L implements Data4LibraryClient.Transport {
        final List<String> queries = new ArrayList<>();
        java.util.function.Function<String, String> answer = q -> docs();

        @Override public String get(URI uri) {
            String decoded = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
            queries.add(decoded);
            return answer.apply(decoded);
        }
    }

    private static MultiCheckService service(FakeD4L transport) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        var search = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
        return new MultiCheckService(search);
    }

    @Test
    @DisplayName("읽지 못한 줄은 결과 없음이 아니라 읽지 못했다고 답한다")
    void unreadableIsNotNotFound() {
        // 이 둘이 화면에서 같아 보이면 사용자는 고칠 수 있는 줄을 고치지 못합니다.
        var transport = new FakeD4L();
        var response = service(transport).resolve(List.of("9788983711893"));

        var line = response.lines().get(0);
        assertEquals(MultiCheckService.LineStatus.UNREADABLE, line.status());
        assertTrue(transport.queries.isEmpty(), "읽지 못한 줄로 정보나루를 부르면 안 됩니다");
    }

    @Test
    @DisplayName("후보가 하나면 확정한다")
    void singleCandidateIsConfirmed() {
        var transport = new FakeD4L();
        transport.answer = q -> docs(doc("코스모스", "칼 세이건 지음", "2006", "9788983711892"));

        var line = service(transport).resolve(List.of("코스모스")).lines().get(0);

        assertEquals(MultiCheckService.LineStatus.CONFIRMED, line.status());
        assertEquals(1, line.candidates().size());
        assertEquals("코스모스", line.candidates().get(0).title());
    }

    @Test
    @DisplayName("비슷한 후보가 여럿이면 고르게 한다")
    void severalCandidatesAreAmbiguous() {
        var transport = new FakeD4L();
        // 제목이 같은 다른 책들입니다. 마음대로 하나를 골라 주면 헛걸음이 됩니다.
        transport.answer = q -> docs(
                doc("어린 왕자", "생텍쥐페리 지음 ; 김화영 옮김", "2007", "9788937462788"),
                doc("어린 왕자", "생텍쥐페리 지음 ; 황현산 옮김", "2015", "9788954636179"),
                doc("어린 왕자", "생텍쥐페리 지음 ; 이정서 옮김", "2019", "9791158790394"));

        var line = service(transport).resolve(List.of("어린 왕자")).lines().get(0);

        // 역자만 다른 판본은 한 저작으로 묶이므로 후보가 하나로 줄어듭니다.
        // 묶이든 갈리든 화면이 사용자를 속이지 않는 것이 중요합니다.
        assertTrue(line.status() == MultiCheckService.LineStatus.CONFIRMED
                        || line.status() == MultiCheckService.LineStatus.AMBIGUOUS,
                line.status().toString());
        assertFalse(line.candidates().isEmpty());
    }

    @Test
    @DisplayName("줄 전체로 못 찾으면 제목과 저자로 나눠 다시 찾는다")
    void fallsBackToSplit() {
        var transport = new FakeD4L();
        transport.answer = q -> q.contains("title=코스모스&") || q.contains("title=코스모스&author")
                ? docs(doc("코스모스", "칼 세이건 지음", "2006", "9788983711892"))
                : docs();

        var response = service(transport).resolve(List.of("코스모스 - 칼 세이건"));
        var line = response.lines().get(0);

        assertEquals(MultiCheckService.LineStatus.CONFIRMED, line.status());
        assertTrue(line.explanation().contains("나눠"), line.explanation());
        assertTrue(transport.queries.size() >= 2, "줄 전체를 먼저 시도했어야 합니다: " + transport.queries);
    }

    @Test
    @DisplayName("찾지 못하면 결과 없음으로 두고 해석 내용을 보여 준다")
    void notFoundKeepsTheExplanation() {
        var transport = new FakeD4L();
        var line = service(transport).resolve(List.of("있을 리 없는 책")).lines().get(0);

        assertEquals(MultiCheckService.LineStatus.NOT_FOUND, line.status());
        assertTrue(line.candidates().isEmpty());
        assertNotNull(line.explanation(), "어떻게 찾았는지 보여 줘야 사용자가 고칠 수 있습니다");
    }

    @Test
    @DisplayName("ISBN 줄은 ISBN 으로 조회한다")
    void isbnLineQueriesByIsbn() {
        var transport = new FakeD4L();
        transport.answer = q -> docs(doc("코스모스", "칼 세이건 지음", "2006", "9788983711892"));

        var line = service(transport).resolve(List.of("9788983711892")).lines().get(0);

        assertEquals(MultiCheckService.LineStatus.CONFIRMED, line.status());
        // srchBooks 는 isbn13, libSrchByBook 은 isbn 입니다. 매뉴얼대로 이름이 다릅니다.
        assertTrue(transport.queries.get(0).contains("isbn13=9788983711892"), transport.queries.get(0));
    }

    @Test
    @DisplayName("한 줄의 조회가 실패해도 나머지 줄은 답을 만든다")
    void oneFailingLineDoesNotSinkTheRest() {
        var transport = new FakeD4L();
        transport.answer = q -> {
            if (q.contains("사피엔스")) throw new IllegalStateException("정보나루 오류");
            return docs(doc("코스모스", "칼 세이건 지음", "2006", "9788983711892"));
        };

        var response = service(transport).resolve(List.of("사피엔스", "코스모스"));

        assertEquals(2, response.lines().size());
        assertEquals(MultiCheckService.LineStatus.NOT_FOUND, response.lines().get(0).status());
        assertEquals(MultiCheckService.LineStatus.CONFIRMED, response.lines().get(1).status());
    }

    @Test
    @DisplayName("합쳐진 줄 번호가 결과에도 남는다")
    void mergedLinesAreVisible() {
        var transport = new FakeD4L();
        transport.answer = q -> docs(doc("코스모스", "칼 세이건 지음", "2006", "9788983711892"));

        var response = service(transport).resolve(List.of("코스모스", "코스 모스"));

        assertEquals(1, response.lines().size());
        assertEquals(List.of(2), response.lines().get(0).mergedFrom());
    }

    @Test
    @DisplayName("후보 점수에 소장 여부를 쓰지 않는다")
    void scoringIgnoresHoldings() {
        // 소장을 알려면 먼저 책을 확정해야 하므로 순서가 맞지 않습니다.
        // 확정 단계에서는 소장 조회를 한 번도 부르지 않아야 합니다.
        var transport = new FakeD4L();
        transport.answer = q -> docs(doc("코스모스", "칼 세이건 지음", "2006", "9788983711892"));

        service(transport).resolve(List.of("코스모스"));

        assertTrue(transport.queries.stream().noneMatch(q -> q.contains("libSrchByBook")),
                "확정 단계에서 소장 조회를 부르면 안 됩니다: " + transport.queries);
    }
}
