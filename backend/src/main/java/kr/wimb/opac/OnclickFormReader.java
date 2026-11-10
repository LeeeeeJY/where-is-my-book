package kr.wimb.opac;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code onclick="fnDetail('a','b','c','d')"} 와 그 함수 본문을 읽어, 브라우저가 폼을 전송했을 때
 * 가는 주소를 만듭니다. {@link DetailRule.OnclickForm} 의 실제 일입니다.
 *
 * <h2>무엇을 읽는가</h2>
 * <p>국내 OPAC 의 상세 함수는 벤더가 달라도 모양이 같습니다. 실측으로 본 것들입니다.
 * <pre>
 *   function fnDetail(bookKey, speciesKey, isbn, pubFormCode){        화성·군산·서대문·은평·고양
 *       var form = document.paramForm;
 *       form.bookKey.value = bookKey; ... form.action = "/nylib/menu/10160/program/30001/searchResultDetail.do";
 *       form.submit(); }
 *   function viewDetail(pageno, reckey, book_type, manage_code){      김제·전주 (action 은 폼 태그에)
 *       document.detailView.pageno.value=pageno; ... document.detailView.submit(); }
 *   function fn_view(manage_code, reg_no) {                             울산 중구 (jQuery)
 *       var frm = $("form[name='viewForm']");
 *       frm.find("input[name='manage_code']").val(manage_code); ... frm.submit(); }
 *   function goView(regNo) {                                            기장
 *       var form = document.getElementById("searchForm");
 *       form.action="/portal/kolas/book/view.do?mId=1000000000"; form.reg_no.value = regNo; form.submit(); }
 *   function fnSearchDetailView(speciesKey, bookKey, publishFormCode){  성북 (본문이 외부 스크립트에)
 *       var form = document.detailForm; form.recKey.value = speciesKey; ... form.submit(); }
 * </pre>
 * 그래서 읽는 것은 넷입니다. <b>어느 폼</b>인지, 어느 <b>칸에 어느 인자</b>를 넣는지,
 * <b>action</b> 을 바꾸는지, 그리고 그 폼이 HTML 에서 들고 있는 <b>숨은 칸의 기본값</b>입니다.
 * 자바스크립트 해석기가 아닙니다. 이 네 가지 모양을 정규식으로 알아보는 것이 전부이고, 모양이
 * 다르면 「못 읽었다」로 끝나 검색 결과로 내려갑니다.
 *
 * <h2>왜 GET 으로 만드는가</h2>
 * <p>폼이 {@code method="post"} 라도 GET 으로 만듭니다. 서버는 302 로 보내는 것뿐이라 POST 를 시킬
 * 수 없고, 2026-09-11 과 2026-11-06 의 실측에서 POST 폼인 전주·울산 남구가 같은 파라미터의 GET 으로
 * 열렸습니다. 안 열리는 곳(기장은 405)은 조사 도구의 확인에서 걸러져 규칙으로 들어오지 않습니다.
 *
 * <h2>토큰 칸은 뺍니다</h2>
 * <p>{@code _csrf} {@code CSRF_TOKEN} 같은 숨은 칸은 그 페이지를 받은 세션에만 유효해서 넣어도
 * 다음 사람에게는 틀린 값입니다. 빼고 보내서 열리면 그 칸이 없어도 되는 것이고, 안 열리면 그
 * 시스템은 이 방법으로 갈 수 없는 것입니다. 조사 도구가 같은 규칙으로 뺍니다.
 */
final class OnclickFormReader {

    private OnclickFormReader() {}

    /** 외부 스크립트 파일을 받아 오는 길. 같은 기관의 것만, 캐시는 부르는 쪽({@link DetailResolver})이 듭니다. */
    @FunctionalInterface
    interface ScriptSource {
        Optional<String> fetch(URI script);
    }

    /** 페이지에서 처음 보인 그 함수의 호출. */
    record Call(String function, List<String> args) {}

    /** 함수의 매개변수 이름들과 본문(중괄호 안). */
    record Function(List<String> params, String body) {}

    /** 만들어진 주소, 또는 왜 못 만들었는지. */
    record Built(String url, String note) {
        static Built failure(String note) { return new Built(null, note); }
        boolean ok() { return url != null; }
    }

