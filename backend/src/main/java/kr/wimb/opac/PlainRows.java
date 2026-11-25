package kr.wimb.opac;

import java.util.ArrayList;
import java.util.List;

/**
 * 같은 ISBN 의 줄이 여럿일 때 <b>큰글자책·점자책 표기가 있는 줄을 건너뜁니다.</b> {@code @plain:} 규칙이 씁니다.
 *
 * <p>도서관이 큰글자판이나 점자책을 보통 판의 ISBN 으로 등록해 두면, 그 ISBN 으로 찾은 결과의 첫 줄이
 * 큰글자책일 수 있습니다. ISBN 으로는 가를 수 없어서 「첫 줄」을 쓰는 규칙은 보통 판을 누른 사람을 큰글자책
 * 화면으로 보냅니다. 그런데 줄의 글자에는 그 판이라는 표시가 있습니다. 2026-11-25 에 받아 본 화면들입니다.
 * <pre>
 *   거창        82년생 김지영 [큰글자 도서] … 청구기호 : 큰글자책 813.7-조192팔
 *   순천 삼산   82년생 김지영 [큰글자 도서] … 청구기호 큰글자 813.7-조198팔
 *   김제 금구   82년생 김지영[큰글자 도서] … 청구기호 : 큰 813.7-조211
 *   청주 오창호수  82년생 김지영 : 조남주 장편소설 : 큰글자책 … 청구기호 큰 813.7-조2118
 *   남양주 퇴계원  [대활자본] 82년생 김지영 … 청구기호: 큰글 808-민67오-13
 *   단양 올누림   82년생 김지영 [큰글자 도서] … 청구기호 대활자813.7-조211ㅍ
 *   부산 화명     채식주의자 : 대활자본 … 청구기호 큰글자책 813.6-22
 *   보령          [큰글자책] 82년생 김지영 … / [큰글씨책] 82년생 김지영 …
 *   청주 오송     채식주의자 : 한강 연작소설 … 청구기호 큰 813.7-한12ㅊ   (표제에는 아무 표시가 없습니다)
 *   순천 삼산     채식주의자 : 한강 연작소설 … 발행처 창비 점자도서관 … 청구기호 점자 813.7-한12채-1
 *   순천 어울림   불편한 편의점 [점자도서] : 김호연 장편소설. 1
 * </pre>
 *
 * <h2>언제 건너뛰는가</h2>
 * <p><b>그 ISBN 의 줄이 둘 이상이고, 표기가 있는 줄과 없는 줄이 섞여 있을 때만</b>입니다. 그때 표기가 없는
 * 첫 줄을 고릅니다. 줄이 전부 그 표기면 방문자가 큰글자판을 누른 것이므로 예전처럼 첫 줄입니다. 줄이
 * 하나뿐이어도 그대로입니다.
 *
 * <h2>줄의 글자를 어디서 어디까지로 보는가</h2>
 * <p>규칙이 잡은 자리에서 다음 줄의 자리 앞까지입니다(같은 내용의 자리는 처음 것만 줄의 시작으로 봅니다.
 * 한 줄에 같은 상세로 가는 링크가 둘인 목록이 흔합니다). 글자는 {@link RowText#visible} 로 태그와 속성값을
 * 뺀 것입니다.
 *
 * <p><b>마지막 줄은 그 앞의 가장 짧은 줄만큼만 봅니다.</b> 다음 줄이 없어 어디서 끝나는지 알 수 없는데,
 * 끝없이 보면 목록 뒤의 글자(쪽 번호, 인기 검색어, 바닥글)가 마지막 줄의 것이 됩니다. 길게 보는 쪽과 짧게
 * 보는 쪽 가운데 짧은 쪽을 골랐습니다. 짧게 보아 마지막 줄의 표기를 놓쳐도 해가 없기 때문입니다. 마지막
 * 줄을 고르는 것은 그 앞의 줄이 전부 표기 있는 줄일 때뿐이고, 그때 마지막 줄이 실은 표기 있는 줄이었다면
 * 첫 줄을 고른 것과 같은 일입니다. 길게 보아 목록 뒤의 글자를 끌어오면 마지막 줄의 보통 판이 표기 있는
 * 줄로 읽혀 고르지 못합니다.
 *
 * <p><b>그래서 규칙은 줄의 맨 앞에서 잡혀야 합니다.</b> 줄의 표기가 규칙이 잡는 자리보다 앞에 있으면 그
 * 글자는 윗줄의 것으로 읽힙니다. 청주의 줄은 제목 링크보다 앞에 「…큰글자책 선택」이라는 숨은 이름표가
 * 있어서, 제목 링크에서 잡는 정규식을 쓰면 윗줄(보통 판)에 표기가 있는 것으로 읽습니다. 그 시스템의
 * {@code @plain:} 줄은 정규식을 줄 머리의 체크 칸에서 시작하게 적습니다. 화면마다 직접 보고 넣어야 하는
 * 까닭입니다.
 *
 * <p><b>줄의 경계를 가르지 못한 것으로 보이면 아무것도 바꾸지 않습니다.</b> 그 ISBN 의 줄 가운데 글자가 없는
 * 줄이 하나라도 있으면 그렇게 봅니다. 한 줄에 서로 다른 상세 주소의 링크가 둘이면(표지 링크와 제목 링크의
 * 주소가 다를 때) 표지 링크가 글자 없는 「줄」로 따로 서는데, 그 줄은 표기가 없어 보여서 큰글자책의 표지
 * 링크를 「표기 없는 줄」로 고르게 됩니다. 그때는 예전처럼 첫 줄입니다.
 *
 * <h2>표기의 낱말</h2>
 * <p>{@link #MARKS} 는 저작을 묶을 때 쓰는 각색 표기({@code kr.wimb.bib.Lexicons.ADAPTATION_TOKENS} 의
 * 「큰글자책」「큰글씨책」「대활자본」「점자」)에서 판을 가리키는 앞머리만 남긴 것이고, 넷 다 위의 화면에서
 * 본 꼴을 덮습니다(「큰글자 도서」, 「큰글자 813.7」, 「대활자813.7」, 「점자도서」).
 *
 * <p><b>청구기호 앞의 별치기호도 읽습니다</b>({@link #CALL_NUMBER_MARK}). 청주 오송도서관은 큰글자책 줄의
 * 표제에 아무 표시가 없고 청구기호가 「큰 813.7-한12ㅊ」으로 시작할 뿐입니다. 그 줄을 읽지 못하면 그
 * 도서관에서는 이 규칙이 아무 일도 하지 않습니다. 다만 「큰」 한 글자는 제목의 글자와 겹치므로 꼴을
 * 좁혔습니다. <b>「큰」이나 「큰글」 뒤에 공백 하나, 숫자 셋, 그리고 점이나 붙임표</b>가 올 때만입니다
 * (「큰 813.7-」, 「큰글 808-」). 「가장 큰 100가지 이야기」는 걸리지 않습니다. 본 것은 청주·보령·김제의
 * 「큰 813.…」과 남양주의 「큰글 808-…」입니다.
 *
 * <p>낱말과 꼴은 화면에서 본 것만 더하세요.
 *
 * <p><b>조사 도구({@code scripts/opac-title-probe.py} 의 {@code plain_rows}·{@code plain_choose})와 같은
 * 답을 내야 합니다.</b> {@code PlainRowsTest} 와 {@code test-opac-title-probe.py} 가 같은 화면에 같은 답을
 * 바랍니다. 한쪽을 고치면 다른 쪽도 고치세요.
 */
