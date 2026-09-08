package kr.wimb.holdings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (ISBN, 지역) 한 쌍의 소장 답을 한동안 기억해 두는 클라이언트.
 *
 * <p>소장 확인은 한 권에 판본 수 × 시도 수만큼 정보나루를 부르고, 요청 사이에 120ms 를
 * 두므로 스무 권이면 몇 초가 걸립니다. 그런데 <b>같은 책을 다시 묻는 일이 아주 흔합니다.</b>
 * 도서관을 하나 더 고르고 「확인」을 다시 누르면 ISBN 도 시도도 그대로이고, 새로 고침이나
 * 같은 책의 재검색도 그렇습니다. 그때마다 같은 답을 정보나루에 다시 물으면 사람은 그만큼
 * 다시 기다리고 하루 예산도 그만큼 새어 나갑니다.
 *
 * <p><b>실패는 기억하지 않습니다.</b> 예외는 그대로 올리고 아무것도 넣지 않습니다. 실패를
 * 기억하면 정보나루가 잠깐 흔들린 뒤에도 한동안 계속 「확인 불가」가 나옵니다.
 *
 * <p><b>빈 답은 기억합니다.</b> 「그 지역에는 소장한 곳이 없다」는 정상적인 답이고,
 * 화면은 그것을 빠짐없이 확인한 미소장으로 그립니다. 실패와는 타입에서부터 다릅니다.
 *
 * <p><b>받은 시각을 그대로 돌려줍니다.</b> 화면의 「n월 n일 조회 기준」이 이 값에서
 * 나옵니다. 캐시가 이것을 지금 시각으로 바꿔 말하면 옛 답이 새 답처럼 읽힙니다.
 *
 * <p>운영에서 쓸 소장 캐시는 DB 에 두고 만료시키지 않는 것이 최종 설계입니다(배경에서
 * 갱신하고 조회 시각을 함께 내보냅니다). 이것은 그 전까지의 <b>프로세스 안 캐시</b>이고,
 * 그래서 만료 시간을 짧게 두고 재배포마다 비워집니다.
 */
public final class CachingHoldingsClient implements HoldingsLookup.HoldingsClient {

    private final HoldingsLookup.HoldingsClient delegate;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final ConcurrentHashMap<String, Answer> cache = new ConcurrentHashMap<>();

    /**
     * @param ttl        한 답을 얼마나 믿을지. 정보나루의 소장 데이터가 하루 단위로 갱신되므로
     *                   몇 시간이면 충분하고, 더 길게 두면 새로 들어온 책을 그만큼 늦게 봅니다.
     * @param maxEntries 메모리 상한. 넘으면 만료된 것을 먼저 비우고, 그래도 넘으면 전부 비웁니다.
     *                   기계 메모리가 1GB 라 자라는 대로 두면 안 됩니다.
     */
    public CachingHoldingsClient(HoldingsLookup.HoldingsClient delegate, Duration ttl,
                                 int maxEntries, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    @Override
    public List<String> libCodesFor(String isbn13, String regionCode) {
        return answerFor(isbn13, regionCode).libCodes();
    }

    @Override
    public Answer answerFor(String isbn13, String regionCode) {
        String key = isbn13 + "/" + (regionCode == null ? "ALL" : regionCode);
        Instant now = clock.instant();

        Answer cached = cache.get(key);
        if (cached != null && isFresh(cached, now)) return cached;

        // 실패하면 여기서 예외가 올라가고 아무것도 기억하지 않습니다.
        Answer fresh = new Answer(List.copyOf(delegate.libCodesFor(isbn13, regionCode)), now);
        if (cache.size() >= maxEntries) evict(now);
        cache.put(key, fresh);
        return fresh;
    }

    /** 지금 기억하고 있는 답의 수. 상한이 지켜지는지 보려는 용도입니다. */
    public int size() {
        return cache.size();
    }

    private boolean isFresh(Answer answer, Instant now) {
        return answer.fetchedAt().plus(ttl).isAfter(now);
    }

    private void evict(Instant now) {
        cache.entrySet().removeIf(entry -> !isFresh(entry.getValue(), now));
        // 만료된 것이 없는데도 가득 찼으면 오래된 것을 고르는 비용을 치르는 대신 비웁니다.
        // 캐시가 비는 것은 느려지는 것이지 틀리는 것이 아닙니다.
        if (cache.size() >= maxEntries) cache.clear();
    }
}
