package kr.wimb.api;

import kr.wimb.data4library.LibraryInfo;
import kr.wimb.data4library.RegionCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도서관 목록을 언제 쓰고 언제 되살리는지입니다. 파일의 모양은 {@link LibraryCatalogTest} 가 봅니다.
 */
class LibraryCatalogStoreTest {

    private static final Instant THEN = Instant.parse("2026-11-11T04:00:00Z");

    private static LibraryInfo library(String code) {
        return new LibraryInfo(code, "도서관" + code, "서울특별시 어디구", null, null,
                null, null, null, null, null, null);
    }

    private static LibraryCatalogStore storeOf(LibraryCatalog catalog, Path file, String harvest) {
        return new LibraryCatalogStore(catalog, file.toString(), harvest);
    }

    @Test
    @DisplayName("앞 서버가 남긴 목록을 다음 서버가 뜰 때 되살린다")
    void theNextServerRestoresWhatThePreviousOneSaved(@TempDir Path dir) {
        Path file = dir.resolve("data").resolve("library-catalog.tsv.gz");
        var before = new LibraryCatalog();
        before.accept(RegionCode.SEOUL, List.of(library("110001"), library("110002")), THEN);
        // 주기 저장이 하는 일입니다. 종료 훅만 믿으면 SIGKILL 로 바뀌는 배포에서 잃습니다.
        storeOf(before, file, "").persist();

        var after = new LibraryCatalog();
        storeOf(after, file, "").restore();

        assertEquals(2, after.size(), "볼륨의 폴더가 아직 없어도 만들어서 써야 합니다");
        assertEquals(RegionCode.SEOUL, after.regionOf("110001").orElseThrow());
    }

    @Test
    @DisplayName("바뀐 것이 없으면 다시 쓰지 않는다")
    void doesNotRewriteAnUnchangedCatalog(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("library-catalog.tsv.gz");
        var catalog = new LibraryCatalog();
        catalog.accept(RegionCode.SEOUL, List.of(library("110001")), THEN);
        var store = storeOf(catalog, file, "");
        store.persist();

        // 무료 등급 VM 의 디스크는 쓰기 45 IOPS 입니다. 1분마다 같은 내용을 되풀이해 쓰면
        // 사람이 기다리는 요청의 몫을 갉아먹습니다.
        FileTime marker = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, marker);
        store.persist();
        store.persistOnShutdown();

        assertEquals(marker, Files.getLastModifiedTime(file));
    }

    @Test
    @DisplayName("되살리기만 한 서버는 파일을 건드리지 않는다")
    void aServerThatOnlyRestoredLeavesTheFileAlone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("library-catalog.tsv.gz");
        var before = new LibraryCatalog();
        before.accept(RegionCode.SEOUL, List.of(library("110001")), THEN);
        storeOf(before, file, "").persist();
        FileTime marker = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, marker);

        var after = new LibraryCatalog();
        var store = storeOf(after, file, "");
        store.restore();
        store.persist();

        assertEquals(marker, Files.getLastModifiedTime(file),
                "정보나루에서 새로 받은 것이 없으면 다시 적을 것도 없습니다");
    }

    @Test
    @DisplayName("서가를 수집하러 뜬 컨테이너는 목록을 되살리지도 쓰지도 않는다")
    void theHarvestContainerNeitherRestoresNorWrites(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("library-catalog.tsv.gz");
        var server = new LibraryCatalog();
        server.accept(RegionCode.SEOUL, List.of(library("110001")), THEN);
        storeOf(server, file, "").persist();
        FileTime marker = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, marker);

        var harvesting = new LibraryCatalog();
        var store = storeOf(harvesting, file, "141321:8");
        store.restore();
        store.persistOnShutdown();

        assertTrue(harvesting.isEmpty());
        assertFalse(harvesting.isDirty());
        assertEquals(marker, Files.getLastModifiedTime(file),
                "수집 컨테이너가 서버가 쓰는 파일을 덮어쓰면 안 됩니다");
    }

    @Test
    @DisplayName("저장에 실패해도 예외를 밖으로 내지 않는다")
    void aFailedSaveDoesNotThrow(@TempDir Path dir) throws IOException {
        // 폴더가 있어야 할 자리에 파일이 있어 쓸 수 없는 경로입니다.
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");
        var catalog = new LibraryCatalog();
        catalog.accept(RegionCode.SEOUL, List.of(library("110001")), THEN);

        storeOf(catalog, blocker.resolve("library-catalog.tsv.gz"), "").persist();

        assertEquals(1, catalog.size(), "저장을 못 해도 서비스는 계속돼야 합니다");
    }
}
