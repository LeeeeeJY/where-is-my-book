package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.data4library.LibraryInfo;
import kr.wimb.data4library.RegionCode;
import kr.wimb.ingest.ApiBudget;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class WimbController {

    private final Data4LibraryClient client;
    private final BookSearchService searchService;

    /**
     * 도서관 마스터를 메모리에 담아 둡니다. 1,604건뿐이라 이걸로 충분하고,
     * 매번 정보나루를 부르면 예산이 검색에 쓸 몫까지 갉아먹습니다.
     */
    private final Map<String, LibraryInfo> catalog = new ConcurrentHashMap<>();

    public WimbController(Data4LibraryClient client, BookSearchService searchService) {
        this.client = client;
        this.searchService = searchService;
    }

    /**
     * 선택 화면에 뿌릴 도서관 목록. 위경도까지 함께 나갑니다.
     *
     * <p>정보나루의 {@code libSrch} 가 위경도를 주기 때문에, 공공데이터포털의
     * 전국도서관표준데이터를 이름과 주소로 대조하는 단계가 필요 없습니다.
     */
    @GetMapping("/libraries")
    public List<LibraryDto> libraries() {
        if (catalog.isEmpty()) loadCatalog();
        return catalog.values().stream()
                .map(LibraryDto::from)
                .sorted(Comparator.comparing(LibraryDto::sido).thenComparing(LibraryDto::name))
                .toList();
    }

    @GetMapping("/search")
    public BookSearchService.SearchResponse search(
            @RequestParam String q,
            @RequestParam(required = false) List<String> libs) {

        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어가 비어 있습니다.");
        }
        List<String> selected = libs == null ? List.of() : libs;
        if (catalog.isEmpty() && !selected.isEmpty()) loadCatalog();

        // 선택한 도서관들이 어느 시도에 걸쳐 있는지 구합니다.
        // libSrchByBook 의 region 이 필수라 이 목록만큼 호출이 곱해집니다.
        List<String> regionCodes = selected.stream()
                .map(catalog::get)
                .filter(java.util.Objects::nonNull)
                .map(LibraryInfo::region)
                .flatMap(Optional::stream)
                .map(RegionCode::code)
                .distinct().toList();

        return searchService.search(q.trim(), regionCodes, selected);
    }

    /**
     * 도서관 페이지로 넘깁니다.
     *
     * <p>지금은 홈페이지까지만 보냅니다. 도서관마다 OPAC 이 달라 상세 페이지 주소를
     * 만들려면 벤더 계열별 템플릿이 필요한데, 그건 실제 주소를 확인하며 채워야 합니다.
     * <b>어느 단계의 링크인지 화면에 밝히는 것이 중요합니다.</b> 조용히 홈페이지로 보내면
     * 사용자는 검색 결과 자체가 틀렸다고 생각합니다.
     */
    @GetMapping("/go/{libCode}")
    public ResponseEntity<Void> go(@PathVariable String libCode) {
        if (catalog.isEmpty()) loadCatalog();
        LibraryInfo library = catalog.get(libCode);
        if (library == null || library.homepage() == null || library.homepage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "홈페이지 주소가 없습니다.");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(library.homepage())).build();
    }

    /** 호출 예산이 얼마나 남았는지. 한도가 예상과 다른지 여기서 드러납니다. */
    @GetMapping("/status")
    public Map<String, Object> status(ApiBudget budget) {
        return Map.of(
                "librariesLoaded", catalog.size(),
                "callsUsedToday", budget.used(Data4LibraryClient.SOURCE_CODE),
                "callsRemaining", budget.remaining(Data4LibraryClient.SOURCE_CODE));
    }

    private synchronized void loadCatalog() {
        if (!catalog.isEmpty()) return;
        List<LibraryInfo> all = new ArrayList<>();
        // 시도별로 나눠 받습니다. region 없이 전부 받으면 한 번에 오는 양이 커집니다.
        for (RegionCode region : RegionCode.values()) {
            all.addAll(client.libraries(region.code(), ApiBudget.Priority.BACKGROUND));
        }
        all.forEach(library -> {
            if (library.libCode() != null) catalog.put(library.libCode(), library);
        });
    }

    /**
     * @param shortId URL 인코딩에 쓰는 짧은 번호. 지금은 도서관부호에서 파생하지만,
     *                DB 를 붙이면 {@code library.short_id} 를 그대로 씁니다.
     *                <b>한 번 발급하면 바뀌면 안 됩니다.</b> 공유된 링크가 전부 깨집니다.
     */
    public record LibraryDto(
            String libCode, int shortId, String name, String sido, String sigungu,
            Double latitude, Double longitude, String homepageUrl,
            String closed, String operatingTime
    ) {
        static LibraryDto from(LibraryInfo info) {
            String[] parts = splitAddress(info.address());
            return new LibraryDto(
                    info.libCode(),
                    shortIdOf(info.libCode()),
                    info.libName(),
                    parts[0], parts[1],
                    info.latitude(), info.longitude(),
                    info.homepage(), info.closed(), info.operatingTime());
        }

        /**
         * 도서관부호가 숫자라 그대로 씁니다. DB 를 붙이기 전까지의 임시 방편이고,
         * 붙인 뒤에는 부여된 번호를 그대로 쓰므로 값이 바뀝니다.
         * 그 전까지 공유한 링크는 한 번 깨집니다.
         */
        private static int shortIdOf(String libCode) {
            try {
                return Integer.parseInt(libCode);
            } catch (NumberFormatException e) {
                return Math.abs(libCode.hashCode() % 1_000_000);
            }
        }

        /** 주소 앞머리에서 시도와 시군구를 뽑습니다. */
        private static String[] splitAddress(String address) {
            if (address == null || address.isBlank()) return new String[] {"기타", "기타"};
            String[] tokens = address.trim().split("\\s+");
            String sido = RegionCode.ofSido(tokens[0]).map(RegionCode::sido).orElse(tokens[0]);
            String sigungu = tokens.length > 1 ? tokens[1] : "기타";
            return new String[] {sido, sigungu};
        }
    }
}
