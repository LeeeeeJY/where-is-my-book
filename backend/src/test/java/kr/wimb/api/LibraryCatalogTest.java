package kr.wimb.api;

import kr.wimb.data4library.LibraryInfo;
import kr.wimb.data4library.RegionCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도서관 목록을 파일로 남겼다가 되살리는 부분입니다.
 *
 * <p>여기서 틀리면 조용히 틀립니다. 소속을 잃으면 그 도서관은 물어보지도 않은 채 「없음」이
 * 되고, 되살린 것을 「새로 받았다」로 치면 그 목록은 영영 갱신되지 않습니다. 둘 다 화면에는
 * 아무 이상이 없어 보입니다.
 */
class LibraryCatalogTest {

    private static final Instant YESTERDAY = Instant.parse("2026-11-11T04:00:00Z");
    private static final Instant TODAY = Instant.parse("2026-11-12T04:00:00Z");

    private static LibraryInfo library(String code, String name, String address) {
        return new LibraryInfo(code, name, address, "02-000-0000", null,
                37.5, 127.0, "https://lib" + code + ".example.kr", "매주 월요일", "09:00~18:00", 1234);
    }

    private static Set<String> codesOf(LibraryCatalog catalog) {
        return catalog.all().stream().map(LibraryInfo::libCode).collect(Collectors.toSet());
    }

    /** 저장했다가 새 목록으로 되살립니다. 재배포가 하는 일입니다. */
    private static LibraryCatalog redeployed(LibraryCatalog before, Path file) throws IOException {
        before.save(file);
        var after = new LibraryCatalog();
        after.load(file);
        return after;
    }

    @Test
    @DisplayName("되살리면 도서관의 모든 칸과 소속이 그대로 돌아온다")
    void restoresEveryFieldAndTheSourceRegion(@TempDir Path dir) throws IOException {
        var before = new LibraryCatalog();
        // 주소는 서울인데 정보나루가 경기 목록에 넣어 준 도서관. 소속을 주소에서 다시 읽으면
        // 서울로 바뀌고, 소장 조회가 엉뚱한 지역으로 나가 「없음」이 됩니다.
        var misfiled = library("310001", "경계에 있는 도서관", "서울특별시 어디구 어디로 1");
        // 주소에서 시도를 읽을 수 없는 도서관. 소속을 잃으면 물어볼 방법이 없습니다.
        var unreadable = library("310002", "고양시립백석도서관", "고양시 일산동구 중앙로 1");
        before.accept(RegionCode.GYEONGGI, List.of(misfiled, unreadable), YESTERDAY);

        var after = redeployed(before, dir.resolve("catalog.tsv.gz"));

        assertEquals(misfiled, after.get("310001"));
        assertEquals(unreadable, after.get("310002"));
        assertEquals(Optional.of(RegionCode.GYEONGGI), after.regionOf("310001"),
                "소속은 주소가 아니라 정보나루가 알려 준 대로 돌아와야 합니다");
        assertEquals(Optional.of(RegionCode.GYEONGGI), after.regionOf("310002"));
        assertEquals(Optional.of(YESTERDAY), after.oldestFetchedAt(),
                "받은 시각을 지금으로 바꿔 말하면 옛 목록이 새 목록처럼 읽힙니다");
    }

    @Test
    @DisplayName("없는 값과 빈 값, 탭과 줄바꿈이 든 값이 서로 바뀌지 않는다")
    void keepsNullsEmptiesAndControlCharactersApart(@TempDir Path dir) throws IOException {
        var before = new LibraryCatalog();
        var odd = new LibraryInfo("110001", "이름에\t탭", "", null, "",
                null, null, null, "첫째 줄\n둘째 줄\r\n셋째 줄", "역슬래시 \\N 과 \\t 가 글자로", null);
        before.accept(RegionCode.SEOUL, List.of(odd), YESTERDAY);

        var after = redeployed(before, dir.resolve("catalog.tsv.gz"));

        assertEquals(odd, after.get("110001"),
                "휴관일과 운영 시간은 도서관이 자유롭게 적는 칸입니다. 무엇이 와도 한 줄 한 칸이 깨지면 안 됩니다");
        assertNull(after.get("110001").tel(), "없는 값은 없는 값으로 돌아옵니다");
        assertEquals("", after.get("110001").address(), "빈 값은 빈 값으로 돌아옵니다");
    }

