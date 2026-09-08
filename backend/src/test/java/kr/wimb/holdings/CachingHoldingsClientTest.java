package kr.wimb.holdings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (ISBN, 지역) 답을 기억해 두는 캐시.
 *
 * <p>가장 중요한 것은 <b>무엇을 기억하지 않는가</b>입니다. 실패를 기억하면 정보나루가 잠깐
 * 흔들린 뒤에도 한동안 「확인 불가」가 이어지고, 받은 시각을 지금으로 바꿔 말하면 옛 답이
 * 새 답처럼 읽힙니다.
 */
class CachingHoldingsClientTest {

    private static final Instant START = Instant.parse("2026-09-08T00:00:00Z");
    private static final Duration TTL = Duration.ofHours(6);

    /** 몇 번 불렸는지 세는 가짜 정보나루. */
    private static final class Counting implements HoldingsLookup.HoldingsClient {
        int calls;
        List<String> answer = List.of("111001");
        boolean failing;

        @Override public List<String> libCodesFor(String isbn13, String regionCode) {
            calls++;
            if (failing) throw new IllegalStateException("정보나루 장애");
            return answer;
        }
    }

    private static Clock movable(AtomicReference<Instant> now) {
        return new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
    }

    @Test
    @DisplayName("같은 ISBN 과 지역을 다시 물으면 정보나루를 부르지 않는다")
    void answersRepeatsFromMemory() {
        var upstream = new Counting();
        var now = new AtomicReference<>(START);
        var cache = new CachingHoldingsClient(upstream, TTL, 100, movable(now));

        var first = cache.answerFor("9788983711892", "11");
        now.set(START.plus(Duration.ofMinutes(30)));
        var again = cache.answerFor("9788983711892", "11");

        assertEquals(1, upstream.calls, "두 번째는 기억에서 답해야 합니다");
        assertEquals(List.of("111001"), again.libCodes());
        assertEquals(START, again.fetchedAt(), "받은 시각을 지금으로 바꿔 말하면 안 됩니다");
        assertEquals(first, again);
    }

    @Test
    @DisplayName("시간이 지나면 다시 받는다")
    void refetchesAfterTheTtl() {
        var upstream = new Counting();
        var now = new AtomicReference<>(START);
        var cache = new CachingHoldingsClient(upstream, TTL, 100, movable(now));

        cache.answerFor("9788983711892", "11");
        now.set(START.plus(TTL).plusSeconds(1));
        var fresh = cache.answerFor("9788983711892", "11");

        assertEquals(2, upstream.calls);
        assertEquals(now.get(), fresh.fetchedAt());
    }

    @Test
    @DisplayName("실패는 기억하지 않는다")
    void doesNotRememberFailures() {
        var upstream = new Counting();
        upstream.failing = true;
        var cache = new CachingHoldingsClient(upstream, TTL, 100, movable(new AtomicReference<>(START)));

        assertThrows(IllegalStateException.class, () -> cache.answerFor("9788983711892", "11"));
        upstream.failing = false;
        var answer = cache.answerFor("9788983711892", "11");

        assertEquals(2, upstream.calls, "장애가 걷히면 바로 다시 물어야 합니다");
        assertEquals(List.of("111001"), answer.libCodes());
    }

    @Test
    @DisplayName("소장한 곳이 없다는 답도 기억한다")
    void remembersEmptyAnswers() {
        // 빈 답은 「그 지역에는 없다」는 정상적인 답입니다. 실패와는 타입에서부터 다릅니다.
        var upstream = new Counting();
        upstream.answer = List.of();
        var cache = new CachingHoldingsClient(upstream, TTL, 100, movable(new AtomicReference<>(START)));

        cache.answerFor("9788983711892", "11");
        var again = cache.answerFor("9788983711892", "11");

        assertEquals(1, upstream.calls);
        assertTrue(again.libCodes().isEmpty());
    }

    @Test
    @DisplayName("지역이 다르면 다른 답이다")
    void regionIsPartOfTheKey() {
        var upstream = new Counting();
        var cache = new CachingHoldingsClient(upstream, TTL, 100, movable(new AtomicReference<>(START)));

        cache.answerFor("9788983711892", "11");
        cache.answerFor("9788983711892", "31");
        cache.answerFor("9788983711892", null);

        assertEquals(3, upstream.calls);
    }

    @Test
    @DisplayName("상한을 넘으면 비워서 메모리가 자라지 않게 한다")
    void staysUnderTheCap() {
        var upstream = new Counting();
        var cache = new CachingHoldingsClient(upstream, TTL, 2, movable(new AtomicReference<>(START)));

        cache.answerFor("A", "11");
        cache.answerFor("B", "11");
        cache.answerFor("C", "11");

        assertTrue(cache.size() <= 2, "상한을 넘겼습니다: " + cache.size());
    }

    @Test
    @DisplayName("libCodesFor 로 불러도 같은 기억을 쓴다")
    void plainCallSharesTheMemory() {
        var upstream = new Counting();
        var cache = new CachingHoldingsClient(upstream, TTL, 100, movable(new AtomicReference<>(START)));

        cache.libCodesFor("9788983711892", "11");
        cache.answerFor("9788983711892", "11");

        assertEquals(1, upstream.calls);
    }
}
