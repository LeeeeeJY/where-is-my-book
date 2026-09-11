package kr.wimb.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 붙여넣은 목록을 읽는 규칙.
 *
 * <p>여기서 잘못 읽으면 사용자는 그것을 <b>"이 책이 도서관에 없다"</b>로 읽습니다.
 * 해석 실패와 미소장이 화면에서 같아 보이면 안 되므로, 읽지 못한 줄은 읽지 못했다고
 * 말해야 합니다.
 */
class LineParserTest {

    private static LineParser.ParsedLine one(String line) {
        var parsed = LineParser.parse(List.of(line));
        assertEquals(1, parsed.lines().size());
        return parsed.lines().get(0);
    }

    private static LineParser.Attempt firstAttempt(String line) {
        var parsed = one(line);
        assertTrue(parsed.readable(), "읽지 못했습니다: " + parsed.problem());
        return parsed.attempts().get(0);
    }

    @Test
    @DisplayName("목록 번호와 기호를 떼어 낸다")
    void stripsListMarkers() {
        for (String line : List.of("1. 코스모스", "1) 코스모스", "- 코스모스", "• 코스모스", "  코스모스  ")) {
            assertEquals("코스모스", firstAttempt(line).title(), line);
        }
    }

    @Test
    @DisplayName("제목이 숫자로 시작해도 목록 번호로 착각하지 않는다")
    void doesNotEatNumericTitles() {
        // 「1984. 조지 오웰」의 「1984.」를 목록 번호로 읽으면 책을 통째로 잃습니다.
        assertEquals("1984. 조지 오웰", firstAttempt("1984. 조지 오웰").title());
        assertEquals("1984", firstAttempt("1984").title());
        // 목록 번호가 붙은 경우는 여전히 떼어 냅니다.
        assertEquals("1984", firstAttempt("3. 1984").title());
    }

    @Test
    @DisplayName("ISBN13 과 ISBN10 을 알아본다")
    void readsIsbn() {
        var isbn13 = firstAttempt("9788983711892");
        assertEquals(LineParser.Kind.ISBN, isbn13.kind());
        assertEquals("9788983711892", isbn13.isbn13());

        // 하이픈이 섞여 있어도 읽습니다.
        assertEquals("9788983711892", firstAttempt("978-89-8371-189-2").isbn13());

        // ISBN10 은 13자리로 바꿔 둡니다. 소장 조회가 13자리를 받기 때문입니다.
        var isbn10 = firstAttempt("8983920777");
        assertEquals(LineParser.Kind.ISBN, isbn10.kind());
        assertTrue(isbn10.isbn13().startsWith("978"), isbn10.isbn13());
        assertTrue(isbn10.explanation().contains("13자리"), isbn10.explanation());
    }

    @Test
    @DisplayName("체크디지트가 틀린 숫자를 제목으로 넘기지 않는다")
    void badIsbnIsReportedNotSearched() {
        // 숫자 나열을 제목으로 찾으면 결과가 0건인데, 사용자는 그것을 "이 책이 없다"로
        // 읽습니다. 읽지 못했다고 말해야 고칠 수 있습니다.
        var line = one("9788983711893");
        assertFalse(line.readable());
        assertTrue(line.problem().contains("체크디지트"), line.problem());
    }

    @Test
    @DisplayName("서점 주소에서 ISBN 을 뽑는다")
    void extractsIsbnFromUrl() {
        var attempt = firstAttempt("https://www.aladin.co.kr/shop/wproduct.aspx?ISBN=9788983711892");
        assertEquals(LineParser.Kind.ISBN, attempt.kind());
        assertEquals("9788983711892", attempt.isbn13());
    }

    @Test
    @DisplayName("ISBN 이 없는 주소는 읽지 못했다고 말한다")
    void urlWithoutIsbnIsUnreadable() {
        var line = one("https://www.yes24.com/Product/Goods/12345678");
        assertFalse(line.readable());
        assertTrue(line.problem().contains("ISBN"), line.problem());
    }

    @Test
    @DisplayName("줄 전체를 제목으로 먼저 보고, 나누는 것은 그다음이다")
    void wholeLineFirstThenSplit() {
        var line = one("코스모스 - 칼 세이건");
        assertEquals(3, line.attempts().size());

        var first = line.attempts().get(0);
        assertEquals(LineParser.Kind.TITLE, first.kind());
        assertEquals("코스모스 - 칼 세이건", first.title(), "먼저 줄 전체를 제목으로 봅니다");

        var second = line.attempts().get(1);
        assertEquals(LineParser.Kind.TITLE_AUTHOR, second.kind());
        assertEquals("코스모스", second.title());
        assertEquals("칼 세이건", second.author());
    }

