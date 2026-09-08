package kr.wimb.holdings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 저작의 ISBN 목록으로 소장 도서관을 찾습니다.
 *
 * <p><b>Open API Manual v20260210 의 13절에 따르면 {@code region} 은 필수입니다.</b>
 * 전국을 한 번에 받는 방법이 없으므로 소장 조회는 선택한 도서관들이 걸친 시도 수만큼
 * 호출이 곱해집니다. 기본값은 {@link RegionModeStore#documented()} 로 잡습니다.
 *
 * <p>그럼에도 탐색 로직을 남겨 둔 것은, 문서와 실제 동작이 다르거나 정보나루가 나중에
 * 동작을 바꿨을 때 스스로 따라갈 수 있어야 하기 때문입니다. 문서를 믿되 그것만 믿지는
 * 않습니다.
 *
 * <p><b>답이 언제 받은 것인지를 함께 들고 다닙니다.</b> 앞에 캐시가 끼어 있으면 방금 받은
 * 답과 몇 시간 전에 받은 답이 한 결과에 섞이는데, 화면은 그 가운데 <b>가장 오래된 시각</b>을
 * 기준으로 「n월 n일 조회 기준」이라고 말해야 합니다. 최신 시각으로 말하면 옛 답을 새 답인
 * 것처럼 읽게 됩니다.
 */
public final class HoldingsLookup {

    /** 정보나루가 {@code region} 을 어떻게 다루는지. */
    public enum RegionMode {
        /** 아직 모릅니다. 첫 조회에서 알아냅니다. */
        UNKNOWN,
        /** region 을 생략하면 전국을 돌려줍니다. ISBN 당 1회로 끝납니다. */
        NATIONWIDE,
        /** region 이 사실상 필수입니다. 시도마다 따로 불러야 합니다. */
        PER_REGION
    }

    /** 한 번 알아낸 방식을 기억해 두는 곳. 운영에서는 설정 테이블에 둡니다. */
    public interface RegionModeStore {
        RegionMode get();

        void set(RegionMode mode);

        static RegionModeStore inMemory() {
            return inMemory(RegionMode.UNKNOWN);
        }

        /**
         * 매뉴얼(v20260210) 13절이 {@code region} 을 필수로 명시하므로 PER_REGION 으로
         * 시작합니다. 탐색 비용을 치르지 않아도 됩니다.
         *
         * <p>탐색 로직은 그대로 남겨 둡니다. 정보나루가 나중에 동작을 바꾸거나, 문서와 실제가
         * 다른 경우에 스스로 따라갈 수 있어야 하기 때문입니다.
         */
        static RegionModeStore documented() {
            return inMemory(RegionMode.PER_REGION);
        }

        static RegionModeStore inMemory(RegionMode initial) {
            AtomicReference<RegionMode> value = new AtomicReference<>(initial);
            return new RegionModeStore() {
                public RegionMode get() { return value.get(); }
                public void set(RegionMode mode) { value.set(mode); }
            };
        }
    }

    /** 실제 호출. {@code regionCode} 가 null 이면 전국 조회를 시도합니다. */
    @FunctionalInterface
    public interface HoldingsClient {
        /**
         * @return 소장 도서관부호 목록. 소장한 곳이 없으면 빈 목록입니다.
         * @throws RuntimeException 조회 자체가 실패한 경우. 빈 결과와 반드시 구분해야 합니다.
         */
        List<String> libCodesFor(String isbn13, String regionCode);

        /**
         * 답과, 그 답을 정보나루에서 <b>실제로 받은 시각</b>.
         *
         * @param fetchedAt 캐시에서 꺼낸 답이면 처음 받았던 시각입니다. 화면의 「조회 기준」이
         *                  이 값에서 나오므로, 캐시가 이것을 지금 시각으로 바꿔 말하면 안 됩니다.
         */
        record Answer(List<String> libCodes, Instant fetchedAt) {}

        /**
         * 기본은 지금 막 받은 것으로 칩니다. 캐시를 끼우는 쪽이 이것을 덮어써서 원래 받았던
         * 시각을 돌려줍니다. {@link #libCodesFor} 만 구현한 가짜 클라이언트도 그대로 동작합니다.
         */
        default Answer answerFor(String isbn13, String regionCode) {
            return new Answer(libCodesFor(isbn13, regionCode), Instant.now());
        }
    }

    /**
     * @param libCodes        소장이 확인된 도서관부호
     * @param unresolvedIsbns 전혀 확인하지 못한 ISBN
     * @param partialIsbns    일부 지역만 확인된 ISBN
     * @param oldestFetchedAt 이 결과에 쓰인 답 가운데 <b>가장 오래된</b> 조회 시각. 아무 답도
     *                        받지 못했으면 null 입니다.
     *
     * <p><b>{@code unresolvedIsbns} 와 {@code partialIsbns} 를 미소장으로 표시하면 안 됩니다.</b>
     * 실제로 있는 책을 없다고 답하게 되어 헛걸음을 만듭니다. 화면에는 "확인 불가"로 따로
     * 표시해야 합니다.
     */
    public record Result(
            Set<String> libCodes,
            List<String> unresolvedIsbns,
            List<String> partialIsbns,
            int calls,
            RegionMode modeUsed,
            Instant oldestFetchedAt
    ) {
        /** 모든 ISBN 을 빠짐없이 확인했는지. 아니면 화면에 그 사실을 밝혀야 합니다. */
        public boolean isComplete() {
            return unresolvedIsbns.isEmpty() && partialIsbns.isEmpty();
        }
    }

    private final HoldingsClient client;
    private final RegionModeStore modeStore;

    public HoldingsLookup(HoldingsClient client, RegionModeStore modeStore) {
        this.client = client;
        this.modeStore = modeStore;
    }

    /**
     * @param isbn13List  저작에 묶인 <b>모든</b> 판본. 하나만 조회하면 도서관이 다른 판을
     *                    가지고 있어도 미소장으로 나옵니다.
     * @param regionCodes 선택한 도서관들이 걸친 시도 코드
     */
    public Result lookup(List<String> isbn13List, List<String> regionCodes) {
        Set<String> found = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        Oldest oldest = new Oldest();
        int calls = 0;

        for (String isbn : isbn13List) {
            if (modeStore.get() == RegionMode.UNKNOWN) {
                calls += probeMode(isbn, regionCodes, found, oldest);
                // 탐색 과정에서 이미 결과를 모았으므로 같은 ISBN 을 다시 부르지 않습니다.
                continue;
            }
            IsbnOutcome outcome = fetchOne(isbn, regionCodes, oldest);
            calls += outcome.calls();
            found.addAll(outcome.libCodes());
            if (outcome.failedAll()) unresolved.add(isbn);
            else if (outcome.failedSome()) partial.add(isbn);
        }

        return new Result(found, List.copyOf(unresolved), List.copyOf(partial),
                calls, modeStore.get(), oldest.value);
    }

    /**
     * 전국 조회를 먼저 시도해 보고, 빈 결과가 오면 지역별로 다시 시도합니다.
     *
     * <p>둘 다 비어 있으면 방식을 확정하지 않습니다. 그 책을 아무 도서관도 소장하지 않은
     * 경우와 구분할 수 없기 때문입니다. 여기서 잘못 확정하면 이후의 모든 조회가 틀립니다.
     */
    private int probeMode(String isbn, List<String> regionCodes, Set<String> found, Oldest oldest) {
        int calls = 0;
        try {
            calls++;
            HoldingsClient.Answer nationwide = client.answerFor(isbn, null);
            if (!nationwide.libCodes().isEmpty()) {
                modeStore.set(RegionMode.NATIONWIDE);
                found.addAll(nationwide.libCodes());
                oldest.note(nationwide.fetchedAt());
                return calls;
            }
        } catch (RuntimeException e) {
            // 오류를 돌려준다는 것은 region 없이는 받지 않는다는 뜻일 가능성이 높습니다.
            // 다만 일시적인 장애일 수도 있으므로 이것만으로 확정하지 않고 아래에서 확인합니다.
        }

        IsbnOutcome outcome = fetchPerRegion(isbn, regionCodes, oldest);
        calls += outcome.calls();
        found.addAll(outcome.libCodes());
        if (!outcome.libCodes().isEmpty()) modeStore.set(RegionMode.PER_REGION);
        return calls;
    }

    private IsbnOutcome fetchOne(String isbn, List<String> regionCodes, Oldest oldest) {
        if (modeStore.get() == RegionMode.NATIONWIDE) {
            try {
                HoldingsClient.Answer answer = client.answerFor(isbn, null);
                oldest.note(answer.fetchedAt());
                return new IsbnOutcome(answer.libCodes(), 1, 0, 1);
            } catch (RuntimeException e) {
                return new IsbnOutcome(List.of(), 1, 1, 1);
            }
        }
        // 방식을 아직 모르면 지역별로 갑니다. 호출이 늘지만 결과가 빠지지 않습니다.
        return fetchPerRegion(isbn, regionCodes, oldest);
    }

    private IsbnOutcome fetchPerRegion(String isbn, List<String> regionCodes, Oldest oldest) {
        Set<String> codes = new LinkedHashSet<>();
        int calls = 0;
        int failures = 0;
        for (String region : regionCodes) {
            calls++;
            try {
                HoldingsClient.Answer answer = client.answerFor(isbn, region);
                codes.addAll(answer.libCodes());
                oldest.note(answer.fetchedAt());
            } catch (RuntimeException e) {
                // 시도 하나가 실패해도 나머지로 답을 만듭니다.
                // 다만 그 사실을 숨기지 않고 partial 로 알립니다.
                failures++;
            }
        }
        return new IsbnOutcome(List.copyOf(codes), calls, failures, Math.max(1, regionCodes.size()));
    }

    private record IsbnOutcome(List<String> libCodes, int calls, int failures, int attempts) {
        boolean failedAll() { return failures > 0 && failures == attempts; }
        boolean failedSome() { return failures > 0 && failures < attempts; }
    }

    /** 가장 오래된 조회 시각을 모아 둡니다. 답을 하나도 못 받았으면 비어 있습니다. */
    private static final class Oldest {
        Instant value;

        void note(Instant fetchedAt) {
            if (fetchedAt == null) return;
            if (value == null || fetchedAt.isBefore(value)) value = fetchedAt;
        }
    }
}
