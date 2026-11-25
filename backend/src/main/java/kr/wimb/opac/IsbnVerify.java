package kr.wimb.opac;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 후보 줄의 <b>상세 화면에 그 ISBN 이 적혀 있는지</b>로 줄을 고릅니다. {@code @verify:} 규칙이 씁니다.
 *
 * <p>{@link IsbnAnchor} 는 결과 목록에 적힌 ISBN 을 닻으로 삼습니다. 목록에 ISBN 이 어떤 모양으로도 없는
 * OPAC 은 그 길이 없습니다. 유성이 그렇습니다. 줄에 있는 것은 제어번호·등록번호·청구기호뿐이고, 표지
 * 그림도 ISBN 이 아닌 번호입니다({@code docs/가정과-검증상태.md} 2-40). 그런데 줄을 눌러 들어간 상세
 * 화면에는 ISBN 이 글자로 적혀 있습니다. 그래서 서버가 후보 줄의 상세 화면을 <b>받아 보고</b> 그 ISBN 이
 * 적힌 것만 보냅니다. 조사 도구가 닻 규칙을 확인할 때 하던 일을 서버가 누를 때마다 하는 것입니다.
 *
 * <p>여기에는 받아 본 뒤의 판정만 있습니다. 무엇을 몇 번 받는지와 시간의 상한은 {@link DetailResolver}
 * 가 정합니다.
 *
 * <h2>무엇을 「적혀 있다」로 보는가</h2>
 * <ul>
 *   <li><b>13자리 숫자 그대로</b>이고 앞뒤가 숫자가 아닐 때입니다. 하이픈을 넣은 꼴과 10자리 꼴은 보지
 *       않습니다. 2026-11-25 에 받아 본 유성과 경산의 상세 화면은 둘 다 13자리를 그대로 적었습니다
 *       ({@code <div class="marcTxt">9791186560129</br></div>}, {@code ISBN 9788996991342}). 본 적 없는
 *       꼴까지 받아 주면 다른 책이 통과할 자리만 늘어납니다.</li>
 *   <li><b>화면의 글자에 있을 때만</b>입니다({@link RowText#visible}). 속성값과 스크립트는 보지 않습니다.
 *       ISBN 으로 검색해 들어간 상세 화면은 숨은 칸에 그 검색어를 되돌려 적는 일이 흔해서, 속성값까지
 *       보면 어느 후보든 통과합니다.</li>
 * </ul>
 *
 * <h2>이 확인이 가르지 못하는 것</h2>
 * <p><b>그 도서관의 레코드에 적힌 ISBN 이 틀렸으면 틀린 책이 통과합니다.</b> 화명도서관은 「꽃으로 왔다」의
 * 레코드에 「미움받을 용기」의 ISBN 이 붙어 있고, 경산시립도서관은 「미움받을 용기 2」의 상세 화면이
 * 1권의 ISBN 을 적습니다(둘 다 2026-11-25 에 본 것). 이 확인은 「그 도서관이 그 ISBN 으로 등록한 책」을
 * 찾는 것이지 「방문자가 누른 그 책」을 찾는 것이 아닙니다. 정보나루가 그 도서관을 소장처로 답한 근거도
 * 같은 레코드입니다.
 *
 * <p><b>조사 도구({@code scripts/opac-title-probe.py} 의 {@code mentions}·{@code distinct}·
 * {@code verify_pick})와 같은 답을 내야 합니다.</b> {@code IsbnVerifyTest} 와
 * {@code test-opac-title-probe.py} 가 같은 화면에 같은 답을 바랍니다. 한쪽을 고치면 다른 쪽도 고치세요.
 */
final class IsbnVerify {

    private IsbnVerify() {}

    /**
     * 받아 본 후보 하나. {@code page} 는 그 상세 화면의 HTML 이고, 받지 못했으면 {@code null} 입니다.
     * {@code marked} 는 목록의 그 줄에 큰글자책·점자책 표기가 있는지입니다({@code @verify:@plain:} 일 때만
     * 채웁니다. {@link PlainRows}).
     */
    record Seen<T>(T candidate, String page, boolean marked) {
        Seen(T candidate, String page) {
            this(candidate, page, false);
        }
    }

    private static final Pattern ISBN13 = Pattern.compile("97[89]\\d{10}");

    /** 그 화면의 글자에 그 ISBN 이 13자리 그대로 적혀 있는지. */
    static boolean mentions(String detailHtml, String isbn13) {
        if (detailHtml == null || isbn13 == null || !ISBN13.matcher(isbn13).matches()) return false;
        return Pattern.compile("(?<!\\d)" + isbn13 + "(?!\\d)").matcher(RowText.visible(detailHtml)).find();
    }

    /**
     * 규칙이 잡은 자리에서 <b>줄마다 하나씩</b>만 남깁니다. 문서 순서이고, 내용이 같은 자리는 처음 것만
     * 남습니다. 한 줄에 같은 상세로 가는 링크가 둘인 목록이 흔합니다(표지와 제목). 그것을 따로 세면
     * 받아 볼 후보 셋이 한 줄 반으로 끝납니다.
     */
    static <T> List<IsbnAnchor.Span<T>> distinct(List<IsbnAnchor.Span<T>> spans) {
        List<IsbnAnchor.Span<T>> out = new ArrayList<>();
        List<T> seen = new ArrayList<>();
        for (IsbnAnchor.Span<T> span : spans) {
            if (seen.contains(span.payload())) continue;
            seen.add(span.payload());
            out.add(span);
        }
        return out;
    }

    /**
     * 받아 본 후보들 가운데 보낼 것.
     *
     * <p>ISBN 은 앞의 것이 먼저입니다(그 도서관이 실제로 가진 판을 앞에 세워 넘깁니다). 한 ISBN 을 두고는
     * 문서 순서로 앞선 후보가 먼저입니다. <b>어느 후보에도 없으면 첫 후보로 내려가지 않습니다.</b> 첫 줄은
     * 제목이 같은 다른 판일 뿐입니다. 받지 못한 후보({@code page} 가 없는 것)는 「없다」로 치지 않습니다.
     * 부르는 쪽이 그것을 「못 찾음」과 갈라 다룹니다.
     *
     * <p><b>그 ISBN 이 적힌 후보가 여럿이면 큰글자책·점자책 표기가 없는 줄의 후보가 먼저입니다</b>
     * ({@link Seen#marked}). 도서관이 큰글자판을 보통 판의 ISBN 으로 등록해 두면 두 줄의 상세 화면에 같은
     * ISBN 이 적혀 있습니다. 유성도서관의 「82년생 김지영」이 그랬습니다(2026-11-25. 첫 줄이
     * 「[큰글자 도서]」이고 그 상세에도 그 ISBN 이 적혀 있었습니다). 전부 표기 있는 줄이면 앞선 후보입니다.
     */
    static <T> IsbnAnchor.Pick<T> pick(List<Seen<T>> seen, List<String> isbns) {
        if (isbns == null || isbns.isEmpty()) return new IsbnAnchor.Pick<>(null, "확인할 ISBN 이 없습니다");
        for (String isbn : isbns) {
            int first = -1;
            int skipped = 0;
            for (int i = 0; i < seen.size(); i++) {
                if (!mentions(seen.get(i).page(), isbn)) continue;
                if (!seen.get(i).marked()) {
                    return new IsbnAnchor.Pick<>(seen.get(i).candidate(), "후보 " + (i + 1)
                            + "번째의 상세 화면에 그 ISBN 이 적혀 있습니다" + (skipped == 0 ? ""
                            : " (같은 ISBN 이 적힌 큰글자책·점자책 줄 " + skipped + "개를 빼고 골랐습니다)"));
                }
                if (first < 0) first = i;
                skipped++;
            }
            if (first >= 0) {
                return new IsbnAnchor.Pick<>(seen.get(first).candidate(),
                        "후보 " + (first + 1) + "번째의 상세 화면에 그 ISBN 이 적혀 있습니다");
            }
        }
        long read = seen.stream().filter(s -> s.page() != null).count();
        return new IsbnAnchor.Pick<>(null, "받아 본 후보 " + read + "개의 상세 화면에 그 ISBN 이 적혀 있지 않습니다");
    }
}
