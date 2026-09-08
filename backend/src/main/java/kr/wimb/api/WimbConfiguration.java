package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.CachingHoldingsClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.ApiBudget;
import kr.wimb.ingest.InMemoryApiBudget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.ZoneId;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

@Configuration
// 소장 캐시 스냅샷을 주기적으로 저장하는 일정이 필요합니다. HoldingCacheStore 를 보세요.
@EnableScheduling
public class WimbConfiguration implements WebMvcConfigurer {

    /**
     * 누구인지 밝히고 연락할 곳을 남깁니다. <b>ASCII 만 씁니다.</b>
     *
     * <p>HTTP 헤더 값에 ASCII 가 아닌 문자가 들어가면 자바가 요청을 만들다가
     * {@link IllegalArgumentException} 을 던집니다. 그러면 요청이 나가지도 않은 채
     * 실패하는데, 화면에는 그냥 "확인 불가"로 보여서 원인을 찾기 어렵습니다.
     * 호출 예산은 요청을 만들기 전에 깎이므로 예산만 줄어듭니다.
     */
    static final String USER_AGENT =
            "where-is-my-books/0.1 (personal project; +https://github.com/LeeeeeJY/where-is-my-book)";

    @Value("${wimb.data4library.auth-key:}")
    private String authKey;

    @Value("${wimb.data4library.daily-call-budget:25000}")
    private int dailyCallBudget;

    @Value("${wimb.data4library.min-request-interval-ms:120}")
    private long minIntervalMs;

    /**
     * 정보나루에 <b>동시에</b> 나가 있을 수 있는 요청 수.
     *
     * <p>실측으로 정보나루 호출 하나가 3~5초 걸립니다(소장 조회 4.9초, 서지 검색 3.7초). 요청
     * 사이의 간격이 아니라 <b>이 왕복 시간</b>이 병목이라, 겹치지 않으면 스무 권 확인에 1분이
     * 걸립니다. 그래서 소장 조회와 서지 검색을 안에서 겹쳐 내보내는데, 그것이 곱해져 한꺼번에
     * 수십 개가 나가지 않도록 여기서 상한을 둡니다. 남의 서버에 대한 예의이자 차단을 피하는
     * 장치입니다. 실제 운영에서 정보나루가 느려지거나 오류를 돌려주면 이 값을 먼저 줄이세요.
     */
    @Value("${wimb.data4library.max-in-flight:12}")
    private int maxInFlight;

    @Value("${wimb.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    @Bean
    public ApiBudget apiBudget() {
        // 재시작하면 잔량이 초기화됩니다. 운영에서는 api_budget 테이블을 쓰는 구현으로
        // 갈아 끼워야 그날 한도를 두 번 쓰는 일이 없습니다.
        // 날짜 경계는 한국 시각입니다. UTC 로 두면 한도가 09:00 KST 에 초기화되어
        // 정보나루의 하루와 어긋나고, 그 어긋난 구간에서 한도를 두 번 쓰게 됩니다.
        return new InMemoryApiBudget(
                Map.of(Data4LibraryClient.SOURCE_CODE, dailyCallBudget),
                Clock.system(ZoneId.of("Asia/Seoul")));
    }

    @Bean
    public Data4LibraryClient data4LibraryClient(ApiBudget budget) {
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalStateException("""
                    D4L_AUTH_KEY 가 비어 있습니다. 정보나루 인증키를 넣어 주세요.
                      cp .env.example .env  후 D4L_AUTH_KEY 를 채우고
                      set -a; . ./.env; set +a  로 환경 변수에 올린 뒤 실행하세요.""");
        }
        return new Data4LibraryClient(
                new ThrottledHttpTransport(minIntervalMs, maxInFlight), authKey, budget);
    }

    /**
     * 도서관별 OPAC 주소 규칙. 정보나루가 홈페이지 주소만 주기 때문에 우리가 들고 있어야
     * 합니다. 규칙이 잘못된 줄이 있으면 여기서 예외가 나 서버가 뜨지 않는데, 조용히 빠진
     * 채로 배포되는 것보다 낫습니다.
     */
    @Bean
    public kr.wimb.opac.OpacTemplates opacTemplates() {
        return kr.wimb.opac.OpacTemplates.load();
    }

    /**
     * (ISBN, 지역) 한 쌍의 소장 답을 기억해 두는 시간.
     *
     * <p>정보나루의 소장 데이터는 하루 단위로 갱신되므로 몇 시간 안의 답은 다시 물어도
     * 같습니다. 도서관을 하나 더 고르고 「확인」을 다시 누르는 것이 이 도구의 가장 흔한
     * 동작인데, 그때 스무 권을 정보나루에 다시 물으면 사람이 몇 초를 다시 기다리고 하루
     * 예산도 그만큼 새어 나갑니다. 화면은 답을 실제로 받은 날짜를 「조회 기준」으로 보여
     * 주므로, 캐시에서 나온 답이라는 사실이 감춰지지 않습니다.
     */
    static final Duration HOLDINGS_CACHE_TTL = Duration.ofHours(6);

