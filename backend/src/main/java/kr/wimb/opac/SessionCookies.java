package kr.wimb.opac;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 도서관 사이트가 <b>응답 머리로 준 쿠키</b>를 한 번 누르는 동안만 드는 일. {@code @frame:COOKIE} 규칙이 씁니다.
 *
 * <p>대전 서구의 결과 프레임({@code lib.seogu.go.kr/ilus/index.php?…})은 쿠키 없이 받으면
 * {@code <script>location.reload();</script>} 35바이트와 함께 응답 머리로 {@code Set-Cookie}
 * ({@code PHPSESSID}, {@code skin}, {@code locaInfo})를 주고, 그 쿠키를 들고 다시 받아야 목록을 줍니다
 * (2026-11-25 에 국내 회선에서 받아 본 것. {@code docs/가정과-검증상태.md} 2-48, 2-49). 상세 화면도 같습니다.
 * 브라우저는 그 스크립트로 스스로 새로 고칩니다. 서버는 스크립트를 돌리지 않으므로 넘지 못했습니다.
 *
 * <p><b>운영자가 2026-11-25 에 대전 서구에 한해 허용했습니다.</b> 범위는 이렇게 묶었습니다.
 * <ul>
 *   <li><b>호스트.</b> {@code cookie-hosts.txt} 에 적힌 호스트이고, 규칙 줄이 {@code @frame:COOKIE} 로 적은
 *       곳에서만 합니다. 그 파일에 없는 호스트에 {@code COOKIE} 를 적은 줄은 서버가 뜨지 않습니다
 *       ({@link DetailRule}). 나머지 받기는 쿠키를 들지 않고, 응답의 {@code Set-Cookie} 를 읽지도 않습니다.
 *       <b>호스트를 더하는 것은 운영자가 정할 일입니다.</b></li>
 *   <li><b>응답 머리로 온 쿠키만.</b> 스크립트가 값을 계산해 심는 쿠키는 얻을 방법이 없고 얻으려 하지도
 *       않습니다. 쿠키를 들고 다시 받아도 스크립트만 있는 화면이면 「받지 못함」입니다.</li>
 *   <li><b>한 번 누르는 동안만.</b> 통은 누름 한 번의 몫({@code DetailResolver.Budget})에 있고 누름이 끝나면
 *       함께 버려집니다. 파일에도 캐시에도 남기지 않고, 다른 방문자의 누름과 나눠 쓰지 않습니다.</li>
 *   <li><b>받은 곳으로만.</b> 통은 출처(스킴·호스트·포트)마다 따로이고, 규칙이 받는 주소의 출처 하나에만
 *       씁니다. 그 요청은 넘겨 주는 응답(3xx)을 따라가지 않습니다. 따라가면 쿠키가 다른 호스트로 실려 갑니다.</li>
 *   <li><b>다시 받는 것은 누름 한 번에 한 번.</b> {@code DetailResolver.MAX_COOKIE_REFETCHES}.</li>
 * </ul>
 *
 * <p><b>쿠키의 값은 어디에도 적지 않습니다.</b> 진단의 사유에도 호스트별 집계에도 로그에도 싣지 않습니다.
 *
 * <p>조사 도구({@code scripts/opac-title-probe.py} 의 {@code cookie_store}·{@code cookie_header}·
 * {@code script_only})가 같은 일을 합니다. 양쪽 테스트가 같은 사례 파일을 읽습니다. <b>한쪽을 고치면 다른
 * 쪽도 고치세요.</b>
 */
public final class SessionCookies {

    static final String HOSTS_RESOURCE = "/opac/cookie-hosts.txt";

    /** 통 하나에 담는 쿠키 수의 상한. 이상한 서버가 수백 개를 주어도 요청 머리가 부풀지 않게 합니다. */
    static final int MAX_COOKIES = 20;
    /** 쿠키 하나의 「이름=값」 길이의 상한. 넘는 것은 담지 않습니다. */
    static final int MAX_PAIR_LENGTH = 1024;

