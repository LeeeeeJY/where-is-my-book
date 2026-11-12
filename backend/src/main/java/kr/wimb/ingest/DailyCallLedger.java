package kr.wimb.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 메모리에서 세고, 파일에 적어 두었다가 다시 뜰 때 이어서 세는 원장.
 *
 * <p><b>날짜 경계는 한국 시각입니다.</b> UTC 로 두면 건수가 09:00 KST 에 0 으로 돌아가
 * 정보나루의 하루와 어긋납니다.
 *
 * <h2>파일로 남기는 이유</h2>
 *
 * <p>메모리에만 두던 때에는 컨테이너가 새로 뜰 때마다 그날 쓴 건수가 0 으로 돌아갔습니다.
 * 실제로 검색을 수십 번 한 날에 {@code /api/diagnose} 가 8건이라고 답했습니다. 그 숫자로
 * 서가를 세울지 정하므로, 배포가 잦은 날에는 남은 건수를 실제보다 넉넉하게 보게 됩니다.
 *
 * <p>파일에는 <b>날짜와 소스별 건수만</b> 적습니다. 되살릴 때 날짜가 오늘과 다르면
 * 버립니다. 어제의 건수를 오늘 것으로 이어 세면 안 됩니다.
 *
 * <p>저장은 {@code kr.wimb.api.CallLedgerStore} 가 주기적으로 부릅니다. 저장과 저장 사이에
 * 컨테이너가 죽으면 그 사이의 건수는 잃습니다. 그만큼은 실제보다 적게 셉니다.
 */
public final class DailyCallLedger implements CallLedger {

    /** 한국 시각 기준으로 날짜가 바뀝니다. 저장은 UTC 로 하되 이 경계만은 KST 개념입니다. */
    public static final ZoneId LEDGER_ZONE = ZoneId.of("Asia/Seoul");

    private static final String DAY_KEY = "day";

    private final Map<String, Integer> limits;
    private final Clock clock;
    private final Map<String, Integer> counters = new TreeMap<>();
    private LocalDate currentDay;
    /** 마지막으로 저장한 뒤에 건수가 바뀌었는지. 바뀐 것이 없으면 쓰지 않습니다. */
    private boolean dirty;

    /**
     * @param dailyLimits 소스별 하루 한도. <b>호출을 막는 데 쓰지 않습니다.</b>
     *                    {@link #remaining} 을 계산하는 데에만 씁니다. 0 이하이거나 없으면
     *                    한도를 모르는 소스로 봅니다.
     */
    public DailyCallLedger(Map<String, Integer> dailyLimits, Clock clock) {
        this.limits = Map.copyOf(dailyLimits);
        this.clock = clock;
        this.currentDay = today();
    }

    @Override
    public synchronized void record(String sourceCode) {
        rollOverIfNewDay();
        counters.merge(sourceCode, 1, Integer::sum);
        dirty = true;
    }

    @Override
    public synchronized int used(String sourceCode) {
        rollOverIfNewDay();
        return counters.getOrDefault(sourceCode, 0);
    }

    @Override
    public synchronized int remaining(String sourceCode) {
        int limit = limits.getOrDefault(sourceCode, 0);
        return limit <= 0 ? Integer.MAX_VALUE : Math.max(0, limit - used(sourceCode));
    }

    /** 마지막으로 저장한 뒤에 건수가 바뀌었는지. */
    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * 날짜와 건수를 파일에 적습니다. <b>임시 파일에 적고 이름을 바꿉니다.</b> 쓰는 도중에
     * 컨테이너가 죽어도 반쯤 쓰인 파일이 남지 않습니다.
     */
    public void save(Path path) throws IOException {
        StringBuilder out = new StringBuilder();
        synchronized (this) {
            rollOverIfNewDay();
            out.append(DAY_KEY).append('\t').append(currentDay).append('\n');
            counters.forEach((code, count) ->
                    out.append(code).append('\t').append(count).append('\n'));
            dirty = false;
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // 적지 못했으면 다음 주기에 다시 적어야 합니다. 「바뀐 것 없음」으로 남겨 두면
            // 그 뒤로 호출이 없는 동안에는 영영 적지 않습니다.
            synchronized (this) {
                dirty = true;
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 임시 파일을 못 지운 것은 다음 저장이 덮어씁니다.
            }
            throw e;
        }
    }

    /**
     * 파일에 적어 둔 건수를 이어받습니다. <b>날짜가 오늘과 같을 때만</b> 받습니다.
     *
     * <p>이미 센 것이 있으면 더합니다. 서버가 뜨면서 도서관 목록을 받는 호출이 되살리기보다
     * 먼저 나갈 수 있어서입니다. 파일이 없거나 읽을 수 없거나 모양이 다르면 아무것도 받지
     * 않습니다. 건수를 잃는 것이지 서버가 못 뜰 일이 아닙니다.
     *
     * @return 이어받은 건수의 합. 받지 않았으면 0 입니다.
     */
    public int load(Path path) {
        List<String> lines;
        try {
            if (!Files.isRegularFile(path)) return 0;
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return 0;
        }

        LocalDate savedDay = null;
        Map<String, Integer> saved = new TreeMap<>();
        try {
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length != 2) return 0;
                if (DAY_KEY.equals(parts[0])) {
                    savedDay = LocalDate.parse(parts[1]);
                } else {
                    int count = Integer.parseInt(parts[1]);
                    if (count < 0) return 0;
                    saved.put(parts[0], count);
                }
            }
        } catch (RuntimeException e) {
            // 날짜나 숫자로 읽히지 않는 줄이 있으면 통째로 믿지 않습니다.
            return 0;
        }

        synchronized (this) {
            rollOverIfNewDay();
            if (savedDay == null || !savedDay.equals(currentDay)) return 0;
            int total = 0;
            for (Map.Entry<String, Integer> entry : saved.entrySet()) {
                counters.merge(entry.getKey(), entry.getValue(), Integer::sum);
                total += entry.getValue();
            }
            return total;
        }
    }

    private void rollOverIfNewDay() {
        LocalDate now = today();
        if (!now.equals(currentDay)) {
            counters.clear();
            currentDay = now;
            dirty = true;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), LEDGER_ZONE);
    }
}
