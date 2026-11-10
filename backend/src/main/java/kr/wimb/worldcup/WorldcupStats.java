package kr.wimb.worldcup;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 「인생 책 월드컵」의 집계. <b>누가 골랐는지는 어디에도 없고 몇 번 골라졌는지만 있습니다.</b>
 *
 * <h2>왜 원본 기록이 아니라 집계인가</h2>
 *
 * <p>완주 한 번은 대결 서른한 번입니다. 그것을 한 줄씩 쌓으면 파일이 참여자 수만큼
 * 자라는데, 이 서버의 디스크는 서가와 소장 캐시가 함께 쓰는 볼륨이고 기계는 메모리 1GB
 * 입니다. 집계로 두면 <b>주제 하나가 책 서른두 권 × 서른한 권의 칸</b>을 넘지 않습니다.
 * 「누가 누구를 이겼는가」(이긴 책, 진 책)는 그 칸에 그대로 남습니다.
 *
 * <h2>명단에 있는 책만 받습니다</h2>
 *
 * <p>이 API 에는 인증이 없어서 주소를 아는 사람은 누구나 부를 수 있습니다. 아무 글자나
 * 받아 적으면 <b>남이 지어낸 주제와 책 이름이 우리 디스크에 쌓입니다.</b> 그래서 주제와
 * 책은 {@link WorldcupRosters} 에 있는 것만 받고, 대진이 실제로 성립하는 모양인지도
 * 봅니다. 같은 사람이 여러 번 내는 것까지 막지는 않습니다. 그것은 화면이 브라우저에
 * 남겨 둔 표시와 주소별 호출 제한이 맡습니다.
 *
 * <h2>한 번 준 책 이름표는 바꾸지 않습니다</h2>
 *
 * <p>책의 식별자는 공유되는 결과 주소에 들어가고 여기 집계의 열쇠이기도 합니다. 바꾸면
 * 공유된 링크가 깨지고 그 책의 집계가 둘로 갈립니다. {@code short_id} 와 {@code work_id}
 * 를 바꾸지 않는 것과 같은 규칙입니다.
 */
public final class WorldcupStats {

    /** 화면이 세어 보내는 단계. 여기 없는 이름은 받지 않습니다. */
    public static final Set<String> EVENTS = Set.of(
            "start", "round_16", "round_8", "round_4", "final",
            "complete", "share", "save_image", "find_library");

    /**
     * 기억해 둘 유입 표시의 가짓수. <b>유입 표시는 방문자가 주소에 적어 보내는 값입니다.</b>
     * 광고에서 붙인 것만 올 거라고 믿으면 누군가 값을 바꿔 가며 불러 칸을 끝없이 늘립니다.
     * 넘으면 「other」 한 칸에 모읍니다.
     */
    static final int MAX_UTM_VALUES = 200;

    /** 유입 표시 한 건의 길이. 광고 이름을 담기에는 넉넉하고 글을 실어 보내기에는 짧습니다. */
    static final int MAX_UTM_LENGTH = 80;

    /** 단계별 횟수를 들고 있는 날 수. 지난 광고를 돌아보는 데 한 해면 충분합니다. */
    static final int KEEP_EVENT_DAYS = 400;

    private static final String HEADER = "#wimb-worldcup-stats v1";

    public record Match(String winner, String loser) {}

    /** 화면에 내보내는 한 주제의 집계. 책별 우승 횟수와 전체 완주 횟수입니다. */
    public record ThemeView(String themeId, long completions, Map<String, Long> wins) {}

    public record EventRow(String day, String event, String utm, long count) {}

    /** 받은 결과가 성립하지 않는 대진일 때. 화면에는 400 으로 나갑니다. */
    public static final class InvalidResultException extends RuntimeException {
        public InvalidResultException(String message) {
            super(message);
        }
    }

    private static final class Theme {
        long completions;
        final Map<String, Long> wins = new TreeMap<>();
        /** 이긴 책 → 진 책 → 횟수. */
        final Map<String, Map<String, Long>> pairs = new TreeMap<>();
    }

    private record EventKey(String day, String theme, String event, String utm) {}

    private final WorldcupRosters rosters;
    private final Map<String, Theme> themes = new TreeMap<>();
    private final Map<EventKey, Long> events = new HashMap<>();
    private final Set<String> utmValues = new HashSet<>();
    private boolean dirty;

    public WorldcupStats(WorldcupRosters rosters) {
        this.rosters = rosters;
    }

    public boolean knows(String themeId) {
        return rosters.books(themeId) != null;
    }