    @Test
    @DisplayName("되살린 시도는 새로 받은 것으로 치지 않는다")
    void restoredRegionsAreNotFresh(@TempDir Path dir) throws IOException {
        var before = new LibraryCatalog();
        for (RegionCode region : RegionCode.values()) {
            before.accept(region, List.of(library(region.code() + "0001", "도서관", "주소")), YESTERDAY);
        }
        assertTrue(before.isComplete());

        var after = redeployed(before, dir.resolve("catalog.tsv.gz"));

        assertEquals(RegionCode.values().length, after.size());
        // 여기가 참이면 미리 받기와 재시도가 「다 받았다」며 건너뛰고, 그 목록은 영영 갱신되지
        // 않습니다. 새 도서관이 안 나오고 문 닫은 도서관이 남는데 화면에는 이상이 없습니다.
        assertFalse(after.isComplete(), "되살린 것으로 「다 받았다」고 치면 안 됩니다");
        assertFalse(after.isFresh(RegionCode.SEOUL));
        assertEquals(RegionCode.values().length, after.restoredRegions());
        assertEquals(0, after.freshRegions());
        assertFalse(after.isDirty(), "되살리기만 했으면 다시 쓸 것이 없습니다");
    }

    @Test
    @DisplayName("정보나루가 답하면 되살린 시도를 새 목록으로 갈아 끼운다")
    void aFreshAnswerReplacesTheRestoredRegion(@TempDir Path dir) throws IOException {
        var before = new LibraryCatalog();
        before.accept(RegionCode.SEOUL, List.of(
                library("110001", "그대로인 도서관", "서울특별시"),
                library("110002", "문 닫은 도서관", "서울특별시")), YESTERDAY);
        before.accept(RegionCode.BUSAN, List.of(library("210001", "부산 도서관", "부산광역시")), YESTERDAY);
        var catalog = redeployed(before, dir.resolve("catalog.tsv.gz"));

        boolean accepted = catalog.accept(RegionCode.SEOUL, List.of(
                library("110001", "이름이 바뀐 도서관", "서울특별시"),
                library("110003", "새로 생긴 도서관", "서울특별시")), TODAY);

        assertTrue(accepted);
        assertEquals(Set.of("110001", "110003", "210001"), codesOf(catalog),
                "새 목록에 없는 도서관은 빠지고, 다른 시도의 도서관은 그대로여야 합니다");
        assertEquals("이름이 바뀐 도서관", catalog.get("110001").libName());
        assertEquals(Optional.empty(), catalog.regionOf("110002"));
        assertTrue(catalog.isFresh(RegionCode.SEOUL));
        assertFalse(catalog.isFresh(RegionCode.BUSAN), "부산은 아직 되살린 채입니다");
        assertEquals(1, catalog.restoredRegions());
        assertEquals(Optional.of(YESTERDAY), catalog.oldestFetchedAt(), "가장 오래된 것은 아직 어제의 부산입니다");
        assertTrue(catalog.isDirty(), "새로 받았으면 파일도 다시 써야 합니다");
    }

    @Test
    @DisplayName("빈 목록은 되살린 시도를 지우지 못한다")
    void anEmptyAnswerDoesNotEraseARestoredRegion(@TempDir Path dir) throws IOException {
        var before = new LibraryCatalog();
        before.accept(RegionCode.SEOUL, List.of(library("110001", "서울 도서관", "서울특별시")), YESTERDAY);
        var catalog = redeployed(before, dir.resolve("catalog.tsv.gz"));

        // 도서관이 있던 시도가 갑자기 0곳이면 그 답이 틀렸을 가능성이 훨씬 큽니다. 받아들이면
        // 그 시도의 도서관이 화면에서 통째로 사라집니다.
        boolean accepted = catalog.accept(RegionCode.SEOUL, List.of(), TODAY);

        assertFalse(accepted);
        assertEquals(Set.of("110001"), codesOf(catalog));
        assertFalse(catalog.isFresh(RegionCode.SEOUL), "못 받은 것으로 쳐서 다음에 다시 받습니다");
        assertFalse(catalog.isDirty());
    }

