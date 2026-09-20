package kr.wimb.shelf;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>서가를 언제 세우는지</b>를 고정합니다.
 *
 * <p>전국 1,619곳을 미리 받으면 48만 회에 디스크 65GB 입니다. 그래서 사람이 여는
 * 서가만 세우는데, 그 판단이 틀리면 둘 중 하나가 됩니다. 너무 자주 세우면 <b>하루
 * 한도가 사라져 검색까지 막히고</b>, 너무 안 세우면 <b>서가가 영영 낡은 채로</b>
 * 남습니다. 둘 다 화면에는 잘 드러나지 않습니다.
 */
class ShelfServiceTest {

    private static final Kdc SUBJECT = Kdc.LITERATURE;

    @Test
    @DisplayName("아직 아무도 열지 않은 서가는 고장이 아니라 「없음」이다")
    void notBuiltYetIsANormalState(@TempDir Path dir) {
        var fixture = new Fixture(dir);

        var status = fixture.service.status("141321", SUBJECT);

        assertEquals(ShelfService.State.ABSENT, status.state());
        assertEquals(0, fixture.calls.get(), "들여다보기만 하는데 정보나루를 부르면 안 됩니다");
    }

    @Test
    @DisplayName("세워 둔 서가를 보는 데는 정보나루를 한 번도 부르지 않는다")
    void readingABuiltShelfCostsNothing(@TempDir Path dir) throws Exception {
        var fixture = new Fixture(dir);
        fixture.writeShelf("141321", "2026-09-20", "2026-09-20", 100);
        fixture.calls.set(0);

        var status = fixture.service.status("141321", SUBJECT);

        assertEquals(ShelfService.State.READY, status.state());
        assertFalse(status.stale());
        assertEquals(0, fixture.calls.get());
    }

    /**
     * <b>기한이 지나도 지우지 않습니다.</b> 지우고 다시 세우면 그동안 서가가 비어
     * 사람이 몇십 초를 기다립니다. 20일 전 서가라도 빈 화면보다 낫고, 기준일이 화면에
     * 적혀 있으니 거짓말도 아닙니다. 소장 캐시가 지키는 규칙과 같습니다.
     */
    @Test
    @DisplayName("기한이 지난 서가도 그대로 보여 주고 갱신은 뒤에서 한다")
    void staleShelvesAreStillServed(@TempDir Path dir) throws Exception {
        var fixture = new Fixture(dir);
        fixture.writeShelf("141321", "2026-08-01", "2026-08-01", 100);

        var status = fixture.service.status("141321", SUBJECT);

        assertEquals(ShelfService.State.READY, status.state(), "낡아도 보여 줍니다");
        assertTrue(status.stale(), "화면이 낡았다는 것을 알 수 있어야 합니다");
    }

    /**
     * <b>같은 서가를 둘이 열어도 한 번만 세웁니다.</b> 세우는 데 몇십 초가 걸려서 그
     * 사이에 다른 사람이 같은 서가를 열 수 있는데, 그때마다 새로 시작하면 같은 일을
     * 겹쳐 하면서 호출만 몇 배로 씁니다.
     */
    @Test
    @DisplayName("같은 서가를 두 번 눌러도 한 번만 세운다")
    void buildsOnlyOnceForTheSameShelf(@TempDir Path dir) throws Exception {
        var fixture = new Fixture(dir);
        fixture.blockBuild();

        fixture.service.build("141321", SUBJECT);
        fixture.awaitBlocked("141321");
        int afterFirst = fixture.calls.get();

        fixture.service.build("141321", SUBJECT);
        fixture.service.build("141321", SUBJECT);

        assertEquals(afterFirst, fixture.calls.get(), "이미 돌고 있으면 새로 시작하지 않습니다");
        assertEquals(ShelfService.State.BUILDING, fixture.service.status("141321", SUBJECT).state());
        fixture.releaseBuild();
    }

    /**
     * <b>하루 몫이 얼마 안 남았으면 시작하지 않습니다.</b> 서가 하나가 수백 회라 남은
     * 것을 여기에 다 쓰면 그날 검색이 통째로 막힙니다. 서가는 내일 세워도 되지만
     * 검색은 그 사람이 지금 기다리고 있습니다.
     */
    @Test
    @DisplayName("하루 몫이 얼마 남지 않으면 서가를 세우지 않는다")
    void doesNotBuildWhenTheDailyBudgetIsNearlyGone(@TempDir Path dir) {
        var fixture = new Fixture(dir, 3_000);   // 남은 몫보다 큰 예비를 둡니다.

        var status = fixture.service.build("141321", SUBJECT);

        assertEquals(ShelfService.State.ABSENT, status.state());
        assertEquals(0, fixture.calls.get(), "시작도 하지 않아야 합니다");
    }