    /**
     * 완주 한 번을 셉니다.
     *
     * <p><b>대진이 성립하는지 봅니다.</b> 명단의 책이 빠짐없이 한 번씩 나오고, 진 책은 다시
     * 나오지 않으며, 끝까지 지지 않은 책이 하나뿐이고 그것이 우승이어야 합니다. 이것을
     * 보지 않으면 「우승만 서른 번 적은 결과」 같은 것이 그대로 집계에 들어갑니다.
     */
    public synchronized ThemeView recordFinish(String themeId, String winner, List<Match> matches) {
        Set<String> roster = rosters.books(themeId);
        if (roster == null) throw new InvalidResultException("없는 주제입니다.");
        if (winner == null || matches == null) throw new InvalidResultException("결과가 비어 있습니다.");
        if (matches.size() != roster.size() - 1) {
            throw new InvalidResultException("대결 수가 맞지 않습니다.");
        }

        Set<String> seen = new HashSet<>();
        Set<String> lost = new HashSet<>();
        for (Match match : matches) {
            String won = match.winner();
            String fell = match.loser();
            if (won == null || fell == null || won.equals(fell)
                    || !roster.contains(won) || !roster.contains(fell)) {
                throw new InvalidResultException("이 주제에 없는 책이 들어 있습니다.");
            }
            // 진 책이 다시 나오면 토너먼트가 아닙니다.
            if (lost.contains(won) || lost.contains(fell)) {
                throw new InvalidResultException("이미 진 책이 다시 나왔습니다.");
            }
            seen.add(won);
            seen.add(fell);
            lost.add(fell);
        }
        if (seen.size() != roster.size() || lost.size() != roster.size() - 1
                || lost.contains(winner) || !seen.contains(winner)) {
            throw new InvalidResultException("우승한 책이 대진과 맞지 않습니다.");
        }

        Theme theme = themes.computeIfAbsent(themeId, key -> new Theme());
        theme.completions++;
        theme.wins.merge(winner, 1L, Long::sum);
        for (Match match : matches) {
            theme.pairs.computeIfAbsent(match.winner(), key -> new TreeMap<>())
                    .merge(match.loser(), 1L, Long::sum);
        }
        dirty = true;
        return viewOf(themeId, theme);
    }

    /** 아직 아무도 완주하지 않은 주제는 0 으로 답합니다. 없는 주제는 null 입니다. */
    public synchronized ThemeView view(String themeId) {
        if (!knows(themeId)) return null;
        Theme theme = themes.get(themeId);
        return theme == null ? new ThemeView(themeId, 0, Map.of()) : viewOf(themeId, theme);
    }

    private static ThemeView viewOf(String themeId, Theme theme) {
        return new ThemeView(themeId, theme.completions, new LinkedHashMap<>(theme.wins));
    }

    /** 이긴 책이 진 책을 몇 번 이겼는지. 화면에는 아직 쓰지 않고 시험과 분석이 봅니다. */
    public synchronized long pairCount(String themeId, String winner, String loser) {
        Theme theme = themes.get(themeId);
        if (theme == null) return 0;
        return theme.pairs.getOrDefault(winner, Map.of()).getOrDefault(loser, 0L);
    }

    /**
     * 단계 하나를 셉니다. 받지 않는 것이면 false 입니다.
     *
     * <p><b>무엇을 눌렀는지만 세고 누가 눌렀는지는 받지 않습니다.</b> 주소도 기기 표시도
     * 인자에 없습니다.
     */
    public synchronized boolean recordEvent(LocalDate day, String themeId, String event, String utm) {
        if (!knows(themeId) || event == null || !EVENTS.contains(event)) return false;
        String source = normalizeUtm(utm);
        if (!utmValues.contains(source)) {
            if (utmValues.size() >= MAX_UTM_VALUES) source = "other";
            else utmValues.add(source);
        }
        events.merge(new EventKey(day.toString(), themeId, event, source), 1L, Long::sum);
        dirty = true;
        return true;
    }