    static final int MAX_SCRIPTS = 24;
    /** 이름에 이것이 든 스크립트를 먼저 받습니다. 성북은 열일곱 스크립트 가운데 열여섯째 {@code search.js} 에 있었습니다. */
    static final Pattern LIKELY_SCRIPT = Pattern.compile("search|book|detail|view|opac|alpas", Pattern.CASE_INSENSITIVE);
    /** 주소에 남으면 규칙이 오늘만 되는 칸. 조사 스크립트의 {@code TOKEN_FIELD} 와 같습니다. */
    static final Pattern TOKEN_FIELD = Pattern.compile("csrf|token|nonce|viewstate|eventvalidation", Pattern.CASE_INSENSITIVE);

    /**
     * 그 함수의 <b>첫 호출</b>. ISBN 으로 검색한 결과라 첫 줄이 그 책이고, 정규식 규칙이 첫 링크를
     * 쓰는 것과 같은 전제입니다. {@code onclick="..."} 과 {@code href="javascript:..."} 둘 다 봅니다.
     */
    static Optional<Call> firstCall(String html, String function) {
        Matcher m = callPattern(function).matcher(html);
        if (!m.find()) return Optional.empty();
        return Optional.of(new Call(function, splitArgs(DetailResolver.unescape(m.group(1)))));
    }

    /**
     * 그 함수의 <b>모든 호출</b>을 문서에 놓인 자리와 함께. 제목 검색 결과에서는 첫 호출이 그 책이
     * 아니므로({@code @anchor:}) 어느 호출이 그 ISBN 의 항목에 있는지 자리를 견줘 고릅니다.
     */
    static List<IsbnAnchor.Span<Call>> allCalls(String html, String function) {
        List<IsbnAnchor.Span<Call>> out = new ArrayList<>();
        Matcher m = callPattern(function).matcher(html);
        while (m.find()) {
            out.add(new IsbnAnchor.Span<>(m.start(), m.end(),
                    new Call(function, splitArgs(DetailResolver.unescape(m.group(1))))));
        }
        return out;
    }

    private static Pattern callPattern(String function) {
        return Pattern.compile(
                "(?:onclick|href)\\s*=\\s*[\"']\\s*(?:javascript:)?\\s*" + Pattern.quote(function) + "\\s*\\(([^)]*)\\)");
    }

    /** 인자 목록. 따옴표 안의 쉼표는 나누지 않고, 따옴표는 벗깁니다. */
    static List<String> splitArgs(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (char c : raw.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0; else current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ',') {
                out.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!raw.isBlank()) out.add(current.toString().strip());
        return out;
    }

    /** 본문에서 {@code function NAME(...){...}} 또는 {@code NAME = function(...){...}} 을 찾아 중괄호를 맞춰 끊습니다. */
    static Optional<Function> findFunction(String text, String function) {
        if (text == null) return Optional.empty();
        String q = Pattern.quote(function);
        Matcher m = Pattern.compile("function\\s+" + q + "\\s*\\(([^)]*)\\)\\s*\\{").matcher(text);
        if (!m.find()) {
            m = Pattern.compile("(?<![\\w$.])" + q + "\\s*[=:]\\s*function\\s*\\(([^)]*)\\)\\s*\\{").matcher(text);
            if (!m.find()) return Optional.empty();
        }
        int depth = 1;
        int i = m.end();
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return Optional.empty();
        List<String> params = new ArrayList<>();
        for (String p : m.group(1).split(",")) if (!p.isBlank()) params.add(p.strip());
        return Optional.of(new Function(params, text.substring(m.end(), i - 1)));
    }

    /**
     * 페이지가 읽어 들이는 스크립트 파일. 같은 기관의 것만, {@value #MAX_SCRIPTS}개까지. 함수 본문이
     * 페이지 안에 없을 때 차례로 받아 보는 데 씁니다. <b>이름에 search·book·detail 같은 말이 든 것을
     * 먼저</b>, 나머지는 문서 순서입니다. jQuery 와 달력 플러그인에 상세 함수가 있을 리 없는데 그것들이
     * 앞에 열 개씩 서 있어서, 문서 순서로만 받으면 그만큼 남의 서버를 헛되이 두드립니다.
     */
    static List<URI> scriptUrls(String html, URI base) {
        List<URI> likely = new ArrayList<>();
        List<URI> rest = new ArrayList<>();
        Matcher m = Pattern.compile("<script\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find() && likely.size() + rest.size() < MAX_SCRIPTS) {
            String src = DetailResolver.unescape(m.group(1)).strip().replace(" ", "%20");
            if (src.contains("\n") || src.contains("\r")) continue;
            try {
                URI u = DetailResolver.resolve(base, src);
                if (u.getHost() == null || !DetailResolver.sameOrganisation(u.getHost(), base.getHost())) continue;
                String path = u.getPath() == null ? "" : u.getPath();
                (LIKELY_SCRIPT.matcher(path.substring(path.lastIndexOf('/') + 1)).find() ? likely : rest).add(u);
            } catch (IllegalArgumentException ignored) {
                // 주소가 아닌 src 는 건너뜁니다.
            }
        }
        likely.addAll(rest);
        return likely;
    }

