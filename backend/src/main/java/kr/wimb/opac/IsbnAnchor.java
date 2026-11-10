package kr.wimb.opac;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결과 목록에서 <b>그 ISBN 이 적힌 항목의 링크</b>를 고릅니다. {@code @anchor:} 규칙이 씁니다.
 *
 * <p>ISBN 으로 검색한 결과는 첫 줄이 그 책이라 「첫 링크」로 충분했습니다. 제목으로 검색한 결과는
 * 그렇지 않습니다. 「미움받을 용기」로 찾으면 2권과 만화판과 다른 출판사 판이 함께 나오고, 그
 * 도서관이 가진 판이 몇째 줄인지는 받아 보기 전에 알 수 없습니다. 첫 링크를 쓰면 <b>제목은 같은데
 * 다른 책</b>으로 갑니다. 틀린 줄 모르고 지나치기 가장 쉬운 종류의 오답입니다.
 *
 * <p>그런데 제목으로만 찾아지는 OPAC 의 상당수가 <b>결과 목록에는 ISBN 을 적어 둡니다</b>
 * (2026-11-10 조사: 제목 규칙 호스트 52개 가운데 32개, 도서관 319곳). 검색이 ISBN 을 색인하지 않을
 * 뿐 화면에는 보여 주는 것입니다. 그 글자를 닻으로 삼습니다.
 *
 * <h2>고르는 차례</h2>
 * <ol>
 *   <li><b>링크 글자 안에 그 ISBN 이 들어 있으면 그것</b>입니다
 *       ({@code fnDetail('…','9788937473135','MO')}). 가장 확실합니다.</li>
 *   <li>아니면 ISBN 이 적힌 자리마다, 규칙이 말한 쪽({@code @anchor:} 는 바로 앞,
 *       {@code @anchor-after:} 는 바로 뒤)의 가장 가까운 링크를 봅니다.</li>
 *   <li><b>그 링크와 ISBN 사이에 다른 ISBN 이 끼어 있으면 버립니다.</b> 남의 항목의 링크라는
 *       뜻입니다. 표지 그림 주소에 ISBN 이 먼저 나오고 그 뒤에 제목 링크가 오는 목록에서, 그림의
 *       ISBN 을 닻으로 삼으면 바로 앞 링크는 <b>윗줄 책의 것</b>입니다. 그 사이에는 윗줄 책의 ISBN 이
 *       있으므로 여기서 걸러지고, 같은 ISBN 이 다음에 적힌 자리(본문의 「ISBN: …」)로 넘어갑니다.</li>
 * </ol>
 *
 * <p><b>앞인지 뒤인지는 시스템마다 다르고, 글자만 보고는 알 수 없습니다.</b> 항목이
 * {@code [링크 … ISBN]} 차례인 목록에 「뒤」를 쓰면 아랫줄 책으로, {@code [ISBN … 링크]} 차례인
 * 목록에 「앞」을 쓰면 윗줄 책으로 갑니다. 그래서 규칙에 적고, 조사 도구가 뽑은 상세 화면에
 * <b>그 ISBN 이 적혀 있는지</b>로 확인한 것만 넣습니다. 제목으로 확인하면 안 됩니다. 틀렸을 때
 * 도착하는 곳이 바로 제목이 같은 다른 판입니다.
 */
final class IsbnAnchor {

    private IsbnAnchor() {}

    /** 규칙이 잡은 자리 하나. {@code payload} 는 그 자리에서 뽑은 것(href, 호출 인자)입니다. */
    record Span<T>(int start, int end, T payload) {}

    /** 고른 것과 어떻게 골랐는지. 못 골랐으면 {@code payload} 가 없고 {@code how} 가 사유입니다. */
    record Pick<T>(T payload, String how) {
        boolean found() { return payload != null; }
    }

    /** 앞뒤가 숫자가 아닌 ISBN-13. 등록번호나 전화번호의 일부를 ISBN 으로 읽지 않습니다. */
    private static final Pattern ISBN_TOKEN = Pattern.compile("(?<!\\d)97[89]\\d{10}(?!\\d)");

    /**
     * @param spans          규칙이 잡은 자리들. 문서 순서여야 합니다.
     * @param linkBeforeIsbn 항목 안에서 링크가 ISBN 보다 앞에 오는 목록이면 참
     */
    static <T> Pick<T> pick(String html, List<Span<T>> spans, String isbn, boolean linkBeforeIsbn) {
        if (isbn == null || !ISBN_TOKEN.matcher(isbn).matches()) {
            return new Pick<>(null, "ISBN-13 이 아닙니다: " + isbn);
        }
        for (Span<T> span : spans) {
            if (html.substring(span.start(), span.end()).contains(isbn)) {
                return new Pick<>(span.payload(), "링크 안에 그 ISBN 이 있습니다");
            }
        }
        List<Integer> others = new ArrayList<>();
        List<int[]> ours = new ArrayList<>();
        Matcher m = ISBN_TOKEN.matcher(html);
        while (m.find()) {
            if (m.group().equals(isbn)) ours.add(new int[] {m.start(), m.end()});
            else others.add(m.start());
        }
        if (ours.isEmpty()) return new Pick<>(null, "결과에 그 ISBN 이 적혀 있지 않습니다");
        for (int[] at : ours) {
            Span<T> near = null;
            if (linkBeforeIsbn) {
                for (Span<T> span : spans) {
                    if (span.end() <= at[0]) near = span; else break;
                }
                if (near != null && clean(others, near.end(), at[0])) {
                    return new Pick<>(near.payload(), "그 ISBN 바로 앞의 링크입니다");
                }
            } else {
                for (Span<T> span : spans) {
                    if (span.start() >= at[1]) { near = span; break; }
                }
                if (near != null && clean(others, at[1], near.start())) {
                    return new Pick<>(near.payload(), "그 ISBN 바로 뒤의 링크입니다");
                }
            }
        }
        return new Pick<>(null, "그 ISBN 이 적힌 항목에서 링크를 찾지 못했습니다");
    }

    /** {@code [from, to)} 사이에 다른 책의 ISBN 이 없는지. */
    private static boolean clean(List<Integer> others, int from, int to) {
        for (int at : others) {
            if (at >= from && at < to) return false;
        }
        return true;
    }
}
