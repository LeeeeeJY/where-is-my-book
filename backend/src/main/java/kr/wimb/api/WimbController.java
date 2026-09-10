package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.CachingHoldingsClient;
import kr.wimb.data4library.LibraryInfo;
import kr.wimb.data4library.RegionCode;
import kr.wimb.ingest.ApiBudget;
import kr.wimb.opac.Homepage;
import kr.wimb.opac.DetailResolver;
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

    /** 표시는 전부 한국 시각 기준입니다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Data4LibraryClient client;
    private final BookSearchService searchService;
    private final MultiCheckService multiCheckService;
    private final BrowseService browseService;
    private final ApiBudget budget;

    /** 소장 캐시. {@code /api/status} 가 항목 수를 내보냅니다. */
    private final CachingHoldingsClient holdingsCache;

    /** 주소별 호출 제한. {@code /api/status} 가 지금 세고 있는 주소 수를 내보냅니다. */
    private final RateLimit rateLimit;
    private final OpacTemplates opacTemplates;

    /** 누를 때 검색 결과에서 상세 링크를 뽑는 쪽. 규칙 표만으로는 상세에 닿지 못합니다. */
    private final DetailResolver detailResolver;

    /**
     * 도서관 마스터를 메모리에 담아 둡니다. 1,619건뿐이라 이걸로 충분하고,
     * 매번 정보나루를 부르면 예산이 검색에 쓸 몫까지 갉아먹습니다.
     */
    private final Map<String, LibraryInfo> catalog = new ConcurrentHashMap<>();

    /**
     * 각 도서관이 어느 시도에 속하는지. <b>정보나루가 그 시도의 {@code libSrch} 목록에
     * 넣어 준 것</b>이 근거입니다.
     *
     * <p>예전에는 도서관 주소의 앞머리에서 시도명을 뽑았습니다. 그런데 {@code libSrchByBook}
     * 의 {@code region} 은 정보나루가 그 도서관에 붙여 둔 지역으로 걸러지는 것이라, 주소를
     * 우리가 읽어 낸 값과 어긋나면 <b>그 도서관은 물어보지도 않은 채 「없음」으로
     * 나갑니다.</b> 실제로 「고양시립」 두 곳은 주소에서 시도를 뽑지 못해 확인 불가로만
     * 답할 수 있었습니다. 정보나루가 시도별로 돌려주는 목록에서 소속을 그대로 받으면
     * 소장 조회가 쓰는 것과 같은 기준이 됩니다. 주소 읽기는 이 값이 없을 때의 예비입니다.
     */
    private final Map<String, RegionCode> regionOf = new ConcurrentHashMap<>();

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
                          OpacTemplates opacTemplates, DetailResolver detailResolver,
                          CachingHoldingsClient holdingsCache,
                          RateLimit rateLimit, BrowseService browseService) {
        this(client, searchService, multiCheckService, budget, opacTemplates, detailResolver,
                holdingsCache, rateLimit, browseService, Clock.systemUTC());
    }

    /** 재시도 시각을 시험할 수 있도록 시계를 받는 생성자입니다. */
    WimbController(Data4LibraryClient client, BookSearchService searchService,
                   MultiCheckService multiCheckService, ApiBudget budget,
                   OpacTemplates opacTemplates, DetailResolver detailResolver,
                   CachingHoldingsClient holdingsCache,
                   RateLimit rateLimit, BrowseService browseService, Clock clock) {
        this.browseService = browseService;
        this.rateLimit = rateLimit;
        this.holdingsCache = holdingsCache;
        this.opacTemplates = opacTemplates;
        this.detailResolver = detailResolver;
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
                .map(info -> LibraryDto.from(info, opacTemplates, regionOf.get(info.libCode())))
                // 시도를 못 알아낸 도서관은 끝으로 보냅니다. 목록에서 빼지는 않습니다.
                .sorted(Comparator.comparing(LibraryDto::sido,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LibraryDto::name))
                .toList();
    }

    /**
     * 지금 돌고 있는 코드가 무엇인지.
     *
     * <p><b>이것이 없으면 배포가 반영됐는지 알 방법이 없습니다.</b> VM 은 {@code latest}
     * 하나만 물어 가므로, 화면이 예전 그대로여도 그것이 「고치다 만 것」인지 「배포가 안 된
     * 것」인지 구별되지 않습니다. 실제로 빌드 셋이 겹쳐 오래된 이미지가 새 이미지를
     * 덮어쓴 적이 있는데, 그때 하루치 추측을 했습니다. 커밋 해시 한 줄이면 1초에 끝날
     * 일이었습니다.
     *
     * <p>비밀이 아닙니다. 저장소가 공개이고 커밋 해시는 거기에 그대로 있습니다.
     * <b>인증키처럼 실제로 감춰야 하는 값은 절대 여기에 싣지 마세요.</b>
     */
    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of(
                "commit", System.getenv().getOrDefault("WIMB_COMMIT", "unknown"),
                "builtAt", System.getenv().getOrDefault("WIMB_BUILT_AT", "unknown"),
                "now", LocalDate.now(SEOUL).toString());
    }

    /**
     * 책 검색. <b>제목·저자·출판사를 따로 줄 수 있고 둘 이상 주면 AND 로 걸립니다.</b>
     *
     * <p>매뉴얼 16절에 {@code srchBooks} 가 {@code title author publisher isbn13} 을 각각
     * 받는다고 적혀 있습니다. 한 칸에 다 넣고 우리가 쪼개는 것보다 정보나루에 그대로
     * 넘기는 편이 정확합니다. 「김영하 알쓸신잡」처럼 붙여 넣으면 그런 제목의 책을 찾다가
     * 0건이 나오고, 사용자는 그것을 「이 책이 없다」로 읽습니다.
     *
     * <p>조건을 하나도 주지 않으면 정보나루는 전체 대출데이터를 훑습니다. 아무 뜻도 없는
     * 결과에 호출만 쓰게 되므로 여기서 막습니다.
     *
     * <p><b>{@code keyword} 는 화면이 쓰지 않습니다. 실측용으로 열어 둔 것입니다.</b>
     * 매뉴얼 16절이 {@code title} 과 별개의 항목으로 두고 있는데, 실제로 불러 보니 제목이
     * 아니라 <b>주제어</b>를 찾았습니다. 제목 검색에 쓰지 마세요. 등록된 IP 에서만 물어볼 수
     * 있는 질문이 다시 생길 때를 위해 통로만 남겨 둡니다.
     */
    @GetMapping("/search")
    public BookSearchService.SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String isbn,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> libs) {

        String title = trimToNull(q);
        String byAuthor = trimToNull(author);
        String byPublisher = trimToNull(publisher);
        String byKeyword = trimToNull(keyword);
        // ISBN 은 사람이 하이픈이나 공백을 섞어 옮겨 적습니다. 그대로 넘기면 0건이 나오고
        // 사용자는 그것을 「이 책이 없다」로 읽습니다. 숫자와 X 만 남깁니다.
        String byIsbn = trimToNull(
                isbn == null ? null : isbn.replaceAll("[^0-9Xx]", "").toUpperCase());
        if (title == null && byAuthor == null && byPublisher == null && byIsbn == null
                && byKeyword == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색 조건이 비어 있습니다.");
        }
        // 고른 도서관은 소장 조회에만 쓰이는데 그것은 /api/holdings 가 따로 답합니다.
        // 여기서 미리 받아 두는 것은 그 조회가 시도 코드를 바로 알 수 있게 하려는 것뿐입니다.
        loadCatalogQuietly(libs == null ? List.of() : libs);

        try {
            return searchService.search(new Data4LibraryClient.BookQuery(
                    title, byAuthor, byPublisher, byIsbn, byKeyword, false));
        } catch (ResponseStatusException | Data4LibraryClient.ApiErrorException
                 | Data4LibraryClient.BudgetExhaustedException e) {
            // **감싸지 않고 그대로 올립니다.** 감싸면 정보나루가 준 errCode 가 사라져서,
            // 화면이 「인증키 문제인지 IP 등록 문제인지 오늘 몫을 다 쓴 것인지」를 갈라
            // 말할 수 없게 됩니다. ApiErrorAdvice 가 코드까지 실어 내보냅니다.
            throw e;
        } catch (RuntimeException e) {
            // 검색 자체를 못 한 것을 500 으로 내면 "우리 서버가 고장"이라는 뜻이 됩니다.
            // 실제로는 물어보지 못한 것이므로 503 과 이유를 함께 냅니다.
            // 화면은 이것을 "결과 없음"이 아니라 "확인하지 못했다"로 그립니다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

    /** 공백만 든 값은 조건이 아닙니다. 그대로 넘기면 정보나루가 빈 조건으로 훑습니다. */
    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 도서관 하나가 속한 시도. <b>정보나루가 준 소속을 먼저 보고</b>, 그것이 없을 때만 주소를
     * 읽습니다. 소장 조회의 {@code region} 이 여기서 나오므로, 정보나루와 다른 기준을 쓰면
     * 그 도서관은 물어보지도 않은 채 「없음」이 됩니다.
     */
    private Optional<RegionCode> regionFor(String libCode) {
        RegionCode fromSource = regionOf.get(libCode);
        if (fromSource != null) return Optional.of(fromSource);
        LibraryInfo info = catalog.get(libCode);
        return info == null ? Optional.empty() : info.region();
    }

    /**
     * 선택한 도서관들이 어느 시도에 걸쳐 있는지 구합니다.
     *
     * <p>{@code libSrchByBook} 의 {@code region} 이 필수라 <b>이 목록의 크기만큼 호출이
     * 곱해집니다.</b> 도서관 수가 아니라 시도 수라는 점이 중요합니다.
     */
    private List<String> regionsOf(List<String> selectedLibs) {
        return selectedLibs.stream()
                .map(this::regionFor)
                .flatMap(Optional::stream)
                .map(RegionCode::code)
                .distinct().toList();
    }

    /**
     * 고른 도서관 가운데 <b>물어볼 수조차 없는 곳</b>의 수.
     *
     * <p>{@code libSrchByBook} 은 {@code region} 이 필수인데, 정보나루가 준 소속도 없고
     * 주소에서도 시도를 알아보지 못하면 region 이 없고, 그러면 <b>그 도서관은 조회 대상에서
     * 아예 빠집니다.</b>
     *
     * <p>여기까지는 어쩔 수 없습니다. 문제는 그다음입니다. 빠진 도서관은 결과 목록에 있을
     * 수 없으므로 <b>「그 도서관에는 없다」로 나갑니다.</b> 물어보지 않고 없다고 답하는
     * 것이고, 실제로 소장한 책을 놓치게 됩니다. 헛걸음을 막으려고 만든 도구가 헛걸음을
     * 만드는 자리라 반드시 세어서 확인 불가로 넘겨야 합니다.
     *
     * <p>전부 물어볼 수 없으면 {@code holdingsOf} 가 따로 걸러 냅니다. 여기서 세는 것은
     * <b>일부만</b> 빠지는 경우입니다. 그쪽이 눈에 띄지 않아 더 위험합니다.
     */
    private int unaskableCount(List<String> selectedLibs) {
        return (int) selectedLibs.stream()
                .filter(code -> regionFor(code).isEmpty())
                .count();
    }

    public record CheckRequest(List<String> lines) {}

    public record HoldingsRequest(List<String> isbn13List, List<String> libs) {}

    /**
     * @param heldIsbns 도서관마다 <b>그 도서관이 가진 것으로 확인된</b> 판본의 ISBN. 화면은
     *                  도서관 링크에 저작의 첫 ISBN 이 아니라 이 값을 먼저 넣습니다. 첫 ISBN 으로
     *                  보내면 그 판이 없는 도서관의 OPAC 검색이 규칙이 맞아도 0건이 됩니다.
     * @param asOf      조회 시각. <b>화면에 반드시 표시합니다.</b> 캐시에서 나온 답이면 캐시된
     *                  날짜이고, 여러 답이 섞였으면 가장 오래된 날짜입니다.
     */
    public record HoldingsResponse(List<String> libCodes, Map<String, List<String>> heldIsbns,
                                   boolean complete, boolean unreadable, String asOf) {}

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
     * 다른 판을 가지고 있어도 미소장으로 나옵니다. 판본이 상한을 넘으면 서비스가 앞의
     * 것만 묻고 {@code complete} 를 내립니다. 거절하면 화면이 「확인 불가」밖에 그릴 수 없습니다.
     */
    @PostMapping("/holdings")
    public HoldingsResponse holdings(@RequestBody HoldingsRequest request) {
        if (request == null || request.isbn13List() == null || request.isbn13List().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회할 ISBN 이 없습니다.");
        }
        List<String> selected = request.libs() == null ? List.of() : request.libs();
        loadCatalogQuietly(selected);

        var result = searchService.holdingsOf(
                request.isbn13List(), regionsOf(selected), selected, unaskableCount(selected));
        // 답을 실제로 받은 날짜를 말합니다. 받은 답이 없으면(물어보지 못했으면) 오늘입니다.
        String asOf = (result.asOf() != null ? result.asOf() : LocalDate.now(SEOUL)).toString();
        return new HoldingsResponse(result.libCodes(), result.heldIsbns(), result.complete(),
                result.unreadable(), asOf);
    }

    /** 상세를 찾으려고 검색 결과를 받아 볼 ISBN 수의 상한. 한 번에 몇 초씩이라 둘까지입니다. */
    static final int MAX_DETAIL_ATTEMPTS = 2;

    /**
     * 도서관 페이지로 넘깁니다. 상세 → 검색 결과 → 홈페이지 순으로 갈 수 있는 데까지 갑니다.
     *
     * <p><b>{@code isbn} 은 여러 개를 받고, 앞의 것이 그 도서관이 실제로 가진 판입니다.</b>
     * 화면이 {@code /api/holdings} 의 {@code heldIsbns} 를 앞에 세워 보냅니다. 저작의 첫 ISBN 으로
     * 검색하면 그 판이 없는 도서관에서는 규칙이 맞아도 0건이 되어 「소장한다더니 그 책이
     * 없네」로 보이기 때문입니다.
     *
     * <p>ISBN 검색 규칙에 상세 패턴이 붙어 있으면 그 검색 결과를 서버가 받아 상세 링크를
     * 뽑아 보냅니다({@link DetailResolver}). 첫 ISBN 으로 링크가 없으면(그 판이 OPAC 에 없는
     * 것) 다음 ISBN 으로 한 번 더 해 보고, <b>페이지를 아예 못 받았으면 다른 판으로 또 두드리지
     * 않습니다.</b> 닿지 않는 호스트를 두 번 기다리게 하는 것이고 결과도 같기 때문입니다.
     * 어느 쪽이든 못 찾으면 검색 결과 주소로 보냅니다. 지금까지의 동작 그대로라 나빠지는
     * 것은 없고, 화면은 {@code DETAIL_LOOKUP} 에 그 가능성을 함께 적습니다.
     *
     * <p><b>어느 단계의 링크인지 화면에 밝히는 것이 중요합니다.</b> 조용히 홈페이지로 보내면
     * 사용자는 검색 결과 자체가 틀렸다고 생각합니다.
     */
    @GetMapping("/go/{libCode}")
    public ResponseEntity<Void> go(@PathVariable String libCode,
                                   @RequestParam(required = false) List<String> isbn,
                                   @RequestParam(required = false) String title) {
        loadCatalogQuietly(List.of(libCode));
        LibraryInfo library = catalog.get(libCode);
        if (library == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "모르는 도서관입니다.");
        }
        List<String> isbns = isbn == null ? List.of()
                : isbn.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
        String first = isbns.isEmpty() ? null : isbns.get(0);
        // 정보나루가 준 홈페이지 주소를 그대로 실으면 안 됩니다. 「없음」을 뜻하는 `-` 나
        // 스킴이 빠진 주소가 섞여 있는데, 그것을 Location 에 실으면 브라우저가 상대 주소로
        // 읽어 우리 서버 안의 없는 경로로 갑니다. 자세한 것은 Homepage 에 있습니다.
        String url = opacTemplates.bestFor(libCode, first, title)
                .map(link -> detailOrSame(libCode, link, isbns))
                .or(() -> Homepage.usable(library.homepage()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "이 도서관은 홈페이지 주소를 알려 주지 않았습니다."));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /** ISBN 검색 링크에 상세 패턴이 있으면 상세를 찾아 보고, 못 찾으면 그 링크 그대로입니다. */
    private String detailOrSame(String libCode, OpacLink link, List<String> isbns) {
        if (link.kind() != OpacLink.Kind.ISBN_SEARCH) return link.url();
        var pattern = opacTemplates.detailPatternFor(link.url());
        if (pattern.isEmpty()) return link.url();
        for (String candidate : isbns.subList(0, Math.min(MAX_DETAIL_ATTEMPTS, isbns.size()))) {
            String searchUrl = candidate.equals(isbns.get(0)) ? link.url()
                    : opacTemplates.bestFor(libCode, candidate, null).map(OpacLink::url).orElse(null);
            if (searchUrl == null) continue;
            var outcome = detailResolver.resolve(searchUrl, pattern.get().regex());
            if (outcome.status() == DetailResolver.Status.RESOLVED) return outcome.detailUrl();
            if (outcome.status() != DetailResolver.Status.NO_MATCH) break;
        }
        return link.url();
    }

    /**
     * 그 도서관에 그 책이 지금 있는지({@code bookExist}).
     *
     * <p><b>사용자가 그 도서관을 눌렀을 때만 부릅니다.</b> 이 호출은 (도서관 × ISBN)이라
     * 목록에 그냥 달면 여러 권 확인 한 번에 1,800회가 나가고 하루 한도가 열여섯 번 만에
     * 사라집니다. 누를 때만 부르므로 저작 하나에 판본 수만큼입니다.
     *
     * <p><b>저작에 묶인 판본을 전부 받아야 합니다.</b> 처음에는 대표 판본 하나만 물었는데,
     * 소장 조회는 판본 전체를 대상으로 하므로 도서관이 2판을 가지고 있으면 1판을 물어보고
     * 「이 도서관에는 없다」고 답하게 됩니다. 소장한다고 표시해 놓고 누르면 없다고 하는
     * 것이라, 소장 정보 자체를 믿지 못하게 만듭니다. 판본 분산 문제가 여기서 다시
     * 나타난 것입니다.
     *
     * <p>판본이 상한을 넘으면 앞의 것만 묻습니다. 그 안에서 찾으면 그것이 답이고, 못 찾았으면
     * <b>묻지 못한 판본이 남은 것이라 「없다」고 말할 수 없습니다.</b>
     *
     * <p>돌려주는 {@code asOf} 는 <b>어제 날짜</b>입니다. 오늘이 아닙니다. 정보나루가 주는
     * 대출 상태가 조회일 기준 전날의 것이기 때문입니다(매뉴얼 11절). 화면이 이 날짜를
     * 그대로 보여 주어야 사용자가 언제 기준인지 알고 판단합니다.
     */
    @GetMapping("/loan")
    public LoanDto loan(@RequestParam String lib, @RequestParam List<String> isbn) {
        if (isbn == null || isbn.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회할 ISBN 이 없습니다.");
        }
        boolean truncated = isbn.size() > BookSearchService.MAX_EDITIONS_PER_LOOKUP;
        List<String> asked = truncated ? isbn.subList(0, BookSearchService.MAX_EDITIONS_PER_LOOKUP) : isbn;

        boolean anyFailed = truncated;
        for (String isbn13 : asked) {
            try {
                var status = client.loanStatus(lib, isbn13, ApiBudget.Priority.USER);
                // 하나라도 가지고 있으면 그것이 답입니다. 나머지는 물어볼 필요가 없습니다.
                if (status.hasBook()) {
                    return new LoanDto(true, status.loanAvailable(), yesterday());
                }
            } catch (RuntimeException e) {
                anyFailed = true;
            }
        }

        // **못 물어본 판본이 남았으면 「없다」고 말할 수 없습니다.** 소장 여부에서 지키는
        // 구분과 같습니다. 화면이 이것을 「확인하지 못했습니다」로 그립니다.
        if (anyFailed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "대출 상태를 확인하지 못했습니다.");
        }
        return new LoanDto(false, false, yesterday());
    }

    private static String yesterday() {
        return LocalDate.now(SEOUL).minusDays(1).toString();
    }

    /**
     * @param asOf 이 상태가 <b>언제 기준</b>인지. 어제 날짜가 들어옵니다.
     *             화면에서 지우지 마세요. 실시간으로 읽히면 헛걸음을 만듭니다.
     */
    public record LoanDto(boolean hasBook, boolean loanAvailable, String asOf) {}

    /** 호출 예산이 얼마나 남았는지. 한도가 예상과 다른지 여기서 드러납니다. */
    /**
     * 둘러보기 화면. <b>오늘의 이야기 한 권과 그 도서관의 인기대출 목록</b>입니다.
     *
     * <p><b>소장 조회를 부르지 마세요.</b> 목록에 소장을 미리 붙이면 스무 권 × 시도 수만큼
     * 호출이 나갑니다. 화면은 줄을 눌렀을 때만 {@code /api/holdings} 로 물어봅니다.
     * 「대출 상태를 목록에 미리 달지 마세요」와 같은 규칙입니다.
     *
     * <p>정보나루가 답하지 않아도 <b>500 을 내지 않습니다.</b> 둘러보기는 곁들이 화면이라
     * 이것 때문에 검색까지 막히면 안 됩니다. 빈 목록으로 답하고 화면이 안내 한 줄을 그립니다.
     */
    @GetMapping("/browse")
    public BrowseResponse browse(@RequestParam("lib") String libCode) {
        if (libCode == null || libCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "도서관을 고르면 보여 드립니다.");
        }
        return new BrowseResponse(
                browseService.storyOf(libCode).orElse(null),
                browseService.popularOf(libCode));
    }

    /**
     * @param story 없을 수 있습니다. 정보나루가 답하지 않았거나, 그 도서관 문학 장서에서
     *              소설을 찾지 못한 경우입니다. <b>화면이 「없다」로 그리면 안 됩니다.</b>
     */
    public record BrowseResponse(BrowseService.Story story,
                                 List<BrowseService.PopularGroup> popular) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("librariesLoaded", catalog.size());
        out.put("callsUsedToday", budget.used(Data4LibraryClient.SOURCE_CODE));
        out.put("callsRemaining", budget.remaining(Data4LibraryClient.SOURCE_CODE));
        // **한도가 실제로 도는지 알 방법이 있어야 합니다.** 호출이 갑자기 줄었을 때
        // 이 숫자가 없으면 사람이 안 오는 것인지 우리가 막고 있는 것인지 구별할 수
        // 없어 추측하게 됩니다. 주소 자체는 내보내지 않습니다. 개인정보이고,
        // 세고 있다는 사실만으로 이 진단에는 충분합니다.
        out.put("rateLimitedClients", rateLimit.trackedClients());
        // **캐시가 실제로 살아 있는지 알 방법이 있어야 합니다.** 이 숫자가 없으면
        // 재배포 뒤에 호출이 줄지 않을 때, 스냅샷을 못 되살린 것인지 저장이 안 된
        // 것인지 캐시가 원래 안 도는 것인지 구별할 수 없어 추측하게 됩니다.
        // 배포 직후 이 값이 0이면 스냅샷을 잃은 것입니다.
        out.put("holdingCacheEntries", holdingsCache.size());
        // 둘러보기는 도서관마다 하루 한 번만 부릅니다. 이 값이 방문자 수를 따라
        // 늘면 날짜 경계가 잘못 잡힌 것이고, 재배포마다 0이면 캐시를 잃는 것입니다.
        out.put("browseStories", browseService.cacheSizes().get("browseStories"));
        out.put("browsePopular", browseService.cacheSizes().get("browsePopular"));
        // OPAC 규칙이 실제로 실려 있는지. 배포 직후 0이면 규칙 파일을 잃은 것이고(예전에
        // .gitignore 가 삼킨 적이 있습니다), 상세 해석의 성적은 어디서 새는지를 말합니다.
        // resolved 는 상세로 갔고, missed 는 페이지는 받았는데 링크가 없었고(그 판이 없거나
        // 패턴이 안 맞음), failed 는 페이지를 못 받은 것(해외 IP 차단이면 여기가 늡니다)입니다.
        out.put("opacRuleLibraries", opacTemplates.size());
        out.put("opacDetailPatterns", opacTemplates.patternCount());
        out.putAll(detailResolver.stats());
        return out;
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
     * 도서관 마스터를 <b>시도별로</b> 받습니다.
     *
     * <p>전국을 한 번에 받으면 호출이 3분의 1로 줄지만, 그러면 <b>각 도서관이 정보나루 기준으로
     * 어느 시도에 속하는지</b>를 알 수 없습니다. 소장 조회({@code libSrchByBook})는 그 소속으로
     * 걸러지므로, 주소를 우리가 읽어 낸 값이 그것과 어긋나는 도서관은 물어보지도 않은 채
     * 「없음」이 됩니다. 시도별로 받으면 목록에 들어온 시도가 곧 소속이라 그 어긋남이
     * 사라집니다. 서버가 뜰 때 배경에서 받는 열일곱 번이라 사람이 기다리지 않습니다.
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
    private synchronized void loadCatalog() {
        if (isComplete()) return;
        // **비어 있을 때도 기다립니다.** 예전에는 목록이 비어 있으면 이 빗장을 건너뛰었는데,
        // 정보나루가 답하지 않는 동안에는 목록이 늘 비어 있습니다. 그래서 아무도 기다리지
        // 않고, 화면을 한 번 열 때마다 시도 열일곱 번을 다시 부르게 됩니다.
        // **가장 안 될 때 가장 많이 부르는 셈**이고, 그 헛호출이 하루 한도를 갉아먹어
        // 고장을 스스로 늘립니다. 비어 있는 쪽이 오히려 더 기다려야 합니다.
        if (clock.instant().isBefore(nextRetry)) return;

        int failures = 0;
        for (RegionCode region : RegionCode.values()) {
            if (loadedRegions.contains(region.code())) continue;
            try {
                for (LibraryInfo library : client.libraries(region.code(), ApiBudget.Priority.BACKGROUND)) {
                    if (library.libCode() == null) continue;
                    catalog.put(library.libCode(), library);
                    regionOf.put(library.libCode(), region);
                }
                loadedRegions.add(region.code());
            } catch (RuntimeException e) {
                // 이 시도만 빼고 계속합니다. 성공으로 기억하지 않으므로 다음에 다시 받습니다.
                failures++;
            }
        }

        if (failures > 0 || !isComplete()) nextRetry = clock.instant().plus(CATALOG_RETRY_AFTER);
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
        /**
         * @param fromSource 정보나루가 시도별 목록에서 알려 준 소속. 모르면 null 이고, 그때만
         *                   주소에서 읽습니다.
         */
        static LibraryDto from(LibraryInfo info, OpacTemplates templates, RegionCode fromSource) {
            String[] parts = splitAddress(info.address(), fromSource);
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
         * 시도와 시군구를 정합니다. <b>시도는 정보나루가 알려 준 소속을 우선합니다.</b>
         *
         * <p>주소 앞머리가 시도명이면 그 다음 어절이 시군구입니다. 「고양시 일산동구 …」처럼
         * 시도명이 빠진 주소는 예전에는 시도를 알아내지 못해 지역 트리에서 빠졋는데, 이제는
         * 소속이 따로 오므로 첫 어절을 시군구로 삼습니다. 다만 그 어절이 시·군·구로 끝나지
         * 않으면 시군구라고 단정하지 않습니다. 길 이름을 시군구로 올리면 트리에 엉뚱한
         * 가지가 생깁니다.
         *
         * <p>둘 다 없으면 null 을 줍니다. 「기타」 묶음은 지역으로 훑는 사람에게 아무것도
         * 알려 주지 않고, 그렇다고 목록에서 빼면 이름으로 검색해도 안 나와서 <b>「그런 도서관이
         * 없다」로 읽힙니다.</b> 값을 비우고 지역 트리에서만 빠지게 합니다.
         */
        private static String[] splitAddress(String address, RegionCode fromSource) {
            String[] tokens = address == null || address.isBlank()
                    ? new String[0] : address.trim().split("\\s+");
            Optional<RegionCode> fromAddress = tokens.length == 0
                    ? Optional.empty() : RegionCode.ofSido(tokens[0]);
            RegionCode region = fromSource != null ? fromSource : fromAddress.orElse(null);
            if (region == null) return new String[] {null, null};

            String sigungu;
            if (fromAddress.isPresent()) {
                sigungu = tokens.length > 1 ? tokens[1] : null;
            } else {
                sigungu = tokens.length > 0 && looksLikeSigungu(tokens[0]) ? tokens[0] : null;
            }
            return new String[] {region.sido(), sigungu};
        }

        private static boolean looksLikeSigungu(String token) {
            return token.endsWith("시") || token.endsWith("군") || token.endsWith("구");
        }
    }
}