    /**
     * 갱신할 때가 되면 <b>권수만 한 번 물어봅니다.</b> 그대로면 다시 세우지 않아
     * 수십~수백 번을 아낍니다.
     *
     * <p><b>그때 기준일을 올리지 않습니다.</b> 받아 온 적 없는 날짜를 받아 온 것처럼
     * 말하게 되기 때문입니다. 올리는 것은 「확인한 날」뿐입니다.
     */
    @Test
    @DisplayName("권수가 그대로면 다시 세우지 않고 확인한 날만 올린다")
    void skipsRebuildWhenTheCountIsUnchanged(@TempDir Path dir) throws Exception {
        var fixture = new Fixture(dir);
        fixture.writeShelf("141321", "2026-08-01", "2026-08-01", 7);
        fixture.numFound.set(7);   // 그대로입니다.
        fixture.calls.set(0);

        fixture.service.status("141321", SUBJECT);   // 낡았으므로 뒤에서 갱신이 돕니다.
        fixture.awaitIdle();

        assertEquals(1, fixture.calls.get(), "권수를 묻는 한 번이면 됩니다");

        String meta = Files.readString(fixture.metaPath("141321"), StandardCharsets.UTF_8);
        assertTrue(meta.contains("\"asOf\":\"2026-08-01\""), "받아 온 날은 그대로여야 합니다");
        assertTrue(meta.contains("\"checkedAt\":\"2026-09-20\""), "확인한 날은 오늘이어야 합니다");
    }

    /**
     * <b>순서를 적는 규칙이 바뀌면 권수가 그대로여도 다시 세웁니다.</b>
     *
     * <p>순서는 수집할 때 계산해 파일에 적어 둡니다. 그래서 규칙만 고치면 이미 세워 둔
     * 서가는 예전 순서 그대로이고, 갱신할 때가 되어도 <b>권수가 그대로라 건너뜁니다.</b>
     * 아무도 손대지 않으면 영영 예전 순서로 남는데, 화면에는 아무 이상이 없어 보여서
     * 규칙을 고친 사람은 고쳐졌다고 믿습니다. 실제로 전집 순서를 고쳤을 때 이 자리가
     * 없어서 사람이 서버에 들어가 손으로 지워야 했습니다.
     */
    @Test
    @DisplayName("순서 규칙이 바뀌면 권수가 그대로여도 다시 세운다")
    void rebuildsWhenTheOrderingRuleChanged(@TempDir Path dir) throws Exception {
        var fixture = new Fixture(dir);
        // 날짜는 오늘이라 기한으로는 낡지 않았고, 권수도 그대로입니다.
        fixture.writeShelf("141321", "2026-09-20", "2026-09-20", 3, ShelfSortKey.VERSION - 1);
        fixture.numFound.set(3);
        fixture.calls.set(0);

        var status = fixture.service.status("141321", SUBJECT);
        assertTrue(status.stale(), "예전 규칙으로 세운 서가는 낡은 것입니다");
        fixture.awaitIdle();

        String meta = Files.readString(fixture.metaPath("141321"), StandardCharsets.UTF_8);
        assertTrue(meta.contains("\"keyVersion\":" + ShelfSortKey.VERSION),
                "다시 세운 뒤에는 지금 판 번호여야 합니다: " + meta);
        assertTrue(fixture.calls.get() > 1,
                "권수만 묻고 끝내면 안 됩니다. 실제로 받은 횟수: " + fixture.calls.get());
    }

    /**
     * 판 번호가 없던 때에 만든 차림표는 0 으로 읽혀 다시 세워집니다. <b>의도한
     * 동작입니다.</b> 그 서가들이 바로 예전 순서로 저장된 것들입니다.
     */
    @Test
    @DisplayName("판 번호가 없는 옛 차림표도 다시 세운다")
    void rebuildsMetaWrittenBeforeTheVersionExisted(@TempDir Path dir) throws Exception {
        var fixture = new Fixture(dir);
        fixture.writeShelf("141321", "2026-09-20", "2026-09-20", 3, 0);

        assertTrue(fixture.service.status("141321", SUBJECT).stale());
    }

    // ── 시험 거들기 ───────────────────────────────────────────────────────

    /** 2026-09-20 한국 시각 낮. */
    private static final Clock NOON =
            Clock.fixed(Instant.parse("2026-09-20T03:00:00Z"), ZoneId.of("UTC"));

    private static final class Fixture {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger numFound = new AtomicInteger(3);
        final AtomicReference<java.util.concurrent.CountDownLatch> gate = new AtomicReference<>();
        final Path dir;
        final ShelfService service;
        final ShelfStore store;

        Fixture(Path dir) {
            this(dir, 0);
        }

