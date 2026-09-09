package kr.wimb.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 주소 하나가 하루 몫을 통째로 태우지 못하게 막는 문지기.
 *
 * <p><b>이것은 비밀 문제가 아니라 접근 통제 문제입니다.</b> {@code VITE_API_BASE} 는 빌드할
 * 때 번들에 박히므로 API 주소는 애초에 감출 수 없습니다. 주소를 아는 사람은 누구나 부를 수
 * 있고, 그 호출은 그대로 정보나루 예산에서 나갑니다. 주소를 감추는 것은 답이 아니고,
 * <b>주소마다 쓸 수 있는 양을 정해 두는 것</b>이 답입니다.
 *
 * <p>막아야 하는 것은 두 가지인데 서로 다릅니다. <b>분당 한도</b>는 짧은 시간에 몰아치는
 * 것을 막고, <b>하루 한도</b>는 한 주소가 천천히 하루치를 다 가져가는 것을 막습니다.
 * 분당 한도만 두면 후자를 못 막습니다. 분당 400 을 하루 내내 쓰면 57만 건이라, 하루 예산
 * 25,000 건은 한 시간이면 사라집니다.
 *
 * <p><b>요청마다 무게가 다릅니다.</b> 요청 수를 그냥 세면 안 됩니다. {@code /api/status} 한
 * 번은 정보나루를 부르지 않지만 {@code /api/check/resolve} 한 번은 줄마다 두세 번씩 불러
 * 백 번을 넘길 수 있습니다. 같은 한 번으로 세면 가장 비싼 요청이 가장 싸게 통과합니다.
 * 무게표는 {@link RateLimitInterceptor} 에 있습니다.
 *
 * <p><b>이 한도는 정보나루 예산 원장을 대신하지 않습니다.</b> 원장({@code api_budget})은
 * 우리가 정보나루에 실제로 몇 번 나갔는지를 세고, 이것은 어느 주소가 얼마나 요청했는지를
 * 셉니다. 앞엣것은 전체 상한이고 뒤엣것은 한 사람 몫입니다. 둘 다 있어야 합니다.
 *
 * <p>토큰은 시간에 비례해 조금씩 다시 찹니다. 창을 잘라 세면 창이 바뀌는 순간에 두 창
 * 몫이 한꺼번에 나가는데, 화면이 소장 조회를 여덟 개씩 겹쳐 보내므로 그 순간이 실제로
 * 자주 옵니다.
 */
public final class RateLimit {

    /** 하루의 경계는 한국 시각입니다. 정보나루 예산 원장과 같은 기준이어야 합니다. */
    public static final ZoneId ZONE = kr.wimb.ingest.InMemoryApiBudget.BUDGET_ZONE;

    /** 어느 한도에 걸렸는지. <b>사람이 할 일이 다르므로 갈라 놓습니다.</b> */
    public enum Scope {
        /** 잠시 뒤 다시 하면 됩니다. */
        MINUTE,
        /** 오늘은 더 못 씁니다. 기다린다고 낫지 않습니다. */
        DAY
    }

    /**
     * @param allowed           통과시켜도 되는지
     * @param scope             막았다면 어느 한도인지. 통과했으면 {@code null} 입니다
     * @param retryAfterSeconds 언제 다시 시도하면 되는지. {@code Retry-After} 헤더에 그대로 실립니다
     */
    public record Decision(boolean allowed, Scope scope, long retryAfterSeconds) {
        static Decision pass() {
            return new Decision(true, null, 0);
        }
    }

    private final double capacity;
    private final double refillPerMs;
    private final int perDay;
    private final int maxClients;
    private final Clock clock;

    /**
     * 주소별 상태. <b>{@code synchronized} 가 아니라 잠금 객체를 씁니다.</b> 요청 처리가
     * 가상 스레드에서 도는데, 가상 스레드는 {@code synchronized} 안에서 잠들면 운반 스레드를
     * 붙잡습니다. 여기서는 산술만 하므로 잠들 일이 없지만, 같은 이유로 이 프로젝트의 다른
     * 잠금도 전부 잠금 객체입니다. 규칙을 한 곳에서만 다르게 두지 않습니다.
     */
    private final ReentrantLock gate = new ReentrantLock();

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param perMinute  주소 하나가 1분에 쓸 수 있는 무게
     * @param burst      한꺼번에 몰아 쓸 수 있는 무게. 화면이 소장 조회를 여덟 개씩 겹쳐
     *                   보내므로 분당 한도와 같게 두면 정상 사용이 걸립니다
     * @param perDay     주소 하나가 하루에 쓸 수 있는 무게
     * @param maxClients 기억해 둘 주소의 수. 메모리 1GB 기계라 자라는 대로 두면 안 됩니다
     */
    public RateLimit(int perMinute, int burst, int perDay, int maxClients, Clock clock) {
        this.refillPerMs = Math.max(1, perMinute) / 60_000.0;
        this.capacity = Math.max(Math.max(1, burst), Math.max(1, perMinute));
        this.perDay = perDay;
        this.maxClients = Math.max(1, maxClients);
        this.clock = clock;
    }