    /** 메모리 1GB 기계에서 캐시가 자라는 대로 두면 안 됩니다. 넘으면 비웁니다. */
    static final int HOLDINGS_CACHE_MAX_ENTRIES = 5_000;

    /**
     * 소장 캐시. <b>빈으로 꺼내 두는 것은 파일 스냅샷 때문입니다.</b>
     * {@link HoldingCacheStore} 가 이것을 주기적으로 저장하고 시작할 때 되살립니다.
     */
    @Bean
    public CachingHoldingsClient holdingsCache(Data4LibraryClient client) {
        return new CachingHoldingsClient(
                client.asHoldingsClient(ApiBudget.Priority.USER),
                HOLDINGS_CACHE_TTL, HOLDINGS_CACHE_MAX_ENTRIES, Clock.systemUTC());
    }

    @Bean
    public HoldingsLookup holdingsLookup(CachingHoldingsClient cache) {
        // 매뉴얼 13절이 region 을 필수로 명시하므로 탐색 비용 없이 PER_REGION 으로 시작합니다.
        return new HoldingsLookup(cache, HoldingsLookup.RegionModeStore.documented());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins).allowedMethods("GET", "POST");
    }

    /**
     * 호출 간격과 동시 요청 수를 지키는 최소한의 전송 계층. 상대 서버에 대한 예의입니다.
     *
     * <p>둘은 서로 다른 것을 막습니다. 간격은 <b>짧은 시간에 몰아치는 것</b>을, 동시 상한은
     * <b>답이 느릴 때 요청이 쌓이는 것</b>을 막습니다. 정보나루는 답이 느린 쪽이라 뒤엣것이
     * 실제로 작동하는 제한입니다.
     */
    private static final class ThrottledHttpTransport implements Data4LibraryClient.Transport {
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        private final long minIntervalMs;
        private long lastCallMs;
        /**
         * {@code synchronized} 가 아니라 잠금 객체를 씁니다. 요청 처리와 줄 확정이 가상
         * 스레드에서 돌기 때문인데, 가상 스레드는 {@code synchronized} 안에서 잠들면
         * 운반 스레드를 붙잡아 다른 가상 스레드가 그동안 돌지 못합니다. 잠금 객체 안에서
         * 잠들면 운반 스레드를 놓아 줍니다.
         */
        private final ReentrantLock gate = new ReentrantLock(true);
        /** 동시에 나가 있는 요청 수의 상한. 공정 모드라 먼저 기다린 요청이 먼저 나갑니다. */
        private final Semaphore inFlight;

        ThrottledHttpTransport(long minIntervalMs, int maxInFlight) {
            this.minIntervalMs = minIntervalMs;
            this.inFlight = new Semaphore(Math.max(1, maxInFlight), true);
        }

        @Override
        public String get(URI uri) {
            try {
                inFlight.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("호출 차례를 기다리다 중단되었습니다.", e);
            }
            try {
                return send(uri);
            } finally {
                inFlight.release();
            }
        }

        private String send(URI uri) {
            pace();
            try {
                var request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        // HTTP 헤더 값은 ASCII 만 허용합니다. 한글을 넣으면 자바가 요청을
                        // 만들다가 IllegalArgumentException 을 던지고, 그러면 요청이 나가지도
                        // 않은 채 실패합니다. 예산만 깎이고 원인은 보이지 않습니다.
                        .header("User-Agent", USER_AGENT)
                        .GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "정보나루가 HTTP " + response.statusCode() + " 를 돌려주었습니다.");
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("호출 중 중단되었습니다.", e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("정보나루에 연결하지 못했습니다.", e);
            }
        }

        /**
         * 요청 사이의 간격만 지키고 <b>호출 자체는 잠금 밖에서</b> 합니다. 그래서 여러 요청의
         * 왕복 시간이 서로 겹치고, 서버가 해외에 있어도 느려지지 않습니다. 호출을 이 안으로
         * 옮기면 그 순간 직렬화되어 왕복 시간이 그대로 쌓입니다.
         */
        private void pace() {
            gate.lock();
            try {
                long wait = minIntervalMs - (System.currentTimeMillis() - lastCallMs);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                lastCallMs = System.currentTimeMillis();
            } finally {
                gate.unlock();
            }
        }
    }
}
