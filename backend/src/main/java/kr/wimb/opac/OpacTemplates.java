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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>1,604곳을 전부 채우려 들면 끝나지 않으므로, <b>자주 가는 도서관부터 채우고 나머지는
 * 홈페이지 폴백에 맡깁니다.</b> 대신 어느 단계로 보냈는지를 화면에 밝힙니다.
 */
public final class OpacTemplates {

    private record Template(OpacLink.Kind kind, Charset encoding, String urlTemplate) {}

    private final Map<String, Map<OpacLink.Kind, Template>> byLibCode;

    private OpacTemplates(Map<String, Map<OpacLink.Kind, Template>> byLibCode) {
        this.byLibCode = byLibCode;
    }

    /** 자원에서 읽어 들입니다. 파일이 없으면 규칙이 하나도 없는 것으로 보고 넘어갑니다. */
    public static OpacTemplates load() {
        return load("/opac/templates.csv");
    }

    static OpacTemplates load(String resourcePath) {
        try (InputStream in = OpacTemplates.class.getResourceAsStream(resourcePath)) {
            if (in == null) return new OpacTemplates(Map.of());
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return parse(reader.lines().toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static OpacTemplates parse(List<String> lines) {
        Map<String, Map<OpacLink.Kind, Template>> out = new HashMap<>();
        List<String> problems = new ArrayList<>();
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
        if (!problems.isEmpty()) {
            // 조용히 넘어가면 규칙이 빠진 채로 배포됩니다. 뜰 때 실패시켜 눈에 띄게 합니다.
            throw new IllegalStateException(
                    "OPAC 주소 규칙을 읽지 못했습니다:\n  " + String.join("\n  ", problems));
        }
        return new OpacTemplates(out);
    }

    /**
     * 그 도서관으로 보낼 가장 좋은 링크.
     *
     * <p>상세 → ISBN 검색 → 제목 검색 순으로 내려가고, 규칙이 하나도 없으면 비어 있는 값을
     * 돌려줍니다. 그때는 부르는 쪽이 홈페이지로 보내면 됩니다.
     */
    public Optional<OpacLink> bestFor(String libCode, String isbn13, String title) {
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
     */
    public OpacLink.Kind kindFor(String libCode) {
        Map<OpacLink.Kind, Template> templates = byLibCode.get(libCode);
        if (templates == null) return OpacLink.Kind.HOMEPAGE;
        for (OpacLink.Kind kind : OpacLink.Kind.values()) {
            if (templates.containsKey(kind)) return kind;
        }
        return OpacLink.Kind.HOMEPAGE;
    }

    /** 규칙을 넣어 둔 도서관 수. 뜰 때 로그로 남겨 두면 빠진 것을 알아차립니다. */
    public int size() {
        return byLibCode.size();
    }

    private static String fill(Template template, String value, OpacLink.Kind kind) {
        String placeholder = kind == OpacLink.Kind.TITLE_SEARCH ? "{title}" : "{isbn13}";
        return template.urlTemplate()
                .replace(placeholder, URLEncoder.encode(value, template.encoding()));
    }
}
