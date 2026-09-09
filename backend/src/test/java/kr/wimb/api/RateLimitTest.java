package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {

    /** 시간을 손으로 밀 수 있어야 토큰이 다시 차는 것을 확인할 수 있습니다. */
    private static final class MovingClock extends Clock {
        private final AtomicReference<Instant> now;

        MovingClock(String start) {
            this.now = new AtomicReference<>(Instant.parse(start));
        }

        void advanceSeconds(long seconds) {
            now.updateAndGet(t -> t.plusSeconds(seconds));
        }

        @Override public ZoneId getZone() { return RateLimit.ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Test
    @DisplayName("한도 안에서는 그대로 통과시킨다")
    void allowsNormalUse() {
        var clock = new MovingClock("2026-09-09T01:00:00Z");
        var limit = new RateLimit(400, 800, 5000, 100, clock);

        // 한 권 검색 한 번의 모양입니다. 검색 8 + 소장 조회 20개.
        assertTrue(limit.check("1.2.3.4", 8).allowed());
        for (int i = 0; i < 20; i++) {
            assertTrue(limit.check("1.2.3.4", 4).allowed(), i + "번째 소장 조회가 막혔습니다");
        }
    }

    @Test
    @DisplayName("몰아치면 막고, 시간이 지나면 다시 통과시킨다")
    void refillsOverTime() {
        var clock = new MovingClock("2026-09-09T01:00:00Z");
        var limit = new RateLimit(60, 60, 0, 100, clock);

        assertTrue(limit.check("1.2.3.4", 60).allowed());
        var blocked = limit.check("1.2.3.4", 30);
        assertFalse(blocked.allowed());
        assertEquals(RateLimit.Scope.MINUTE, blocked.scope());
        // 「0초 뒤에 다시」라고 답하면 곧바로 눌러 또 막힙니다.
        assertTrue(blocked.retryAfterSeconds() >= 1);

        clock.advanceSeconds(30);   // 분당 60 이면 30초에 30 이 찹니다.
        assertTrue(limit.check("1.2.3.4", 30).allowed(), "30초를 기다렸는데도 막혔습니다");
    }

    @Test
    @DisplayName("주소가 다르면 서로의 한도를 쓰지 않는다")
    void limitsArePerClient() {
        var clock = new MovingClock("2026-09-09T01:00:00Z");
        var limit = new RateLimit(60, 60, 0, 100, clock);

        assertTrue(limit.check("1.2.3.4", 60).allowed());
        assertFalse(limit.check("1.2.3.4", 10).allowed());
        assertTrue(limit.check("5.6.7.8", 60).allowed(), "남이 쓴 몫이 내 한도를 깎았습니다");
    }

    @Test
    @DisplayName("하루 한도는 기다린다고 풀리지 않는다")
    void dailyLimitDoesNotRefill() {
        var clock = new MovingClock("2026-09-09T01:00:00Z");
        var limit = new RateLimit(400, 800, 500, 100, clock);

        for (int i = 0; i < 5; i++) assertTrue(limit.check("1.2.3.4", 100).allowed());

        var blocked = limit.check("1.2.3.4", 1);
        assertFalse(blocked.allowed());
        // **분당 한도와 갈라 말해야 합니다.** 「잠시 뒤 다시」로 답하면 하루치를 다 쓴
        // 사람이 30초마다 다시 눌러 보게 되고, 그때마다 똑같이 막힙니다.
        assertEquals(RateLimit.Scope.DAY, blocked.scope());

        clock.advanceSeconds(600);
        assertFalse(limit.check("1.2.3.4", 1).allowed(), "10분 기다렸다고 하루 한도가 풀렸습니다");
    }

    @Test
    @DisplayName("날이 바뀌면 하루 한도가 한국 시각 기준으로 초기화된다")
    void dailyLimitRollsOverAtSeoulMidnight() {
        // 09-09 23:30 KST = 09-09 14:30 UTC. 30분 뒤가 한국의 자정입니다.
        var clock = new MovingClock("2026-09-09T14:30:00Z");
        var limit = new RateLimit(400, 800, 100, 100, clock);

        assertTrue(limit.check("1.2.3.4", 100).allowed());
        assertFalse(limit.check("1.2.3.4", 1).allowed());

        clock.advanceSeconds(31 * 60);
        // **UTC 로 세면 여기서 아직 막혀 있습니다.** 정보나루의 하루는 한국 시각이므로
        // 원장이 새 날을 시작한 뒤에도 어제 몫에 걸려 있는 사람이 생깁니다.
        assertTrue(limit.check("1.2.3.4", 100).allowed(), "한국 시각 자정에 초기화되지 않았습니다");
    }

    @Test
    @DisplayName("주소가 밀려들어도 몰아치던 주소는 한도를 되찾지 못한다")
    void evictionDoesNotHandBackTheLimitOfAnActiveClient() {
        var clock = new MovingClock("2026-09-09T01:00:00Z");
        var limit = new RateLimit(60, 60, 0, 8, clock);

        // 한 주소가 자기 몫을 다 씁니다.
        assertTrue(limit.check("10.0.0.99", 60).allowed());
        assertFalse(limit.check("10.0.0.99", 1).allowed());

        // 그 뒤로 새 주소가 상한을 넘도록 밀려듭니다. **시간은 흐르지 않게 둡니다.**
        // 시간이 흐르면 토큰이 저절로 다시 차서 축출 때문인지 아닌지 구별할 수 없습니다.
        for (int i = 0; i < 20; i++) {
            limit.check("10.1.0." + i, 1);
            // **통째로 비우면 여기서 한도가 풀립니다.** 지금 몰아치고 있는 주소는 가장
            // 최근에 본 것이라 오래된 것부터 덜어 내는 한 살아남아야 하고, 그래야 주소를
            // 잔뜩 만들어 자기 기록을 밀어내는 수법이 통하지 않습니다.
            assertFalse(limit.check("10.0.0.99", 1).allowed(),
                    i + "번째 새 주소에서 몰아치던 주소의 한도가 풀렸습니다");
        }
        assertTrue(limit.trackedClients() <= 8, "상한을 넘겨 기억하고 있습니다");
    }

    @Test
    @DisplayName("X-Forwarded-For 는 마지막 값을 믿는다")
    void trustsTheLastForwardedHop() {
        // 헤더는 프록시를 지날 때마다 뒤에 덧붙습니다. 클라이언트가 지어낸 값은 앞에 남고
        // 우리 프록시가 실제로 본 주소가 맨 뒤에 붙습니다. **첫 값을 쓰면 헤더 한 줄로
        // 한도를 빠져나갑니다.**
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");
        request.setRemoteAddr("172.17.0.1");

        var interceptor = new RateLimitInterceptor(
                new RateLimit(60, 60, 0, 100, Clock.systemUTC()), true, true);
        assertEquals("203.0.113.7", interceptor.clientKey(request));
    }

    @Test
    @DisplayName("프록시 뒤가 아니면 헤더를 믿지 않는다")
    void ignoresForwardedHeaderWhenNotBehindAProxy() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        request.setRemoteAddr("203.0.113.7");

        var interceptor = new RateLimitInterceptor(
                new RateLimit(60, 60, 0, 100, Clock.systemUTC()), true, false);
        assertEquals("203.0.113.7", interceptor.clientKey(request));
    }

    @Test
    @DisplayName("IPv6 는 /64 로 묶어 센다")
    void groupsIpv6ByPrefix() {
        // 회선 하나에 /64 가 통째로 딸려 오므로, 주소 하나씩 세면 같은 사람이 주소만
        // 바꿔 가며 한도를 무한히 늘릴 수 있습니다. IPv6 사용자에게만 한도가 없어집니다.
        String a = RateLimitInterceptor.groupKey("2001:db8:1234:5678:1111:2222:3333:4444");
        String b = RateLimitInterceptor.groupKey("2001:db8:1234:5678:9999:8888:7777:6666");
        assertEquals(a, b, "같은 /64 인데 서로 다른 몫으로 셌습니다");

        String other = RateLimitInterceptor.groupKey("2001:db8:1234:9999::1");
        assertNotEquals(a, other, "다른 /64 를 한 몫으로 묶었습니다");
    }

    @Test
    @DisplayName("주소에 붙은 포트를 떼고 센다")
    void stripsPorts() {
        assertEquals("203.0.113.7", RateLimitInterceptor.groupKey("203.0.113.7:51234"));
        assertEquals(RateLimitInterceptor.groupKey("2001:db8::1"),
                RateLimitInterceptor.groupKey("[2001:db8::1]:443"));
    }

    @Test
    @DisplayName("주소가 아닌 값이 와도 DNS 를 찾아 나서지 않는다")
    void doesNotResolveHostnames() {
        // 남이 보낸 헤더로 우리 서버가 요청마다 이름 조회를 하게 되면 안 됩니다.
        // 이름은 그대로 열쇠로 쓰고 넘어갑니다.
        assertEquals("evil.example.com", RateLimitInterceptor.groupKey("evil.example.com"));
        assertEquals("unknown", RateLimitInterceptor.groupKey("  "));
    }

    @Test
    @DisplayName("CORS 사전 요청은 한도를 깎지 않는다")
    void preflightRequestsAreNotCounted() {
        // 사전 요청은 정보나루를 한 번도 부르지 않는데 Spring 은 여기에도 인터셉터를
        // 태웁니다. 세면 `/api/check/resolve` 의 값이 두 배가 되고, **막히면 CORS 헤더가
        // 붙기 전에 끊겨 브라우저가 연결 실패로 보고합니다.** 화면에는 서버가 실어 보낸
        // 이유 대신 「서버에 연결하지 못했습니다」가 떠서, 멀쩡한 서버를 다시 띄우게 됩니다.
        var limit = new RateLimit(60, 60, 0, 100, Clock.systemUTC());
        var interceptor = new RateLimitInterceptor(limit, true, true);

        var preflight = new org.springframework.mock.web.MockHttpServletRequest(
                "OPTIONS", "/api/check/resolve");
        preflight.setRemoteAddr("203.0.113.7");
        preflight.addHeader("Origin", "https://where-is-my-book.vercel.app");
        preflight.addHeader("Access-Control-Request-Method", "POST");

        var response = new org.springframework.mock.web.MockHttpServletResponse();
        for (int i = 0; i < 50; i++) {
            assertTrue(interceptor.preHandle(preflight, response, null),
                    i + "번째 사전 요청이 막혔습니다");
        }

        // 사전 요청을 쉰 번 보낸 뒤에도 본 요청 몫은 그대로 남아 있어야 합니다.
        assertTrue(limit.check("203.0.113.7", 60).allowed(), "사전 요청이 한도를 깎았습니다");
    }

    @Test
    @DisplayName("줄 확정이 소장 조회보다 무겁게 매겨진다")
    void resolveCostsMoreThanHoldings() {
        // **요청 수를 그냥 세면 안 됩니다.** /api/check/resolve 한 번은 줄마다 두세 번씩
        // 정보나루를 불러 백 번을 넘길 수 있는데, /api/status 한 번은 한 번도 부르지
        // 않습니다. 같은 한 번으로 세면 가장 비싼 요청이 가장 싸게 통과합니다.
        assertTrue(RateLimitInterceptor.costOf("/api/check/resolve")
                > RateLimitInterceptor.costOf("/api/holdings"));
        assertTrue(RateLimitInterceptor.costOf("/api/holdings")
                > RateLimitInterceptor.costOf("/api/status"));
        assertEquals(RateLimitInterceptor.costOf("/api/version"),
                RateLimitInterceptor.costOf("/api/status"));
    }
}
