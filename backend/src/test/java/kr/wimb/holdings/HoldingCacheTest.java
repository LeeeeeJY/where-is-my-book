package kr.wimb.holdings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HoldingCacheTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    /** 시각을 마음대로 옮길 수 있는 시계. 신선도 판정을 실제 시간 없이 확인합니다. */
    private static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    void 같은_부호는_문자열_객체를_공유한다() {
        var cache = new HoldingCache(100, Clock.fixed(T0, ZoneOffset.UTC));
        // 파싱해서 막 만들어진 문자열을 흉내 냅니다. 값은 같지만 객체는 다릅니다.
        cache.put("9788983711892", "11", List.of(new String("111001".toCharArray())));
        cache.put("9788983711893", "11", List.of(new String("111001".toCharArray())));

        String a = cache.get("9788983711892", "11").orElseThrow().libCodes().get(0);
        String b = cache.get("9788983711893", "11").orElseThrow().libCodes().get(0);

        // 값이 같은지가 아니라 **같은 객체인지**를 봅니다. 이것이 깨지면 부호 하나가
        // 수만 번 중복 저장되어, 실측에서 10만 항목이 900MB 를 넘겼습니다.
        assertSame(a, b, "도서관부호 문자열을 공유하지 않으면 메모리가 열 배로 늡니다.");
    }

    @Test
    void 상한을_넘으면_오래_안_쓴_것부터_밀려난다() {
        var cache = new HoldingCache(2, Clock.fixed(T0, ZoneOffset.UTC));
        cache.put("A", "11", List.of("111001"));
        cache.put("B", "11", List.of("111002"));
        cache.get("A", "11");                       // A 를 최근에 쓴 것으로 만듭니다.
        cache.put("C", "11", List.of("111003"));

        assertEquals(2, cache.size());
        assertTrue(cache.get("A", "11").isPresent(), "방금 쓴 것이 밀려나면 안 됩니다.");
        assertTrue(cache.get("B", "11").isEmpty());
        assertTrue(cache.get("C", "11").isPresent());
    }

    @Test
    void 가장_오래된_수집_시각을_돌려준다() {
        var clock = new MovableClock(T0);
        var cache = new HoldingCache(100, clock);
        cache.put("A", "11", List.of("111001"));
        clock.advance(Duration.ofDays(10));
        cache.put("A", "31", List.of("311001"));

        // 두 시도를 합쳐 만든 답이므로, 그 답은 열흘 전 값을 담고 있습니다.
        // 최근 시각을 말하면 실제보다 새것처럼 보입니다.
        assertEquals(T0, cache.oldestFetchedAt(List.of("A"), List.of("11", "31")).orElseThrow());
    }

    @Test
    void 캐시에_없으면_수집_시각도_없다() {
        var cache = new HoldingCache(100, Clock.fixed(T0, ZoneOffset.UTC));
        assertTrue(cache.oldestFetchedAt(List.of("A"), List.of("11")).isEmpty());
    }

    @Test
    void 저장한_뒤_읽으면_그대로_돌아온다(@TempDir Path dir) throws IOException {
        var cache = new HoldingCache(100, Clock.fixed(T0, ZoneOffset.UTC));
        cache.put("9788983711892", "11", List.of("111001", "111002"));
        // 「그 시도에는 소장한 곳이 없다」도 답이라 빈 목록도 남깁니다.
        cache.put("9788983711892", "31", List.of());

        Path file = dir.resolve("holding-cache.tsv.gz");
        cache.save(file);

        var restored = new HoldingCache(100, Clock.fixed(T0.plusSeconds(999), ZoneOffset.UTC));
        assertEquals(2, restored.load(file));
        assertEquals(List.of("111001", "111002"),
                restored.get("9788983711892", "11").orElseThrow().libCodes());
        // 빈 목록이 「물어보지 않음」으로 바뀌면 안 됩니다. 있어야 하고, 비어 있어야 합니다.
        var empty = restored.get("9788983711892", "31");
        assertTrue(empty.isPresent(), "빈 결과가 사라지면 그 책을 물어볼 때마다 호출이 나갑니다.");
        assertTrue(empty.get().libCodes().isEmpty());
        // 수집 시각은 되살아난 시각이 아니라 원래 받은 시각이어야 합니다.
        assertEquals(T0, empty.get().fetchedAt());
    }

    @Test
    void 파일이_없거나_깨졌으면_빈_채로_시작한다(@TempDir Path dir) throws IOException {
        var cache = new HoldingCache(100, Clock.fixed(T0, ZoneOffset.UTC));
        assertEquals(0, cache.load(dir.resolve("missing.tsv.gz")));

        Path junk = dir.resolve("corrupt.tsv.gz");
        Files.writeString(junk, "이것은 gzip 이 아닙니다");
        // 캐시는 있으면 좋은 것이지 없으면 안 되는 것이 아닙니다. 파일 하나 때문에
        // 서버가 뜨지 않으면 잃는 것이 훨씬 큽니다.
        assertEquals(0, cache.load(junk));
    }

    @Test
    void 저장은_임시_파일을_거쳐_원자적으로_바뀐다(@TempDir Path dir) throws IOException {
        var cache = new HoldingCache(100, Clock.fixed(T0, ZoneOffset.UTC));
        cache.put("A", "11", List.of("111001"));
        Path file = dir.resolve("holding-cache.tsv.gz");
        cache.save(file);

        try (var kids = Files.list(dir)) {
            // .tmp 가 남아 있으면 쓰다 만 파일이 다음에 읽힐 수 있습니다.
            assertEquals(List.of(file.getFileName().toString()),
                    kids.map(p -> p.getFileName().toString()).toList());
        }
    }

    // ---------------------------------------------------------------
    // 캐시를 통과하는 조회
    // ---------------------------------------------------------------

    @Test
    void 두_번째_조회는_정보나루를_부르지_않는다() {
        var calls = new AtomicInteger();
        var clock = new MovableClock(T0);
        var cache = new HoldingCache(100, clock);
        var client = new CachingHoldingsClient(
                (isbn, region) -> { calls.incrementAndGet(); return List.of("111001"); },
                cache, Duration.ofDays(7), clock);

        assertEquals(List.of("111001"), client.libCodesFor("A", "11"));
        assertEquals(List.of("111001"), client.libCodesFor("A", "11"));
        assertEquals(1, calls.get(), "같은 (ISBN, 시도)를 두 번 부르면 캐시가 없는 것입니다.");
    }

    @Test
    void 신선도_한계를_넘으면_다시_부른다() {
        var calls = new AtomicInteger();
        var clock = new MovableClock(T0);
        var cache = new HoldingCache(100, clock);
        var client = new CachingHoldingsClient(
                (isbn, region) -> { calls.incrementAndGet(); return List.of("111001"); },
                cache, Duration.ofDays(7), clock);

        client.libCodesFor("A", "11");
        clock.advance(Duration.ofDays(8));
        client.libCodesFor("A", "11");
        assertEquals(2, calls.get());
    }

    @Test
    void 다시_부르지_못하면_옛_값을_쓴다() {
        var clock = new MovableClock(T0);
        var cache = new HoldingCache(100, clock);
        var failing = new AtomicInteger();
        var client = new CachingHoldingsClient((isbn, region) -> {
            if (failing.getAndIncrement() > 0) throw new IllegalStateException("정보나루 장애");
            return List.of("111001");
        }, cache, Duration.ofDays(7), clock);

        client.libCodesFor("A", "11");
        clock.advance(Duration.ofDays(8));

        // 소장 정보가 없어 빈 화면을 보여 주는 것보다 언제 기준인지 밝히는 편이 낫습니다.
        // 여기서 예외가 나가면 정보나루가 흔들리는 동안 아는 답까지 「확인 불가」가 됩니다.
        assertEquals(List.of("111001"), client.libCodesFor("A", "11"));
        assertEquals(T0, cache.get("A", "11").orElseThrow().fetchedAt(),
                "실패했는데 수집 시각이 갱신되면 오래된 값이 오늘 것으로 보입니다.");
    }

    @Test
    void 캐시에_없는데_실패하면_그대로_던진다() {
        var clock = new MovableClock(T0);
        var client = new CachingHoldingsClient((isbn, region) -> {
            throw new IllegalStateException("정보나루 장애");
        }, new HoldingCache(100, clock), Duration.ofDays(7), clock);

        // 물어보지 못한 것을 「소장한 곳이 없다」로 바꾸면 안 됩니다. 실제로 있는 책을
        // 없다고 답하게 되어 헛걸음을 만듭니다.
        assertThrows(IllegalStateException.class, () -> client.libCodesFor("A", "11"));
    }

    @Test
    void 실패는_캐시에_남지_않는다() {
        var clock = new MovableClock(T0);
        var cache = new HoldingCache(100, clock);
        var client = new CachingHoldingsClient((isbn, region) -> {
            throw new IllegalStateException("정보나루 장애");
        }, cache, Duration.ofDays(7), clock);

        assertThrows(IllegalStateException.class, () -> client.libCodesFor("A", "11"));
        assertTrue(cache.get("A", "11").isEmpty(),
                "실패가 빈 결과로 캐시되면 그 책이 영영 미소장으로 나갑니다.");
    }

    @Test
    void 전국_조회는_시도_코드와_섞이지_않는다() {
        var clock = new MovableClock(T0);
        var cache = new HoldingCache(100, clock);
        var seen = new ArrayList<String>();
        var client = new CachingHoldingsClient((isbn, region) -> {
            seen.add(String.valueOf(region));
            return List.of("111001");
        }, cache, Duration.ofDays(7), clock);

        client.libCodesFor("A", null);              // region 없이 전국
        client.libCodesFor("A", "11");              // 서울만

        assertEquals(List.of("null", "11"), seen, "둘은 다른 질문이라 따로 캐시해야 합니다.");
        assertTrue(cache.get("A", HoldingCache.NATIONWIDE).isPresent());
        assertTrue(cache.get("A", "11").isPresent());
    }
}
