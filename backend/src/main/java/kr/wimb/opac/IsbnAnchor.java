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

    /** 그 ISBN 의 줄로 본 자리들(문서에서 찾은 차례)과 어떻게 찾았는지. 없으면 비어 있고 {@code how} 가 사유입니다. */
    record Picks<T>(List<Span<T>> spans, String how) {
        boolean found() { return !spans.isEmpty(); }
    }

    /**
     * <b>그 ISBN 의 줄 전부.</b> {@link #pick} 은 그 가운데 첫 줄 하나만 돌려주는데, 한 ISBN 에 줄이 여럿일 때
     * 그 가운데서 다시 골라야 하는 규칙({@code @plain:})이 이것을 씁니다. 도서관이 큰글자판을 보통 판의
     * ISBN 으로 등록해 두면 같은 ISBN 의 줄이 둘 이상 나옵니다.
     *
     * <p><b>첫 자리는 {@link #pick} 이 고르는 것과 같습니다.</b> 차례도 같습니다.
     * <ol>
     *   <li>링크 글자 안에 그 ISBN 이 든 자리가 있으면 <b>그런 자리 전부</b>입니다.</li>
     *   <li>아니면 ISBN 이 적힌 자리마다 규칙이 말한 쪽의 가장 가까운 링크입니다. 그 사이에 다른 ISBN 이
     *       끼어 있으면 버립니다.</li>
     *   <li>「뒤」 방향에서는 그 가까운 링크 <b>뒤로 다른 ISBN 이 나오기 전까지의 링크도</b> 그 ISBN 의
     *       줄입니다. 남양주의 ISBN 검색 화면은 목록 앞에 「요청하신 'ISBN:…'」이 한 번 적히고 줄에는
     *       ISBN 이 없어서, 그 글자 뒤의 줄이 전부 그 ISBN 의 줄입니다(2026-11-25 에 받아 본 화면).
     *       줄마다 ISBN 이 적힌 목록에서는 다음 줄의 ISBN 이 사이에 끼므로 한 줄만 남습니다.</li>
     * </ol>
     * 「앞」 방향은 넓히지 않습니다. 그런 화면을 본 적이 없고, 넓히면 첫 자리가 {@link #pick} 과 달라집니다.
     */
    static <T> Picks<T> pickAll(String html, List<Span<T>> spans, String isbn, boolean linkBeforeIsbn) {
        if (isbn == null || !ISBN_TOKEN.matcher(isbn).matches()) {
            return new Picks<>(List.of(), "ISBN-13 이 아닙니다: " + isbn);
        }
        List<Span<T>> carrying = new ArrayList<>();
        for (Span<T> span : spans) {
            if (html.substring(span.start(), span.end()).contains(isbn)) carrying.add(span);
        }
        if (!carrying.isEmpty()) return new Picks<>(carrying, "링크 안에 그 ISBN 이 있습니다");
        List<Integer> others = new ArrayList<>();
        List<int[]> ours = new ArrayList<>();
        Matcher m = ISBN_TOKEN.matcher(html);
        while (m.find()) {
            if (m.group().equals(isbn)) ours.add(new int[] {m.start(), m.end()});
            else others.add(m.start());
        }
        if (ours.isEmpty()) return new Picks<>(List.of(), "결과에 그 ISBN 이 적혀 있지 않습니다");
        List<Span<T>> found = new ArrayList<>();
        for (int[] at : ours) {
            if (linkBeforeIsbn) {
                Span<T> near = null;
                for (Span<T> span : spans) {
                    if (span.end() <= at[0]) near = span; else break;
                }
                if (near != null && clean(others, near.end(), at[0]) && !found.contains(near)) found.add(near);
            } else {
                for (Span<T> span : spans) {
                    if (span.start() < at[1]) continue;
                    if (!clean(others, at[1], span.start())) break;      // 여기부터는 다른 책의 줄입니다
                    if (!found.contains(span)) found.add(span);
                }
            }
        }
        if (found.isEmpty()) return new Picks<>(List.of(), "그 ISBN 이 적힌 항목에서 링크를 찾지 못했습니다");
        return new Picks<>(found, linkBeforeIsbn ? "그 ISBN 바로 앞의 링크입니다" : "그 ISBN 바로 뒤의 링크입니다");
    }

    /** {@code [from, to)} 사이에 다른 책의 ISBN 이 없는지. */
    private static boolean clean(List<Integer> others, int from, int to) {
        for (int at : others) {
            if (at >= from && at < to) return false;
        }
        return true;
    }
}