    private static final Pattern HOST =
            Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");
    /** RFC 6265 의 이름(token)과 값(cookie-octet). 공백·쉼표·세미콜론·따옴표·역슬래시·제어 문자는 받지 않습니다. */
    private static final Pattern NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
    private static final Pattern VALUE = Pattern.compile("[!#$%&'()*+\\-./0-9:<=>?@A-Z\\[\\]^_`a-z{|}~]*");
    private static final Pattern MAX_AGE =
            Pattern.compile(";[ \\t]*max-age[ \\t]*=[ \\t]*(-?[0-9]+)[ \\t]*(?:;|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE);

    private SessionCookies() {}

    /** 파일은 클래스가 처음 쓰일 때 한 번 읽습니다. 형식이 틀리면 서버가 뜨지 않습니다. */
    private static final class Loaded {
        static final Set<String> HOSTS = parseHosts(read(HOSTS_RESOURCE));
    }

    /** 쿠키를 들어도 되는 호스트. 포트 없는 소문자 이름입니다. */
    public static Set<String> hosts() {
        return Loaded.HOSTS;
    }

    public static boolean isCookieHost(String host) {
        return host != null && Loaded.HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** {@code #} 뒤는 주석입니다. 스킴·포트·대문자가 섞인 줄은 조용히 안 맞는 대신 뜰 때 실패합니다. */
    static Set<String> parseHosts(String text) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String raw : text.split("\n")) {
            int hash = raw.indexOf('#');
            String line = (hash < 0 ? raw : raw.substring(0, hash)).trim();
            if (line.isEmpty()) continue;
            if (!HOST.matcher(line).matches()) {
                throw new IllegalStateException(HOSTS_RESOURCE + " 의 줄이 호스트 이름이 아닙니다"
                        + "(소문자로, 스킴과 포트 없이 적습니다): " + line);
            }
            hosts.add(line);
        }
        return Set.copyOf(hosts);
    }

    private static String read(String resource) {
        try (InputStream in = SessionCookies.class.getResourceAsStream(resource)) {
            // 파일이 없으면 쿠키를 드는 호스트가 없는 것입니다. COOKIE 를 적은 규칙 줄이 있으면 그 줄에서 막힙니다.
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 응답의 {@code Set-Cookie} 머리들을 통에 담습니다. 통이 달라졌으면 참입니다.
     *
     * <p>담는 것은 <b>「이름=값」뿐</b>입니다. {@code Path}·{@code Domain}·{@code Expires}·{@code Secure} 는
     * 읽지 않습니다. 통이 출처 하나의 것이고 누름 한 번(몇 초)만 살기 때문입니다. 다만 지우라는 뜻은
     * 읽습니다. 값이 비었거나 {@code Max-Age} 가 0 이하면 그 이름을 통에서 뺍니다(PHP 가 쿠키를 지울 때
     * 그렇게 보냅니다). 이름이나 값에 받지 않는 글자가 있으면 그 쿠키는 담지 않습니다. 요청 머리에 그대로
     * 실을 글자라서입니다.
     */
    static boolean store(Map<String, String> jar, List<String> setCookies) {
        boolean changed = false;
        if (setCookies == null) return false;
        for (String header : setCookies) {
            if (header == null) continue;
            int semi = header.indexOf(';');
            String pair = trim(semi < 0 ? header : header.substring(0, semi));
            int eq = pair.indexOf('=');
            if (eq <= 0 || pair.length() > MAX_PAIR_LENGTH) continue;
            String name = trim(pair.substring(0, eq));
            String value = trim(pair.substring(eq + 1));
            if (!NAME.matcher(name).matches() || !VALUE.matcher(value).matches()) continue;
            Matcher age = semi < 0 ? null : MAX_AGE.matcher(header.substring(semi));
            boolean expired = age != null && age.find() && age.group(1).matches("-[0-9]+|0+");
            if (value.isEmpty() || expired) {
                changed |= jar.remove(name) != null;
            } else if (jar.containsKey(name) || jar.size() < MAX_COOKIES) {
                changed |= !value.equals(jar.put(name, value));
            }
        }
        return changed;
    }

    /** 양 끝의 ASCII 공백만 뗍니다. {@code strip()} 은 자바와 파이썬이 떼는 글자가 달라 도구와 답이 갈립니다. */
    private static String trim(String text) {
        int from = 0;
        int to = text.length();
        while (from < to && " \t\n\u000B\f\r".indexOf(text.charAt(from)) >= 0) from++;
        while (to > from && " \t\n\u000B\f\r".indexOf(text.charAt(to - 1)) >= 0) to--;
        return text.substring(from, to);
    }

    /** 요청의 {@code Cookie} 머리 값. 받은 차례대로 {@code a=1; b=2} 이고, 통이 비었으면 {@code null} 입니다. */
    static String header(Map<String, String> jar) {
        if (jar == null || jar.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> cookie : jar.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(cookie.getKey()).append('=').append(cookie.getValue());
        }
        return out.toString();
    }

    /**
     * <b>글자가 하나도 없고 스크립트만 있는 화면</b>인지. 대전 서구가 쿠키 없는 요청에 주는
     * {@code <script>location.reload();</script>} 가 이것입니다.
     *
     * <p>이런 화면은 「결과가 없다」가 아니라 「아직 화면을 받지 못했다」입니다. 응답 머리로 새 쿠키가
     * 왔으면 그것을 들고 한 번 다시 받고, 그래도 이런 화면이면 「받지 못함」으로 끝냅니다. 스크립트가 값을
     * 계산해 쿠키를 심는 확인 화면이 그렇게 끝납니다.
     */
    static boolean scriptOnly(String html) {
        if (html == null || !SCRIPT_TAG.matcher(html).find()) return false;
        return RowText.visible(html).isEmpty();
    }
}
