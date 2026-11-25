package kr.wimb.opac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 누른 링크를 상세로 보내려고 서버가 OPAC 을 받아 본 결과를 <b>호스트마다</b> 셉니다.
 *
 * <p>왜 필요한가. {@code /api/status} 의 {@code opacDetailFailed} 는 합계 하나뿐이라 어느 호스트에서
 * 새는지 말해 주지 않습니다. 그리고 상세 규칙을 넣은 뒤의 확인은 {@code /api/diagnose/opac} 으로
 * 도서관마다 <b>한 번</b> 재는 것이 전부였습니다. 충주처럼 응답이 1초일 때도 25초일 때도 있는 곳,
 * 구미처럼 접속이 가끔 시간 초과로 끝나는 곳, 보은처럼 몇 분씩 접속을 받지 않는 곳은 한 번 재서
 * {@code RESOLVED} 가 나왔다고 「운영에서 상세로 간다」고 말할 수 없습니다. 방문자가 실제로 눌렀을 때
 * 몇 번이 상세로 갔고 몇 번이 검색 결과로 내려갔는지를 호스트마다 쌓아 두어야 압니다.
 *
 * <p><b>세는 것은 방문자의 길({@link DetailResolver#resolve})뿐입니다.</b> 진단
 * ({@link DetailResolver#probe})은 세지 않습니다. 그 답은 진단이 바로 돌려주고, 운영자가 배포한 뒤에
 * 한 번씩 잰 것이 섞이면 누르는 사람이 적은 호스트의 숫자가 운영자의 것으로 채워집니다.
 * <b>기억해 둔 답으로 보낸 것도 세지 않습니다.</b> 그때는 그 호스트로 요청이 나가지 않습니다. 그래서
 * 여기의 횟수는 「누른 횟수」가 아니라 「서버가 그 호스트를 받아 보려 한 횟수」입니다. 한 번 눌러도
 * 판을 바꾸거나 표제에서 권차를 떼고 두 번 받아 보는 일이 있습니다.
 *
 * <p><b>누가 눌렀는지도 어느 책이었는지도 적지 않습니다.</b> 호스트 이름과 횟수와 날짜(한국 시각의
 * 날, 시각은 적지 않습니다)뿐입니다. 마지막 실패의 사유는 예외의 이름과 메시지인데, 주소가 섞여
 * 있으면 그 앞에서 자릅니다. 검색 주소에는 책 제목이 들어 있기 때문입니다.
 *
 * <p>파일로 남겨 서버가 다시 떠도 이어서 셉니다. 배포할 때마다 0 으로 돌아가면 며칠에 걸쳐 쌓아야
 * 보이는 것을 볼 수 없습니다. 저장은 부르는 쪽({@code OpacHostTallyStore})이 주기적으로 합니다.
 */
public final class OpacHostTally {

    /** 받아 보려 한 번의 끝. */
    public enum Event {
        /** 상세 링크를 찾았습니다. */
        RESOLVED("resolved"),
        /** 답은 받았는데 링크를 고르지 못했습니다. 그 판이 없거나 규칙이 더는 맞지 않는 것입니다. */
        NO_MATCH("noMatch"),
        /** 접속 자체가 제한 시간 안에 되지 않았습니다. 해외 IP 를 받지 않는 호스트가 이렇게 끝납니다. */
        CONNECT_TIMEOUT("connectTimeout"),
        /** 붙기는 했는데 응답 머리가 받기 제한 안에 오지 않았습니다. 느린 OPAC 입니다. */
        RESPONSE_TIMEOUT("responseTimeout"),
        /** 200 이 아닌 응답. */
        HTTP_ERROR("httpError"),
        /** 그 밖에 받지 못한 것. 접속 거부, TLS, 끊긴 연결. */
        OTHER_FAILURE("otherFailure"),
        /** 연속으로 닿지 않아 걸린 빗장 때문에 받아 보지 않았습니다. */
        SKIPPED_DOWN("skippedDown"),
        /** robots.txt 가 그 경로를 막아 받아 보지 않았습니다. */
        BLOCKED_BY_ROBOTS("blockedByRobots"),
        /** 그 호스트로 이미 요청이 나가 있어 기다리지 않았습니다. */
        BUSY("busy"),
        /**
         * 상세 확인 규칙({@code @verify:})이 한 번 누를 때의 시간 상한에 닿아 그만뒀습니다. 그 규칙은 검색
         * 화면과 후보 상세를 여러 번 받는데, 하나하나는 받기 제한 안이어도 합치면 상한을 넘을 수 있습니다.
         * 이 숫자가 쌓이는 호스트는 그 규칙으로 얻는 것보다 기다리게 하는 것이 많은 호스트입니다.
         */
        TIME_LIMIT("timeLimit");

        private final String field;

        Event(String field) {
            this.field = field;
        }

        /** 받으려 했는데 받지 못한 것인지. 마지막 실패로 적어 둘지를 가릅니다. */
        boolean isFetchFailure() {
            return this == CONNECT_TIMEOUT || this == RESPONSE_TIMEOUT || this == HTTP_ERROR || this == OTHER_FAILURE;
        }
    }

    /** 날짜의 경계. 화면과 원장이 쓰는 것과 같습니다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String MAGIC = "#wimb-opac-host-tally";
    private static final String FORMAT = "1";
    /** 호스트는 규칙 표에서 나오므로 수백 개를 넘지 않습니다. 그래도 끝없이 자라지는 않게 막습니다. */
    static final int MAX_HOSTS = 2_000;
    static final int MAX_NOTE = 120;

    private static final class Row {
        final EnumMap<Event, Long> counts = new EnumMap<>(Event.class);
        long answered;
        long totalMillis;
        long maxMillis;
        long slowAnswers;
        LocalDate firstOn;
        LocalDate lastOn;
        LocalDate lastFailureOn;
        String lastFailure;

        long count(Event event) {
            return counts.getOrDefault(event, 0L);
        }

        long attempts() {
            long sum = 0;
            for (long n : counts.values()) sum += n;
            return sum;
        }

        /** 상세로도 못 갔고 「그 판이 없다」도 아닌 것. 이것이 큰 호스트가 먼저 볼 호스트입니다. */
        long notFetched() {
            return attempts() - count(Event.RESOLVED) - count(Event.NO_MATCH);
        }
    }

    private final Clock clock;
    private final long slowMillis;
    private final Map<String, Row> rows = new TreeMap<>();
    private LocalDate since;
    private boolean dirty;

    /**
     * @param slowMillis 답은 받았지만 이보다 오래 걸린 것을 따로 셉니다. 서버의 받기 제한은 응답 머리가
     *                   올 때까지만 걸려서, 머리를 바로 주고 본문을 느리게 주는 OPAC 은 제한을 넘겨서도
     *                   답을 받습니다. 그동안 누른 사람은 기다립니다.
     */
    public OpacHostTally(Clock clock, long slowMillis) {
        this.clock = clock;
        this.slowMillis = slowMillis;
    }

    /** 받아 보지 않았거나 받지 못한 한 번. */
    public synchronized void record(String host, Event event, String note) {
        Row row = rowFor(host);
        if (row == null) return;
        bump(row, event, note);
    }

    /** 답을 받은 한 번. 걸린 시간을 함께 적습니다. 상태 코드가 200 이 아닌 답도 「받은」 것입니다. */
    public synchronized void recordAnswered(String host, Event event, long millis, String note) {
        Row row = rowFor(host);
        if (row == null) return;
        long took = Math.max(0, millis);
        row.answered++;
        row.totalMillis += took;
        row.maxMillis = Math.max(row.maxMillis, took);
        if (took >= slowMillis) row.slowAnswers++;
        bump(row, event, note);
    }

    private Row rowFor(String host) {
        if (host == null || host.isBlank()) return null;
        String key = host.toLowerCase(Locale.ROOT);
        Row row = rows.get(key);
        if (row == null) {
            if (rows.size() >= MAX_HOSTS) return null;
            row = new Row();
            rows.put(key, row);
        }
        return row;
    }

    private void bump(Row row, Event event, String note) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);
        row.counts.merge(event, 1L, Long::sum);
        if (row.firstOn == null) row.firstOn = today;
        row.lastOn = today;
        if (event.isFetchFailure()) {
            row.lastFailureOn = today;
            row.lastFailure = tidy(note);
        }
        if (since == null) since = today;
        dirty = true;
    }

    /**
     * 사유를 한 줄로 줄입니다. 탭과 줄바꿈은 파일의 칸을 깨므로 공백으로 바꾸고, 주소가 섞여 있으면 그
     * 앞에서 자릅니다.
     */
    static String tidy(String note) {
        if (note == null) return null;
        String flat = note.replaceAll("[\\t\\r\\n]+", " ").strip();
        int url = flat.indexOf("://");
        if (url >= 0) {
            int cut = flat.lastIndexOf(' ', url);
            flat = flat.substring(0, Math.max(cut, 0)).strip();
        }
        if (flat.length() > MAX_NOTE) flat = flat.substring(0, MAX_NOTE);
        return flat.isEmpty() ? null : flat;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** 세기 시작한 날(한국 시각). 아직 한 번도 세지 않았으면 비어 있습니다. */
    public synchronized LocalDate since() {
        return since;
    }

    /**
     * 진단이 내보내는 모양. 받지 못한 횟수가 많은 호스트가 앞입니다. 같으면 많이 받아 본 호스트, 그다음
     * 이름 차례입니다.
     */
    public synchronized List<Map<String, Object>> snapshot() {
        List<Map.Entry<String, Row>> entries = new ArrayList<>(rows.entrySet());
        entries.sort(Comparator
                .comparingLong((Map.Entry<String, Row> e) -> e.getValue().notFetched()).reversed()
                .thenComparing(Comparator.comparingLong(
                        (Map.Entry<String, Row> e) -> e.getValue().attempts()).reversed())
                .thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Row> entry : entries) {
            Row row = entry.getValue();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("host", entry.getKey());
            one.put("attempts", row.attempts());
            for (Event event : Event.values()) one.put(event.field, row.count(event));
            one.put("answered", row.answered);
            one.put("avgMillis", row.answered == 0 ? 0 : row.totalMillis / row.answered);
            one.put("maxMillis", row.maxMillis);
            one.put("slowAnswers", row.slowAnswers);
            one.put("firstOn", row.firstOn == null ? null : row.firstOn.toString());
            one.put("lastOn", row.lastOn == null ? null : row.lastOn.toString());
            one.put("lastFailureOn", row.lastFailureOn == null ? null : row.lastFailureOn.toString());
            one.put("lastFailure", row.lastFailure);
            out.add(one);
        }
        return out;
    }

    // ── 파일 ────────────────────────────────────────────────────────────────

    /**
     * 임시 파일에 적고 이름을 바꿉니다. 쓰다가 죽어도 앞의 파일이 남습니다.
     *
     * <p>줄 하나가 호스트 하나이고 칸은 {@code 이름=값} 입니다. 세는 종류가 늘어도 예전 파일을 그대로
     * 읽으려고 자리 대신 이름으로 적습니다.
     */
    public void save(Path path) throws IOException {
        String text;
        synchronized (this) {
            StringBuilder out = new StringBuilder();
            out.append(MAGIC).append('\t').append(FORMAT).append('\n');
            out.append("#since\t").append(since == null ? "" : since).append('\n');
            for (Map.Entry<String, Row> entry : rows.entrySet()) {
                Row row = entry.getValue();
                out.append(entry.getKey());
                for (Event event : Event.values()) {
                    long n = row.count(event);
                    if (n > 0) out.append('\t').append(event.name()).append('=').append(n);
                }
                out.append("\tanswered=").append(row.answered);
                out.append("\ttotalMillis=").append(row.totalMillis);
                out.append("\tmaxMillis=").append(row.maxMillis);
                out.append("\tslowAnswers=").append(row.slowAnswers);
                if (row.firstOn != null) out.append("\tfirstOn=").append(row.firstOn);
                if (row.lastOn != null) out.append("\tlastOn=").append(row.lastOn);
                if (row.lastFailureOn != null) out.append("\tlastFailureOn=").append(row.lastFailureOn);
                if (row.lastFailure != null) out.append("\tlastFailure=").append(row.lastFailure);
                out.append('\n');
            }
            text = out.toString();
            dirty = false;
        }
        Path absolute = path.toAbsolutePath();
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try {
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                dirty = true;                     // 적지 못했으니 다음 주기에 다시 해 봅니다.
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 임시 파일이 남는 것은 다음 저장이 덮어씁니다.
            }
            throw e;
        }
    }

    /**
     * 파일에서 이어받습니다. 이미 센 것이 있으면 거기에 더합니다.
     *
     * <p>파일이 없거나 우리 것이 아니면 아무것도 하지 않습니다. 읽지 못하는 줄과 모르는 칸은 건너뜁니다.
     * 숫자 하나가 깨졌다고 나머지 호스트의 며칠치를 버리지 않습니다.
     *
     * @return 이어받은 호스트 수
     */
    public int load(Path path) {
        List<String> lines;
        try {
            if (!Files.isRegularFile(path)) return 0;
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        if (lines.isEmpty() || !lines.get(0).equals(MAGIC + "\t" + FORMAT)) return 0;
        int loaded = 0;
        synchronized (this) {
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) continue;
                String[] cells = line.split("\t", -1);
                if (cells[0].equals("#since")) {
                    LocalDate saved = cells.length > 1 ? dateOrNull(cells[1]) : null;
                    if (saved != null && (since == null || saved.isBefore(since))) since = saved;
                    continue;
                }
                if (cells[0].startsWith("#")) continue;
                Row row = rowFor(cells[0]);
                if (row == null) continue;
                for (int i = 1; i < cells.length; i++) {
                    int eq = cells[i].indexOf('=');
                    if (eq <= 0) continue;
                    absorb(row, cells[i].substring(0, eq), cells[i].substring(eq + 1));
                }
                loaded++;
            }
        }
        return loaded;
    }

    private static void absorb(Row row, String name, String value) {
        switch (name) {
            case "answered" -> row.answered += longOrZero(value);
            case "totalMillis" -> row.totalMillis += longOrZero(value);
            case "maxMillis" -> row.maxMillis = Math.max(row.maxMillis, longOrZero(value));
            case "slowAnswers" -> row.slowAnswers += longOrZero(value);
            case "firstOn" -> {
                LocalDate saved = dateOrNull(value);
                if (saved != null && (row.firstOn == null || saved.isBefore(row.firstOn))) row.firstOn = saved;
            }
            case "lastOn" -> {
                LocalDate saved = dateOrNull(value);
                if (saved != null && (row.lastOn == null || saved.isAfter(row.lastOn))) row.lastOn = saved;
            }
            case "lastFailureOn" -> {
                LocalDate saved = dateOrNull(value);
                if (saved != null && row.lastFailureOn == null) row.lastFailureOn = saved;
            }
            case "lastFailure" -> {
                if (row.lastFailure == null) row.lastFailure = tidy(value);
            }
            default -> {
                for (Event event : Event.values()) {
                    if (event.name().equals(name)) {
                        long n = longOrZero(value);
                        if (n > 0) row.counts.merge(event, n, Long::sum);
                        return;
                    }
                }
            }
        }
    }

    private static long longOrZero(String value) {
        try {
            return Math.max(0, Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LocalDate dateOrNull(String value) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
