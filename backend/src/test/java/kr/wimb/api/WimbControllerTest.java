package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.data4library.RegionCode;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 도서관 마스터를 시도별로 받는 부분입니다.
 *
 * <p>여기서 부분 실패를 전체 실패로 만들면, 목록에 없는 도서관이 사용자에게
 * <b>"그런 도서관이 없다"</b>로 보입니다. 조용히 틀린 답을 주는 자리입니다.
 */
class WimbControllerTest {

    private static String libsOf(String... codes) {
        var sb = new StringBuilder("<response><libs>");
        for (String code : codes) {
            sb.append("<lib><libCode>").append(code).append("</libCode>")
              .append("<libName><![CDATA[도서관").append(code).append("]]></libName>")
              .append("<address><![CDATA[서울특별시 강남구 어디로 1]]></address></lib>");
        }
        return sb.append("</libs></response>").toString();
    }

    /** 특정 지역에서만 실패하는 가짜 정보나루입니다. */
    private static final class RegionAware implements Data4LibraryClient.Transport {
        /** region 없이(전국) 부른 호출을 기록할 때 쓰는 표시입니다. */
        static final String ALL = "ALL";
        final List<String> asked = new ArrayList<>();
        java.util.function.Predicate<String> failsFor = region -> false;

        @Override public String get(URI uri) {
            String url = uri.toString();
            if (!url.contains("region=")) {
                // 전국을 한 번에 받는 길입니다. 어느 시도든 문제가 있는 상황이면 이쪽도
                // 실패한다고 봅니다. 그래야 시도별로 나눠 받는 길로 넘어갑니다.
                asked.add(ALL);
                boolean anyBroken = java.util.Arrays.stream(RegionCode.values())
                        .anyMatch(r -> failsFor.test(r.code()));
                if (anyBroken) throw new IllegalStateException("전국 조회 실패");
                return libsOf(java.util.Arrays.stream(RegionCode.values())
                        .map(r -> r.code() + "0001").toArray(String[]::new));
            }
            String region = url.replaceAll(".*[?&]region=([0-9]+).*", "$1");
            asked.add(region);
            if (failsFor.test(region)) throw new IllegalStateException("이 지역만 실패");
            // 지역마다 도서관 하나씩 돌려줍니다.
            return libsOf(region + "0001");
        }
    }

    private static WimbController controller(RegionAware transport) {
        return controller(transport, new AtomicReference<>(Instant.parse("2026-09-06T00:00:00Z")));
    }

    /** 재시도 시각을 넘겨 보려면 시계를 움직일 수 있어야 합니다. */
    private static WimbController controller(RegionAware transport, AtomicReference<Instant> now) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 100_000),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        var search = new BookSearchService(client, new HoldingsLookup(
                (isbn, region) -> List.of(), HoldingsLookup.RegionModeStore.documented()));
        Clock moving = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        return new WimbController(client, search, new MultiCheckService(search), budget, moving);
    }

    @Test
    @DisplayName("정상일 때는 전국을 한 번에 받고 시도별로 부르지 않는다")
    void healthyPathAsksOnceForEveryRegion() {
        // 시도 17번을 하나씩 도는 동안 왕복이 그만큼 쌓입니다. libSrch 의 region 은 선택
        // 항목이라 한 번으로 끝낼 수 있고, 그 시간을 사람이 기다릴 이유가 없습니다.
        var transport = new RegionAware();
        var libraries = controller(transport).libraries();

        assertEquals(RegionCode.values().length, libraries.size());
        assertEquals(List.of(RegionAware.ALL), transport.asked,
                "전국 조회 한 번으로 끝나야 하는데 시도별로도 불렀습니다: " + transport.asked);
    }

    @Test
    @DisplayName("시도 하나가 실패해도 나머지 도서관은 보여 준다")
    void oneFailingRegionDoesNotSinkTheList() {
        var transport = new RegionAware();
        transport.failsFor = region -> region.equals("11");   // 서울만 실패

        var libraries = controller(transport).libraries();

        assertEquals(16, libraries.size(), "17개 시도 가운데 16곳은 받았어야 합니다");
        assertTrue(libraries.stream().noneMatch(l -> l.libCode().startsWith("11")));
    }

    @Test
    @DisplayName("실패한 시도를 성공으로 기억하지 않는다")
    void failedRegionIsRetriedLater() {
        // 기억해 버리면 그 지역 도서관이 영영 목록에 나타나지 않고,
        // 사용자에게는 "그런 도서관이 없다"로 보입니다.
        var transport = new RegionAware();
        var seoulBroken = new AtomicReference<>(true);
        transport.failsFor = region -> region.equals("11") && seoulBroken.get();

        var now = new AtomicReference<>(Instant.parse("2026-09-06T00:00:00Z"));
        var controller = controller(transport, now);
        assertEquals(16, controller.libraries().size());

        // 서울이 돌아왔고, 재시도 간격도 지났습니다.
        seoulBroken.set(false);
        now.set(Instant.parse("2026-09-06T00:06:00Z"));
        long seoulAsked = transport.asked.stream().filter("11"::equals).count();

        var again = controller.libraries();
        assertEquals(17, again.size(), "돌아온 지역을 다시 받아야 합니다");
        assertTrue(transport.asked.stream().filter("11"::equals).count() > seoulAsked,
                "서울을 다시 물어봤어야 합니다");
    }

    @Test
    @DisplayName("계속 실패하는 지역을 매 요청마다 두드리지 않는다")
    void failingRegionIsNotHammered() {
        // 남의 서버에 대한 예의입니다. 5분 안에는 다시 부르지 않습니다.
        var transport = new RegionAware();
        transport.failsFor = region -> region.equals("11");
        var controller = controller(transport);

        controller.libraries();
        long first = transport.asked.stream().filter("11"::equals).count();
        controller.libraries();
        controller.libraries();

        assertEquals(first, transport.asked.stream().filter("11"::equals).count());
    }

    @Test
    @DisplayName("성공한 시도는 다시 부르지 않는다")
    void successfulRegionsAreNotRefetched() {
        var transport = new RegionAware();
        var controller = controller(transport);

        controller.libraries();
        int firstRound = transport.asked.size();
        controller.libraries();

        assertEquals(firstRound, transport.asked.size(), "예산을 두 번 쓰면 안 됩니다");
    }

    @Test
    @DisplayName("한 곳도 못 받으면 빈 목록 대신 503 을 낸다")
    void totalFailureIsNotAnEmptyList() {
        // 빈 목록을 정상인 척 돌려주면 화면이 "도서관이 하나도 없다"로 그립니다.
        var transport = new RegionAware();
        transport.failsFor = region -> true;

        var thrown = assertThrows(ResponseStatusException.class, () -> controller(transport).libraries());
        assertEquals(503, thrown.getStatusCode().value());
    }
}
