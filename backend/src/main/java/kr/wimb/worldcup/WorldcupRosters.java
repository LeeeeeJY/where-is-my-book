package kr.wimb.worldcup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 주제마다 어떤 책이 나오는지. <b>집계가 받아 적어도 되는 이름의 전부입니다.</b>
 *
 * <p>후보의 원본은 화면 쪽 {@code frontend/src/worldcup/themes/*.json} 입니다. 서버는
 * 그 가운데 식별자만 {@code worldcup/rosters.tsv} 로 들고 있습니다. 이미지가
 * {@code backend/} 만 가지고 만들어져 화면 쪽 파일을 읽을 수 없기 때문입니다.
 *
 * <p><b>이 파일을 손으로 고치지 마세요.</b> {@code frontend/tools/make-worldcup-assets.mjs}
 * 가 주제 파일에서 만들어 내고, 화면 쪽 시험이 둘이 어긋나면 실패합니다. 둘이 갈리면
 * 화면에는 아무 이상이 없는데 그 책의 완주만 조용히 거절됩니다.
 */
public final class WorldcupRosters {

    /** 식별자는 주소에 그대로 들어가므로 소문자·숫자·하이픈만 씁니다. */
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final Map<String, Set<String>> byTheme;

    public WorldcupRosters(Map<String, Set<String>> byTheme) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        byTheme.forEach((theme, books) ->
                copy.put(theme, Collections.unmodifiableSet(new LinkedHashSet<>(books))));
        this.byTheme = Collections.unmodifiableMap(copy);
    }

    /** 그 주제의 책 식별자. 없는 주제면 null 입니다. */
    public Set<String> books(String themeId) {
        return themeId == null ? null : byTheme.get(themeId);
    }

    public Set<String> themes() {
        return byTheme.keySet();
    }

    public static WorldcupRosters fromClasspath() {
        try (InputStream in = WorldcupRosters.class.getResourceAsStream("/worldcup/rosters.tsv")) {
            if (in == null) return new WorldcupRosters(Map.of());
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code 주제<탭>책} 한 줄씩입니다. <b>모양이 틀린 줄은 서버가 뜨지 않게 막습니다.</b>
     * 조용히 건너뛰면 그 책이 명단에서 빠진 채 돌고, 그 책이 낀 완주가 전부 거절됩니다.
     */
    static WorldcupRosters parse(BufferedReader in) throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        String raw;
        int number = 0;
        while ((raw = in.readLine()) != null) {
            number++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cell = line.split("\t");
            if (cell.length != 2 || !ID.matcher(cell[0]).matches() || !ID.matcher(cell[1]).matches()) {
                throw new IllegalStateException(
                        "worldcup/rosters.tsv " + number + "번째 줄을 읽지 못했습니다: " + raw);
            }
            if (!out.computeIfAbsent(cell[0], key -> new LinkedHashSet<>()).add(cell[1])) {
                throw new IllegalStateException(
                        "worldcup/rosters.tsv " + number + "번째 줄의 책이 겹칩니다: " + raw);
            }
        }
        return new WorldcupRosters(out);
    }
}