final class PlainRows {

    private PlainRows() {}

    /** 줄의 글자에 이 가운데 하나가 있으면 큰글자책·점자책 줄로 봅니다. */
    static final List<String> MARKS = List.of("큰글자", "큰글씨", "대활자", "점자");

    /** 청구기호 앞의 별치기호. 「큰 813.7-조2118」(청주·보령·김제), 「큰글 808-민67오-13」(남양주). */
    static final java.util.regex.Pattern CALL_NUMBER_MARK =
            java.util.regex.Pattern.compile("(?<![가-힣])큰글? [0-9]{3}[.\\-]");

    /** 화면의 줄 하나. {@code text} 는 그 줄의 글자이고 {@code marked} 는 판 표기가 있는지입니다. */
    record Row<T>(IsbnAnchor.Span<T> span, String text, boolean marked) {}

    /**
     * 화면의 줄들. {@code spans} 는 규칙이 잡은 자리 전부(문서 순서)입니다. 내용이 같은 자리는 처음 것만
     * 줄의 시작으로 삼습니다.
     */
    static <T> List<Row<T>> rows(String html, List<IsbnAnchor.Span<T>> spans) {
        List<IsbnAnchor.Span<T>> starts = IsbnVerify.distinct(spans);
        List<Row<T>> out = new ArrayList<>();
        int shortest = Integer.MAX_VALUE;
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i).start();
            int to;
            if (i + 1 < starts.size()) {
                to = starts.get(i + 1).start();
                shortest = Math.min(shortest, to - from);
            } else {
                // 마지막 줄은 그 앞의 가장 짧은 줄만큼만 봅니다. 줄이 하나뿐이면 고를 것이 없어 어디까지든 같습니다.
                to = shortest == Integer.MAX_VALUE ? html.length() : Math.min(html.length(), from + shortest);
            }
            String text = RowText.visible(html.substring(afterOpenTag(html, from, to), beforeOpenTag(html, from, to)));
            out.add(new Row<>(starts.get(i), text, marked(text)));
        }
        return out;
    }

    /**
     * 규칙이 태그 한가운데에서 잡혔으면 그 태그가 끝난 자리. 잡힌 자리에서부터 글자를 뽑으면 그 태그의 남은
     * 속성값({@code href="…" class="…">})이 글자로 읽힙니다.
     */
    private static int afterOpenTag(String html, int from, int to) {
        int gt = html.indexOf('>', from);
        int lt = html.indexOf('<', from);
        if (gt >= 0 && gt < to && (lt < 0 || gt < lt)) return gt + 1;
        return from;
    }

    /**
     * 다음 줄의 자리가 태그 한가운데면 그 태그가 열린 자리. 줄의 끝에 다음 줄의 열다 만 태그
     * ({@code <input title="…큰글자책" name="bookCheck"})가 남으면 그 속성값이 이 줄의 글자로 읽힙니다.
     */
    private static int beforeOpenTag(String html, int from, int to) {
        int lt = html.lastIndexOf('<', to - 1);
        if (lt >= from && html.indexOf('>', lt) >= to) return lt;
        return to;
    }

    static boolean marked(String text) {
        for (String mark : MARKS) {
            if (text.contains(mark)) return true;
        }
        return CALL_NUMBER_MARK.matcher(text).find();
    }

    /**
     * 그 ISBN 의 줄들 가운데 보낼 줄.
     *
     * @param spans 규칙이 잡은 자리 전부(문서 순서). 줄의 경계를 여기서 얻습니다.
     * @param mine  그 가운데 그 ISBN 의 줄들(문서 순서). ISBN 으로 검색한 결과면 전부이고, 닻 규칙이면
     *              {@link IsbnAnchor#pickAll} 이 고른 것입니다. 비어 있으면 안 됩니다.
     * @param how   {@code mine} 을 어떻게 얻었는지. 고른 사유 앞에 붙입니다.
     */
    static <T> IsbnAnchor.Pick<T> choose(String html, List<IsbnAnchor.Span<T>> spans,
                                         List<IsbnAnchor.Span<T>> mine, String how) {
        List<IsbnAnchor.Span<T>> candidates = IsbnVerify.distinct(mine);
        T first = candidates.get(0).payload();
        if (candidates.size() < 2) return new IsbnAnchor.Pick<>(first, how);
        List<Row<T>> rows = rows(html, spans);
        List<Row<T>> ours = new ArrayList<>();
        for (IsbnAnchor.Span<T> candidate : candidates) {
            for (Row<T> row : rows) {
                if (row.span().payload().equals(candidate.payload())) { ours.add(row); break; }
            }
        }
        if (ours.size() != candidates.size()) return new IsbnAnchor.Pick<>(first, how);   // 줄을 다 찾지 못했습니다
        int marked = 0;
        for (Row<T> row : ours) {
            // 글자가 없는 「줄」은 줄이 아니라 줄 안의 다른 링크입니다. 경계를 가르지 못한 화면입니다.
            if (row.text().isEmpty()) {
                return new IsbnAnchor.Pick<>(first, how + " (글자 없는 줄이 있어 판 표기를 보지 않았습니다)");
            }
            if (row.marked()) marked++;
        }
        if (marked == 0 || marked == ours.size()) return new IsbnAnchor.Pick<>(first, how);
        for (int i = 0; i < ours.size(); i++) {
            if (!ours.get(i).marked()) {
                return new IsbnAnchor.Pick<>(ours.get(i).span().payload(), how + " (그 ISBN 의 줄 " + ours.size()
                        + "개 가운데 큰글자책·점자책 표기가 있는 줄 " + marked + "개를 빼고 고른 " + (i + 1) + "번째 줄입니다)");
            }
        }
        return new IsbnAnchor.Pick<>(first, how);
    }
}
