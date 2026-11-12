package kr.wimb.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiSourceAdapterTest {

    private static final String CODE = "TEST_API";

    /** 정해진 응답을 순서대로 돌려주는 가짜 통신. */
    private static final class FakeTransport implements HttpApiSourceAdapter.Transport {
        final Deque<Object> queued = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        FakeTransport ok(String body) { queued.add(new Response(200, body)); return this; }
        FakeTransport status(int code) { queued.add(new Response(code, "")); return this; }
        FakeTransport ioError() { queued.add(new IOException("연결 끊김")); return this; }

        @Override
        public Response send(URI uri) throws IOException {
            calls.incrementAndGet();
            Object next = queued.isEmpty() ? new Response(200, "EMPTY") : queued.poll();
            if (next instanceof IOException e) throw e;
            return (Response) next;
        }
    }

    /** 한 줄에 한 레코드가 있고, 마지막 쪽이면 END 로 끝나는 아주 단순한 형식. */
    private static final class TestAdapter extends HttpApiSourceAdapter {
        TestAdapter(Transport transport, CallLedger budget) {
            // 대기는 실제로 하지 않고, 지터도 고정해 테스트가 빠르고 결정적이게 합니다.
            super(transport, budget, d -> {}, new Random(0));
        }

        @Override public String code() { return CODE; }
        @Override public java.util.Set<SourceRecord.Kind> produces() {
            return java.util.Set.of(SourceRecord.Kind.BIB);
        }
        @Override public SourceCapabilities capabilities() {
            return SourceCapabilities.httpApi(100, Duration.ZERO, 90);
        }
        @Override public List<IngestUnit> discover(DiscoveryContext ctx) { return List.of(); }

        @Override protected URI pageUri(IngestUnit unit, int pageNo) {
            return URI.create("https://example.test/api?page=" + pageNo);
        }

        @Override protected PageResult parse(String body, IngestUnit unit, RecordSink sink) {
            if (body.equals("EMPTY")) return new PageResult(0, false);
            List<String> lines = new ArrayList<>(List.of(body.split("\n")));
            boolean hasNext = !lines.remove("END");
            for (String line : lines) {
                sink.accept(new SourceRecord(SourceRecord.Kind.BIB, CODE, line,
                        unit.scopeKey(), Map.of("raw", line), line));
            }
            return new PageResult(lines.size(), hasNext);
        }
    }

    private static final class CollectingSink implements RecordSink {
        final List<SourceRecord> records = new ArrayList<>();
        final List<String> rejects = new ArrayList<>();
        long lastProgress;

        public void accept(SourceRecord r) { records.add(r); }
        public void reject(String raw, String reason, String detail) { rejects.add(reason); }
        public void progress(long rowsRead) { lastProgress = rowsRead; }
    }

    private static CallLedger budget(int limit) {
        return new DailyCallLedger(Map.of(CODE, limit),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
    }

    private static IngestUnit unit() {
        return new IngestUnit(CODE, "2024-01", "v1", IngestUnit.Mode.BACKFILL, null, null);
    }

    @Test
    @DisplayName("끝 표시가 나올 때까지 쪽을 넘긴다")
    void paginatesUntilEnd() {
        var transport = new FakeTransport().ok("a\nb").ok("c\nEND");
        var sink = new CollectingSink();

        new TestAdapter(transport, budget(100)).fetch(unit(), sink);

        assertEquals(List.of("a", "b", "c"),
                sink.records.stream().map(SourceRecord::sourceRecordId).toList());
        assertEquals(3, sink.lastProgress);
    }

    @Test
    @DisplayName("빈 쪽이 오면 멈춘다")
    void stopsOnEmptyPage() {
        var transport = new FakeTransport().ok("a\nb");   // 이후는 EMPTY 가 돌아옵니다
        var sink = new CollectingSink();

        new TestAdapter(transport, budget(100)).fetch(unit(), sink);

        assertEquals(2, sink.records.size());
        assertEquals(2, transport.calls.get(), "빈 쪽을 확인한 뒤 더 부르지 않아야 합니다");
    }

    @Test
    @DisplayName("5xx 와 통신 오류는 다시 시도한다")
    void retriesServerErrors() {
        var transport = new FakeTransport().status(503).ioError().ok("a\nEND");
        var sink = new CollectingSink();

        new TestAdapter(transport, budget(100)).fetch(unit(), sink);

        assertEquals(3, transport.calls.get());
        assertEquals(1, sink.records.size());
    }

    @Test
    @DisplayName("4xx 는 다시 시도하지 않는다")
    void doesNotRetryClientErrors() {
        // 인증키가 틀렸거나 파라미터가 잘못된 것이므로 다시 해도 같습니다.
        var transport = new FakeTransport().status(401);
        var adapter = new TestAdapter(transport, budget(100));

        var e = assertThrows(SourceAdapter.SourceException.class,
                () -> adapter.fetch(unit(), new CollectingSink()));

        assertEquals(1, transport.calls.get());
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    @DisplayName("재시도도 예산을 쓴다")
    void retriesConsumeBudget() {
        // 재시도를 공짜로 세면 원장이 실제 잔량과 어긋납니다.
        var budget = budget(100);
        var transport = new FakeTransport().status(503).status(503).ok("a\nEND");

        new TestAdapter(transport, budget).fetch(unit(), new CollectingSink());

        assertEquals(3, budget.used(CODE));
    }

    @Test
    @DisplayName("원장은 세기만 한다. 하루 한도 숫자를 넘겨도 호출을 막지 않는다")
    void ledgerCountsButNeverBlocks() {
        // 예전에는 한도에 닿으면 부르지 않았고, 배경 작업은 그 80% 에서 먼저 멈췄습니다.
        // 둘 다 없앴습니다. 한도를 넘겼는지는 상대 서버가 오류로 알려 줍니다.
        var ledger = budget(1);
        var transport = new FakeTransport().ok("a").ok("b").ok("c\nEND");
        var sink = new CollectingSink();

        new TestAdapter(transport, ledger).fetch(unit(), sink);

        assertEquals(3, transport.calls.get(), "한도 숫자는 호출을 막지 않습니다");
        assertEquals(3, ledger.used(CODE), "넘긴 뒤에도 계속 셉니다");
        assertEquals(0, ledger.remaining(CODE), "남은 건수는 음수가 되지 않습니다");
    }

    @Test
    @DisplayName("한도를 모르는 소스는 남은 건수를 끝없이 넉넉하게 본다")
    void unknownLimitMeansPlentyRemaining() {
        var ledger = new DailyCallLedger(Map.of(),
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("UTC")));
        ledger.record(CODE);
        assertEquals(1, ledger.used(CODE));
        assertEquals(Integer.MAX_VALUE, ledger.remaining(CODE));
    }

    @Test
    @DisplayName("한국 시각으로 날이 바뀌면 건수가 0 으로 돌아간다")
    void resetsOnKoreanMidnight() {
        var clock = new MutableClock(Instant.parse("2026-09-05T14:30:00Z")); // KST 05-05 23:30
        var budget = new DailyCallLedger(Map.of(CODE, 10), clock);

        budget.record(CODE);
        assertEquals(1, budget.used(CODE));

        clock.advance(Duration.ofHours(1));   // KST 로 자정을 넘깁니다
        assertEquals(0, budget.used(CODE), "한국 시각 기준으로 날짜가 바뀌면 초기화됩니다");
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
