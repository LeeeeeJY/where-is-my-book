package kr.wimb.opac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * 서버와 조사 도구가 <b>같은 화면에 같은 답</b>을 내는지 봅니다. 사례는 {@code /opac/cases/cases.json} 한
 * 곳에 있고, {@code scripts/test-opac-title-probe.py} 가 같은 파일을 읽어 도구의 답을 견줍니다.
 *
 * <p>왜 파일을 함께 읽는가. 서버가 누를 때 고르는 줄과 도구가 넣기 전에 확인하는 줄이 다르면, 도구가
 * 확인한 규칙이 서버에서는 다른 책으로 갑니다. {@link IsbnAnchorTest} 는 같은 목록을 두 테스트에 따로
 * 적어 두었는데, 따로 적은 것은 한쪽만 고쳐도 둘 다 통과합니다. 상세 화면의 ISBN 으로 고르는 것
 * ({@link IsbnVerify})과 큰글자책 줄을 건너뛰는 것({@link PlainRows})은 글자를 읽는 규칙까지 맞아야 해서
 * 한 파일로 묶었습니다.
 *
 * <p>화면은 2026-11-25 에 국내 회선에서 받아 본 것을 줄인 것입니다. 지어낸 것은 파일 머리에 그렇게
 * 적었습니다.
 *
 * <p>받기 방식 둘({@code @frame:POST}, {@code @frame:COOKIE})도 여기서 견줍니다. 규칙 한 줄이 어떤 요청으로
 * 바뀌는지({@code frames}), 어느 줄을 받아 주지 않는지({@code frameProblems}), 응답 머리의 쿠키를 어떻게
 * 담고 싣는지({@code cookies}), 어떤 화면을 「아직 받지 못한 화면」으로 보는지({@code scriptOnly}), 그리고
 * 가짜 사이트에 대고 규칙 한 줄이 내보내는 <b>요청의 차례와 끝</b>({@code sites})입니다. 쿠키를 실었는지까지
 * 같은 차례여야 합니다.
 */
class SharedPickCasesTest {

    private static final JsonNode CASES = load();

