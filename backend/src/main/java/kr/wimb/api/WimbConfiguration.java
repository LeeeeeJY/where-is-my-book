package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.ApiBudget;
import kr.wimb.ingest.InMemoryApiBudget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
public class WimbConfiguration implements WebMvcConfigurer {

    @Value("${wimb.data4library.auth-key:}")
    private String authKey;

    @Value("${wimb.data4library.daily-call-budget:25000}")
    private int dailyCallBudget;

    @Value("${wimb.data4library.min-request-interval-ms:120}")
    private long minIntervalMs;

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
        return new Data4LibraryClient(new ThrottledHttpTransport(minIntervalMs), authKey, budget);
    }

    @Bean
    public HoldingsLookup holdingsLookup(Data4LibraryClient client) {
        // 매뉴얼 13절이 region 을 필수로 명시하므로 탐색 비용 없이 PER_REGION 으로 시작합니다.
        return new HoldingsLookup(
                client.asHoldingsClient(ApiBudget.Priority.USER),
                HoldingsLookup.RegionModeStore.documented());
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
                        .header("User-Agent", "where-is-my-books/0.1 (개인 프로젝트)")
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
