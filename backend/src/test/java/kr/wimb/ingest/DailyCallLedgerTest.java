package kr.wimb.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 그날 부른 건수가 <b>서버가 다시 떠도 이어지는지</b>를 봅니다.
 *
 * <p>메모리에만 두던 때에는 컨테이너가 새로 뜰 때마다 0 으로 돌아가, 검색을 수십 번 한 날에
 * {@code /api/diagnose} 가 8건이라고 답했습니다. 여기서 틀리면 오류가 나지 않고 숫자만
 * 조용히 틀립니다. 어제의 건수를 오늘 것으로 이어 세는 쪽이 특히 그렇습니다.
 */
class DailyCallLedgerTest {

    private static final String CODE = "DATA4LIBRARY_API";

    /** 한국 시각 2026-11-12 12:00. */
    private static final Instant NOON = Instant.parse("2026-11-12T03:00:00Z");

    private static Clock at(AtomicReference<Instant> now) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
    }

    private static DailyCallLedger ledger(AtomicReference<Instant> now) {
        return new DailyCallLedger(Map.of(CODE, 30_000), at(now));
    }

    @Test
    @DisplayName("저장한 건수를 다시 뜬 서버가 이어서 센다")
    void countsCarryOverARestart(@TempDir Path dir) throws Exception {
        var now = new AtomicReference<>(NOON);
        Path file = dir.resolve("call-ledger.tsv");

        var before = ledger(now);
        for (int i = 0; i < 1234; i++) before.record(CODE);
        before.save(file);

        var after = ledger(now);
        assertEquals(1234, after.load(file));
        assertEquals(1234, after.used(CODE));
        assertEquals(30_000 - 1234, after.remaining(CODE));

        after.record(CODE);
        assertEquals(1235, after.used(CODE), "이어받은 뒤에는 거기서부터 셉니다");
    }

    @Test
    @DisplayName("되살리기 전에 이미 센 것이 있으면 더한다")
    void restoredCountsAddToWhatWasAlreadyCounted(@TempDir Path dir) throws Exception {
        // 서버가 뜨면서 도서관 목록을 받는 호출이 되살리기보다 먼저 나갈 수 있습니다.
        var now = new AtomicReference<>(NOON);
        Path file = dir.resolve("call-ledger.tsv");
        var before = ledger(now);
        for (int i = 0; i < 100; i++) before.record(CODE);
        before.save(file);

        var after = ledger(now);
        for (int i = 0; i < 17; i++) after.record(CODE);
        after.load(file);

        assertEquals(117, after.used(CODE));
    }

    @Test
    @DisplayName("어제 적은 건수는 이어받지 않는다")
    void yesterdaysCountIsNotCarriedIntoToday(@TempDir Path dir) throws Exception {
        var now = new AtomicReference<>(NOON);
        Path file = dir.resolve("call-ledger.tsv");
        var before = ledger(now);
        for (int i = 0; i < 500; i++) before.record(CODE);
        before.save(file);

        // 한국 시각으로 이튿날 00:00:01 에 다시 뜬 서버입니다.
        now.set(Instant.parse("2026-11-12T15:00:01Z"));
        var after = ledger(now);

        assertEquals(0, after.load(file), "어제의 건수를 오늘 것으로 세면 안 됩니다");
        assertEquals(0, after.used(CODE));
    }

    @Test
    @DisplayName("한국 시각 자정 직전에 적은 것은 자정 직후에 이어받지 않는다")
    void theDayBoundaryIsKoreanMidnight(@TempDir Path dir) throws Exception {
        // UTC 로는 같은 날(11-12 14:59 와 15:01)이지만 한국 시각으로는 날이 바뀝니다.
        var now = new AtomicReference<>(Instant.parse("2026-11-12T14:59:00Z"));
        Path file = dir.resolve("call-ledger.tsv");
        var before = ledger(now);
        before.record(CODE);
        before.save(file);

        now.set(Instant.parse("2026-11-12T15:01:00Z"));
        assertEquals(0, ledger(now).load(file));
    }

    @Test
    @DisplayName("돌고 있는 동안 날이 바뀌면 0 에서 다시 세고, 그 뒤의 저장은 새 날짜로 적는다")
    void rollsOverWhileRunningAndSavesTheNewDay(@TempDir Path dir) throws Exception {
        var now = new AtomicReference<>(Instant.parse("2026-11-12T14:59:00Z"));
        Path file = dir.resolve("call-ledger.tsv");
        var ledger = ledger(now);
        for (int i = 0; i < 9; i++) ledger.record(CODE);
        ledger.save(file);

        now.set(Instant.parse("2026-11-12T15:01:00Z"));
        assertEquals(0, ledger.used(CODE));
        ledger.record(CODE);
        ledger.save(file);

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("day\t2026-11-13"), written);
        assertEquals(1, ledger(now).load(file));
    }

    @Test
    @DisplayName("파일이 없거나 깨져 있으면 0 에서 시작하고 죽지 않는다")
    void missingOrBrokenFileStartsFromZero(@TempDir Path dir) throws Exception {
        var now = new AtomicReference<>(NOON);

        assertEquals(0, ledger(now).load(dir.resolve("no-such-file.tsv")));

        for (String broken : new String[] {
                "",
                "아무 말\n",
                "day\t어제\n" + CODE + "\t10\n",
                "day\t2026-11-12\n" + CODE + "\t열\n",
                "day\t2026-11-12\n" + CODE + "\t-5\n",
                CODE + "\t10\n",                          // 날짜가 없습니다
        }) {
            Path file = dir.resolve("broken.tsv");
            Files.writeString(file, broken, StandardCharsets.UTF_8);
            var ledger = ledger(now);
            assertEquals(0, ledger.load(file), "믿을 수 없는 파일을 받아들였습니다: " + broken);
            assertEquals(0, ledger.used(CODE));
        }
    }

    @Test
    @DisplayName("바뀐 것이 없으면 저장할 것이 없다고 말한다")
    void isDirtyOnlyAfterACall(@TempDir Path dir) throws Exception {
        var now = new AtomicReference<>(NOON);
        var ledger = ledger(now);
        assertFalse(ledger.isDirty(), "한 번도 부르지 않았으면 쓸 것이 없습니다");

        ledger.record(CODE);
        assertTrue(ledger.isDirty());

        ledger.save(dir.resolve("call-ledger.tsv"));
        assertFalse(ledger.isDirty(), "저장한 뒤에는 다시 쓸 것이 없습니다");
    }

    @Test
    @DisplayName("저장은 임시 파일을 남기지 않는다")
    void saveLeavesNoTempFileBehind(@TempDir Path dir) throws Exception {
        var ledger = ledger(new AtomicReference<>(NOON));
        ledger.record(CODE);
        ledger.save(dir.resolve("deep/call-ledger.tsv"));

        try (var files = Files.list(dir.resolve("deep"))) {
            assertEquals(java.util.List.of("call-ledger.tsv"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }
}
