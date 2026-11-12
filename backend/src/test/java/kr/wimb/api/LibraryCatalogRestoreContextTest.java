package kr.wimb.api;

import kr.wimb.data4library.LibraryInfo;
import kr.wimb.data4library.RegionCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서버가 뜰 때 도서관 목록을 <b>실제로 파일에서 되살리는지</b> 봅니다.
 *
 * <p>되살리는 코드가 멀쩡해도, 그것을 부르는 저장소({@link LibraryCatalogStore})가 빈으로
 * 묶이지 않았거나 컨트롤러가 다른 목록 객체를 받았으면 서버는 빈 목록으로 뜹니다. 단위
 * 테스트는 클래스를 직접 만들어 쓰므로 그것을 잡지 못합니다. 설정 이름에 오타가 나 경로가
 * 기본값으로 내려앉는 것도 여기서만 걸립니다.
 *
 * <p>{@code /api/libraries} 가 아니라 {@code /api/status} 로 봅니다. 앞엣것은 새로 받지 못한
 * 시도가 있으면 정보나루를 부르는데, 컨텍스트 테스트는 정보나루를 부르지 않습니다.
 */
@WimbContextTest(properties = "wimb.test.catalog-path=build/tmp/catalog-restore/library-catalog.tsv.gz")
@AutoConfigureMockMvc
class LibraryCatalogRestoreContextTest {

    private static final Path SNAPSHOT = Path.of("build/tmp/catalog-restore/library-catalog.tsv.gz");

    // 컨텍스트가 뜨기 전에 파일이 있어야 합니다. @BeforeAll 은 그보다 늦을 수 있어서
    // 클래스가 준비될 때 적습니다. 앞 서버가 2026-11-11 에 받아 남긴 것으로 칩니다.
    static {
        var previous = new LibraryCatalog();
        previous.accept(RegionCode.SEOUL, List.of(
                library("111001", "서울 도서관 하나", "서울특별시 어디구 어디로 1"),
                library("111002", "서울 도서관 둘", "서울특별시 어디구 어디로 2")),
                Instant.parse("2026-11-11T03:00:00Z"));
        previous.accept(RegionCode.GYEONGGI, List.of(
                library("141001", "고양시립백석도서관", "고양시 일산동구 중앙로 1")),
                Instant.parse("2026-11-11T03:00:00Z"));
        try {
            previous.save(SNAPSHOT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LibraryInfo library(String code, String name, String address) {
        return new LibraryInfo(code, name, address, null, null, 37.5, 127.0,
                "https://lib" + code + ".example.kr", null, null, null);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LibraryCatalog catalog;

    @Test
    @DisplayName("뜰 때 파일의 목록을 되살리고, 되살린 것임을 상태에 밝힌다")
    void restoresTheSnapshotOnStartup() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.librariesLoaded").value(3))
                .andExpect(jsonPath("$.libraryRegionsRestored").value(2))
                .andExpect(jsonPath("$.libraryRegionsFresh").value(0))
                .andExpect(jsonPath("$.libraryCatalogOldest").value("2026-11-11"));
    }

    @Test
    @DisplayName("되살린 도서관의 소속이 살아 있고, 새로 받은 것으로 치지 않는다")
    void theRestoredCatalogKeepsRegionsAndStaysStale() {
        assertEquals(RegionCode.GYEONGGI, catalog.regionOf("141001").orElseThrow(),
                "주소에서 시도를 읽을 수 없는 도서관입니다. 소속을 잃으면 물어볼 방법이 없습니다");
        assertFalse(catalog.isComplete(),
                "되살린 것으로 「다 받았다」고 치면 서버가 그 목록을 영영 갱신하지 않습니다");
    }
}