        Fixture(Path dir, int reserve) {
            this.dir = dir;
            var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1_000), NOON);
            var client = new Data4LibraryClient(this::respond, "테스트키", budget);
            this.store = new ShelfStore(dir);
            this.service = new ShelfService(store, new ShelfHarvester(client, dir, NOON),
                    budget, 14, 2, reserve, NOON);
        }

        private String respond(URI uri) {
            calls.incrementAndGet();
            var waiting = gate.get();
            if (waiting != null) {
                try {
                    waiting.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <response><numFound>%d</numFound><docs>
                  <doc><bookname><![CDATA[토지]]></bookname><isbn13>9788937437267</isbn13>
                    <class_no>813.6</class_no>
                    <callNumbers><callNumber><book_code>박14ㅌ</book_code>
                      <shelf_loc_name>종합자료실</shelf_loc_name></callNumber></callNumbers></doc>
                </docs></response>""".formatted(numFound.get());
        }

        /** 세우는 일이 끝나지 않게 붙들어 둡니다. 「세우는 중」 상태를 볼 수 있습니다. */
        void blockBuild() {
            gate.set(new java.util.concurrent.CountDownLatch(1));
        }

        void releaseBuild() {
            var waiting = gate.getAndSet(null);
            if (waiting != null) waiting.countDown();
        }

        /**
         * 세우는 일이 시작해서 <b>빗장 앞에 멈출 때까지</b> 기다립니다.
         *
         * <p>「세우는 중」이 된 것만 보고 넘어가면 안 됩니다. <b>상태는 빗장을 지르는
         * 순간 바뀌는데 호출은 그 뒤에 나갑니다.</b> 그 사이에 센 값을 기준으로 「더
         * 늘지 않았다」를 보면, 뒤늦게 나간 호출 때문에 시험이 이따금 실패합니다.
         * 한꺼번에 여러 쪽을 부르므로 <b>한 번 불렀는지가 아니라 더 늘지 않는지</b>를
         * 봐야 합니다.
         */
        void awaitBlocked(String libCode) throws InterruptedException {
            awaitBuilding(libCode);
            int stable = 0;
            int before = -1;
            for (int i = 0; i < 300 && stable < 5; i++) {
                int now = calls.get();
                stable = now == before && now > 0 ? stable + 1 : 0;
                before = now;
                Thread.sleep(10);
            }
            if (calls.get() == 0) fail("정보나루를 한 번도 부르지 않았습니다");
        }

        void awaitBuilding(String libCode) throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                if (service.status(libCode, SUBJECT).state() == ShelfService.State.BUILDING) return;
                Thread.sleep(10);
            }
            fail("서가를 세우기 시작하지 않았습니다");
        }

        /** 뒤에서 도는 일이 끝나기를 기다립니다. */
        void awaitIdle() throws InterruptedException {
            int stable = 0;
            int before = -1;
            for (int i = 0; i < 300 && stable < 5; i++) {
                int now = calls.get();
                stable = now == before ? stable + 1 : 0;
                before = now;
                Thread.sleep(10);
            }
        }

        Path metaPath(String libCode) {
            return dir.resolve(libCode).resolve(SUBJECT.slug()).resolve("meta.json");
        }

        /** 이미 세워 둔 서가를 흉내 냅니다. */
        void writeShelf(String libCode, String asOf, String checkedAt, int reported)
                throws Exception {
            writeShelf(libCode, asOf, checkedAt, reported, ShelfSortKey.VERSION);
        }

        /** 판 번호를 골라 적습니다. 예전 규칙으로 세운 서가를 흉내 낼 때 씁니다. */
        void writeShelf(String libCode, String asOf, String checkedAt, int reported, int keyVersion)
                throws Exception {
            Path room = dir.resolve(libCode).resolve(SUBJECT.slug()).resolve("r0");
            Files.createDirectories(room);
            Files.writeString(room.resolve("0.json"),
                    "[{\"isbn\":\"9788937437267\",\"title\":\"토지\",\"call\":\"813.6 박14ㅌ\"}]",
                    StandardCharsets.UTF_8);
            Files.writeString(metaPath(libCode), """
                {"libCode":"%s","kdc":"%s","asOf":"%s","checkedAt":"%s","chunkSize":200,\
                "count":1,"reported":%d,"keyVersion":%d,\
                "rooms":[{"slug":"r0","name":"종합자료실","count":1,\
                "chunks":1,"firstCall":"813.6 박14ㅌ","lastCall":"813.6 박14ㅌ",\
                "chosungAt":{"ㅂ":0}}]}"""
                    .formatted(libCode, SUBJECT.code(), asOf, checkedAt, reported, keyVersion),
                    StandardCharsets.UTF_8);
        }
    }

    /** 예산이 넉넉한지 확인해 두는 자리. 시험이 예산 때문에 실패하면 헷갈립니다. */
    @Test
    @DisplayName("시험용 예산은 서가를 세울 만큼 남아 있다")
    void fixtureBudgetIsEnough(@TempDir Path dir) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 1_000), NOON);
        assertTrue(budget.remaining(Data4LibraryClient.SOURCE_CODE) > 0);
        assertTrue(budget.tryAcquire(Data4LibraryClient.SOURCE_CODE, ApiBudget.Priority.BACKGROUND));
    }
}