    /**
     * 이 주소가 이만큼 써도 되는지 묻고, 된다면 그만큼 깎습니다.
     *
     * <p><b>하루 한도를 먼저 봅니다.</b> 둘 다 걸렸을 때 「잠시 뒤 다시」라고 답하면 사용자가
     * 기다렸다가 다시 눌러도 똑같이 막히고, 무엇이 문제인지 알 방법이 없습니다.
     */
    public Decision check(String clientKey, int cost) {
        long nowMs = clock.millis();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);

        gate.lock();
        try {
            if (buckets.size() >= maxClients && !buckets.containsKey(clientKey)) evict();

            Bucket bucket = buckets.computeIfAbsent(
                    clientKey, key -> new Bucket(capacity, nowMs, today));
            bucket.lastSeenMs = nowMs;

            if (!today.equals(bucket.day)) {
                bucket.day = today;
                bucket.usedToday = 0;
            }

            if (perDay > 0 && bucket.usedToday + cost > perDay) {
                return new Decision(false, Scope.DAY, secondsUntilTomorrow());
            }

            double filled = Math.min(capacity, bucket.tokens + (nowMs - bucket.lastRefillMs) * refillPerMs);
            bucket.tokens = filled;
            bucket.lastRefillMs = nowMs;
            if (filled < cost) {
                // 모자란 만큼이 다시 차는 데 걸리는 시간입니다. 0초라고 답하면 곧바로 다시
                // 눌러 또 막히므로 최소 1초로 올립니다.
                long wait = (long) Math.ceil((cost - filled) / refillPerMs / 1000.0);
                return new Decision(false, Scope.MINUTE, Math.max(1, wait));
            }

            bucket.tokens = filled - cost;
            bucket.usedToday += cost;
            return Decision.pass();
        } finally {
            gate.unlock();
        }
    }

    /** 지금 기억하고 있는 주소의 수. {@code /api/status} 가 내보냅니다. */
    public int trackedClients() {
        return buckets.size();
    }

    /**
     * 상한을 넘으면 <b>오래된 것부터 4분의 1만 덜어 냅니다.</b> 통째로 비우면 그 순간
     * 모두의 한도가 함께 풀려, 마침 몰아치던 주소가 상한을 다시 채우며 계속 통과합니다.
     * 소장 캐시에서 쓰는 것과 같은 방식입니다.
     */
    private void evict() {
        List<Map.Entry<String, Bucket>> entries = new ArrayList<>(buckets.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().lastSeenMs));
        int drop = Math.max(1, entries.size() / 4);
        for (int i = 0; i < drop; i++) buckets.remove(entries.get(i).getKey());
    }

    private long secondsUntilTomorrow() {
        var now = clock.instant().atZone(ZONE);
        var midnight = now.toLocalDate().plusDays(1).atStartOfDay(ZONE);
        return Math.max(1, java.time.Duration.between(now, midnight).toSeconds());
    }

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
        long lastSeenMs;
        LocalDate day;
        int usedToday;

        Bucket(double tokens, long nowMs, LocalDate day) {
            this.tokens = tokens;
            this.lastRefillMs = nowMs;
            this.lastSeenMs = nowMs;
            this.day = day;
        }
    }

    /**
     * 한도에 걸렸다는 것. {@link ApiErrorAdvice} 가 429 와 {@code Retry-After} 로 바꿉니다.
     *
     * <p><b>화면이 이것을 「미소장」으로 그리면 안 됩니다.</b> 물어보지 못한 것이지 없는
     * 것이 아닙니다. 소장 조회는 실패를 {@code unreadable} 로 받으므로 그대로 「확인 불가」가
     * 됩니다. 이 구분이 무너지면 실제로 있는 책을 없다고 답하게 됩니다.
     */
    public static final class LimitExceededException extends RuntimeException {
        private final transient Decision decision;

        public LimitExceededException(Decision decision, String message) {
            super(message);
            this.decision = decision;
        }

        public Decision decision() {
            return decision;
        }
    }
}