    @Test
    @DisplayName("되살린 것이 없는 시도의 빈 목록은 예전처럼 받아들인다")
    void anEmptyAnswerIsAcceptedWhenNothingWasRestored() {
        var catalog = new LibraryCatalog();

        assertTrue(catalog.accept(RegionCode.SEJONG, List.of(), TODAY));
        assertTrue(catalog.isFresh(RegionCode.SEJONG));
        assertTrue(catalog.isEmpty());
    }

    @Test
    @DisplayName("새로 받지 못한 시도도 다음 파일에 그대로 남긴다")
    void restoredRegionsSurviveASecondRedeploy(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("catalog.tsv.gz");
        var first = new LibraryCatalog();
        first.accept(RegionCode.SEOUL, List.of(library("110001", "서울 도서관", "서울특별시")), YESTERDAY);
        first.accept(RegionCode.BUSAN, List.of(library("210001", "부산 도서관", "부산광역시")), YESTERDAY);

        // 둘째 배포: 부산만 답합니다. 서울은 되살린 채로 남습니다.
        var second = redeployed(first, file);
        second.accept(RegionCode.BUSAN, List.of(library("210001", "부산 도서관", "부산광역시")), TODAY);

        // 셋째 배포: 아무것도 답하지 않습니다. 둘째가 서울을 파일에서 빼 버렸으면 여기서 잃습니다.
        var third = redeployed(second, file);

        assertEquals(Set.of("110001", "210001"), codesOf(third));
        assertEquals(Optional.of(RegionCode.SEOUL), third.regionOf("110001"));
        assertEquals(Optional.of(YESTERDAY), third.oldestFetchedAt(),
                "서울은 여전히 어제 받은 것이라고 말해야 합니다");
    }

    @Test
    @DisplayName("이미 새로 받은 시도는 파일의 것으로 덮지 않는다")
    void loadingDoesNotOverwriteFreshRegions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("catalog.tsv.gz");
        var old = new LibraryCatalog();
        old.accept(RegionCode.SEOUL, List.of(library("110001", "옛 이름", "서울특별시")), YESTERDAY);
        old.save(file);

        var catalog = new LibraryCatalog();
        catalog.accept(RegionCode.SEOUL, List.of(library("110001", "새 이름", "서울특별시")), TODAY);
        catalog.load(file);

        assertEquals("새 이름", catalog.get("110001").libName());
        assertTrue(catalog.isFresh(RegionCode.SEOUL));
        assertEquals(Optional.of(TODAY), catalog.oldestFetchedAt());
    }

    @Test
    @DisplayName("파일이 없거나 읽을 수 없어도 서버가 뜨는 것을 막지 않는다")
    void anUnreadableFileIsNotAStartupFailure(@TempDir Path dir) throws IOException {
        var catalog = new LibraryCatalog();

        assertEquals(0, catalog.load(dir.resolve("missing.tsv.gz")));

        Path garbage = dir.resolve("garbage.tsv.gz");
        Files.writeString(garbage, "gzip 이 아닌 글자들", StandardCharsets.UTF_8);
        assertEquals(0, assertDoesNotThrow(() -> catalog.load(garbage)));

        assertTrue(catalog.isEmpty());
    }

    @Test
    @DisplayName("형식 판이 다른 파일과 모르는 지역 코드의 줄은 읽지 않는다")
    void ignoresOtherFormatsAndUnknownRegions(@TempDir Path dir) throws IOException {
        Path otherVersion = dir.resolve("v0.tsv.gz");
        writeGzip(otherVersion, "# wimb-library-catalog v0\n11\t1\t110001\t이름\t주소\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\n");
        var catalog = new LibraryCatalog();
        assertEquals(0, catalog.load(otherVersion), "형식이 바뀌면 예전 파일을 조용히 무시합니다");

        Path mixed = dir.resolve("mixed.tsv.gz");
        writeGzip(mixed, "# wimb-library-catalog v1\n"
                + "99\t1\t990001\t없는 지역\t주소\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\n"
                + "칸이 모자란 줄\n"
                + "11\t숫자가아님\t110009\t시각이 깨진 줄\t주소\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\n"
                + "11\t1762920000\t110001\t멀쩡한 줄\t주소\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\n");
        assertEquals(1, catalog.load(mixed), "소속을 지어내지 않습니다. 읽을 수 있는 줄만 씁니다");
        assertEquals(Set.of("110001"), codesOf(catalog));
    }

    private static void writeGzip(Path path, String text) throws IOException {
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
