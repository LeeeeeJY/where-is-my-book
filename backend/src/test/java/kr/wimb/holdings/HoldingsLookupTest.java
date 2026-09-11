package kr.wimb.holdings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static kr.wimb.holdings.HoldingsLookup.RegionMode.*;
import static org.junit.jupiter.api.Assertions.*;

class HoldingsLookupTest {

    private static final List<String> SEOUL_GYEONGGI = List.of("11", "31");

    /** 정보나루가 어떻게 동작하든 흉내 낼 수 있는 가짜 서버. */
    private static final class FakeServer implements HoldingsLookup.HoldingsClient {
        /** 이제 호출이 동시에 나가므로 목록도 동시에 써도 안전해야 합니다. */
        final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean nationwideSupported = true;
        boolean nationwideThrows = false;
        Map<String, List<String>> byRegion = Map.of();
        List<String> nationwideResult = List.of();
        java.util.Set<String> failingRegions = java.util.Set.of();

        @Override
        public List<String> libCodesFor(String isbn13, String regionCode) {
            calls.add(isbn13 + "/" + (regionCode == null ? "ALL" : regionCode));
            if (regionCode == null) {
                if (nationwideThrows) throw new IllegalStateException("region 이 필요합니다");
                return nationwideSupported ? nationwideResult : List.of();
            }
            if (failingRegions.contains(regionCode)) throw new IllegalStateException("일시 장애");
            return byRegion.getOrDefault(regionCode, List.of());
        }
    }

    private static HoldingsLookup lookupWith(FakeServer server, HoldingsLookup.RegionModeStore store) {
        return new HoldingsLookup(server, store);
    }

    @Test
    @DisplayName("전국을 한 번에 주는 서버면 ISBN 당 1회로 끝난다")
    void nationwideCostsOneCallPerIsbn() {
        var server = new FakeServer();
        server.nationwideResult = List.of("011001", "141053");
        var store = HoldingsLookup.RegionModeStore.inMemory();

        var result = lookupWith(server, store).lookup(List.of("A", "B", "C"), SEOUL_GYEONGGI);

        assertEquals(NATIONWIDE, result.modeUsed());
        assertEquals(3, result.calls(), "탐색 비용 없이 ISBN 당 1회여야 합니다");
        assertEquals(java.util.Set.of("011001", "141053"), result.libCodes());
        assertTrue(result.isComplete());
    }

    @Test
    @DisplayName("region 이 필수인 서버면 스스로 알아내고 지역별로 조회한다")
    void discoversPerRegionMode() {
        var server = new FakeServer();
        server.nationwideSupported = false;   // region 없이 부르면 빈 결과
        server.byRegion = Map.of("11", List.of("011001"), "31", List.of("141053"));
        var store = HoldingsLookup.RegionModeStore.inMemory();

        var result = lookupWith(server, store).lookup(List.of("A"), SEOUL_GYEONGGI);

        assertEquals(PER_REGION, result.modeUsed());
        assertEquals(java.util.Set.of("011001", "141053"), result.libCodes(),
                "여러 시도의 결과를 합쳐야 합니다");
        assertEquals(3, result.calls(), "전국 시도 1회 + 지역 2회");
    }

    @Test
    @DisplayName("region 없는 호출이 오류를 내도 지역별로 넘어간다")
    void fallsBackWhenNationwideThrows() {
        var server = new FakeServer();
        server.nationwideThrows = true;
        server.byRegion = Map.of("11", List.of("011001"));
        var store = HoldingsLookup.RegionModeStore.inMemory();

        var result = lookupWith(server, store).lookup(List.of("A"), List.of("11"));

        assertEquals(PER_REGION, result.modeUsed());
        assertEquals(java.util.Set.of("011001"), result.libCodes());
    }

    @Test
    @DisplayName("한 번 알아낸 방식은 기억해 다시 탐색하지 않는다")
    void remembersDiscoveredMode() {
        var server = new FakeServer();
        server.nationwideSupported = false;
        server.byRegion = Map.of("11", List.of("011001"), "31", List.of());
        var store = HoldingsLookup.RegionModeStore.inMemory();
        var lookup = lookupWith(server, store);

        lookup.lookup(List.of("A"), SEOUL_GYEONGGI);
        server.calls.clear();
        var second = lookup.lookup(List.of("B"), SEOUL_GYEONGGI);

        assertEquals(2, second.calls(), "두 번째부터는 전국 시도 없이 지역만 부릅니다");
        assertTrue(server.calls.stream().noneMatch(c -> c.endsWith("/ALL")));
    }

