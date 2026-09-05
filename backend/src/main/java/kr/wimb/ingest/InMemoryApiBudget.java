package kr.wimb.ingest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리에 두는 예산 원장. 테스트와 단일 프로세스 실행에 씁니다.
 *
 * <p>운영에서는 {@code api_budget} 테이블을 쓰는 구현으로 갈아 끼웁니다.
 * 재시작해도 잔량이 유지되어야 하기 때문입니다. 배치가 절반쯤 돌다 재시작했을 때
 * 예산이 초기화되면 그날 한도를 두 배로 쓰게 됩니다.
 */
public final class InMemoryApiBudget implements ApiBudget {

    /** 한국 시각 기준으로 날짜가 바뀝니다. 저장은 UTC 로 하되 이 경계만은 KST 개념입니다. */
    public static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Seoul");

    /** 배경 작업은 여기까지만 씁니다. 나머지는 사람이 기다리는 요청 몫으로 남겨 둡니다. */
    private static final double BACKGROUND_CEILING = 0.8;

    private final Map<String, Integer> limits;
    private final Clock clock;
    private final Map<String, Integer> counters = new ConcurrentHashMap<>();
    private volatile LocalDate currentDay;

    public InMemoryApiBudget(Map<String, Integer> dailyLimits, Clock clock) {
        this.limits = Map.copyOf(dailyLimits);
        this.clock = clock;
        this.currentDay = today();
    }

    @Override
    public synchronized boolean tryAcquire(String sourceCode, Priority priority) {
        rollOverIfNewDay();
        int limit = limits.getOrDefault(sourceCode, 0);
        if (limit <= 0) return true;   // 파일 기반 소스는 한도가 없습니다.

        int used = counters.getOrDefault(sourceCode, 0);
        int ceiling = priority == Priority.BACKGROUND
                ? (int) Math.floor(limit * BACKGROUND_CEILING)
                : limit;
        if (used >= ceiling) return false;

        counters.put(sourceCode, used + 1);
        return true;
    }

    @Override
    public synchronized int used(String sourceCode) {
        rollOverIfNewDay();
        return counters.getOrDefault(sourceCode, 0);
    }

    @Override
    public synchronized int remaining(String sourceCode) {
        int limit = limits.getOrDefault(sourceCode, 0);
        return limit <= 0 ? Integer.MAX_VALUE : Math.max(0, limit - used(sourceCode));
    }

    private void rollOverIfNewDay() {
        LocalDate now = today();
        if (!now.equals(currentDay)) {
            counters.clear();
            currentDay = now;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUDGET_ZONE);
    }
}