    // ── 함수 본문 읽기 ─────────────────────────────────────────────────────

    private static final Pattern FIELD_BY_VALUE = Pattern.compile(
            "([\\w$]+)\\s*\\.\\s*([\\w$]+)\\s*\\.\\s*value\\s*=\\s*([^;]+);");
    private static final Pattern FIELD_BY_FIND = Pattern.compile(
            "find\\(\\s*[\"']input\\[name=[\"']?([\\w$]+)[\"']?]\\s*[\"']\\s*\\)\\s*\\.val\\(\\s*([^)]+)\\)");
    private static final Pattern FIELD_BY_NAME_SELECTOR = Pattern.compile(
            "\\$\\(\\s*[\"']input\\[name=[\"']?([\\w$]+)[\"']?]\\s*[\"']\\s*\\)\\s*\\.val\\(\\s*([^)]+)\\)");
    private static final Pattern FIELD_BY_ID_SELECTOR = Pattern.compile(
            "\\$\\(\\s*[\"']#([\\w$-]+)[\"']\\s*\\)\\s*\\.val\\(\\s*([^)]+)\\)");
    private static final Pattern ACTION_ASSIGN = Pattern.compile("\\.action\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ACTION_ATTR = Pattern.compile("\\.attr\\(\\s*[\"']action[\"']\\s*,\\s*[\"']([^\"']+)[\"']");
    private static final Pattern FORM_DOCUMENT = Pattern.compile("document\\.(?!getElementById|forms|querySelector)([\\w$]+)");
    private static final Pattern FORM_BY_ID = Pattern.compile("getElementById\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern FORM_FORMS = Pattern.compile("document\\.forms\\[\\s*[\"']([^\"']+)[\"']\\s*]");
    private static final Pattern FORM_SELECTOR = Pattern.compile("form\\[name=[\"']?([\\w$]+)[\"']?]");
    private static final Pattern FORM_HASH = Pattern.compile("\\$\\(\\s*[\"']#([\\w$-]+)[\"']\\s*\\)\\s*\\.(?:attr\\(\\s*[\"']action|submit)");
    private static final Pattern ENCODE_WRAP = Pattern.compile("(?:encodeURIComponent|encodeURI|escape)\\(\\s*([\\w$]+)\\s*\\)");

    /**
     * 호출과 본문으로 주소를 만듭니다. 어느 단계에서 모양을 못 알아보면 그 이유를 적어 실패합니다.
     *
     * @param html 검색 결과 페이지. 폼 태그와 숨은 칸을 여기서 찾습니다.
     * @param base 상대 action 을 풀 기준. 리다이렉트를 따라간 뒤의 주소입니다.
     */
    static Built build(String html, URI base, Call call, Function fn) {
        if (fn.params().size() < call.args().size()) {
            // 인자가 매개변수보다 많으면 다른 함수(같은 이름의 오버로드)입니다. 적게 넘기는 것은 흔합니다.
            return Built.failure("호출 인자 " + call.args().size() + "개가 매개변수 " + fn.params().size() + "개보다 많습니다");
        }
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < call.args().size(); i++) env.put(fn.params().get(i), call.args().get(i));

        String body = fn.body();
        LinkedHashMap<String, String> assigned = new LinkedHashMap<>();
        String formName = null;
        Matcher m = FIELD_BY_VALUE.matcher(body);
        while (m.find()) {
            if (m.group(1).equals("document")) continue;           // document.X.value 는 폼이 아니라 칸이 아닙니다
            String value = evaluate(m.group(3), env);
            if (value != null) assigned.put(m.group(2), value);
        }
        for (Pattern p : List.of(FIELD_BY_FIND, FIELD_BY_NAME_SELECTOR)) {
            m = p.matcher(body);
            while (m.find()) {
                String value = evaluate(m.group(2), env);
                if (value != null) assigned.put(m.group(1), value);
            }
        }
        m = FIELD_BY_ID_SELECTOR.matcher(body);
        while (m.find()) {
            String value = evaluate(m.group(2), env);
            if (value == null) continue;
            // id 로 집은 칸은 전송될 때 name 으로 가므로 HTML 에서 그 id 의 name 을 찾습니다.
            assigned.put(nameOfInputWithId(html, m.group(1)).orElse(m.group(1)), value);
        }
        if (assigned.isEmpty()) return Built.failure("함수 본문에서 폼 칸에 인자를 넣는 자리를 찾지 못했습니다");

        String action = firstGroup(ACTION_ASSIGN, body);
        if (action == null) action = firstGroup(ACTION_ATTR, body);

        // document.X 는 「document.X.Y.value」의 X 도 잡으므로 가장 먼저 나온 것을 폼으로 봅니다.
        for (Pattern p : List.of(FORM_BY_ID, FORM_FORMS, FORM_SELECTOR, FORM_DOCUMENT, FORM_HASH)) {
            String name = firstGroup(p, body);
            if (name != null) { formName = name; break; }
        }

        String method = "get";
        LinkedHashMap<String, String> hidden = new LinkedHashMap<>();
        Optional<FormTag> form = formName == null ? Optional.empty() : findForm(html, formName);
        if (form.isPresent()) {
            method = form.get().method();
            if (action == null) action = form.get().action();
            hidden.putAll(form.get().hidden());
        }
        if (action == null || action.isBlank()) {
            return Built.failure("폼의 action 을 찾지 못했습니다 (폼 " + (formName == null ? "?" : formName) + ")");
        }

        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        for (var e : hidden.entrySet()) {
            if (TOKEN_FIELD.matcher(e.getKey()).find()) continue;   // 세션에 묶인 값은 넣어도 다음 사람에게 틀립니다
            query.put(e.getKey(), e.getValue());
        }
        query.putAll(assigned);

        StringBuilder qs = new StringBuilder();
        for (var e : query.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        String target = action + (action.contains("?") ? "&" : "?") + qs;
        try {
            return new Built(DetailResolver.resolve(base, target.replace(" ", "%20")).toString(),
                    "폼 " + (formName == null ? "?" : formName) + " (" + method + ") 을 GET 으로 만들었습니다");
        } catch (IllegalArgumentException e) {
            return Built.failure("action 을 주소로 만들지 못했습니다: " + action);
        }
    }

    /** 매개변수 이름이면 인자, 따옴표 글자면 그 글자, 그 밖은 모르는 것(null). */
    static String evaluate(String expr, Map<String, String> env) {
        String e = expr.strip();
        if (env.containsKey(e)) return env.get(e);
        Matcher wrap = ENCODE_WRAP.matcher(e);
        if (wrap.matches() && env.containsKey(wrap.group(1))) return env.get(wrap.group(1));
        if (e.length() >= 2 && (e.charAt(0) == '"' || e.charAt(0) == '\'') && e.charAt(e.length() - 1) == e.charAt(0)) {
            return e.substring(1, e.length() - 1);
        }
        return null;
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** HTML 의 폼 태그 하나. */
    record FormTag(String method, String action, Map<String, String> hidden) {}

    private static final Pattern FORM_OPEN = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** name 또는 id 가 그 이름인 폼과, 그 안의 숨은 칸들(name → value). */
    static Optional<FormTag> findForm(String html, String nameOrId) {
        Matcher m = FORM_OPEN.matcher(html);
        while (m.find()) {
            String tag = m.group(0);
            String name = attr(tag, "name");
            String id = attr(tag, "id");
            if (!nameOrId.equals(name) && !nameOrId.equals(id)) continue;
            int end = html.toLowerCase(Locale.ROOT).indexOf("</form>", m.end());
            String inner = html.substring(m.end(), end < 0 ? html.length() : end);
            LinkedHashMap<String, String> hidden = new LinkedHashMap<>();
            Matcher in = INPUT.matcher(inner);
            while (in.find()) {
                String input = in.group(0);
                String type = attr(input, "type");
                if (type == null || !type.equalsIgnoreCase("hidden")) continue;
                String n = attr(input, "name");
                if (n == null) continue;
                String v = attr(input, "value");
                hidden.putIfAbsent(n, v == null ? "" : DetailResolver.unescape(v));
            }
            String method = attr(tag, "method");
            String action = attr(tag, "action");
            return Optional.of(new FormTag(method == null ? "get" : method.toLowerCase(Locale.ROOT),
                    action == null ? null : DetailResolver.unescape(action), hidden));
        }
        return Optional.empty();
    }

    static Optional<String> nameOfInputWithId(String html, String id) {
        Matcher in = INPUT.matcher(html);
        while (in.find()) {
            if (id.equals(attr(in.group(0), "id"))) return Optional.ofNullable(attr(in.group(0), "name"));
        }
        return Optional.empty();
    }

    /** 태그 글자에서 속성값. 따옴표가 있으면 그 안, 없으면 공백까지. */
    static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(tag);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
    }
}