    @Test
    @DisplayName("아무 데도 소장하지 않은 책으로는 방식을 확정하지 않는다")
    void doesNotDecideFromEmptyResults() {
        // 여기서 잘못 확정하면 이후의 모든 조회가 틀립니다.
        var server = new FakeServer();   // 전국도 지역도 모두 빈 결과
        var store = HoldingsLookup.RegionModeStore.inMemory();

        var result = lookupWith(server, store).lookup(List.of("A"), SEOUL_GYEONGGI);

        assertEquals(UNKNOWN, result.modeUsed());
        assertTrue(result.libCodes().isEmpty());
        assertTrue(result.isComplete(), "빈 결과는 실패가 아니라 미소장입니다");
    }

    @Test
    @DisplayName("방식을 모르는 동안에도 결과가 빠지지 않게 지역별로 조회한다")
    void staysSafeWhileModeUnknown() {
        var server = new FakeServer();
        server.byRegion = Map.of("31", List.of("141053"));
        var store = HoldingsLookup.RegionModeStore.inMemory();

        // 첫 ISBN 은 아무 데도 없어 방식이 확정되지 않고, 두 번째는 경기도에만 있습니다.
        var result = lookupWith(server, store).lookup(List.of("없는책", "B"), SEOUL_GYEONGGI);

        assertEquals(java.util.Set.of("141053"), result.libCodes(),
                "방식을 몰라도 결과를 놓치면 안 됩니다");
    }

    @Test
    @DisplayName("조회에 실패한 ISBN 은 미소장이 아니라 확인 불가로 남는다")
    void failedIsbnIsUnresolvedNotMissing() {
        // 실패를 미소장으로 표시하면 실제로 있는 책을 없다고 답해 헛걸음을 만듭니다.
        var server = new FakeServer();
        server.nationwideResult = List.of("011001");
        var store = HoldingsLookup.RegionModeStore.inMemory();
        store.set(NATIONWIDE);
        server.nationwideThrows = true;

        var result = lookupWith(server, store).lookup(List.of("A"), SEOUL_GYEONGGI);

        assertEquals(List.of("A"), result.unresolvedIsbns());
        assertTrue(result.libCodes().isEmpty());
        assertFalse(result.isComplete(), "화면에 확인 불가를 표시해야 합니다");
    }

    @Test
    @DisplayName("시도 하나가 실패해도 나머지로 답하되 그 사실을 알린다")
    void partialFailureStillAnswers() {
        var server = new FakeServer();
        server.byRegion = Map.of("11", List.of("011001"), "31", List.of("141053"));
        server.failingRegions = java.util.Set.of("31");
        var store = HoldingsLookup.RegionModeStore.inMemory();
        store.set(PER_REGION);

        var result = lookupWith(server, store).lookup(List.of("A"), SEOUL_GYEONGGI);

        assertEquals(java.util.Set.of("011001"), result.libCodes(), "성공한 지역의 결과는 씁니다");
        assertEquals(List.of("A"), result.partialIsbns());
        assertTrue(result.unresolvedIsbns().isEmpty());
        assertFalse(result.isComplete());
    }

    @Test
    @DisplayName("여러 답이 섞이면 가장 오래된 조회 시각을 기준으로 삼는다")
    void reportsTheOldestFetchTime() {
        // 캐시가 끼면 방금 받은 답과 몇 시간 전 답이 한 결과에 섞입니다. 화면의
        // 「n월 n일 조회 기준」은 그 가운데 가장 오래된 것이어야 합니다. 최신으로 말하면
        // 옛 답을 새 답처럼 읽게 됩니다.
        var old = java.time.Instant.parse("2026-09-07T01:00:00Z");
        var fresh = java.time.Instant.parse("2026-09-08T01:00:00Z");
        HoldingsLookup.HoldingsClient client = new HoldingsLookup.HoldingsClient() {
            @Override public List<String> libCodesFor(String isbn13, String regionCode) {
                return List.of(isbn13);
            }
            @Override public Answer answerFor(String isbn13, String regionCode) {
                return new Answer(List.of(isbn13), isbn13.equals("옛판") ? old : fresh);
            }
        };

        var result = new HoldingsLookup(client, HoldingsLookup.RegionModeStore.documented())
                .lookup(List.of("새판", "옛판"), List.of("11"));

        assertEquals(old, result.oldestFetchedAt());
        assertEquals(java.util.Set.of("새판", "옛판"), result.libCodes());
    }

    @Test
    @DisplayName("답을 하나도 못 받았으면 조회 시각도 없다")
    void noAnswerMeansNoFetchTime() {
        HoldingsLookup.HoldingsClient failing = (isbn13, regionCode) -> {
            throw new IllegalStateException("장애");
        };
        var result = new HoldingsLookup(failing, HoldingsLookup.RegionModeStore.documented())
                .lookup(List.of("A"), List.of("11"));

        assertNull(result.oldestFetchedAt());
        assertEquals(List.of("A"), result.unresolvedIsbns());
    }

