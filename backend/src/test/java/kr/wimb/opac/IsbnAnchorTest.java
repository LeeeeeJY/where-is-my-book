package kr.wimb.opac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 제목 검색 결과에서 「그 ISBN 이 적힌 항목의 링크」를 고르는 규칙.
 *
 * <p>여기서 틀리면 가는 곳이 <b>제목이 같은 다른 판</b>입니다. 링크가 깨지지도 않고 책 제목도 맞아서
 * 눌러 본 사람조차 알아차리기 어렵습니다. 그래서 「어느 줄인가」를 한 줄씩 못박아 둡니다.
 */
class IsbnAnchorTest {

    private static final Pattern LINK = Pattern.compile("href=\"(/detail/\\d+)\"");

    private static List<IsbnAnchor.Span<String>> links(String html) {
        List<IsbnAnchor.Span<String>> out = new ArrayList<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) out.add(new IsbnAnchor.Span<>(m.start(), m.end(), m.group(1)));
        return out;
    }

    // 동대문·강남·평택 계열(plusSearchResultList)의 모양입니다. 제목 링크가 먼저, ISBN 이 뒤입니다.
    private static final String LINK_THEN_ISBN = """
            <li><a href="/detail/1">미움받을 용기</a><span>ISBN: 9788996991342</span><a href="#">담기</a></li>
            <li><a href="/detail/2">미움받을 용기 2</a><span>ISBN: 9791186560136</span><a href="#">담기</a></li>
            <li><a href="/detail/3">만화 미움받을 용기</a><span>ISBN: 9788901222127</span></li>
            """;

    @Test
    @DisplayName("링크가 ISBN 앞에 오는 목록에서는 그 ISBN 바로 앞의 링크다")
    void picksTheLinkJustBeforeTheIsbn() {
        var second = IsbnAnchor.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9791186560136", true);
        assertEquals("/detail/2", second.payload(), second.how());

        var third = IsbnAnchor.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9788901222127", true);
        assertEquals("/detail/3", third.payload());

        // 첫 줄이라고 특별하지 않습니다. 제목 검색의 첫 줄이 그 책이라는 보장이 없을 뿐입니다.
        assertEquals("/detail/1", IsbnAnchor.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9788996991342", true).payload());
    }

    // 표지 그림의 data-isbn 이 먼저 오고 제목 링크가 뒤에 오는 목록입니다.
    private static final String ISBN_THEN_LINK = """
            <li><img data-isbn="9788996991342"><a href="/detail/1">미움받을 용기</a></li>
            <li><img data-isbn="9791186560136"><a href="/detail/2">미움받을 용기 2</a></li>
            <li><img data-isbn="9788901222127"><a href="/detail/3">만화 미움받을 용기</a></li>
            """;

    @Test
    @DisplayName("링크가 ISBN 뒤에 오는 목록에서는 그 ISBN 바로 뒤의 링크다")
    void picksTheLinkJustAfterTheIsbn() {
        var second = IsbnAnchor.pick(ISBN_THEN_LINK, links(ISBN_THEN_LINK), "9791186560136", false);
        assertEquals("/detail/2", second.payload(), second.how());
        assertEquals("/detail/3", IsbnAnchor.pick(ISBN_THEN_LINK, links(ISBN_THEN_LINK), "9788901222127", false).payload());
    }

    @Test
    @DisplayName("방향을 거꾸로 적으면 윗줄이나 아랫줄 책이 잡힌다 (그래서 규칙에 적고 상세의 ISBN 으로 확인한다)")
    void theWrongDirectionPicksTheNeighbour() {
        // 이것은 지켜야 할 동작이 아니라 **왜 방향을 조사해서 적어야 하는지**의 기록입니다.
        // 글자만 봐서는 이 둘을 가를 수 없습니다. 조사 도구가 도착한 상세에 그 ISBN 이 있는지로 가릅니다.
        var wrong = IsbnAnchor.pick(ISBN_THEN_LINK, links(ISBN_THEN_LINK), "9791186560136", true);
        assertEquals("/detail/1", wrong.payload(), "「앞」으로 적으면 윗줄 책입니다");

        var alsoWrong = IsbnAnchor.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9788996991342", false);
        assertFalse("/detail/1".equals(alsoWrong.payload()), "「뒤」로 적으면 그 책이 아닙니다");
    }

    @Test
    @DisplayName("링크와 ISBN 사이에 다른 책의 ISBN 이 끼어 있으면 그 링크는 버리고 다음 자리로 넘어간다")
    void skipsALinkThatBelongsToAnotherItem() {
        // 표지 주소에 ISBN 이 먼저 나오고, 제목 링크, 그 뒤에 「ISBN: …」 글자가 한 번 더 나옵니다.
        // 첫 자리(표지)를 닻으로 삼으면 바로 앞 링크는 윗줄 책의 것인데, 그 사이에 윗줄 책의 ISBN 이 있습니다.
        String html = """
                <li><img src="/cover/9788996991342"><a href="/detail/1">미움받을 용기</a><span>ISBN: 9788996991342</span></li>
                <li><img src="/cover/9791186560136"><a href="/detail/2">미움받을 용기 2</a><span>ISBN: 9791186560136</span></li>
                """;

        var pick = IsbnAnchor.pick(html, links(html), "9791186560136", true);

        assertEquals("/detail/2", pick.payload(), pick.how());
    }

    @Test
    @DisplayName("링크 글자 안에 그 ISBN 이 들어 있으면 자리와 무관하게 그것이다")
    void aLinkCarryingTheIsbnWins() {
        Pattern call = Pattern.compile("onclick=\"(fnDetail\\([^)]*\\))\"");
        String html = """
                <li><a onclick="fnDetail('11','9788996991342','MO')">미움받을 용기</a></li>
                <li><a onclick="fnDetail('22','9791186560136','MO')">미움받을 용기 2</a></li>
                """;
        List<IsbnAnchor.Span<String>> spans = new ArrayList<>();
        Matcher m = call.matcher(html);
        while (m.find()) spans.add(new IsbnAnchor.Span<>(m.start(), m.end(), m.group(1)));

        // 방향을 어느 쪽으로 적었든 같습니다.
        assertTrue(IsbnAnchor.pick(html, spans, "9791186560136", true).payload().contains("'22'"));
        assertTrue(IsbnAnchor.pick(html, spans, "9791186560136", false).payload().contains("'22'"));
    }

    @Test
    @DisplayName("결과에 그 ISBN 이 없으면 아무것도 고르지 않는다")
    void noAnchorNoPick() {
        // 그 판이 첫 쪽에 없거나, 그 도서관에 없는 판입니다. 첫 줄로 내려가면 다른 판으로 갑니다.
        var pick = IsbnAnchor.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9788937473135", true);

        assertFalse(pick.found());
        assertTrue(pick.how().contains("적혀 있지 않습니다"), pick.how());
    }

    @Test
    @DisplayName("더 긴 숫자의 일부를 ISBN 으로 읽지 않는다")
    void doesNotReadPartOfALongerNumber() {
        // 등록번호나 바코드 안에 ISBN 과 같은 숫자열이 들어 있는 경우입니다.
        String html = "<li><a href=\"/detail/1\">책</a><span>바코드 19788996991342000</span></li>";

        assertFalse(IsbnAnchor.pick(html, links(html), "9788996991342", true).found());
    }

    @Test
    @DisplayName("그 항목에 링크가 없으면 옆 항목의 링크를 빌려 오지 않는다")
    void doesNotBorrowALinkFromTheNextItem() {
        // 둘째 줄에는 상세 링크가 없습니다(전자책처럼 링크 모양이 다른 줄). 「앞」 방향에서 바로 앞
        // 링크는 첫 줄의 것이고, 그 사이에 첫 줄의 ISBN 이 있어 걸러집니다.
        String html = """
                <li><a href="/detail/1">미움받을 용기</a><span>ISBN: 9788996991342</span></li>
                <li><span>미움받을 용기 2 (전자책)</span><span>ISBN: 9791186560136</span></li>
                <li><a href="/detail/3">만화 미움받을 용기</a><span>ISBN: 9788901222127</span></li>
                """;

        var pick = IsbnAnchor.pick(html, links(html), "9791186560136", true);

        assertFalse(pick.found(), String.valueOf(pick.payload()));
    }

    @Test
    @DisplayName("ISBN-13 이 아닌 값으로는 찾지 않는다")
    void rejectsNonIsbn() {
        assertFalse(IsbnAnchor.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "미움받을 용기", true).found());
        assertFalse(IsbnAnchor.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), null, true).found());
    }
}