    /**
     * <b>구분자 뒤에 오는 말이 저자인지 출판사인지는 줄만 보고 알 수 없습니다.</b>
     * 「마의 산 - 토마스 만」과 「마의 산 - 을유문화사」는 생김새가 같습니다. 그래서 여기서
     * 가르지 않고 둘 다 만들고, {@code MultiCheckService} 가 조회 결과의 점수로 고릅니다.
     */
    @Test
    @DisplayName("나눈 줄은 저자로도 출판사로도 읽어 둔다")
    void splitLineIsReadBothAsAuthorAndAsPublisher() {
        var line = one("마의 산 - 을유문화사");

        var asPublisher = line.attempts().get(2);
        assertEquals(LineParser.Kind.TITLE_PUBLISHER, asPublisher.kind());
        assertEquals("마의 산", asPublisher.title());
        assertEquals("을유문화사", asPublisher.publisher());
        assertNull(asPublisher.author(), "출판사로 읽은 해석에는 저자가 없습니다");

        // 저자로 읽은 해석이 먼저입니다. 붙여 넣는 목록은 저자를 적은 쪽이 훨씬 흔합니다.
        assertEquals(LineParser.Kind.TITLE_AUTHOR, line.attempts().get(1).kind());
        assertNull(line.attempts().get(1).publisher());
    }

    @Test
    @DisplayName("구분자가 없는 줄은 해석을 늘리지 않는다")
    void plainTitleGetsOnlyOneAttempt() {
        // 해석이 곧 정보나루 호출입니다. 제목만 적은 줄에 출판사 해석까지 붙으면
        // 아무것도 얻지 못한 채 호출만 두 배가 됩니다.
        assertEquals(1, one("불안의 책").attempts().size());
    }

    @Test
    @DisplayName("쉼표가 들어간 제목을 성급하게 가르지 않는다")
    void commaInTitleIsNotSplitFirst() {
        // 「82년생 김지영, 그 후」를 먼저 가르면 「82년생 김지영」과 「그 후」가 되어
        // 엉뚱한 책을 찾습니다. 줄 전체를 먼저 보기 때문에 그렇게 되지 않습니다.
        var line = one("82년생 김지영, 그 후");
        assertEquals("82년생 김지영, 그 후", line.attempts().get(0).title());
    }

    @Test
    @DisplayName("마지막 구분자에서 나눈다")
    void splitsAtLastSeparator() {
        var line = one("총, 균, 쇠 - 재레드 다이아몬드");
        var split = line.attempts().get(1);
        assertEquals("총, 균, 쇠", split.title());
        assertEquals("재레드 다이아몬드", split.author());
    }

    @Test
    @DisplayName("어떻게 읽었는지 사람이 읽을 수 있게 남긴다")
    void explainsItself() {
        assertTrue(firstAttempt("코스모스").explanation().contains("코스모스"));
        assertTrue(firstAttempt("9788983711892").explanation().contains("9788983711892"));

        var split = one("코스모스 - 칼 세이건").attempts().get(1);
        assertTrue(split.explanation().contains("코스모스") && split.explanation().contains("칼 세이건"),
                split.explanation());
    }

    @Test
    @DisplayName("같은 책을 가리키는 줄을 합치고, 어느 줄이 합쳐졌는지 남긴다")
    void mergesDuplicates() {
        var parsed = LineParser.parse(List.of("코스모스", "1. 코스 모스", "사피엔스"));
        assertEquals(2, parsed.lines().size(), "띄어쓰기만 다른 줄은 같은 책입니다");

        var cosmos = parsed.lines().get(0);
        assertEquals(1, cosmos.lineNo());
        assertEquals(List.of(2), cosmos.mergedFrom(), "합쳐진 줄 번호를 남겨야 합니다");
    }

    @Test
    @DisplayName("읽지 못한 줄은 합치지 않는다")
    void doesNotMergeUnreadableLines() {
        // 고쳐야 할 줄이 조용히 사라지면 사용자는 무엇을 고칠지 알 수 없습니다.
        var parsed = LineParser.parse(List.of("9788983711893", "9788983711893"));
        assertEquals(2, parsed.lines().size());
        assertTrue(parsed.lines().stream().noneMatch(LineParser.ParsedLine::readable));
    }

    @Test
    @DisplayName("빈 줄은 세지 않고 줄 번호도 밀리지 않는다")
    void skipsBlankLines() {
        var parsed = LineParser.parse(List.of("코스모스", "", "   ", "사피엔스"));
        assertEquals(2, parsed.lines().size());
        assertEquals(1, parsed.lines().get(0).lineNo());
        assertEquals(2, parsed.lines().get(1).lineNo());
    }

    @Test
    @DisplayName("50줄을 넘으면 잘라 내되 잘랐다고 알린다")
    void truncatesBeyondLimit() {
        List<String> many = IntStream.rangeClosed(1, 60).mapToObj(i -> "책 " + i).toList();
        var parsed = LineParser.parse(many);
        assertEquals(LineParser.MAX_LINES, parsed.lines().size());
        assertTrue(parsed.truncated(), "잘라 낸 사실을 숨기면 안 됩니다");
    }

    @Test
    @DisplayName("번호만 있는 줄은 읽지 못했다고 말한다")
    void markerOnlyLineIsUnreadable() {
        var line = one("3.");
        assertFalse(line.readable());
        assertNotNull(line.problem());
    }
}
