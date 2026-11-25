package kr.wimb.opac;

import kr.wimb.opac.OpacHostTally.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 호스트별 상세 집계.
 *
 * <p>여기서 틀리면 「그 호스트는 운영에서 잘 간다」를 틀린 숫자로 말하게 됩니다. 규칙을 넣을지 뺄지를
 * 이 숫자로 정하므로, 세는 것과 파일로 이어받는 것을 함께 봅니다.
 */
class OpacHostTallyTest {

    private static final long SLOW = 5_000;

    private static Clock at(AtomicReference<Instant> now) {
        return new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
    }

    private static OpacHostTally tally() {
        return new OpacHostTally(at(new AtomicReference<>(Instant.parse("2026-11-24T03:00:00Z"))), SLOW);
    }

    private static Map<String, Object> row(OpacHostTally tally, String host) {
        return tally.snapshot().stream().filter(r -> host.equals(r.get("host"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("호스트마다 따로 세고, 받지 못한 횟수가 많은 호스트를 앞에 세운다")
    void countsPerHostAndPutsTheTroubledFirst() {
        var tally = tally();
        for (int i = 0; i < 9; i++) tally.recordAnswered("lib.fine.kr", Event.RESOLVED, 800, null);
        tally.recordAnswered("lib.fine.kr", Event.NO_MATCH, 700, null);
        tally.recordAnswered("lib.slow.kr", Event.RESOLVED, 900, null);
        tally.record("lib.slow.kr", Event.RESPONSE_TIMEOUT, "HttpTimeoutException: request timed out");
        tally.record("lib.slow.kr", Event.RESPONSE_TIMEOUT, "HttpTimeoutException: request timed out");
        tally.record("lib.blocked.kr", Event.CONNECT_TIMEOUT, "HttpConnectTimeoutException: HTTP connect timed out");

        List<Map<String, Object>> rows = tally.snapshot();

        assertEquals(List.of("lib.slow.kr", "lib.blocked.kr", "lib.fine.kr"),
                rows.stream().map(r -> r.get("host")).toList(),
                "많이 눌린 호스트가 아니라 받지 못한 횟수가 많은 호스트가 먼저입니다");
        var slow = row(tally, "lib.slow.kr");
        assertEquals(3L, slow.get("attempts"));
        assertEquals(1L, slow.get("resolved"));
        assertEquals(2L, slow.get("responseTimeout"));
        assertEquals(0L, slow.get("connectTimeout"));
        var fine = row(tally, "lib.fine.kr");
        assertEquals(10L, fine.get("attempts"));
        assertEquals(1L, fine.get("noMatch"), "그 판이 없다는 답은 받지 못한 것이 아닙니다");
        assertNull(fine.get("lastFailure"));
    }

    @Test
    @DisplayName("답을 받은 것만 시간을 재고, 받기 제한을 넘겨서 받은 답을 따로 센다")
    void timesOnlyAnswersAndCountsTheSlowOnes() {
        var tally = tally();
        tally.recordAnswered("lib.example.kr", Event.RESOLVED, 1_000, null);
        tally.recordAnswered("lib.example.kr", Event.RESOLVED, 8_600, null);   // 머리는 바로 왔고 본문이 느렸던 경우
        tally.recordAnswered("lib.example.kr", Event.HTTP_ERROR, 400, "HTTP 500");
        tally.record("lib.example.kr", Event.RESPONSE_TIMEOUT, "HttpTimeoutException: request timed out");

        var row = row(tally, "lib.example.kr");

        assertEquals(4L, row.get("attempts"));
        assertEquals(3L, row.get("answered"), "시간 초과로 끊긴 것은 답을 받은 것이 아닙니다");
        assertEquals(8_600L, row.get("maxMillis"));
        assertEquals((1_000L + 8_600L + 400L) / 3, row.get("avgMillis"));
        assertEquals(1L, row.get("slowAnswers"));
        assertEquals("HttpTimeoutException: request timed out", row.get("lastFailure"));
    }

    @Test
    @DisplayName("날짜는 한국 시각의 날로 적고 시각은 적지 않는다")
    void recordsSeoulDatesNotTimes() {
        // UTC 로는 23일 저녁이지만 한국은 이미 24일입니다.
        var now = new AtomicReference<>(Instant.parse("2026-11-23T16:30:00Z"));
        var tally = new OpacHostTally(at(now), SLOW);
        tally.record("lib.example.kr", Event.OTHER_FAILURE, "ConnectException");
        now.set(Instant.parse("2026-11-26T01:00:00Z"));
        tally.recordAnswered("lib.example.kr", Event.RESOLVED, 500, null);

        var row = row(tally, "lib.example.kr");

        assertEquals(LocalDate.parse("2026-11-24"), tally.since());
        assertEquals("2026-11-24", row.get("firstOn"));
        assertEquals("2026-11-26", row.get("lastOn"));
        assertEquals("2026-11-24", row.get("lastFailureOn"), "성공은 마지막 실패의 날짜를 건드리지 않습니다");
    }

    @Test
    @DisplayName("사유에 주소가 섞여 있으면 그 앞에서 자른다")
    void cutsNotesBeforeAnyAddress() {
        // 검색 주소에는 책 제목이 들어 있습니다. 누가 무엇을 눌렀는지를 파일에 남기지 않습니다.
        assertEquals("IOException: failed to read",
                OpacHostTally.tidy("IOException: failed to read https://lib.example.kr/search?title=데미안"));
        assertEquals("SSLHandshakeException: PKIX path building failed",
                OpacHostTally.tidy("SSLHandshakeException: PKIX\tpath building\nfailed"));
        assertNull(OpacHostTally.tidy("https://lib.example.kr/search?title=데미안"));
        assertEquals(OpacHostTally.MAX_NOTE, OpacHostTally.tidy("x".repeat(500)).length());
    }

    @Test
    @DisplayName("적어 둔 것을 다시 뜬 서버가 이어받아 그 위에 계속 센다")
    void whatOneServerSavesTheNextCarriesOn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data").resolve("opac-host-tally.tsv");
        var first = tally();
        first.recordAnswered("lib.gumi.go.kr", Event.RESOLVED, 1_200, null);
        first.recordAnswered("lib.gumi.go.kr", Event.RESOLVED, 6_000, null);
        first.record("lib.gumi.go.kr", Event.CONNECT_TIMEOUT, "HttpConnectTimeoutException: HTTP connect timed out");
        first.record("lib.gumi.go.kr", Event.SKIPPED_DOWN, null);
        assertTrue(first.isDirty());
        first.save(file);
        assertFalse(first.isDirty(), "적은 뒤에는 바뀐 것이 없습니다");

        var later = new AtomicReference<>(Instant.parse("2026-11-27T03:00:00Z"));
        var second = new OpacHostTally(at(later), SLOW);
        assertEquals(1, second.load(file));
        assertFalse(second.isDirty(), "이어받기만 한 것을 다시 적을 까닭이 없습니다");
        second.recordAnswered("lib.gumi.go.kr", Event.RESOLVED, 900, null);

        var row = row(second, "lib.gumi.go.kr");
        assertEquals(5L, row.get("attempts"));
        assertEquals(3L, row.get("resolved"));
        assertEquals(1L, row.get("connectTimeout"));
        assertEquals(1L, row.get("skippedDown"));
        assertEquals(3L, row.get("answered"));
        assertEquals(6_000L, row.get("maxMillis"));
        assertEquals(1L, row.get("slowAnswers"));
        assertEquals("2026-11-24", row.get("firstOn"));
        assertEquals("2026-11-27", row.get("lastOn"));
        assertEquals("2026-11-24", row.get("lastFailureOn"));
        assertEquals("HttpConnectTimeoutException: HTTP connect timed out", row.get("lastFailure"));
        assertEquals(LocalDate.parse("2026-11-24"), second.since(), "세기 시작한 날은 앞 서버의 것입니다");
    }

    @Test
    @DisplayName("시간 상한에 닿아 그만둔 것은 받지 못한 것으로 세되 그 호스트의 실패 사유로는 적지 않는다")
    void countsTimeLimitStopsWithoutBlamingTheHost(@TempDir Path dir) throws Exception {
        // 상세 확인 규칙(@verify:)은 검색 화면과 후보 상세를 여러 번 받습니다. 하나하나는 받기 제한 안이어도
        // 합쳐서 상한을 넘기면 그만둡니다. 그 호스트가 답하지 않은 것과는 다른 일이라 따로 셉니다.
        var tally = tally();
        tally.recordAnswered("lib.yuseong.go.kr", Event.RESOLVED, 6_400, null);
        tally.record("lib.yuseong.go.kr", Event.TIME_LIMIT, null);
        tally.record("lib.yuseong.go.kr", Event.TIME_LIMIT, null);
        tally.recordAnswered("lib.fine.kr", Event.RESOLVED, 900, null);

        var row = row(tally, "lib.yuseong.go.kr");
        assertEquals(3L, row.get("attempts"));
        assertEquals(2L, row.get("timeLimit"));
        assertNull(row.get("lastFailure"), "그 호스트가 답하지 않은 것이 아닙니다");
        assertEquals("lib.yuseong.go.kr", tally.snapshot().get(0).get("host"),
                "상세로 가지 못한 횟수가 많은 호스트가 먼저입니다");

        // 예전 서버가 적은 파일에는 이 칸이 없고, 새 서버가 적은 파일을 다시 읽어도 그대로 이어집니다.
        Path file = dir.resolve("opac-host-tally.tsv");
        tally.save(file);
        var next = tally();
        next.load(file);
        assertEquals(2L, row(next, "lib.yuseong.go.kr").get("timeLimit"));
        assertEquals(0L, row(next, "lib.fine.kr").get("timeLimit"));
    }

    @Test
    @DisplayName("우리 것이 아닌 파일은 읽지 않고, 깨진 줄은 그 줄만 건너뛴다")
    void ignoresForeignFilesAndSkipsBrokenCells(@TempDir Path dir) throws Exception {
        Path foreign = dir.resolve("foreign.tsv");
        Files.writeString(foreign, "lib.example.kr\tRESOLVED=3\n");
        var tally = tally();
        assertEquals(0, tally.load(foreign), "첫 줄이 우리 표시가 아니면 읽지 않습니다");
        assertEquals(0, tally.load(dir.resolve("missing.tsv")));

        Path damaged = dir.resolve("damaged.tsv");
        Files.writeString(damaged, String.join("\n",
                "#wimb-opac-host-tally\t1",
                "#since\t언젠가",
                "lib.a.kr\tRESOLVED=열\tNO_MATCH=2\t모르는칸=7\t칸이아님\tfirstOn=2026-13-99",
                "",
                "lib.b.kr\tRESOLVED=4\tanswered=4\ttotalMillis=4000\tmaxMillis=1500",
                ""));
        assertEquals(2, tally.load(damaged));

        assertEquals(0L, row(tally, "lib.a.kr").get("resolved"));
        assertEquals(2L, row(tally, "lib.a.kr").get("noMatch"), "숫자 하나가 깨졌다고 그 줄의 나머지를 버리지 않습니다");
        assertNull(row(tally, "lib.a.kr").get("firstOn"));
        assertEquals(4L, row(tally, "lib.b.kr").get("resolved"));
        assertEquals(1_000L, row(tally, "lib.b.kr").get("avgMillis"));
        assertNull(tally.since());
    }

    @Test
    @DisplayName("호스트 이름은 대소문자를 가리지 않고, 이름이 없으면 세지 않는다")
    void foldsCaseAndIgnoresMissingHosts() {
        var tally = tally();
        tally.recordAnswered("Lib.Example.KR", Event.RESOLVED, 100, null);
        tally.recordAnswered("lib.example.kr", Event.RESOLVED, 100, null);
        tally.record(null, Event.BUSY, null);
        tally.record(" ", Event.BUSY, null);

        assertEquals(1, tally.snapshot().size());
        assertEquals(2L, row(tally, "lib.example.kr").get("attempts"));
    }
}
