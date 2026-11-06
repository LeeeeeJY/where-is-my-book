package kr.wimb.opac;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 검색 결과 HTML 에서 <b>그 책의 상세 주소</b>를 얻는 방법. {@code detail-patterns.csv} 의 둘째 칸입니다.
 *
 * <p>처음에는 「href 를 잡는 정규식」 하나뿐이었습니다. 그런데 2026-11-06 에 검색 결과까지만 가던
 * 342곳을 들여다보니 <b>제목 링크가 {@code href="#"} 이고 자바스크립트 함수에 열쇠만 넘기는 시스템</b>이
 * 절반을 넘었습니다({@code onclick="fnDetail('2141529613','2141529611','9788937473135','MO')"}).
 * 그 함수는 어느 시스템이나 똑같은 일을 합니다. <b>숨은 폼의 칸에 인자를 채우고, action 을 정하고,
 * 전송합니다.</b> 그 폼과 함수 본문이 서버가 받는 HTML(또는 같은 기관의 스크립트 파일)에 그대로
 * 있으므로, 서버가 그것을 읽어 같은 주소를 만들 수 있습니다. 브라우저가 폼을 전송했을 때 가는
 * 주소와 같습니다. 실측으로 화성·군산·서대문·은평·고양·김제·전주·울산 중구·울산 남구 아홉 시스템이
 * 이 한 가지 읽기로 상세 페이지를 돌려주었습니다.
 *
 * <p>세 종류입니다.
 * <ul>
 *   <li>{@link Href} — 정규식. 잡을 그룹이 하나이고 그것이 href 값입니다. 예전부터 있던 것입니다.</li>
 *   <li>{@link OnclickForm} — {@code @onclick:함수이름}. 그 함수의 첫 호출 인자를 함수 본문이 채우는
 *       폼 칸에 넣고, 폼의 숨은 칸과 함께 GET 주소로 만듭니다. 읽는 규칙은 {@link OnclickFormReader} 에
 *       있습니다.</li>
 *   <li>{@link Template} — {@code @template:정규식 => 주소틀}. 정규식의 그룹들을 주소틀의
 *       {@code {1}} {@code {2}} 에 끼웁니다. 포항처럼 열쇠가 {@code <a>} 의 속성({@code regNo}
 *       {@code manageCode})에 있고 jQuery 가 그것을 읽어 주소를 만드는 시스템을 위한 것입니다.</li>
 * </ul>
 *
 * <p><b>어느 종류든 「확인한 것만」이라는 규칙은 같습니다.</b> 함수 이름이 틀리면 그 호출이 없어
 * 검색 결과로 내려갈 뿐이지만, 함수 이름이 맞는데 다른 책의 호출이 먼저 있으면 엉뚱한 책으로
 * 갑니다. 그래서 조사 도구({@code scripts/opac-onclick-probe.py})가 실제로 만든 주소를 열어 그 책이
 * 나오고 다른 책은 나오지 않는 것을 본 것만 넣습니다.
 */
public sealed interface DetailRule permits DetailRule.Href, DetailRule.OnclickForm, DetailRule.Template {

    /** 정규식 하나. 그룹 하나가 href 입니다. */
    record Href(Pattern regex) implements DetailRule {
        @Override public String describe() { return regex.pattern(); }
    }

    /** {@code @onclick:fnDetail} — 자바스크립트 폼 전송을 읽어 주소를 만듭니다. */
    record OnclickForm(String function) implements DetailRule {
        @Override public String describe() { return ONCLICK_PREFIX + function; }
    }

    /** {@code @template:정규식 => 주소틀} — 그룹을 주소틀에 끼웁니다. */
    record Template(Pattern regex, String urlTemplate) implements DetailRule {
        @Override public String describe() { return TEMPLATE_PREFIX + regex.pattern() + TEMPLATE_SEPARATOR + urlTemplate; }
    }

    String ONCLICK_PREFIX = "@onclick:";
    String TEMPLATE_PREFIX = "@template:";
    String TEMPLATE_SEPARATOR = " => ";
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$.]*");
    Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /** 진단과 로그에 적는 한 줄. CSV 에 적은 모양 그대로입니다. */
    String describe();

    /**
     * CSV 둘째 칸을 규칙으로. 서버가 뜰 때 부르므로 <b>잘못된 줄은 예외</b>입니다. 조용히 빠진 줄은
     * 그 도서관을 검색 결과로만 보내는데, 그것이 「패턴이 없어서」인지 「줄이 틀려서」인지 아무도
     * 모르게 됩니다. 메시지는 {@code OpacTemplates} 가 행 번호를 붙여 모읍니다.
     */
    static DetailRule parse(String text) {
        String value = text.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("규칙이 비어 있습니다");
        if (value.toLowerCase(Locale.ROOT).startsWith(ONCLICK_PREFIX)) {
            String function = value.substring(ONCLICK_PREFIX.length()).strip();
            if (!IDENTIFIER.matcher(function).matches()) {
                throw new IllegalArgumentException("@onclick: 뒤에는 자바스크립트 함수 이름이 와야 합니다 — " + text);
            }
            return new OnclickForm(function);
        }
        if (value.toLowerCase(Locale.ROOT).startsWith(TEMPLATE_PREFIX)) {
            String rest = value.substring(TEMPLATE_PREFIX.length());
            int sep = rest.indexOf(TEMPLATE_SEPARATOR.strip());
            if (sep < 0) {
                throw new IllegalArgumentException("@template: 은 「정규식 => 주소틀」이어야 합니다 — " + text);
            }
            Pattern regex = compile(rest.substring(0, sep).strip());
            String template = rest.substring(sep + TEMPLATE_SEPARATOR.strip().length()).strip();
            if (template.isEmpty()) throw new IllegalArgumentException("@template: 의 주소틀이 비어 있습니다 — " + text);
            int groups = regex.matcher("").groupCount();
            if (groups < 1) throw new IllegalArgumentException("@template: 의 정규식에 잡을 그룹이 없습니다 — " + text);
            Matcher ph = PLACEHOLDER.matcher(template);
            boolean any = false;
            while (ph.find()) {
                any = true;
                int n = Integer.parseInt(ph.group(1));
                if (n < 1 || n > groups) {
                    throw new IllegalArgumentException("@template: 의 자리표 {" + n + "} 에 해당하는 그룹이 없습니다 (그룹 "
                            + groups + "개) — " + text);
                }
            }
            if (!any) throw new IllegalArgumentException("@template: 의 주소틀에 {1} 같은 자리표가 없습니다 — " + text);
            if (regex.matcher("").find()) throw new IllegalArgumentException("빈 문자열에도 맞는 정규식은 아무 페이지에서나 「찾았다」가 됩니다 — " + text);
            return new Template(regex, template);
        }
        Pattern regex = compile(value);
        if (regex.matcher("").groupCount() != 1) {
            // 그룹이 없으면 무엇이 주소인지 알 수 없고, 둘 이상이면 어느 것인지 모릅니다.
            throw new IllegalArgumentException("잡을 그룹이 정확히 하나여야 합니다 — " + text);
        }
        if (regex.matcher("").find()) {
            throw new IllegalArgumentException("빈 문자열에도 맞는 정규식은 아무 페이지에서나 「찾았다」가 됩니다 — " + text);
        }
        return new Href(regex);
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("정규식이 잘못되었습니다 — " + e.getDescription());
        }
    }
}
