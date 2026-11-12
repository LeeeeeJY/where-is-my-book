package kr.wimb.opac;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * <p>종류는 아래와 같습니다. 뒤의 셋({@link Json}, {@link Anchored}, {@link Frame})은 나중에 더했고
 * 각자의 설명에 사정이 적혀 있습니다.
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
public sealed interface DetailRule
        permits DetailRule.Href, DetailRule.OnclickForm, DetailRule.Template, DetailRule.Json, DetailRule.Anchored,
                DetailRule.Frame {

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

    /**
     * {@code @json:METHOD 주소 [본문] => 목록경로 => 상세주소틀} — 결과를 자바스크립트로 그리는 OPAC.
     *
     * <p>노원·부천·서초·안산(ALPAS-Q)과 구로·시흥(Pyxis)은 검색 결과 HTML 에 책이 없고 화면이 JSON 을
     * 받아 그립니다. 서버가 그 JSON 검색을 대신 불러(주소의 {@code {isbn13}} 과 본문의 {@code {isbn13}} 을
     * 채워) 목록경로({@code contents.bookList})의 항목에서 열쇠를 꺼내 상세주소틀의 {@code {bookKey}}
     * 같은 자리에 끼웁니다. 항목이 여럿이면 <b>누른 도서관의 {@code libCode} 가 같은 것</b>입니다.
     * 부천·서초·안산·창원은 소장본마다 정보나루 도서관부호를 {@code libCode} 로 함께 주어서, 누른 분관의
     * 소장본 상세로 바로 보낼 수 있습니다. <b>목록이 도서관을 가려 주는데 누른 도서관이 없으면 남의
     * 분관으로 보내지 않고 {@code NO_MATCH} 입니다.</b> ISBN 이 같은 것 → 첫 항목으로 내려가는 것은
     * 목록이 도서관을 가려 주지 않거나(시흥) 누른 도서관을 모를 때뿐입니다.
     *
     * <p>요청의 주소와 본문에는 {@code {isbn13}} 과 {@code {libCode}}(누른 도서관의 정보나루 부호)를 쓸
     * 수 있습니다. 수원·세종교육청은 분관을 그 부호 그대로 받아서 요청에서 바로 좁힙니다. 본문은 공백
     * 없는 한 덩이이고 GET 이면 생략합니다. <b>{@code {} 나 {@code [} 로 시작하면 JSON, 아니면 폼</b>
     * ({@code a=1&b=2})으로 보냅니다. 수원의 검색은 JSON 본문에 빈 답을 줍니다.
     */
    record Json(String method, String url, String body, String listPath, String urlTemplate) implements DetailRule {
        @Override public String describe() {
            return JSON_PREFIX + method + " " + url + (body == null ? "" : " " + body)
                    + TEMPLATE_SEPARATOR + listPath + TEMPLATE_SEPARATOR + urlTemplate;
        }
    }

    /**
     * {@code @anchor:규칙} / {@code @anchor-after:규칙} — 결과 목록에서 <b>그 ISBN 이 적힌 항목의</b> 링크를
     * 고릅니다. 안쪽 규칙은 정규식·{@code @onclick:}·{@code @template:} 가운데 하나입니다.
     *
     * <p>제목으로만 찾아지는 OPAC 을 위한 것입니다. 제목 검색 결과는 같은 제목의 다른 판이 섞여 나와
     * 「첫 링크」를 쓸 수 없습니다. 대신 목록에 적힌 ISBN 을 닻으로 삼아 그 항목의 링크를 고릅니다
     * ({@link IsbnAnchor}). {@code @anchor:} 는 항목 안에서 링크가 ISBN 보다 앞에 오는 목록,
     * {@code @anchor-after:} 는 뒤에 오는 목록입니다. <b>어느 쪽인지는 조사 도구가 뽑은 상세 화면에
     * 그 ISBN 이 적혀 있는 것을 보고 정합니다.</b> 틀리면 윗줄이나 아랫줄 책으로 갑니다.
     *
     * <p>ISBN 검색 규칙에 달아도 됩니다. 그때도 첫 링크가 아니라 그 ISBN 의 항목을 고릅니다.
     * 거꾸로 <b>제목 검색 규칙은 이 규칙이 달렸을 때만 상세로 갑니다.</b> 닻 없는 규칙을 제목 검색
     * 결과에 쓰면 첫 줄, 곧 아무 판으로나 갑니다.
     */
    record Anchored(DetailRule inner, boolean linkBeforeIsbn) implements DetailRule {
        @Override public String describe() {
            return (linkBeforeIsbn ? ANCHOR_PREFIX : ANCHOR_AFTER_PREFIX) + inner.describe();
        }

        @Override public boolean anchored() { return true; }
    }

    /**
     * {@code @frame:[인코딩 ]프레임주소틀 => 안쪽 규칙} — 결과를 <b>다른 주소의 프레임</b>으로 보여 주는 OPAC.
     *
     * <p>충북교육청 계열(14곳)의 검색 화면({@code /srch/smart2.do?…&searchKeyword=…})은 서버가 받는
     * HTML 에 결과가 없고, 결과는 같은 호스트의 다른 포트에 있는 프레임
     * ({@code https://www.cbelib.go.kr:7443/dls_le/index.php?mod=wdDataSearch&act=searchIList&word=…
     * &manageCode=MA&siteId=SITE_…})에 있습니다. 그 프레임은 직접 받을 수 있고 목록에 ISBN 이 적혀
     * 있습니다(2026-11-11 에 국내 회선에서 한 번 봤습니다). 그래서 서버가 <b>검색 주소 대신 프레임
     * 주소를 받아</b> 그 HTML 에 안쪽 규칙을 적용합니다. {@link Json} 이 검색 화면 대신 JSON 검색을
     * 받는 것과 같은 구조입니다.
     *
     * <ul>
     *   <li>프레임주소틀에는 {@code {title}}(검색에 넣은 표제), {@code {isbn13}}, {@code {libCode}} 를 쓸
     *       수 있고 {@code {title}} 이나 {@code {isbn13}} 가운데 하나는 있어야 합니다. 자리표가 없으면
     *       어느 책을 눌러도 같은 화면을 받습니다.</li>
     *   <li>인코딩은 {@code {title}} 을 퍼센트 부호로 바꿀 때 쓰는 글자 집합이고 적지 않으면 UTF-8
     *       입니다. 한글 검색어를 EUC-KR 로 받는 프레임에 UTF-8 로 보내면 200 이 오면서 결과만
     *       0건입니다. 공백은 {@code %20} 으로 보냅니다.</li>
     *   <li>{@code manageCode}·{@code siteId} 처럼 기관마다 다른 값은 주소틀에 글자 그대로 적습니다.
     *       그래서 호스트마다 한 줄입니다.</li>
     *   <li>안쪽 규칙은 정규식·{@code @onclick:}·{@code @template:}·{@code @anchor:} 입니다. 제목으로
     *       찾는 프레임이면 {@code @anchor:} 여야 상세로 갑니다(제목 검색은 첫 줄이 그 책이 아닙니다).</li>
     *   <li>상대 링크는 <b>프레임 주소 기준으로</b> 풉니다. robots.txt 도 프레임의 출처(포트까지)의
     *       것을 봅니다. 검색 화면의 출처와 다른 출처입니다.</li>
     * </ul>
     *
     * <p><b>못 찾으면 예전처럼 검색 화면으로 보냅니다.</b> 거기에는 프레임 안에 결과가 보입니다.
     */
    record Frame(Charset encoding, String urlTemplate, DetailRule inner) implements DetailRule {
        @Override public String describe() {
            return FRAME_PREFIX + (encoding.equals(StandardCharsets.UTF_8) ? "" : encoding.name() + " ")
                    + urlTemplate + TEMPLATE_SEPARATOR + inner.describe();
        }

        @Override public boolean anchored() { return inner.anchored(); }
    }

    String FRAME_PREFIX = "@frame:";
    Pattern FRAME_SHAPE = Pattern.compile("^(?:(\\S+)\\s+)?(https?://\\S+)\\s+=>\\s*(.+)$", Pattern.DOTALL);
    String ANCHOR_PREFIX = "@anchor:";
    String ANCHOR_AFTER_PREFIX = "@anchor-after:";
    String JSON_PREFIX = "@json:";
    Pattern FIELD_PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][\\w]*)}");
    String ONCLICK_PREFIX = "@onclick:";
    String TEMPLATE_PREFIX = "@template:";
    String TEMPLATE_SEPARATOR = " => ";
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$.]*");
    Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /** 진단과 로그에 적는 한 줄. CSV 에 적은 모양 그대로입니다. */
    String describe();

    /**
     * 결과 목록에서 <b>그 ISBN 이 적힌 항목</b>을 골라 가는 규칙인지. 제목 검색 규칙은 이것이 참일 때만
     * 상세로 갑니다. {@link Anchored} 자신과, 안쪽이 그런 {@link Frame} 이 참입니다.
     */
    default boolean anchored() { return false; }

    /**
     * CSV 둘째 칸을 규칙으로. 서버가 뜰 때 부르므로 <b>잘못된 줄은 예외</b>입니다. 조용히 빠진 줄은
     * 그 도서관을 검색 결과로만 보내는데, 그것이 「패턴이 없어서」인지 「줄이 틀려서」인지 아무도
     * 모르게 됩니다. 메시지는 {@code OpacTemplates} 가 행 번호를 붙여 모읍니다.
     */
    static DetailRule parse(String text) {
        String value = text.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("규칙이 비어 있습니다");
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.startsWith(FRAME_PREFIX)) return parseFrame(value.substring(FRAME_PREFIX.length()).strip(), text);
        // 긴 쪽을 먼저 봅니다. "@anchor-after:" 는 "@anchor:" 로 시작하지 않지만 차례를 못박아 둡니다.
        for (String prefix : new String[] {ANCHOR_AFTER_PREFIX, ANCHOR_PREFIX}) {
            if (!lowered.startsWith(prefix)) continue;
            DetailRule inner = parse(value.substring(prefix.length()));
            if (inner instanceof Json || inner instanceof Anchored || inner instanceof Frame) {
                throw new IllegalArgumentException(prefix + " 뒤에는 정규식·@onclick:·@template: 규칙이 와야 합니다 — " + text);
            }
            return new Anchored(inner, prefix.equals(ANCHOR_PREFIX));
        }
        if (value.toLowerCase(Locale.ROOT).startsWith(ONCLICK_PREFIX)) {
            String function = value.substring(ONCLICK_PREFIX.length()).strip();
            if (!IDENTIFIER.matcher(function).matches()) {
                throw new IllegalArgumentException("@onclick: 뒤에는 자바스크립트 함수 이름이 와야 합니다 — " + text);
            }
            return new OnclickForm(function);
        }
        if (value.toLowerCase(Locale.ROOT).startsWith(JSON_PREFIX)) {
            String[] parts = value.substring(JSON_PREFIX.length()).split(Pattern.quote(TEMPLATE_SEPARATOR.strip()), -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("@json: 은 「METHOD 주소 [본문] => 목록경로 => 상세주소틀」이어야 합니다 — " + text);
            }
            String[] request = parts[0].strip().split("\\s+", 3);
            if (request.length < 2) throw new IllegalArgumentException("@json: 요청은 「METHOD 주소」로 시작해야 합니다 — " + text);
            String method = request[0].toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("POST")) {
                throw new IllegalArgumentException("@json: 의 METHOD 는 GET 또는 POST 입니다 — " + text);
            }
            String url = request[1];
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("@json: 의 주소는 http(s):// 로 시작해야 합니다 — " + text);
            }
            if (!url.contains("{isbn13}") && (request.length < 3 || !request[2].contains("{isbn13}"))) {
                throw new IllegalArgumentException("@json: 의 주소나 본문에 {isbn13} 자리표가 있어야 합니다 — " + text);
            }
            String body = request.length == 3 ? request[2].strip() : null;
            if (method.equals("GET") && body != null) throw new IllegalArgumentException("@json: GET 에는 본문이 없습니다 — " + text);
            if (method.equals("POST") && body == null) throw new IllegalArgumentException("@json: POST 에는 본문이 있어야 합니다 — " + text);
            String listPath = parts[1].strip();
            if (listPath.isEmpty() || !listPath.matches("[A-Za-z_][\\w.]*")) {
                throw new IllegalArgumentException("@json: 의 목록경로는 contents.bookList 처럼 점으로 이은 이름이어야 합니다 — " + text);
            }
            String template = parts[2].strip();
            if (!FIELD_PLACEHOLDER.matcher(template).find()) {
                throw new IllegalArgumentException("@json: 의 상세주소틀에 {bookKey} 같은 자리표가 없습니다 — " + text);
            }
            return new Json(method, url, body, listPath, template);
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

    /**
     * {@code [인코딩 ]프레임주소틀 => 안쪽 규칙}. 주소에는 공백이 없으므로 첫 {@code =>} 가 경계입니다.
     * 안쪽 규칙에 {@code =>} 가 또 있어도({@code @template:}) 그것은 안쪽의 것입니다.
     */
    private static DetailRule parseFrame(String rest, String text) {
        Matcher shape = FRAME_SHAPE.matcher(rest);
        if (!shape.matches()) {
            throw new IllegalArgumentException("@frame: 은 「[인코딩 ]http(s)://프레임주소틀 => 안쪽 규칙」이어야 합니다 — " + text);
        }
        Charset encoding = StandardCharsets.UTF_8;
        if (shape.group(1) != null) {
            try {
                encoding = Charset.forName(shape.group(1));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("@frame: 의 인코딩을 모릅니다(" + shape.group(1) + ") — " + text);
            }
        }
        String url = shape.group(2);
        if (!url.contains("{title}") && !url.contains("{isbn13}")) {
            // 자리표가 없으면 어느 책을 눌러도 같은 화면을 받습니다. 조용히 엉뚱한 책으로 보내느니
            // 뜰 때 실패하는 편이 낫습니다. templates.csv 의 자리표 규칙과 같습니다.
            throw new IllegalArgumentException("@frame: 의 주소틀에 {title} 이나 {isbn13} 자리표가 있어야 합니다 — " + text);
        }
        Matcher placeholder = FIELD_PLACEHOLDER.matcher(url);
        while (placeholder.find()) {
            String name = placeholder.group(1);
            if (!name.equals("title") && !name.equals("isbn13") && !name.equals("libCode")) {
                throw new IllegalArgumentException("@frame: 의 주소틀이 모르는 자리표를 씁니다({" + name + "}) — " + text);
            }
        }
        DetailRule inner = parse(shape.group(3));
        if (inner instanceof Json || inner instanceof Frame) {
            throw new IllegalArgumentException("@frame: 의 안쪽에는 정규식·@onclick:·@template:·@anchor: 규칙이 와야 합니다 — " + text);
        }
        return new Frame(encoding, url, inner);
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("정규식이 잘못되었습니다 — " + e.getDescription());
        }
    }
}
