package kr.wimb.api;

import kr.wimb.ingest.DailyCallLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원장 파일을 <b>누가 읽고 쓰는지</b>를 봅니다. 건수 계산 자체는 {@code DailyCallLedgerTest}
 * 가 봅니다.
 */
class CallLedgerStoreTest {

    private static final String CODE = "DATA4LIBRARY_API";
    private static final Clock NOON =
            Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneId.of("UTC"));

    private static DailyCallLedger ledger() {
        return new DailyCallLedger(Map.of(CODE, 30_000), NOON);
    }

    @Test
    @DisplayName("주기 저장으로 적은 건수를 다시 뜬 서버가 이어받는다")
    void whatOneServerPersistsTheNextRestores(@TempDir Path dir) {
        String file = dir.resolve("call-ledger.tsv").toString();

        var first = ledger();
        var firstStore = new CallLedgerStore(first, file, "");
        firstStore.restore();
        for (int i = 0; i < 40; i++) first.record(CODE);
        firstStore.persist();
        // 종료 훅 없이 죽은 경우입니다(docker rm -f 는 SIGKILL 입니다). 주기 저장이 남긴
        // 것만으로 이어져야 합니다.

        var second = ledger();
        new CallLedgerStore(second, file, "").restore();
        assertEquals(40, second.used(CODE));
    }

    @Test
    @DisplayName("바뀐 것이 없으면 파일을 쓰지 않는다")
    void doesNotWriteWhenNothingChanged(@TempDir Path dir) {
        Path file = dir.resolve("call-ledger.tsv");
        var store = new CallLedgerStore(ledger(), file.toString(), "");
        store.restore();
        store.persist();
        store.persistOnShutdown();

        assertFalse(Files.exists(file), "한 번도 부르지 않았는데 파일을 썼습니다");
    }

    @Test
    @DisplayName("서가를 수집하러 뜬 컨테이너는 원장 파일을 읽지도 쓰지도 않는다")
    void harvestContainerLeavesTheFileAlone(@TempDir Path dir) throws Exception {
        // 수집 컨테이너는 서비스하는 서버와 같은 볼륨을 붙이고 따로 돕니다. 둘이 같은 파일에
        // 저마다 센 값을 적으면 나중에 적은 쪽이 앞의 값을 덮어씁니다.
        Path file = dir.resolve("call-ledger.tsv");
        var server = ledger();
        for (int i = 0; i < 300; i++) server.record(CODE);
        server.save(file);
        String written = Files.readString(file);

        var harvester = ledger();
        var store = new CallLedgerStore(harvester, file.toString(), "141321:8");
        store.restore();
        assertEquals(0, harvester.used(CODE), "수집 컨테이너는 서버의 건수를 이어받지 않습니다");

        for (int i = 0; i < 50; i++) harvester.record(CODE);
        store.persist();
        store.persistOnShutdown();
        assertEquals(written, Files.readString(file), "서버가 적은 건수를 덮어썼습니다");
    }

    @Test
    @DisplayName("저장하지 못해도 죽지 않는다")
    void survivesAnUnwritablePath(@TempDir Path dir) throws Exception {
        // 파일이 있어야 할 자리에 디렉터리가 있습니다.
        Path blocked = dir.resolve("call-ledger.tsv");
        Files.createDirectories(blocked.resolve("inside"));
        var ledger = ledger();
        var store = new CallLedgerStore(ledger, blocked.toString(), "");

        store.restore();
        ledger.record(CODE);
        assertDoesNotThrow(store::persist);
        assertEquals(1, ledger.used(CODE));
        assertTrue(ledger.isDirty(), "적지 못했으면 다음 주기에 다시 적어야 합니다");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "실패한 저장이 임시 파일을 남겼습니다");
        }
    }
}
