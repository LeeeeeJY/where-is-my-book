package kr.wimb.opac;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 도서관별 OPAC 주소 규칙. `resources/opac/templates.csv` 를 읽어 들고 있습니다.
 *
 * <p>정보나루는 <b>홈페이지 주소만</b> 줍니다. 매뉴얼 1절과 13절의 응답 명세를 확인했고,
 * 그 책의 상세 페이지 주소를 주는 항목은 없습니다({@code bookDtlUrl} 은 인기대출도서 계열에만
 * 있고 정보나루 자기 페이지입니다). 그래서 규칙을 우리가 들고 있어야 합니다.
 *
 * <p><b>규칙은 실제로 열어 확인한 것만 넣습니다.</b> 틀린 규칙은 HTTP 200 을 주면서 결과만
 * 0건이 되어, 사용자에게는 「소장한다더니 그 책이 없네」로 보입니다. 링크가 깨진 것보다
 * 나쁩니다. 깨진 링크는 눈에 보이지만 이것은 보이지 않습니다.
 *
 * <p>1,619곳을 전부 채우려 들면 끝나지 않으므로, <b>자주 가는 도서관부터 채우고 나머지는
 * 홈페이지 폴백에 맡깁니다.</b> 대신 어느 단계로 보냈는지를 화면에 밝힙니다.
 *
 * <h2>상세 패턴 (`resources/opac/detail-patterns.csv`)</h2>
 *
 * <p>상세 페이지 주소는 대부분 도서관 <b>내부 키</b>를 씁니다(노원 {@code /bookDetail/MO/629661,…},
 * 김해 {@code book_key=150671418}, 대구 {@code regNo}). 책마다 달라서 미리 만들 수 없고,
 * 그래서 규칙 표의 {@code ISBN_DETAIL} 은 상세 주소에 ISBN 이 그대로 들어가는 드문 OPAC 에만
 * 씁니다. 나머지는 <b>누를 때</b> ISBN 검색 결과를 서버가 받아 그 안의 상세 링크를 뽑아
 * 보냅니다({@link DetailResolver}). 그때 「검색 결과 HTML 에서 상세 링크가 어떻게 생겼는지」가
 * 이 파일이고, 열쇠는 검색 규칙 주소의 호스트(필요하면 경로 조각까지)입니다. 한 OPAC 시스템에
 * 한 줄이면 그 시스템을 쓰는 도서관 전체에 적용됩니다.
 *
 * <p>패턴은 정규식이고 <b>잡을 그룹이 정확히 하나</b>여야 합니다. 그 그룹이 href 값입니다.
 * 빈 문자열에 맞는 패턴은 받지 않습니다. 아무 페이지에서나 「찾았다」가 되어 엉뚱한 곳으로
 * 보내기 때문입니다.
 *
 * <h2>규칙을 통째로 끄는 스위치 ({@code wimb.opac.links-enabled})</h2>
 *
 * <p><b>지금 운영에서는 꺼져 있어 모든 도서관이 홈페이지로 갑니다.</b> 규칙 307줄이 실제
 * OPAC 에서 검증되지 않아 정확도를 믿을 수 없기 때문입니다. 틀린 규칙은 HTTP 200 을 주면서
 * 결과만 0건이라 「소장한다더니 그 책이 없네」로 보이는데, 그것이 소장 정보 자체를 믿지 못하게
 * 만듭니다. <b>검증되지 않은 규칙으로 보내느니 홈페이지로 보내는 편이 낫습니다.</b> 색인을
 * 검증 없이 전환하지 않는 것과 같은 판단입니다.
 *
 * <p><b>그런데 규칙 표는 그대로 읽습니다.</b> 껐다고 파일을 비우거나 빈 객체로 바꾸면 셋을
 * 잃습니다. 보완의 기반인 307줄, {@code /api/status} 의 {@code opacRuleLibraries} 가 「꺼서 0」
 * 인지 「파일을 잃어서 0」인지 가르는 근거, 그리고 배포된 서버에서 규칙을 시험하는
 * {@code /api/diagnose/opac} 입니다. 진단은 {@link #asIfEnabled()} 로 스위치를 건너뛰므로
 * <b>끈 상태에서도 한 줄씩 확인해 가며 채울 수 있습니다.</b>
 */
public final class OpacTemplates {

    private record Template(OpacLink.Kind kind, Charset encoding, String urlTemplate) {}

    /** 검색 결과 HTML 에서 상세 링크를 뽑는 정규식과, 그것이 어느 OPAC 의 것인지. */
    public record DetailPattern(String key, Pattern regex) {}

    private final Map<String, Map<OpacLink.Kind, Template>> byLibCode;
    private final Map<String, DetailPattern> patterns;

    /**
     * 규칙으로 링크를 만들어 줄지. 꺼 두면 규칙을 읽어 들고는 있되 {@link #bestFor} 와
     * {@link #kindFor} 가 아무것도 돌려주지 않아 <b>부르는 쪽이 저절로 홈페이지로 내려앉습니다.</b>
     * 위의 「규칙을 통째로 끄는 스위치」를 보세요.
     */
    private final boolean linksEnabled;

    private OpacTemplates(Map<String, Map<OpacLink.Kind, Template>> byLibCode,
                          Map<String, DetailPattern> patterns, boolean linksEnabled) {
        this.byLibCode = byLibCode;
        this.patterns = patterns;
        this.linksEnabled = linksEnabled;
    }

    /** 자원에서 읽어 들입니다. 파일이 없으면 규칙이 하나도 없는 것으로 보고 넘어갑니다. */
    public static OpacTemplates load() {
        return load(true);
    }

    /** 규칙을 읽어 들이되 링크로 쓸지 말지를 정합니다. 설정이 그 값을 넘깁니다. */
    public static OpacTemplates load(boolean linksEnabled) {
        return load("/opac/templates.csv", "/opac/detail-patterns.csv", linksEnabled);
    }

    static OpacTemplates load(String templatesPath, String patternsPath, boolean linksEnabled) {
        return of(readLines(templatesPath), readLines(patternsPath), linksEnabled);
    }

    private static List<String> readLines(String resourcePath) {
        try (InputStream in = OpacTemplates.class.getResourceAsStream(resourcePath)) {
            if (in == null) return List.of();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 규칙 표만으로. 상세 패턴은 없는 것으로 봅니다. */
    static OpacTemplates parse(List<String> lines) {
        return of(lines, List.of());
    }

    /** 규칙 표와 상세 패턴을 글줄로 받습니다. 테스트가 씁니다. */
    public static OpacTemplates of(List<String> templateLines, List<String> patternLines) {
        return of(templateLines, patternLines, true);
    }

    /** 규칙 표와 상세 패턴을 글줄로 받고, 그것을 링크로 쓸지까지 정합니다. */
    public static OpacTemplates of(List<String> templateLines, List<String> patternLines,
                                   boolean linksEnabled) {
        List<String> problems = new ArrayList<>();
        Map<String, Map<OpacLink.Kind, Template>> templates = parseTemplates(templateLines, problems);
        Map<String, DetailPattern> patterns = parsePatterns(patternLines, problems);
        if (!problems.isEmpty()) {
            // 조용히 넘어가면 규칙이 빠진 채로 배포됩니다. 뜰 때 실패시켜 눈에 띄게 합니다.
            // **스위치를 꺼 두었을 때도 그대로 던집니다.** 지금 쓰지 않는다고 잘못된 줄을
            // 통과시키면, 나중에 켜는 날 그 줄이 남아 있어 그때 서버가 뜨지 않습니다.
            throw new IllegalStateException(
                    "OPAC 주소 규칙을 읽지 못했습니다:\n  " + String.join("\n  ", problems));
        }
        return new OpacTemplates(templates, patterns, linksEnabled);
    }

    private static Map<String, Map<OpacLink.Kind, Template>> parseTemplates(
            List<String> lines, List<String> problems) {
        Map<String, Map<OpacLink.Kind, Template>> out = new HashMap<>();
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // urlTemplate 에 쉼표가 들어갈 수 있으므로 앞의 셋만 나눕니다.
            String[] parts = line.split(",", 4);
            if (parts.length < 4) {
                problems.add(lineNo + "행: 칸이 넷이어야 합니다 — " + line);
                continue;
            }
            OpacLink.Kind kind;
            try {
                kind = OpacLink.Kind.valueOf(parts[1].strip());
            } catch (IllegalArgumentException e) {
                problems.add(lineNo + "행: 모르는 kind — " + parts[1]);
                continue;
            }
            if (kind == OpacLink.Kind.HOMEPAGE) {
                problems.add(lineNo + "행: HOMEPAGE 는 폴백이라 적지 않습니다");
                continue;
            }
            if (kind == OpacLink.Kind.DETAIL_LOOKUP) {
                problems.add(lineNo + "행: DETAIL_LOOKUP 은 적는 것이 아닙니다. ISBN_SEARCH 규칙과 "
                        + "detail-patterns.csv 의 패턴이 함께 있으면 저절로 됩니다");
                continue;
            }
            Charset encoding;
            try {
                encoding = Charset.forName(parts[2].strip());
            } catch (RuntimeException e) {
                problems.add(lineNo + "행: 모르는 인코딩 — " + parts[2]);
                continue;
            }
            String url = parts[3].strip();
            String needed = kind == OpacLink.Kind.TITLE_SEARCH ? "{title}" : "{isbn13}";
            if (!url.contains(needed)) {
                // 자리표가 없으면 어느 책을 넣어도 같은 주소가 나옵니다. 조용히 엉뚱한
                // 페이지로 보내느니 그 줄을 버리는 편이 낫습니다.
                problems.add(lineNo + "행: " + needed + " 자리표가 없습니다 — " + url);
                continue;
            }
            out.computeIfAbsent(parts[0].strip(), k -> new EnumMap<>(OpacLink.Kind.class))
               .put(kind, new Template(kind, encoding, url));
        }
        return out;
    }

    /**
     * 상세 패턴 파일. 한 줄이 {@code 열쇠,정규식} 이고 열쇠는 검색 주소의 호스트, 필요하면
     * {@code 호스트/경로조각} 입니다. 정규식에 쉼표가 있어도 되도록 첫 쉼표에서만 나눕니다.
     */
    private static Map<String, DetailPattern> parsePatterns(List<String> lines,
                                                            List<String> problems) {
        Map<String, DetailPattern> out = new LinkedHashMap<>();
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split(",", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                problems.add("상세 패턴 " + lineNo + "행: 「열쇠,정규식」이어야 합니다 — " + line);
                continue;
            }
            String key = normalizeKey(parts[0]);
            if (key.isEmpty() || key.startsWith("/") || key.contains("://")) {
                problems.add("상세 패턴 " + lineNo + "행: 열쇠는 호스트(또는 호스트/경로)여야 합니다 — "
                        + parts[0]);
                continue;
            }
            Pattern regex;
            try {
                regex = Pattern.compile(parts[1].strip());
            } catch (PatternSyntaxException e) {
                problems.add("상세 패턴 " + lineNo + "행: 정규식이 잘못되었습니다 — " + e.getDescription());
                continue;
            }
            if (regex.matcher("").groupCount() != 1) {
                // 그룹이 없으면 무엇이 주소인지 알 수 없고, 둘 이상이면 어느 것인지 모릅니다.
                problems.add("상세 패턴 " + lineNo + "행: 잡을 그룹이 정확히 하나여야 합니다 — " + parts[1]);
                continue;
            }
            if (regex.matcher("").find()) {
                problems.add("상세 패턴 " + lineNo + "행: 빈 문자열에도 맞는 정규식은 아무 페이지에서나 "
                        + "「찾았다」가 됩니다 — " + parts[1]);
                continue;
            }
            if (out.containsKey(key)) {
                problems.add("상세 패턴 " + lineNo + "행: 열쇠가 겹칩니다 — " + key);
                continue;
            }
            out.put(key, new DetailPattern(key, regex));
        }
        return out;
    }

    private static String normalizeKey(String raw) {
        String key = raw.strip().toLowerCase(Locale.ROOT);
        while (key.endsWith("/")) key = key.substring(0, key.length() - 1);
        return key;
    }

    /**
     * 그 도서관으로 보낼 가장 좋은 링크.
     *
     * <p>상세 → ISBN 검색 → 제목 검색 순으로 내려가고, 규칙이 하나도 없으면 비어 있는 값을
     * 돌려줍니다. 그때는 부르는 쪽이 홈페이지로 보내면 됩니다. <b>스위치가 꺼져 있으면 규칙이
     * 있어도 비어 있는 값입니다.</b> 부르는 쪽에서 보면 규칙이 없는 도서관과 같아서, 홈페이지로
     * 내려앉는 길을 새로 만들 필요가 없습니다. {@code DETAIL_LOOKUP} 은
     * 여기서 나오지 않습니다. ISBN 검색 링크를 받은 쪽이 {@link #detailPatternFor} 로 패턴을
     * 얻어 {@link DetailResolver} 에 넘기면 그것이 상세 조회입니다.
     */
    public Optional<OpacLink> bestFor(String libCode, String isbn13, String title) {
        if (!linksEnabled) return Optional.empty();
        Map<OpacLink.Kind, Template> templates = byLibCode.get(libCode);
        if (templates == null) return Optional.empty();

        for (OpacLink.Kind kind : OpacLink.Kind.values()) {
            Template template = templates.get(kind);
            if (template == null) continue;
            String value = kind == OpacLink.Kind.TITLE_SEARCH ? title : isbn13;
            if (value == null || value.isBlank()) continue;   // 값이 없으면 다음 단계로
            return Optional.of(new OpacLink(fill(template, value, kind), kind));
        }
        return Optional.empty();
    }

    /**
     * 그 도서관 링크가 어느 단계까지 갈 수 있는지. 화면에 미리 밝히는 데 씁니다.
     *
     * <p>ISBN 과 제목이 모두 있다고 보고 계산합니다. 검색 결과에는 둘 다 있습니다.
     * ISBN 검색 규칙에 상세 패턴이 붙어 있으면 {@code DETAIL_LOOKUP} 입니다. <b>누를 때 찾는
     * 것이라 못 찾을 수 있고</b>, 그때는 검색 결과로 내려갑니다. 화면 문구가 그것을 말합니다.
     */
    public OpacLink.Kind kindFor(String libCode) {
        if (!linksEnabled) return OpacLink.Kind.HOMEPAGE;
        Map<OpacLink.Kind, Template> templates = byLibCode.get(libCode);
        if (templates == null) return OpacLink.Kind.HOMEPAGE;
        for (OpacLink.Kind kind : OpacLink.Kind.values()) {
            Template template = templates.get(kind);
            if (template == null) continue;
            if (kind == OpacLink.Kind.ISBN_SEARCH && detailPatternFor(template.urlTemplate()).isPresent()) {
                return OpacLink.Kind.DETAIL_LOOKUP;
            }
            return kind;
        }
        return OpacLink.Kind.HOMEPAGE;
    }

    /**
     * 이 검색 주소의 결과 화면에서 상세 링크를 뽑을 패턴. 열쇠가 여럿 맞으면 <b>가장 긴 것</b>이
     * 이깁니다. 한 호스트에 시스템이 둘 있어 경로로 갈라 둔 경우를 위해서입니다.
     *
     * <p>자리표가 든 규칙 주소({@code /KeywordSearchResult/{isbn13}})도 받아야 하므로 URI 로
     * 파싱하지 않고 글자로 자릅니다.
     */
    public Optional<DetailPattern> detailPatternFor(String url) {
        if (patterns.isEmpty() || url == null) return Optional.empty();
        String rest = url.strip();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) rest = rest.substring(scheme + 3);
        int end = indexOfAny(rest, '?', '#');
        if (end >= 0) rest = rest.substring(0, end);
        int slash = rest.indexOf('/');
        String host = (slash >= 0 ? rest.substring(0, slash) : rest).toLowerCase(Locale.ROOT);
        String hostPath = host + (slash >= 0 ? rest.substring(slash) : "");
        while (hostPath.endsWith("/")) hostPath = hostPath.substring(0, hostPath.length() - 1);

        DetailPattern best = null;
        for (DetailPattern candidate : patterns.values()) {
            String key = candidate.key();
            boolean matches = hostPath.equals(key) || hostPath.startsWith(key + "/");
            if (matches && (best == null || key.length() > best.key().length())) best = candidate;
        }
        return Optional.ofNullable(best);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    /**
     * 규칙을 링크로 쓰고 있는지. {@code /api/status} 가 내보냅니다.
     *
     * <p><b>{@code opacRuleLibraries} 와 함께 보아야 뜻이 통합니다.</b> 규칙 수가 0인 이유가
     * 「꺼 두어서」인지 「규칙 파일을 잃어서」인지는 이 값이 없으면 구별되지 않고, 그러면
     * 예전에 {@code .gitignore} 가 CSV 를 삼켰을 때처럼 없는 원인을 찾게 됩니다.
     */
    public boolean linksEnabled() {
        return linksEnabled;
    }

    /**
     * 스위치를 건너뛰고 규칙을 그대로 쓰는 사본. <b>{@code /api/diagnose/opac} 전용입니다.</b>
     *
     * <p>규칙을 꺼 둔 채로 보완하려면 배포된 서버에서 한 줄씩 시험해 볼 수 있어야 합니다.
     * 서버가 그 OPAC 에 닿는지는 배포된 곳의 나가는 IP 에 달려 있어 로컬에서 확인한 것으로는
     * 알 수 없기 때문입니다. 운영자가 부르는 통로라 여기서만 씁니다. <b>화면으로 나가는
     * 경로에서 부르지 마세요.</b> 그 순간 스위치가 아무것도 막지 못합니다.
     */
    public OpacTemplates asIfEnabled() {
        return linksEnabled ? this : new OpacTemplates(byLibCode, patterns, true);
    }

    /** 규칙을 넣어 둔 도서관 수. 뜰 때 로그로 남겨 두면 빠진 것을 알아차립니다. */
    public int size() {
        return byLibCode.size();
    }

    /** 상세 패턴 수. 배포 직후 0이면 파일을 잃은 것입니다. */
    public int patternCount() {
        return patterns.size();
    }

    private static String fill(Template template, String value, OpacLink.Kind kind) {
        String placeholder = kind == OpacLink.Kind.TITLE_SEARCH ? "{title}" : "{isbn13}";
        return template.urlTemplate()
                .replace(placeholder, URLEncoder.encode(value, template.encoding()));
    }
}
