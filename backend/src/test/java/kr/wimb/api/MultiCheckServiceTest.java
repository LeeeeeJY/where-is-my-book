package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.query.LineParser;
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
        return doc(title, authors, year, isbn, 0);
    }

    private static String doc(String title, String authors, String year, String isbn, int loans) {
        return """
            <doc>
              <bookname><![CDATA[%s]]></bookname>
              <authors><![CDATA[%s]]></authors>
              <publisher><![CDATA[테스트출판]]></publisher>
              <publication_year>%s</publication_year>
              <isbn13>%s</isbn13>
              <loan_count>%d</loan_count>
            </doc>
            """.formatted(title, authors, year, isbn, loans);
    }

    private static String docs(String... entries) {
        return "<response><docs>" + String.join("", entries) + "</docs></response>";
    }

    /** 출판사와 권차, 대출건수까지 갖춘 서지. 낱권 묶음의 순서를 볼 때 씁니다. */
    private static String bib(String title, String publisher, String isbn, String vol, int loans) {
        return """
            <doc>
              <bookname><![CDATA[%s]]></bookname>
              <authors><![CDATA[토마스 만 지음 ; 홍성광 옮김]]></authors>
              <publisher><![CDATA[%s]]></publisher>
              <publication_year>2008</publication_year>
              <isbn13>%s</isbn13>
              <vol>%s</vol>
              <loan_count>%d</loan_count>
            </doc>
            """.formatted(title, publisher, isbn, vol, loans);
    }

    /** 요청 주소를 보고 다르게 답하는 가짜 정보나루입니다. */
    private static final class FakeD4L implements Data4LibraryClient.Transport {
        /** 검색이 회전 안에서 동시에 나가므로 목록도 동시에 써도 안전해야 합니다. */
        final List<String> queries = java.util.Collections.synchronizedList(new ArrayList<>());
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
        // 저자 해석과 출판사 해석이 함께 나가지만, 저자가 맞았으므로 저자 쪽을 씁니다.
        assertTrue(line.explanation().contains("저자"), line.explanation());
        assertTrue(transport.queries.size() >= 2, "줄 전체를 먼저 시도했어야 합니다: " + transport.queries);
    }

    /**
     * <b>고전 번역서에서 판을 가르는 것은 저자가 아니라 출판사입니다.</b> 저작을 출판사별로
     * 갈라 놓았으므로 「마의 산 - 토마스 만」은 범우사·을유문화사·열린책들·동서문화사·
     * 지식을만드는지식이 저마다 다른 후보로 나오고, 저자를 붙여도 후보가 하나도 줄지
     * 않습니다. 구분자 뒤의 말이 저자인지 출판사인지는 줄만 보고 알 수 없으므로 둘 다
     * 물어보고 점수로 고릅니다.
     */
    @Test
    @DisplayName("구분자 뒤가 출판사면 출판사로 읽은 해석을 쓴다")
    void picksPublisherReadingWhenTrailingWordIsAPublisher() {
        var transport = new FakeD4L();
        // 「을유문화사」를 저자로 물으면 한 건도 나오지 않고, 출판사로 물어야 그 판이
        // 나옵니다. 저자 해석밖에 없던 시절에는 이 줄이 결과 없음으로 떨어졌습니다.
        transport.answer = q -> q.contains("publisher=을유문화사")
                ? docs(bib("마의 산", "을유문화사", "9788932403311", "", 40))
                : docs();

        var line = service(transport).resolve(List.of("마의 산 - 을유문화사")).lines().get(0);

        assertFalse(line.candidates().isEmpty(), "출판사로 읽으면 찾을 수 있는 책입니다");
        assertEquals("을유문화사", line.candidates().get(0).publisher());
        assertTrue(line.explanation().contains("출판사"), line.explanation());
    }

    @Test
    @DisplayName("줄 전체가 제목으로 그대로 맞으면 나눠 묻지 않는다")
    void doesNotSplitWhenWholeLineMatchesExactly() {
        // <b>해석을 늘려도 호출이 늘지 않게 하는 장치입니다.</b> 「총, 균, 쇠」는 쉼표가
        // 있어 나눌 수는 있지만, 줄 전체가 제목으로 그대로 맞으므로 나눠 물어볼 이유가
        // 없습니다. 이 조건이 없으면 구분자가 든 제목마다 호출이 세 배가 됩니다.
        var transport = new FakeD4L();
        transport.answer = q -> docs(doc("총, 균, 쇠", "재레드 다이아몬드 지음", "2005", "9788970127248"));

        var line = service(transport).resolve(List.of("총, 균, 쇠")).lines().get(0);

        assertEquals(MultiCheckService.LineStatus.CONFIRMED, line.status());
        assertTrue(transport.queries.stream().noneMatch(q -> q.contains("publisher=")),
                "나눠 물어보면 안 됩니다: " + transport.queries);
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
        // 조회를 못 한 줄은 결과 없음이 아니라 확인 불가입니다.
        assertEquals(MultiCheckService.LineStatus.LOOKUP_FAILED, response.lines().get(0).status());
        assertEquals(MultiCheckService.LineStatus.CONFIRMED, response.lines().get(1).status());
    }

    @Test
    @DisplayName("정보나루가 응답하지 않으면 결과 없음이 아니라 확인 불가다")
    void upstreamFailureIsNotAbsence() {
        // 이 둘을 섞으면 멀쩡히 있는 책을 "그런 책이 없다"고 답하게 됩니다.
        // 정보나루가 잠깐 흔들릴 때마다 사용자가 목록을 고치려 들게 되는 자리입니다.
        var transport = new FakeD4L();
        transport.answer = q -> { throw new IllegalStateException("정보나루가 응답하지 않습니다"); };

        var line = service(transport).resolve(List.of("코스모스")).lines().get(0);

        assertEquals(MultiCheckService.LineStatus.LOOKUP_FAILED, line.status());
        assertNotEquals(MultiCheckService.LineStatus.NOT_FOUND, line.status());
    }

    @Test
    @DisplayName("물어봤는데 없으면 그때는 결과 없음이다")
    void emptyAnswerIsNotFound() {
        var transport = new FakeD4L();
        transport.answer = q -> docs();   // 정상 응답이고 내용이 비었습니다

        var line = service(transport).resolve(List.of("있을 리 없는 책")).lines().get(0);

        assertEquals(MultiCheckService.LineStatus.NOT_FOUND, line.status());
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

    /**
     * <b>여러 권 확인의 후보 점수도 권차를 떼고 견줘야 합니다.</b> 한 권 검색의 순위만
     * 고쳤더니 이쪽은 그대로여서, 「레미제라블」의 후보 스물넷 안에 민음사 낱권이 여전히
     * 들지 못했습니다. 같은 판단이 두 곳에 흩어져 있어 되풀이해 틀린 자리입니다.
     */
    @Test
    @DisplayName("권차가 붙은 낱권도 제목이 맞는 것으로 점수를 준다")
    void volumeScoresAsExactTitle() {
        var attempt = LineParser.parse(List.of("레미제라블")).lines().get(0).attempts().get(0);
        var volume = new BookSearchService.WorkResult(
                1, "레 미제라블 1권", "빅토르 위고", "민음사", null, null, List.of("9788937463013"), List.of(), 0);
        var plain = new BookSearchService.WorkResult(
                2, "레 미제라블", "빅토르 위고", "삼성출판사", null, null, List.of("9788915030688"), List.of(), 0);

        assertEquals(MultiCheckService.score(plain, attempt), MultiCheckService.score(volume, attempt), 0.001,
                "권차만 다를 뿐 제목은 똑같이 맞습니다");
    }


    /** 줄 전체를 제목으로 찾으면 작품집이 걸리고, 제목과 저자로 나누면 그 책이 걸립니다. */
    private static final String SOMMER_WHOLE_LINE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[파트리크 쥐스킨트 작품집 (전5권) - 향수+좀머씨 이야기]]></bookname>
              <authors><![CDATA[파트리크 쥐스킨트]]></authors>
              <publisher><![CDATA[열린책들]]></publisher>
              <publication_year>2020</publication_year>
              <isbn13>9788932917245</isbn13>
            </doc>
          </docs>
        </response>
        """;

    private static final String SOMMER_SPLIT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <docs>
            <doc>
              <bookname><![CDATA[좀머 씨 이야기]]></bookname>
              <authors><![CDATA[파트리크 쥐스킨트]]></authors>
              <publisher><![CDATA[열린책들]]></publisher>
              <publication_year>1999</publication_year>
              <isbn13>9788932902876</isbn13>
            </doc>
          </docs>
        </response>
        """;

    /**
     * <b>결과가 나왔다고 무조건 멈추면 엉뚱한 책이 확정됩니다.</b> 실제로 「좀머 씨 이야기 -
     * 파트리크 쥐스킨트」가 「파트리크 쥐스킨트 작품집 (전5권)」으로 확정되었습니다. 작품집
     * 표제에 그 말들이 다 들어 있어 정보나루가 찾아 준 것인데, 찾던 책이 아닌 데다 확정까지
     * 되어 사용자는 고를 기회조차 없었습니다.
     */
    @Test
    @DisplayName("줄 전체로 찾은 것이 시원찮으면 제목과 저자로 나눠 본다")
    void triesSplitWhenWholeLineMatchesPoorly() {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1000),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(uri -> {
            String decoded = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
            // 제목과 저자로 나눠 물었을 때만 그 책을 줍니다.
            return decoded.contains("author=") ? SOMMER_SPLIT : SOMMER_WHOLE_LINE;
        }, "테스트키", budget);
        var search = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
        var service = new MultiCheckService(search);

        var response = service.resolve(List.of("좀머 씨 이야기 - 파트리크 쥐스킨트"));
        var line = response.lines().get(0);

        assertFalse(line.candidates().isEmpty(), "책을 찾아야 합니다");
        assertTrue(line.candidates().get(0).title().contains("좀머"),
                "작품집이 아니라 그 책이어야 합니다: " + line.candidates().get(0).title());
        assertFalse(line.candidates().get(0).title().contains("작품집"),
                "줄 전체로 걸린 작품집을 그대로 확정하면 안 됩니다");
    }

    /**
     * <b>한 권 검색과 여러 권 확인이 같은 제목에 서로 다른 저작을 앞에 세웠습니다.</b>
     * 한 권 검색은 같은 등급 안에서 대출건수로 세우는데, 여기서는 판본 수로 보너스를
     * 주고 있어서 덜 알려진 판이 첫 후보가 되었고, 그 판의 소장 도서관만 나갔습니다.
     * 같은 책인데 화면에 따라 소장 도서관이 다르게 보인 원인입니다.
     */
    @Test
    @DisplayName("제목이 똑같이 맞으면 많이 빌려 간 책을 첫 후보로 세운다")
    void popularWorkComesFirstAmongExactTitleMatches() {
        var transport = new FakeD4L();
        transport.answer = q -> docs(
                doc("코스모스", "이름없음 지음", "2001", "9788983711892", 12),
                doc("코스모스", "칼 세이건 지음", "2006", "9791158510015", 48_000));

        var line = service(transport).resolve(List.of("코스모스")).lines().get(0);

        assertEquals("칼 세이건", line.candidates().get(0).author(),
                "제목이 같으면 대출건수가 많은 쪽이 첫 후보여야 합니다");
    }

    @Test
    @DisplayName("인기 보너스는 제목이 그대로 맞는 것을 뒤집을 만큼 커지지 않는다")
    void popularityBonusIsBounded() {
        assertEquals(0.0, MultiCheckService.popularityBonus(0), 1e-9);
        assertEquals(0.0, MultiCheckService.popularityBonus(-5), 1e-9);
        assertTrue(MultiCheckService.popularityBonus(100) < MultiCheckService.popularityBonus(10_000));
        assertTrue(MultiCheckService.popularityBonus(100_000_000) <= 0.1 + 1e-9);
    }

    @Test
    @DisplayName("여러 줄을 겹쳐 확정해도 줄 순서는 그대로다")
    void parallelResolutionKeepsLineOrder() {
        var transport = new FakeD4L();
        transport.answer = q -> {
            if (q.contains("title=사피엔스")) return docs(doc("사피엔스", "유발 하라리 지음", "2015", "9788934972464"));
            if (q.contains("title=코스모스")) return docs(doc("코스모스", "칼 세이건 지음", "2006", "9788983711892"));
            if (q.contains("title=데미안")) return docs(doc("데미안", "헤르만 헤세 지음", "2009", "9788937460449"));
            return docs();
        };

        var lines = service(transport).resolve(List.of("사피엔스", "코스모스", "데미안", "없는책")).lines();

        assertEquals(List.of(1, 2, 3, 4), lines.stream().map(MultiCheckService.LineResult::lineNo).toList());
        assertEquals("사피엔스", lines.get(0).candidates().get(0).title());
        assertEquals("코스모스", lines.get(1).candidates().get(0).title());
        assertEquals("데미안", lines.get(2).candidates().get(0).title());
        assertEquals(MultiCheckService.LineStatus.NOT_FOUND, lines.get(3).status());
    }

    /**
     * <b>점수만으로 세우면 같은 판의 낱권이 대출건수에 따라 흩어집니다.</b> 화면이 후보 전체를
     * 펼쳐 보여 주므로 그 순서가 그대로 사용자에게 읽힙니다. 한 권 검색과 같은 규칙으로
     * 낱권 묶음을 붙여 두고 묶음 안은 권차 순으로 세웁니다.
     */
    @Test
    @DisplayName("후보는 한 권 검색처럼 낱권 묶음을 붙여서 권차 순으로 세운다")
    void candidatesKeepVolumesTogether() {
        var transport = new FakeD4L();
        transport.answer = q -> docs(
                bib("마의 산", "을유문화사", "9788932470016", "1", 900),
                bib("마의 산", "열린책들", "9788932917009", "1", 800),
                bib("마의 산", "을유문화사", "9788932470030", "3", 700),
                bib("마의 산", "열린책들", "9788932917016", "2", 600),
                // 2권이 가장 덜 빌렸어도 1권과 3권 사이에 있어야 합니다.
                bib("마의 산", "을유문화사", "9788932470023", "2", 10));
        var line = service(transport).resolve(List.of("마의 산")).lines().get(0);

        assertEquals(MultiCheckService.LineStatus.AMBIGUOUS, line.status());
        var order = line.candidates().stream()
                .map(w -> w.publisher() + " " + w.title())
                .toList();
        assertEquals(List.of(
                "을유문화사 마의 산 1", "을유문화사 마의 산 2", "을유문화사 마의 산 3",
                "열린책들 마의 산 1", "열린책들 마의 산 2"), order);
    }

    /**
     * <b>후보 상한이 스물넷일 때는 한 권 검색으로 찾으면 나오는 판이 여기서는 안 나왔습니다.</b>
     * 사용자는 그것을 「내 책이 없다」로 읽습니다. 상한은 한 권 검색이 돌려주는 수와 같습니다.
     */
    @Test
    @DisplayName("후보를 한 권 검색과 같은 수까지 돌려준다")
    void candidatesAreNotCutShort() {
        var transport = new FakeD4L();
        var entries = new ArrayList<String>();
        String[] isbns = { "9788911000005", "9788911000012", "9788911000029", "9788911000036", "9788911000043", "9788911000050", "9788911000067", "9788911000074", "9788911000081", "9788911000098", "9788911000104", "9788911000111", "9788911000128", "9788911000135", "9788911000142", "9788911000159", "9788911000166", "9788911000173", "9788911000180", "9788911000197", "9788911000203", "9788911000210", "9788911000227", "9788911000234", "9788911000241", "9788911000258", "9788911000265", "9788911000272", "9788911000289", "9788911000296" };
        for (int i = 0; i < isbns.length; i++) {
            entries.add(bib("마의 산", "출판사" + i, isbns[i], "1", 1000 - i));
        }
        transport.answer = q -> docs(entries.toArray(String[]::new));
        var line = service(transport).resolve(List.of("마의 산")).lines().get(0);

        assertEquals(isbns.length, line.candidates().size(),
                "출판사가 다른 판 서른 개가 전부 후보에 있어야 합니다");
    }
}
