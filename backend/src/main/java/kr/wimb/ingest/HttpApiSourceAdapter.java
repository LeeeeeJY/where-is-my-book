package kr.wimb.ingest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * HTTP API 소스의 공통 뼈대.
 *
 * <p>하위 클래스는 <b>주소를 만드는 일과 응답을 읽는 일만</b> 합니다.
 * 호출 건수 세기, 호출 간격, 재시도는 여기서 한 번만 구현해 모든 소스가 같이 씁니다.
 * 소스마다 다시 짜면 어느 하나는 반드시 간격을 지키지 않게 됩니다.
 */
public abstract class HttpApiSourceAdapter implements SourceAdapter {

    /** 실제 통신. 테스트에서는 가짜를 끼웁니다. */
    @FunctionalInterface
    public interface Transport {
        Response send(URI uri) throws IOException;

        record Response(int statusCode, String body) {}
    }

    /** 대기. 테스트에서 시간을 흘려보내지 않기 위해 밖으로 뺐습니다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        Sleeper REAL = d -> Thread.sleep(d.toMillis());
    }

    protected record PageResult(int itemCount, boolean hasNext) {}

    /** 5xx 와 통신 오류를 이만큼까지 다시 시도합니다. 4xx 는 다시 해도 같으므로 재시도하지 않습니다. */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(500);
    /** 페이지를 무한히 넘기는 사고를 막는 상한. */
    private static final int MAX_PAGES = 10_000;

    private final Transport transport;
    private final CallLedger ledger;
    private final Sleeper sleeper;
    private final Random jitter;
    private final Map<String, Long> lastCallNanos = new HashMap<>();

    protected HttpApiSourceAdapter(Transport transport, CallLedger ledger) {
        this(transport, ledger, Sleeper.REAL, new Random());
    }

    protected HttpApiSourceAdapter(Transport transport, CallLedger ledger,
                                   Sleeper sleeper, Random jitter) {
        this.transport = transport;
        this.ledger = ledger;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    /** 몇 쪽짜리 주소를 만들지는 소스마다 다릅니다. */
    protected abstract URI pageUri(IngestUnit unit, int pageNo);

    /** 응답을 읽어 sink 로 흘려보내고, 다음 쪽이 있는지 알려 줍니다. */
    protected abstract PageResult parse(String body, IngestUnit unit, RecordSink sink);

    @Override
    public void fetch(IngestUnit unit, RecordSink sink) {
        long rowsRead = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            String body = get(pageUri(unit, page));
            PageResult result = parse(body, unit, sink);
            rowsRead += result.itemCount();
            sink.progress(rowsRead);
            if (!result.hasNext() || result.itemCount() == 0) return;
        }
        throw new SourceException(
                "쪽 넘기기가 " + MAX_PAGES + "쪽을 넘었습니다. 종료 조건을 확인하세요: " + unit.scopeKey());
    }

    /**
     * 한 번의 조회. 원장, 간격, 재시도를 모두 거칩니다.
     *
     * <p>재시도도 실제 호출이므로 시도마다 원장에 적습니다. 재시도를 세지 않으면
     * 원장이 실제로 쓴 건수와 어긋납니다. <b>원장은 세기만 하고 막지 않습니다.</b>
     * 한도를 넘겼는지는 상대 서버가 오류로 알려 줍니다.
     */
    protected String get(URI uri) {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ledger.record(code());
            pace();
            try {
                Transport.Response response = transport.send(uri);
                if (response.statusCode() / 100 == 2) return response.body();
                if (response.statusCode() / 100 == 4) {
                    // 잘못된 요청이나 인증 실패는 다시 해도 같습니다.
                    throw new SourceException(code() + " 가 " + response.statusCode()
                            + " 를 돌려주었습니다. 인증키와 파라미터를 확인하세요.");
                }
                lastFailure = new IOException("HTTP " + response.statusCode());
            } catch (IOException e) {
                lastFailure = e;
            }
            if (attempt < MAX_ATTEMPTS) backOff(attempt);
        }
        throw new SourceException(code() + " 호출이 " + MAX_ATTEMPTS + "번 모두 실패했습니다.",
                lastFailure);
    }

    /** 상대 서버에 대한 예의이자 차단을 피하는 장치입니다. */
    private void pace() {
        Duration minInterval = capabilities().minRequestInterval();
        if (minInterval.isZero() || minInterval.isNegative()) return;

        Long last = lastCallNanos.get(code());
        long now = System.nanoTime();
        if (last != null) {
            long waitNanos = minInterval.toNanos() - (now - last);
            if (waitNanos > 0) sleepQuietly(Duration.ofNanos(waitNanos));
        }
        lastCallNanos.put(code(), System.nanoTime());
    }

    /** 여러 클라이언트가 동시에 몰리지 않도록 대기 시간을 흩뜨립니다. */
    private void backOff(int attempt) {
        long base = FIRST_BACKOFF.toMillis() * (1L << (attempt - 1));
        sleepQuietly(Duration.ofMillis(base + jitter.nextInt((int) Math.max(1, base / 2))));
    }

    private void sleepQuietly(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("대기 중 중단되었습니다.", e);
        }
    }
}