    private static JsonNode load() {
        try (InputStream in = SharedPickCasesTest.class.getResourceAsStream("/opac/cases/cases.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String file(String name) {
        try (InputStream in = SharedPickCasesTest.class.getResourceAsStream("/opac/cases/" + name)) {
            assertNotNull(in, "사례 파일이 없습니다: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 사례가 화면을 파일로 가리키면 그 파일, 아니면 사례에 적힌 HTML. */
    private static String pageOf(JsonNode c) {
        return c.has("file") ? file(c.get("file").asText()) : c.get("html").asText();
    }

    private static List<IsbnAnchor.Span<String>> spans(String html, String regex) {
        List<IsbnAnchor.Span<String>> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(html);
        while (m.find()) out.add(new IsbnAnchor.Span<>(m.start(), m.end(), m.group(1)));
        return out;
    }

    @Test
    @DisplayName("사례 파일이 비어 있지 않다 (파일을 잃으면 아래 테스트가 전부 조용히 0건이 됩니다)")
    void casesArePresent() {
        for (String kind : List.of("visible", "mentions", "pickAll", "rows", "rules",
                "frames", "frameProblems", "cookies", "scriptOnly", "sites")) {
            assertNotNull(CASES.get(kind), kind + " 사례가 없습니다");
            assertTrue(CASES.get(kind).size() >= 5, kind + " 사례가 " + CASES.get(kind).size() + "건뿐입니다");
        }
    }

    @TestFactory
    @DisplayName("화면의 글자")
    List<DynamicTest> visibleText() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("visible")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(),
                    () -> assertEquals(c.get("text").asText(), RowText.visible(c.get("html").asText()))));
        }
        return out;
    }

    @TestFactory
    @DisplayName("상세 화면에 그 ISBN 이 적혀 있는가")
    List<DynamicTest> mentions() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("mentions")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertEquals(
                    c.get("expect").asBoolean(), IsbnVerify.mentions(pageOf(c), c.get("isbn").asText()))));
        }
        return out;
    }

    @TestFactory
    @DisplayName("그 ISBN 의 줄 전부")
    List<DynamicTest> pickAll() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("pickAll")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                String html = pageOf(c);
                var all = spans(html, c.get("regex").asText());
                boolean linkFirst = c.get("linkFirst").asBoolean();
                var picks = IsbnAnchor.pickAll(html, all, c.get("isbn").asText(), linkFirst);
                List<String> want = new ArrayList<>();
                c.get("expect").forEach(n -> want.add(n.asText()));
                assertEquals(want, picks.spans().stream().map(IsbnAnchor.Span::payload).toList(), picks.how());
                // 첫 자리는 닻 규칙이 고르는 줄과 같아야 합니다. 다르면 @plain: 을 붙인 것만으로 다른 줄로 갑니다.
                var single = IsbnAnchor.pick(html, all, c.get("isbn").asText(), linkFirst);
                assertEquals(want.isEmpty() ? null : want.get(0), single.payload(), single.how());
            }));
        }
        return out;
    }

    @TestFactory
    @DisplayName("줄마다의 글자와 판 표기")
    List<DynamicTest> rows() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("rows")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                String html = pageOf(c);
                var rows = PlainRows.rows(html, spans(html, c.get("regex").asText()));
                assertEquals(c.get("expect").size(), rows.size(), "줄 수: " + rows);
                for (int i = 0; i < rows.size(); i++) {
                    JsonNode want = c.get("expect").get(i);
                    var row = rows.get(i);
                    String at = (i + 1) + "번째 줄 「" + row.text() + "」";
                    assertEquals(want.get("marked").asBoolean(), row.marked(), at);
                    if (want.has("has")) assertTrue(row.text().contains(want.get("has").asText()), at);
                    if (want.has("lacks")) assertFalse(row.text().contains(want.get("lacks").asText()), at);
                    if (want.has("text")) assertEquals(want.get("text").asText(), row.text(), at);
                }
            }));
        }
        return out;
    }

    /** 사례의 화면과 상세 화면을 주는 가짜 서버. 받아 간 상세 주소를 차례대로 적어 둡니다. */
    private static final class CaseSite implements DetailResolver.Fetcher {
        final Map<String, String> pages = new HashMap<>();
        final List<String> missing = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        String searchUrl;

        @Override public DetailResolver.Page fetch(URI url) {
            String key = url.toString();
            if (key.endsWith("/robots.txt")) return new DetailResolver.Page(404, "", url);
            if (!key.equals(searchUrl)) details.add(key);
            if (missing.contains(key) || !pages.containsKey(key)) return new DetailResolver.Page(404, "", url);
            return new DetailResolver.Page(200, pages.get(key), url);
        }
    }

    @TestFactory
    @DisplayName("규칙 한 줄을 통째로 (서버의 DetailResolver, 도구의 run_rule)")
    List<DynamicTest> rules() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("rules")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                var site = new CaseSite();
                site.searchUrl = c.get("url").asText();
                site.pages.put(site.searchUrl, pageOf(c));
                if (c.has("details")) {
                    CASES.get("detailSets").get(c.get("details").asText()).fields()
                            .forEachRemaining(e -> site.pages.put(e.getKey(), file(e.getValue().asText())));
                }
                if (c.has("missing")) c.get("missing").forEach(n -> site.missing.add(n.asText()));
                List<String> isbns = new ArrayList<>();
                c.get("isbns").forEach(n -> isbns.add(n.asText()));

                var outcome = new DetailResolver(site, Clock.systemUTC()).resolve(site.searchUrl,
                        DetailRule.parse(c.get("rule").asText()),
                        new DetailResolver.Context(isbns.get(0), "000000", isbns));

                String want = c.get("expect").isNull() ? null : c.get("expect").asText();
                assertEquals(want, outcome.detailUrl(), outcome.status() + " " + outcome.note());
                List<String> fetched = new ArrayList<>();
                c.get("fetched").forEach(n -> fetched.add(n.asText()));
                assertEquals(fetched, site.details, "받아 본 후보 상세의 차례");
            }));
        }
        return out;
    }
    // ── 받기 방식 둘: @frame:POST, @frame:COOKIE ─────────────────────────────

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    @TestFactory
    @DisplayName("프레임 규칙이 받는 요청 (서버의 frameUrlFor·frameBodyFor, 도구의 frame_request)")
    List<DynamicTest> frames() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("frames")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                var frame = (DetailRule.Frame) DetailRule.parse(c.get("rule").asText());
                var context = new DetailResolver.Context(c.get("isbn").asText(), c.get("libCode").asText())
                        .withTitle(c.get("title").asText());
                JsonNode want = c.get("expect");
                assertEquals(want.get("method").asText(), frame.method());
                assertEquals(want.get("url").asText(), DetailResolver.frameUrlFor(frame, context));
                String body = DetailResolver.frameBodyFor(frame, context);
                assertEquals(textOrNull(want.get("body")), body);
                assertEquals(textOrNull(want.get("contentType")), body == null ? null : DetailResolver.bodyTypeOf(body));
                assertEquals(want.get("cookie").asBoolean(), frame.carriesCookies());
                assertEquals(c.get("describe").asText(), frame.describe(), "되돌려 말한 한 줄");
                // 되돌려 말한 줄을 다시 읽어도 같은 규칙입니다. 진단에 적힌 줄을 그대로 옮겨 적을 수 있어야 합니다.
                // (정규식은 값으로 견줄 수 없어 되돌려 말한 줄로 견줍니다.)
                assertEquals(frame.describe(), DetailRule.parse(frame.describe()).describe());
            }));
        }
        return out;
    }

    @TestFactory
    @DisplayName("받아 주지 않는 프레임 규칙 (서버는 뜨지 않고, 도구는 넣지 않습니다)")
    List<DynamicTest> frameProblems() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("frameProblems")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> DetailRule.parse(c.get("rule").asText()))));
        }
        return out;
    }

    @TestFactory
    @DisplayName("응답 머리의 쿠키를 담고 싣기 (서버의 SessionCookies, 도구의 cookie_store·cookie_header)")
    List<DynamicTest> cookies() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("cookies")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                Map<String, String> jar = new java.util.LinkedHashMap<>();
                List<Boolean> changed = new ArrayList<>();
                for (JsonNode step : c.get("steps")) {
                    List<String> headers = new ArrayList<>();
                    step.forEach(n -> headers.add(n.asText()));
                    changed.add(SessionCookies.store(jar, headers));
                }
                List<Boolean> want = new ArrayList<>();
                c.get("changed").forEach(n -> want.add(n.asBoolean()));
                assertEquals(want, changed, "응답마다 통이 달라졌는가");
                assertEquals(textOrNull(c.get("header")), SessionCookies.header(jar));
            }));
        }
        return out;
    }

    @TestFactory
    @DisplayName("글자 없이 스크립트만 있는 화면인가 (서버의 SessionCookies.scriptOnly, 도구의 script_only)")
    List<DynamicTest> scriptOnly() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("scriptOnly")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(),
                    () -> assertEquals(c.get("expect").asBoolean(), SessionCookies.scriptOnly(pageOf(c)))));
        }
        return out;
    }

    /**
     * 사례에 적힌 가짜 사이트. 받은 요청을 「METHOD 주소[ body=…][ cookie=…]」 한 줄씩 적어 둡니다.
     *
     * <p>{@code site.cookie} 가 있으면 그 쿠키({@code need})를 실은 요청에만 화면을 주고, 나머지에는
     * {@code stub} 과 {@code Set-Cookie}({@code set})를 줍니다. 실제 받기처럼 <b>쿠키를 드는 규칙의 요청에만</b>
     * 응답의 쿠키를 돌려줍니다. robots.txt 는 없는 것으로 답하고 적지 않습니다.
     */
    private static final class FakeSite implements DetailResolver.Fetcher {
        final JsonNode site;
        final List<String> requests = new ArrayList<>();

        FakeSite(JsonNode site) {
            this.site = site;
        }

        @Override public DetailResolver.Page fetch(URI url) {
            return fetch(Request.get(url));
        }

        @Override public DetailResolver.Page fetch(Request request) {
            String url = request.url().toString();
            if (url.endsWith("/robots.txt")) return new DetailResolver.Page(404, "", request.url());
            requests.add(request.method() + " " + url + (request.body() == null ? "" : " body=" + request.body())
                    + (request.cookie() == null ? "" : " cookie=" + request.cookie()));
            JsonNode cookie = site.get("cookie");
            if (cookie != null && !cookie.isNull()) {
                boolean carried = request.cookie() != null
                        && List.of(request.cookie().split("; ")).contains(cookie.get("need").asText());
                if (!carried) {
                    List<String> set = new ArrayList<>();
                    if (request.session()) cookie.get("set").forEach(n -> set.add(n.asText()));
                    return new DetailResolver.Page(200, cookie.get("stub").asText(), request.url(), set);
                }
            }
            for (JsonNode page : site.get("pages")) {
                if (page.get("method").asText().equals(request.method()) && page.get("url").asText().equals(url)
                        && java.util.Objects.equals(textOrNull(page.get("body")), request.body())) {
                    return new DetailResolver.Page(200, file(page.get("file").asText()), request.url());
                }
            }
            return new DetailResolver.Page(404, "", request.url());
        }
    }

    @TestFactory
    @DisplayName("가짜 사이트에 대고 프레임 규칙 한 줄을 통째로: 요청의 차례와 끝 (도구의 run_frame)")
    List<DynamicTest> sites() {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : CASES.get("sites")) {
            out.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                var site = new FakeSite(c.get("site"));
                List<String> isbns = new ArrayList<>();
                c.get("isbns").forEach(n -> isbns.add(n.asText()));
                var context = new DetailResolver.Context(isbns.get(0), c.get("libCode").asText(), isbns)
                        .withTitle(c.get("title").asText());

                var outcome = new DetailResolver(site, Clock.systemUTC()).resolve(c.get("searchUrl").asText(),
                        DetailRule.parse(c.get("rule").asText()), context);

                List<String> requests = new ArrayList<>();
                c.get("requests").forEach(n -> requests.add(n.asText()));
                assertEquals(requests, site.requests, "내보낸 요청의 차례 (" + outcome.note() + ")");
                assertEquals(c.get("expect").get("status").asText(), outcome.status().name(), outcome.note());
                assertEquals(textOrNull(c.get("expect").get("url")), outcome.detailUrl(), outcome.note());
                // 쿠키의 값은 사유에 실리지 않습니다. 진단의 답은 인증 없이 열립니다.
                JsonNode cookie = c.get("site").get("cookie");
                if (cookie != null && !cookie.isNull()) {
                    for (JsonNode set : cookie.get("set")) {
                        String value = set.asText().split(";")[0].split("=", 2)[1];
                        // 규칙 줄에 이미 적힌 글자(thisLib 의 값이 locaInfo 쿠키의 값과 같습니다)는 쿠키에서 온 것이 아닙니다.
                        if (value.length() <= 1 || c.get("rule").asText().contains(value)) continue;
                        assertFalse(outcome.note().contains(value), "사유에 쿠키의 값이 실렸습니다: " + outcome.note());
                    }
                }
            }));
        }
        return out;
    }
}
