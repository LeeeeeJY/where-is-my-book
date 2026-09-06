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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class WimbController {

    /** 한 요청에서 조회할 판본 수 상한. 한 요청이 예산을 통째로 쓰지 못하게 막습니다. */
    private static final int MAX_EDITIONS_PER_LOOKUP = 20;

    /** 표시는 전부 한국 시각 기준입니다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Data4LibraryClient client;
    private final BookSearchService searchService;
    private final MultiCheckService multiCheckService;
    private final ApiBudget budget;

    /**
     * 도서관 마스터를 메모리에 담아 둡니다. 1,604건뿐이라 이걸로 충분하고,
     * 매번 정보나루를 부르면 예산이 검색에 쓸 몫까지 갉아먹습니다.
     */
    private final Map<String, LibraryInfo> catalog = new ConcurrentHashMap<>();

    public WimbController(Data4LibraryClient client, BookSearchService searchService,
                          MultiCheckService multiCheckService, ApiBudget budget) {
        this.client = client;
        this.searchService = searchService;
        this.multiCheckService = multiCheckService;
        this.budget = budget;
    }

    /**
     * 선택 화면에 뿌릴 도서관 목록. 위경도까지 함께 나갑니다.
     *
     * <p>정보나루의 {@code libSrch} 가 위경도를 주기 때문에, 공공데이터포털의
     * 전국도서관표준데이터를 이름과 주소로 대조하는 단계가 필요 없습니다.
     */
    @GetMapping("/libraries")
    public List<LibraryDto> libraries() {
        if (catalog.isEmpty()) {
            try {
                loadCatalog();
            } catch (RuntimeException e) {
                // 우리 서버가 고장 난 것이 아니라 정보나루가 답을 주지 않은 것이므로
                // 503 으로 내고 이유를 밝힙니다.
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
            }
        }
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
        // 마스터를 못 받아도 여기서 멈추지 않습니다. 시도를 알 수 없으면 소장 조회가
        // "확인 불가"로 나가고, 책 정보는 그대로 보여 줄 수 있습니다.
        loadCatalogQuietly(selected);

        try {
            return searchService.search(q.trim(), regionsOf(selected), selected);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            // 검색 자체를 못 한 것을 500 으로 내면 "우리 서버가 고장"이라는 뜻이 됩니다.
            // 실제로는 물어보지 못한 것이므로 503 과 이유를 함께 냅니다.
            // 화면은 이것을 "결과 없음"이 아니라 "확인하지 못했다"로 그립니다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

    /**
     * 선택한 도서관들이 어느 시도에 걸쳐 있는지 구합니다.
     *
     * <p>{@code libSrchByBook} 의 {@code region} 이 필수라 <b>이 목록의 크기만큼 호출이
     * 곱해집니다.</b> 도서관 수가 아니라 시도 수라는 점이 중요합니다.
     */
    private List<String> regionsOf(List<String> selectedLibs) {
        return selectedLibs.stream()
                .map(catalog::get)
                .filter(java.util.Objects::nonNull)
                .map(LibraryInfo::region)
                .flatMap(Optional::stream)
                .map(RegionCode::code)
                .distinct().toList();
    }

    public record CheckRequest(List<String> lines) {}

    public record HoldingsRequest(List<String> isbn13List, List<String> libs) {}

    /**
     * @param asOf 조회 시각. <b>화면에 반드시 표시합니다.</b>
     */
    public record HoldingsResponse(List<String> libCodes, boolean complete, boolean unreadable,
                                   String asOf) {}

    /**
     * 붙여넣은 목록을 줄 단위로 해석하고 책을 확정합니다. <b>소장 조회는 하지 않습니다.</b>
     *
     * <p>화면은 이 응답만으로 목록을 즉시 그리고, 소장은 {@link #holdings} 로 한 권씩
     * 채워 넣습니다. 그래야 캐시가 빈 상태에서도 빈 화면으로 기다리는 구간이 없습니다.
     */
    @PostMapping("/check/resolve")
    public MultiCheckService.ResolveResponse resolve(@RequestBody CheckRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "확인할 목록이 비어 있습니다.");
        }
        return multiCheckService.resolve(request.lines());
    }

    /**
     * 저작 하나의 소장 여부. 화면이 책마다 따로 부르고 도착하는 대로 채웁니다.
     *
     * <p>저작에 묶인 <b>모든 판본</b>을 함께 보내야 합니다. 판본 하나만 조회하면 도서관이
     * 다른 판을 가지고 있어도 미소장으로 나옵니다.
     */
    @PostMapping("/holdings")
    public HoldingsResponse holdings(@RequestBody HoldingsRequest request) {
        if (request == null || request.isbn13List() == null || request.isbn13List().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회할 ISBN 이 없습니다.");
        }
        if (request.isbn13List().size() > MAX_EDITIONS_PER_LOOKUP) {
            // 한 요청이 하루치 예산을 통째로 쓰는 것을 막습니다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "한 번에 조회할 판본이 너무 많습니다.");
        }
        List<String> selected = request.libs() == null ? List.of() : request.libs();
        loadCatalogQuietly(selected);

        var result = searchService.holdingsOf(request.isbn13List(), regionsOf(selected), selected);
        return new HoldingsResponse(result.libCodes(), result.complete(), result.unreadable(),
                LocalDate.now(SEOUL).toString());
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
        loadCatalogQuietly(List.of(libCode));
        LibraryInfo library = catalog.get(libCode);
        if (library == null || library.homepage() == null || library.homepage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "홈페이지 주소가 없습니다.");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(library.homepage())).build();
    }

    /** 호출 예산이 얼마나 남았는지. 한도가 예상과 다른지 여기서 드러납니다. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "librariesLoaded", catalog.size(),
                "callsUsedToday", budget.used(Data4LibraryClient.SOURCE_CODE),
                "callsRemaining", budget.remaining(Data4LibraryClient.SOURCE_CODE));
    }

    /**
     * 마스터를 받아 보되 실패해도 예외를 올리지 않습니다.
     *
     * <p>여기서 500 을 내면 "우리 서버가 고장 났다"는 뜻이 되는데, 실제로는 정보나루에
     * 물어보지 못한 것입니다. 마스터가 비면 시도를 알 수 없어 소장 조회가
     * <b>"확인 불가"</b>로 나가는데, 그것이 정확한 표현입니다.
     */
    private void loadCatalogQuietly(List<String> selectedLibs) {
        if (!catalog.isEmpty() || selectedLibs.isEmpty()) return;
        try {
            loadCatalog();
        } catch (RuntimeException e) {
            // 삼키되 결과가 확인 불가로 나가므로 사용자를 속이지는 않습니다.
        }
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
