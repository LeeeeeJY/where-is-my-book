package kr.wimb.holdings;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 소장 조회를 캐시로 감쌉니다.
 *
 * <p>{@code libSrchByBook} 의 인자가 그대로 캐시 열쇠라 여기가 가장 좁은 자리입니다.
 * {@link HoldingsLookup} 위쪽에 두면 「저작 + 고른 도서관」 단위가 되어, 도서관을 한 곳만
 * 더 골라도 캐시가 통째로 빗나갑니다. 도서관 조합은 셀 수 없이 많지만 (ISBN, 시도) 쌍은
 * 유한합니다.
 *
 * <p><b>빈 결과도 캐시합니다.</b> 「그 시도에는 소장한 곳이 없다」도 정보나루에게서 받은
 * 답이고, 그것을 남기지 않으면 소장하지 않은 책을 물어볼 때마다 호출이 그대로 나갑니다.
 * 실패는 예외로 오므로 캐시에 들어가지 않습니다. <b>둘을 섞으면 안 됩니다.</b>
 */
public final class CachingHoldingsClient implements HoldingsLookup.HoldingsClient {

    private final HoldingsLookup.HoldingsClient origin;
    private final HoldingCache cache;
    private final Duration freshFor;
    private final Clock clock;

    /**
     * @param freshFor 이 나이를 넘기면 다시 부릅니다. <b>버리는 만료가 아닙니다.</b>
     *                 다시 부르지 못하면 옛 값을 그대로 씁니다.
     */
    public CachingHoldingsClient(HoldingsLookup.HoldingsClient origin, HoldingCache cache,
                                 Duration freshFor, Clock clock) {
        this.origin = origin;
        this.cache = cache;
        this.freshFor = freshFor;
        this.clock = clock;
    }

    @Override
    public List<String> libCodesFor(String isbn13, String regionCode) {
        String key = regionCode == null ? HoldingCache.NATIONWIDE : regionCode;
        Optional<HoldingCache.Entry> hit = cache.get(isbn13, key);
        if (hit.isPresent() && isFresh(hit.get())) return hit.get().libCodes();

        try {
            List<String> fresh = origin.libCodesFor(isbn13, regionCode);
            cache.put(isbn13, key, fresh);
            return fresh;
        } catch (RuntimeException e) {
            // 다시 부르려다 실패했습니다. 옛 값이라도 있으면 그것을 씁니다.
            // 화면에는 그 값을 받은 시각이 함께 나가므로 사용자가 언제 기준인지 압니다.
            // 여기서 예외를 그대로 내보내면, 정보나루가 흔들리는 동안 이미 알고 있는
            // 답까지 「확인 불가」가 되어 캐시를 둔 보람이 없어집니다.
            if (hit.isPresent()) return hit.get().libCodes();
            throw e;
        }
    }

    private boolean isFresh(HoldingCache.Entry entry) {
        return Duration.between(entry.fetchedAt(), clock.instant()).compareTo(freshFor) < 0;
    }
}