    /**
     * 유입 표시를 받아 적을 수 있는 모양으로 바꿉니다.
     *
     * <p>영문·숫자와 구분 기호 몇 가지만 남깁니다. 탭과 줄바꿈이 섞이면 파일의 칸이
     * 밀리고, 길이를 두지 않으면 누구나 아무 글이나 우리 디스크에 적을 수 있습니다.
     */
    static String normalizeUtm(String utm) {
        if (utm == null) return "-";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < utm.length() && out.length() < MAX_UTM_LENGTH; i++) {
            char c = utm.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/';
            if (plain) out.append(c);
        }
        return out.isEmpty() ? "-" : out.toString();
    }

    /** 날짜 순으로 세운 단계별 횟수. */
    public synchronized List<EventRow> events(String themeId) {
        List<EventRow> rows = new ArrayList<>();
        events.forEach((key, count) -> {
            if (key.theme().equals(themeId)) {
                rows.add(new EventRow(key.day(), key.event(), key.utm(), count));
            }
        });
        rows.sort((a, b) -> {
            int byDay = a.day().compareTo(b.day());
            if (byDay != 0) return byDay;
            int byEvent = a.event().compareTo(b.event());
            return byEvent != 0 ? byEvent : a.utm().compareTo(b.utm());
        });
        return rows;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * 파일에 적습니다. <b>옆에 다 적은 뒤 이름을 바꿉니다.</b> 적는 도중에 컨테이너가
     * 죽으면 반쯤 적힌 파일이 남고, 그것을 되살리면 집계가 조용히 줄어듭니다.
     */
    public synchronized void save(Path path, LocalDate today) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        String oldest = today.minusDays(KEEP_EVENT_DAYS).toString();
        try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.newLine();
            for (Map.Entry<String, Theme> entry : themes.entrySet()) {
                String id = entry.getKey();
                Theme theme = entry.getValue();
                line(out, "F", id, Long.toString(theme.completions));
                for (Map.Entry<String, Long> win : theme.wins.entrySet()) {
                    line(out, "W", id, win.getKey(), Long.toString(win.getValue()));
                }
                for (Map.Entry<String, Map<String, Long>> winner : theme.pairs.entrySet()) {
                    for (Map.Entry<String, Long> loser : winner.getValue().entrySet()) {
                        line(out, "P", id, winner.getKey(), loser.getKey(),
                                Long.toString(loser.getValue()));
                    }
                }
            }
            List<EventKey> keys = new ArrayList<>(events.keySet());
            keys.sort(Comparator.comparing(EventKey::day).thenComparing(EventKey::theme)
                    .thenComparing(EventKey::event).thenComparing(EventKey::utm));
            for (EventKey key : keys) {
                // 오래된 날은 덜어 냅니다. 메모리의 것은 다음에 뜰 때 함께 사라집니다.
                if (key.day().compareTo(oldest) < 0) continue;
                line(out, "E", key.day(), key.theme(), key.event(), key.utm(),
                        Long.toString(events.get(key)));
            }
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirty = false;
    }

    private static void line(BufferedWriter out, String... cells) throws IOException {
        out.write(String.join("\t", cells));
        out.newLine();
    }

    /**
     * 파일에서 되살립니다. 되살린 줄 수를 돌려줍니다.
     *
     * <p><b>못 읽는 줄은 건너뛰고 나머지를 살립니다.</b> 한 줄이 깨졌다고 전부 버리면
     * 그동안 모은 집계가 통째로 사라집니다. 명단에서 빠진 주제나 책의 줄도 그대로
     * 들고 있습니다. 명단 파일을 잘못 고친 날 집계까지 함께 잃지 않기 위해서입니다.
     */
    public synchronized int load(Path path) {
        if (!Files.isRegularFile(path)) return 0;
        int loaded = 0;
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = in.readLine()) != null) {
                if (raw.isEmpty() || raw.startsWith("#")) continue;
                String[] cell = raw.split("\t", -1);
                try {
                    if (apply(cell)) loaded++;
                } catch (NumberFormatException | DateTimeParseException e) {
                    // 깨진 줄 하나입니다. 나머지는 계속 읽습니다.
                }
            }
        } catch (IOException e) {
            return loaded;
        }
        return loaded;
    }

    private boolean apply(String[] cell) {
        switch (cell[0]) {
            case "F" -> {
                if (cell.length != 3) return false;
                themes.computeIfAbsent(cell[1], key -> new Theme()).completions = Long.parseLong(cell[2]);
            }
            case "W" -> {
                if (cell.length != 4) return false;
                themes.computeIfAbsent(cell[1], key -> new Theme()).wins.put(cell[2], Long.parseLong(cell[3]));
            }
            case "P" -> {
                if (cell.length != 5) return false;
                themes.computeIfAbsent(cell[1], key -> new Theme()).pairs
                        .computeIfAbsent(cell[2], key -> new TreeMap<>())
                        .put(cell[3], Long.parseLong(cell[4]));
            }
            case "E" -> {
                if (cell.length != 6) return false;
                LocalDate.parse(cell[1]);
                utmValues.add(cell[4]);
                events.put(new EventKey(cell[1], cell[2], cell[3], cell[4]), Long.parseLong(cell[5]));
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
