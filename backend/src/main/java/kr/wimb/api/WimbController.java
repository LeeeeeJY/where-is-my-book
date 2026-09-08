package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.data4library.LibraryInfo;
import kr.wimb.data4library.RegionCode;
import kr.wimb.ingest.ApiBudget;
import kr.wimb.opac.OpacLink;
import kr.wimb.opac.OpacTemplates;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class WimbController {

    /** 실패한 시도를 다시 받기까지 기다리는 시간. 계속 실패하는 지역을 매번 두드리지 않습니다. */
    private static final Duration CATALOG_RETRY_AFTER = Duration.ofMinutes(5);

    /** 한 요청에서 조회할 판본 수 상한. 한 요청이 예산을 통째로 쓰지 못하게 막습니다. */
    private static final int MAX_EDITIONS_PER_LOOKUP = 20;

    /** 표시는 전부 한국 시각 기준입니다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Data4LibraryClient client;
    private final BookSearchService searchService;
    private final MultiCheckService multiCheckService;
    private final ApiBudget budget;
    private final OpacTemplates opacTemplates;

    /**
     * 도서관 마스터를 메모리에 담아 둡니다. 1,604건뿐이라 이걸로 충분하고,
     * 매번 정보나루를 부르면 예산이 검색에 쓸 몫까지 갉아먹습니다.
     */
    private final Map<String, LibraryInfo> catalog = new ConcurrentHashMap<>();

    /**
     * 이미 받아 둔 시도. <b>실패한 시도는 여기에 없으므로 다음 기회에 다시 받습니다.</b>
     * 이것이 없으면 시도 하나가 잠깐 실패했을 때 그 지역 도서관이 영영 목록에 나타나지
     * 않고, 사용자에게는 "그런 도서관이 없다"로 보입니다.
     */
    private final Set<String> loadedRegions = ConcurrentHashMap.newKeySet();

    /** 실패한 시도를 다시 받아 볼 시각. 계속 실패하는 지역을 매 요청마다 두드리지 않습니다. */
    private volatile Instant nextRetry = Instant.EPOCH;

    private final Clock clock;

    /**
     * <b>{@code @Autowired} 를 지우지 마세요.</b> Spring 이 생성자를 알아서 고르는 것은
     * 생성자가 하나일 때뿐입니다. 아래에 시계를 받는 생성자가 있으므로 둘이 되고, 표시가
     * 없으면 Spring 이 기본 생성자를 찾다가 실패해 <b>서버가 아예 뜨지 못합니다.</b>
     * 실제로 그 상태로 배포되어 컨테이너가 시작하다 죽었고, 단위 테스트는 전부 통과했습니다.
     * {@code WimbStartupTest} 가 이제 그것을 잡습니다.
     */
    @Autowired
    public WimbController(Data4LibraryClient client, BookSearchService searchService,
                          MultiCheckService multiCheckService, ApiBudget budget,
                          OpacTemplates opacTemplates) {
        this(client, searchService, multiCheckService, budget, opacTemplates, Clock.systemUTC());
    }

    /** 재시도 시각을 시험할 수 있도록 시계를 받는 생성자입니다. */
    WimbController(Data4LibraryClient client, BookSearchService searchService,
                   MultiCheckService multiCheckService, ApiBudget budget,
                   OpacTemplates opacTemplates, Clock clock) {
        this.opacTemplates = opacTemplates;
        this.client = client;
        this.searchService = searchService;
        this.multiCheckService = multiCheckService;
        this.budget = budget;
        this.clock = clock;
    }

    /**
     * 선택 화면에 뿌릴 도서관 목록. 위경도까지 함께 나갑니다.
     *
     * <p>정보나루의 {@code libSrch} 가 위경도를 주기 때문에, 공공데이터포털의
     * 전국도서관표준데이터를 이름과 주소로 대조하는 단계가 필요 없습니다.
     */
    @GetMapping("/libraries")
    public List<LibraryDto> libraries() {
        if (!isComplete()) {
            try {
                loadCatalog();
            } catch (RuntimeException e) {
                // 우리 서버가 고장 난 것이 아니라 정보나루가 답을 주지 않은 것이므로
                // 503 으로 내고 이유를 밝힙니다.
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
            }
        }
        return catalog.values().stream()
                .map(info -> LibraryDto.from(info, opacTemplates))
                // 시도를 못 알아낸 도서관은 끝으로 보냅니다. 목록에서 빼지는 않습니다.
                .sorted(Comparator.comparing(LibraryDto::sido,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LibraryDto::name))
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
    public ResponseEntity<Void> go(@PathVariable String libCode,
                                   @RequestParam(required = false) String isbn,
                                   @RequestParam(required = false) String title) {
        loadCatalogQuietly(List.of(libCode));
        LibraryInfo library = catalog.get(libCode);
        if (library == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "모르는 도서관입니다.");
        }
        String url = opacTemplates.bestFor(libCode, isbn, title)
                .map(OpacLink::url)
                .orElse(library.homepage());
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보낼 주소가 없습니다.");
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
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
        if (isComplete() || selectedLibs.isEmpty()) return;
        try {
            loadCatalog();
        } catch (RuntimeException e) {
            // 삼키되 결과가 확인 불가로 나가므로 사용자를 속이지는 않습니다.
        }
    }

    /**
     * 도서관 마스터를 시도별로 받습니다.
     *
     * <p><b>시도 하나가 실패해도 나머지로 목록을 만듭니다.</b> 17곳을 한 덩어리로 다루면
     * 한 곳이 잠깐 흔들릴 때 전국 목록을 통째로 못 쓰게 되는데, 그것은 부분 실패를 전체
     * 실패로 만드는 것입니다.
     *
     * <p>대신 실패한 시도를 성공으로 기억하지 않습니다. 그러지 않으면 그 지역 도서관이
     * 영영 목록에 나타나지 않고, 사용자에게는 <b>"그런 도서관이 없다"로 보입니다.</b>
     *
     * @throws ResponseStatusException 한 시도도 받지 못한 경우. 빈 목록을 정상인 척
     *                                 돌려주면 화면이 도서관이 없는 것으로 그립니다.
     */
    /**
     * 서버가 뜨면 곧바로 도서관 목록을 받아 둡니다.
     *
     * <p>예전에는 <b>첫 요청이 들어온 뒤에야</b> 받기 시작해서, 처음 들어온 사람이 그 시간을
     * 통째로 기다렸습니다. 서버는 미국에 있고 정보나루는 한국에 있어 왕복이 붙는데, 그것을
     * 사람이 기다릴 이유가 없습니다. 실패해도 삼킵니다. 목록을 못 받았다고 서버가 뜨지
     * 못하면 그때는 아무것도 할 수 없게 됩니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmCatalog() {
        Thread.ofVirtual().name("catalog-warm").start(() -> {
            try {
                loadCatalog();
            } catch (RuntimeException e) {
                // 다음 요청에서 다시 받습니다.
            }
        });
    }

    /**
     * 전국을 한 번에 받아 봅니다.
     *
     * <p><b>{@code libSrch} 의 {@code region} 은 선택 항목입니다(매뉴얼 1절).</b> 없으면 모든
     * 지역을 돌려주므로, 시도 17번을 도는 대신 쪽수만큼만 부르면 됩니다. 호출이 3분의 1로
     * 줄고 그만큼 왕복도 줄어듭니다. {@code libSrchByBook} 의 {@code region} 이 필수인 것과
     * 혼동하지 마세요. 그쪽은 여전히 시도마다 불러야 합니다.
     *
     * @return 받았으면 true. 실패하면 false 를 주고 시도별로 다시 받습니다. 시도별로 받으면
     *         일부만 실패했을 때 나머지라도 건집니다.
     */
    private boolean loadEveryRegionAtOnce() {
        try {
            for (LibraryInfo library : client.libraries(null, ApiBudget.Priority.BACKGROUND)) {
                if (library.libCode() != null) catalog.put(library.libCode(), library);
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (catalog.isEmpty()) return false;
        for (RegionCode region : RegionCode.values()) loadedRegions.add(region.code());
        return true;
    }

    private synchronized void loadCatalog() {
        if (isComplete()) return;
        if (clock.instant().isBefore(nextRetry) && !catalog.isEmpty()) return;

        if (loadedRegions.isEmpty() && loadEveryRegionAtOnce()) return;

        int failures = 0;
        // 전국 한 번에 받기가 실패했을 때의 길입니다. 시도별로 나누면 일부만 실패해도
        // 나머지는 건집니다.
        for (RegionCode region : RegionCode.values()) {
            if (loadedRegions.contains(region.code())) continue;
            try {
                for (LibraryInfo library : client.libraries(region.code(), ApiBudget.Priority.BACKGROUND)) {
                    if (library.libCode() != null) catalog.put(library.libCode(), library);
                }
                loadedRegions.add(region.code());
            } catch (RuntimeException e) {
                // 이 시도만 빼고 계속합니다. 성공으로 기억하지 않으므로 다음에 다시 받습니다.
                failures++;
            }
        }

        if (failures > 0) nextRetry = clock.instant().plus(CATALOG_RETRY_AFTER);
        if (catalog.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "도서관 목록을 한 곳도 받지 못했습니다. 정보나루가 답하지 않습니다.");
        }
    }

    private boolean isComplete() {
        return loadedRegions.size() == RegionCode.values().length;
    }

    /**
     * @param shortId URL 인코딩에 쓰는 짧은 번호. 지금은 도서관부호에서 파생하지만,
     *                DB 를 붙이면 {@code library.short_id} 를 그대로 씁니다.
     *                <b>한 번 발급하면 바뀌면 안 됩니다.</b> 공유된 링크가 전부 깨집니다.
     */
    public record LibraryDto(
            String libCode, int shortId, String name, String sido, String sigungu,
            Double latitude, Double longitude, String homepageUrl,
            String closed, String operatingTime,
            /**
             * 이 도서관 링크가 어느 단계까지 가는지. 화면이 이것을 그대로 밝혀야 합니다.
             * 홈페이지로 내려앉은 것을 감추면 사용자는 검색 결과 자체를 의심합니다.
             */
            OpacLink.Kind linkKind
    ) {
        static LibraryDto from(LibraryInfo info, OpacTemplates templates) {
            String[] parts = splitAddress(info.address());
            return new LibraryDto(
                    info.libCode(),
                    shortIdOf(info.libCode()),
                    info.libName(),
                    parts[0], parts[1],
                    info.latitude(), info.longitude(),
                    info.homepage(), info.closed(), info.operatingTime(),
                    templates.kindFor(info.libCode()));
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

        /**
         * 주소 앞머리에서 시도와 시군구를 뽑습니다. <b>못 알아내면 null 을 줍니다.</b>
         *
         * <p>예전에는 「기타」라는 묶음에 몰아넣었는데, 지역으로 훑는 사람에게 「기타」는
         * 아무것도 알려 주지 않는 이름이라 열어 볼 이유가 없습니다. 그렇다고 목록에서 아예
         * 빼면 이름으로 검색해도 안 나와서 <b>「그런 도서관이 없다」로 읽히는데</b>, 그것이
         * 더 나쁩니다. 그래서 값을 비우고, 지역 트리에서만 빠지게 합니다. 이름 검색에서는
         * 그대로 나옵니다.
         */
        private static String[] splitAddress(String address) {
            if (address == null || address.isBlank()) return new String[] {null, null};
            String[] tokens = address.trim().split("\\s+");
            String sido = RegionCode.ofSido(tokens[0]).map(RegionCode::sido).orElse(null);
            if (sido == null) return new String[] {null, null};
            return new String[] {sido, tokens.length > 1 ? tokens[1] : null};
        }
    }
}
