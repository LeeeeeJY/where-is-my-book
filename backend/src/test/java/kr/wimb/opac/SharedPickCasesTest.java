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
        for (String kind : List.of("visible", "mentions", "pickAll", "rows", "rules")) {
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
}