    @Test
    @DisplayName("판본과 지역이 많아도 호출은 (판본 × 지역)만큼이고 전부 모인다")
    void fansOutOverEveryPairAndCollectsAll() {
        // 정보나루의 소장 조회는 한 번에 4~5초라 차례로 부르면 판본 아홉 개짜리가 40초입니다.
        // 동시에 내보내되, 어느 쌍도 빠뜨리거나 두 번 부르지 않아야 합니다.
        var server = new FakeServer();
        server.byRegion = Map.of("11", List.of("011001"), "31", List.of("141053"), "21", List.of());
        var store = HoldingsLookup.RegionModeStore.documented();
        List<String> isbns = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I");

        var result = lookupWith(server, store).lookup(isbns, List.of("11", "31", "21"));

        assertEquals(27, result.calls());
        assertEquals(27, server.calls.size(), "쌍마다 정확히 한 번씩 불러야 합니다");
        assertEquals(27, new java.util.HashSet<>(server.calls).size(), "같은 쌍을 두 번 부르면 안 됩니다");
        assertEquals(java.util.Set.of("011001", "141053"), result.libCodes());
        assertTrue(result.isComplete());
    }

    @Test
    @DisplayName("동시에 불러도 시도 하나의 실패는 그 판본의 일부 실패로만 남는다")
    void concurrentPartialFailureIsStillReported() {
        var server = new FakeServer();
        server.byRegion = Map.of("11", List.of("011001"), "31", List.of("141053"));
        server.failingRegions = java.util.Set.of("31");
        var store = HoldingsLookup.RegionModeStore.documented();

        var result = lookupWith(server, store).lookup(List.of("A", "B"), SEOUL_GYEONGGI);

        assertEquals(java.util.Set.of("011001"), result.libCodes());
        assertEquals(List.of("A", "B"), result.partialIsbns(), "두 판본 모두 경기가 빠졌습니다");
        assertTrue(result.unresolvedIsbns().isEmpty());
        assertFalse(result.isComplete());
    }

    @Test
    @DisplayName("저작에 묶인 모든 판본을 조회해 합친다")
    void unionsAcrossAllEditions() {
        // 판본 하나만 조회하면 도서관이 다른 판을 가지고 있어도 미소장으로 나옵니다.
        var store = HoldingsLookup.RegionModeStore.inMemory();
        store.set(NATIONWIDE);
        HoldingsLookup.HoldingsClient perIsbn = (isbn13, regionCode) -> switch (isbn13) {
            case "초판" -> List.of("011001");
            case "개정판" -> List.of("141053");
            default -> List.of();
        };

        var result = new HoldingsLookup(perIsbn, store)
                .lookup(List.of("초판", "개정판"), SEOUL_GYEONGGI);

        assertEquals(java.util.Set.of("011001", "141053"), result.libCodes());
    }

    @Test
    @DisplayName("도서관마다 어느 판을 가졌는지를 잃지 않고 내보낸다")
    void keepsWhichEditionEachLibraryHolds() {
        // libCodes 는 판본 전체의 합집합이라 어느 판을 가졌는지를 잃습니다. 그러면 도서관
        // 링크가 저작의 첫 ISBN 으로 나가고, 그 판이 없는 도서관의 OPAC 검색은 규칙이 맞아도
        // 0건이 됩니다. 「소장한다더니 그 책이 없네」로 보이는 자리라 여기서 고정합니다.
        var store = HoldingsLookup.RegionModeStore.documented();
        HoldingsLookup.HoldingsClient perIsbn = (isbn13, regionCode) -> switch (isbn13) {
            case "초판" -> List.of("011001");
            case "개정판" -> List.of("141053", "011001");
            default -> List.of();
        };

        var result = new HoldingsLookup(perIsbn, store)
                .lookup(List.of("초판", "개정판", "전자책"), List.of("11"));

        assertEquals(List.of("초판", "개정판"), result.isbnsByLib().get("011001"),
                "두 판을 다 가진 곳은 둘 다, 판본 목록의 순서대로");
        assertEquals(List.of("개정판"), result.isbnsByLib().get("141053"),
                "개정판만 가진 곳에 초판 ISBN 을 붙이면 그 도서관 OPAC 에서 0건이 됩니다");
        assertFalse(result.isbnsByLib().containsKey("아무데도없음"));
    }

    @Test
    @DisplayName("방식을 탐색하는 동안 받은 답도 어느 판인지를 잃지 않는다")
    void keepsEditionWhileProbingMode() {
        var server = new FakeServer();
        server.nationwideResult = List.of("011001");
        var store = HoldingsLookup.RegionModeStore.inMemory();

        var result = lookupWith(server, store).lookup(List.of("A"), SEOUL_GYEONGGI);

        assertEquals(NATIONWIDE, result.modeUsed());
        assertEquals(List.of("A"), result.isbnsByLib().get("011001"));
    }
}
