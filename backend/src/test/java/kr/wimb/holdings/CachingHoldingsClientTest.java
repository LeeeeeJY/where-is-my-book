package kr.wimb.holdings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    // ---------------------------------------------------------------
    // 메모리와 파일 스냅샷
    // ---------------------------------------------------------------

    @Test
    @DisplayName("같은 도서관부호는 문자열 객체를 공유한다")
    void sharesLibraryCodeStrings() {
        var upstream = new Counting();
        var cache = new CachingHoldingsClient(upstream, TTL, 100, movable(new AtomicReference<>(START)));

        // 파싱해서 막 만들어진 문자열을 흉내 냅니다. 값은 같지만 객체는 다릅니다.
        upstream.answer = List.of(new String("111001".toCharArray()));
        String a = cache.answerFor("9788983711892", "11").libCodes().get(0);
        upstream.answer = List.of(new String("111001".toCharArray()));
        String b = cache.answerFor("9788983711893", "11").libCodes().get(0);

        // 값이 같은지가 아니라 **같은 객체인지**를 봅니다. 이것이 깨지면 여섯 자리 부호가
        // 수만 번 중복 저장되어, 실측에서 10만 항목이 900MB 힙에 담기지 않았습니다.
        assertSame(a, b, "도서관부호 문자열을 공유하지 않으면 메모리가 열 배로 늡니다.");
    }

    @Test
    @DisplayName("상한을 넘겨도 통째로 비우지는 않는다")
    void keepsSomethingWhenFull() {
        var upstream = new Counting();
        var now = new AtomicReference<>(START);
        var cache = new CachingHoldingsClient(upstream, TTL, 4, movable(now));

        for (int i = 0; i < 6; i++) cache.answerFor("BOOK" + i, "11");

        // 파일 스냅샷이 생기면서 통째로 비우는 것이 여러 배포에 걸쳐 쌓은 것을 날리게
        // 되었습니다. 오래된 것부터 덜어 내되 남는 것이 있어야 합니다.
        assertTrue(cache.size() > 0, "가득 찼다고 통째로 비우면 쌓아 둔 것을 잃습니다.");
        assertTrue(cache.size() <= 4, "상한을 넘겼습니다: " + cache.size());
    }

    @Test
    @DisplayName("저장한 뒤 읽으면 답도 받은 시각도 그대로 돌아온다")
    void survivesASaveAndLoad(@TempDir Path dir) throws IOException {
        var upstream = new Counting();
        var now = new AtomicReference<>(START);
        var first = new CachingHoldingsClient(upstream, TTL, 100, movable(now));
        first.answerFor("9788983711892", "11");
        upstream.answer = List.of();                     // 「그 지역에는 없다」도 답입니다.
        first.answerFor("9788983711892", "31");

        Path file = dir.resolve("holding-cache.tsv.gz");
        first.save(file);

        var upstream2 = new Counting();
        var second = new CachingHoldingsClient(upstream2, TTL, 100, movable(now));
        assertEquals(2, second.load(file));

        var restored = second.answerFor("9788983711892", "11");
        assertEquals(List.of("111001"), restored.libCodes());
        // 되살아난 시각이 아니라 원래 받은 시각이어야 합니다. 여기가 틀리면 옛 답이
        // 새 답처럼 읽힙니다.
        assertEquals(START, restored.fetchedAt());
        // 빈 답이 「물어보지 않음」으로 바뀌면 그 책을 물어볼 때마다 호출이 나갑니다.
        assertTrue(second.answerFor("9788983711892", "31").libCodes().isEmpty());
        assertEquals(0, upstream2.calls, "되살렸으면 정보나루를 다시 부르지 않아야 합니다.");
    }

    @Test
    @DisplayName("이미 만료된 항목은 되살리지 않는다")
    void skipsStaleEntriesOnLoad(@TempDir Path dir) throws IOException {
        var now = new AtomicReference<>(START);
        var first = new CachingHoldingsClient(new Counting(), TTL, 100, movable(now));
        first.answerFor("9788983711892", "11");
        Path file = dir.resolve("holding-cache.tsv.gz");
        first.save(file);

        now.set(START.plus(Duration.ofDays(1)));
        var second = new CachingHoldingsClient(new Counting(), TTL, 100, movable(now));
        // 어차피 다음 조회에서 다시 부를 것이라, 들여 놓아 봐야 상한만 차지합니다.
        assertEquals(0, second.load(file));
    }

    @Test
    @DisplayName("파일이 없거나 깨졌으면 빈 채로 시작한다")
    void survivesAMissingOrBrokenFile(@TempDir Path dir) throws IOException {
        var cache = new CachingHoldingsClient(new Counting(), TTL, 100, movable(new AtomicReference<>(START)));
        assertEquals(0, cache.load(dir.resolve("missing.tsv.gz")));

        Path junk = dir.resolve("corrupt.tsv.gz");
        Files.writeString(junk, "이것은 gzip 이 아닙니다");
        // 캐시는 있으면 좋은 것이지 없으면 안 되는 것이 아닙니다. 파일 하나 때문에
        // 서버가 뜨지 않으면 잃는 것이 훨씬 큽니다.
        assertEquals(0, cache.load(junk));
    }

    @Test
    @DisplayName("저장은 임시 파일을 거쳐 원자적으로 바뀐다")
    void writesThroughATempFile(@TempDir Path dir) throws IOException {
        var cache = new CachingHoldingsClient(new Counting(), TTL, 100, movable(new AtomicReference<>(START)));
        cache.answerFor("9788983711892", "11");
        Path file = dir.resolve("holding-cache.tsv.gz");
        cache.save(file);

        try (var kids = Files.list(dir)) {
            // .tmp 가 남아 있으면 쓰다 만 파일이 다음에 읽힐 수 있습니다.
            assertEquals(List.of(file.getFileName().toString()),
                    kids.map(x -> x.getFileName().toString()).toList());
        }
    }
}
