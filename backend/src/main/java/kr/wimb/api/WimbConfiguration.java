package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.CachingHoldingsClient;
import kr.wimb.holdings.HoldingCache;
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

@Configuration
// 소장 캐시를 주기적으로 파일에 남기는 일정이 필요합니다. HoldingCacheStore 를 보세요.
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

    @Value("${wimb.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    @Value("${wimb.holdings.cache-max-entries:50000}")
    private int cacheMaxEntries;

    @Value("${wimb.holdings.fresh-for-days:7}")
    private long freshForDays;

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
        return new Data4LibraryClient(new ThrottledHttpTransport(minIntervalMs), authKey, budget);
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
     * 소장 캐시. 상한을 두는 것은 메모리가 1GB 뿐인 기계에서 도는 것을 전제로 하기
     * 때문입니다. 실측에서 5만 항목이 소장 179곳 기준 45MB 였습니다.
     */
    @Bean
    public HoldingCache holdingCache() {
        return new HoldingCache(cacheMaxEntries, Clock.systemUTC());
    }

    @Bean
    public HoldingsLookup holdingsLookup(Data4LibraryClient client, HoldingCache cache) {
        // **캐시를 HoldingsLookup 안쪽에 끼웁니다.** 바깥에 두면 「저작 + 고른 도서관」이
        // 열쇠가 되어 도서관을 한 곳만 더 골라도 통째로 빗나갑니다. (ISBN, 시도) 쌍은
        // 유한하지만 도서관 조합은 그렇지 않습니다.
        var cached = new CachingHoldingsClient(
                client.asHoldingsClient(ApiBudget.Priority.USER),
                cache, Duration.ofDays(freshForDays), Clock.systemUTC());
        // 매뉴얼 13절이 region 을 필수로 명시하므로 탐색 비용 없이 PER_REGION 으로 시작합니다.
        return new HoldingsLookup(cached, HoldingsLookup.RegionModeStore.documented());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins).allowedMethods("GET", "POST");
    }

    /** 호출 간격을 지키는 최소한의 전송 계층. 상대 서버에 대한 예의입니다. */
    private static final class ThrottledHttpTransport implements Data4LibraryClient.Transport {
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        private final long minIntervalMs;
        private long lastCallMs;

        ThrottledHttpTransport(long minIntervalMs) {
            this.minIntervalMs = minIntervalMs;
        }

        @Override
        public String get(URI uri) {
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

        private synchronized void pace() {
            long wait = minIntervalMs - (System.currentTimeMillis() - lastCallMs);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastCallMs = System.currentTimeMillis();
        }
    }
}
