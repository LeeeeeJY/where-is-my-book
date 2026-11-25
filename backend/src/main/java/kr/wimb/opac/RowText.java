package kr.wimb.opac;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 받은 HTML 에서 <b>사람이 읽는 글자</b>만 남깁니다. {@code @verify:}({@link IsbnVerify})가 상세 화면에서
 * ISBN 을 찾을 때와 {@code @plain:}({@link PlainRows})이 줄의 글자에서 판 표기를 찾을 때 씁니다.
 *
 * <p>왜 글자만 보는가. 속성값에는 그 책의 것이 아닌 글자가 섞입니다. 검색 화면을 거쳐 들어간 상세 화면은
 * 숨은 칸과 「목록으로」 링크에 <b>검색어를 되돌려 적는데</b>, ISBN 으로 검색해 들어갔다면 그 검색어가 바로
 * 찾는 ISBN 입니다. 속성값까지 보면 어느 후보의 상세 화면이든 「그 ISBN 이 있다」가 됩니다. 줄의 글자도
 * 같습니다. 다음 줄의 표지 그림에 달린 {@code alt="…큰글자책"} 이 앞줄의 것으로 읽힙니다. 견주는 글자를
 * 좁히는 쪽이 안전합니다. 넓힐수록 다른 책이 통과합니다.
 *
 * <p>하는 일은 넷이고 차례가 있습니다. ① 주석·{@code <script>}·{@code <style>} 을 통째로 버리고 ② 남은
 * 태그를 공백으로 바꾸고 ③ {@code &#44544;} 같은 숫자 문자 참조를 글자로 되돌리고 ④ 공백을 하나로
 * 줄입니다. ③ 은 거창 때문에 있습니다. 그 화면은 한글을 전부 숫자 참조로 적습니다(2026-11-25 에 받아 본
 * 화면: {@code [&#53360;&#44544;&#51088; &#46020;&#49436;]} 가 「[큰글자 도서]」입니다). 태그를 버린
 * <b>뒤에</b> 되돌립니다. 먼저 되돌리면 {@code &#60;} 가 태그를 만들어 냅니다.
 *
 * <p>HTML 해석기가 아닙니다. 속성값 안에 {@code >} 가 들어 있으면 그 뒤가 글자로 남습니다(거창의
 * {@code onerror="…<div…>표지<br>없음</div>'"}). 그런 찌꺼기는 판정에 쓰는 글자(ISBN, 판 표기)가 아니어서
 * 그대로 둡니다.
 *
 * <p><b>조사 도구({@code scripts/opac-title-probe.py} 의 {@code visible_text})와 같은 글자를 내야 합니다.</b>
 * 서버가 본 글자와 도구가 본 글자가 다르면 도구가 확인한 규칙이 서버에서 다른 줄을 고릅니다. 양쪽 테스트가
 * 같은 HTML 에 같은 글자를 바랍니다. 한쪽을 고치면 다른 쪽도 고치세요.
 */
final class RowText {

    private RowText() {}

    private static final Pattern HIDDEN = Pattern.compile(
            "<!--.*?-->|<script\\b.*?</script\\s*>|<style\\b.*?</style\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC = Pattern.compile("&#(?:([0-9]{1,7})|[xX]([0-9A-Fa-f]{1,6}));");
    /** 자바와 파이썬의 {@code \s} 는 받는 글자가 달라서 글자를 늘어놓습니다. */
    private static final Pattern SPACES = Pattern.compile("[ \\t\\r\\n\\f\\u000B]+");

    /** 태그와 스크립트를 뺀 글자. 숫자 문자 참조는 글자로 되돌리고 공백은 하나로 줄입니다. */
    static String visible(String html) {
        if (html == null || html.isEmpty()) return "";
        String text = HIDDEN.matcher(html).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        Matcher m = NUMERIC.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int code = m.group(1) != null ? Integer.parseInt(m.group(1)) : Integer.parseInt(m.group(2), 16);
            boolean valid = code > 0 && code <= 0x10FFFF && (code < 0xD800 || code > 0xDFFF);
            m.appendReplacement(out, Matcher.quoteReplacement(valid ? new String(Character.toChars(code)) : m.group()));
        }
        m.appendTail(out);
        String squeezed = SPACES.matcher(out).replaceAll(" ");
        // 양 끝은 위에서 줄인 공백 한 글자만 뗍니다. strip() 은 자바와 파이썬이 떼는 글자가 다릅니다.
        int from = squeezed.startsWith(" ") ? 1 : 0;
        int to = squeezed.length() > from && squeezed.endsWith(" ") ? squeezed.length() - 1 : squeezed.length();
        return squeezed.substring(from, to);
    }
}
