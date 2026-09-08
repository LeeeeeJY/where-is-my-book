package kr.wimb.data4library;

import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.ApiBudget;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정보나루 Open API 클라이언트. Open API Manual v20260210 을 그대로 구현합니다.
 *
 * <p><b>이 API 하나로 1차 범위가 전부 채워집니다.</b> 매뉴얼을 읽고 알게 된 사실인데,
 * {@code libSrch} 가 위도와 경도까지 주기 때문에 도서관 마스터를 위해 공공데이터포털의
 * 전국도서관표준데이터를 받아 이름과 주소로 대조할 필요가 없습니다. 계획서에서 가장 큰
 * 위험으로 꼽았던 대조 실패 문제가 통째로 사라집니다.
 *
 * <table>
 *   <caption>쓰는 엔드포인트</caption>
 *   <tr><td>{@code libSrch}</td><td>도서관 마스터. 코드·이름·주소·위경도·홈페이지·휴관일·운영시간</td></tr>
 *   <tr><td>{@code srchBooks}</td><td>서지. 표지 URL 과 대출건수까지</td></tr>
 *   <tr><td>{@code libSrchByBook}</td><td>소장 도서관. <b>region 이 필수</b>입니다</td></tr>
 * </table>
 *
 * <p>{@code bookExist}(11절)는 <b>사용자가 도서관을 눌렀을 때만</b> 부릅니다({@link #loanStatus}).
 * 돌려주는 대출 가능 여부가 전날 기준이라, 목록에 미리 달아 두면 실시간으로 읽혀 헛걸음을
 * 만들고 호출도 (도서관 × ISBN)으로 폭발합니다.
 */
public final class Data4LibraryClient {

    public static final String SOURCE_CODE = "DATA4LIBRARY_API";
    private static final String BASE = "https://data4library.kr/api";

    /**
     * 한 번에 받을 수 있는 최대치가 문서에 없어, 예시에 나온 300 을 넘지 않게 잡았습니다.
     *
     * <p>공개해 둔 것은 부르는 쪽이 <b>「첫 쪽이 가득 찼는지」</b>를 알아야 하기 때문입니다.
     * 가득 찼으면 뒤에 판본이 더 있을 수 있고, 그것을 놓치면 그 판본만 가진 도서관이
     * 「없음」으로 나갑니다.
     */
    public static final int PAGE_SIZE = 300;

    @FunctionalInterface
    public interface Transport {
        String get(URI uri);
    }

    private final Transport transport;
    private final String authKey;
    private final ApiBudget budget;
    private final Clock clock;

    /**
     * 인증 계열 오류를 만난 뒤 다시 부르기까지 기다리는 시간.
     *
     * <p>키가 없거나 활성화되지 않은 것은 <b>사람이 고쳐야 낫는 상태</b>라, 재시도해도
     * 절대 성공하지 않습니다. 그런데 여러 권 확인은 한 번에 수십 번을 부르므로, 막지 않으면
     * 실패할 것이 뻔한 요청으로 남의 서버를 수십 번 두드리게 됩니다.
     */
    private static final Duration AUTH_ERROR_BACKOFF = Duration.ofMinutes(5);

    /** 인증 계열 오류를 만난 사실. 이 시각까지는 부르지 않고 바로 같은 오류를 돌려줍니다. */
    private volatile AuthFailure authFailure;

    private record AuthFailure(Data4LibraryResponse.ApiError error, Instant until) {}

    public Data4LibraryClient(Transport transport, String authKey, ApiBudget budget) {
        this(transport, authKey, budget, Clock.systemUTC());
    }

    public Data4LibraryClient(Transport transport, String authKey, ApiBudget budget, Clock clock) {
        this.transport = transport;
        this.authKey = authKey;
        this.budget = budget;
        this.clock = clock;
    }

    // ---------------------------------------------------------------
    // 도서관 마스터
    // ---------------------------------------------------------------

    /**
     * 정보를 공개하는 도서관 목록. 위경도까지 함께 옵니다.
     *
     * @param regionCode 특정 시도만 받으려면 지정하고, 전국이면 null 을 줍니다.
     *                   {@code libSrch} 에서는 {@code region} 이 선택 항목입니다.
     */
    public List<LibraryInfo> libraries(String regionCode, ApiBudget.Priority priority) {
        List<LibraryInfo> out = new ArrayList<>();
        int page = 1;
        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            if (regionCode != null) params.put("region", regionCode);
            params.put("pageNo", String.valueOf(page));
            params.put("pageSize", String.valueOf(PAGE_SIZE));

            String xml = call("libSrch", params, priority);
            List<Map<String, String>> items = Data4LibraryResponse.items(xml, "lib");
            items.stream().map(LibraryInfo::from).forEach(out::add);

            if (items.size() < PAGE_SIZE) return out;
            page++;
        }
    }

    /**
     * 그 도서관에 그 책이 있는지, 지금 빌릴 수 있는지({@code bookExist}).
     *
     * <p><b>목록에 붙이지 마세요.</b> 이 호출은 (도서관 하나 × ISBN 하나)라서, 여러 권
     * 확인 화면에 그냥 달면 30권 × 판본 3개 × 도서관 20곳 = 1,800회가 됩니다. 하루 한도
     * 30,000건이 열여섯 번 만에 사라집니다. <b>사용자가 그 도서관을 눌렀을 때만</b>
     * 부릅니다. 그때는 1회입니다.
     *
     * <p>그리고 <b>대출 가능 여부는 조회일 기준 전날의 상태입니다</b>(매뉴얼 11절).
     * 실시간이 아니므로 화면에 그 사실을 반드시 함께 적어야 합니다. 이 값을 실시간으로
     * 믿고 갔다가 허탕치는 것이 이 도구를 못 쓰게 만드는 가장 큰 요인입니다.
     */
    public LoanStatus loanStatus(String libCode, String isbn13, ApiBudget.Priority priority) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("libCode", libCode);
        params.put("isbn13", isbn13);

        String xml = call("bookExist", params, priority);
        return new LoanStatus(
                "Y".equalsIgnoreCase(Data4LibraryResponse.scalar(xml, "hasBook")),
                "Y".equalsIgnoreCase(Data4LibraryResponse.scalar(xml, "loanAvailable")));
    }

    /**
     * @param hasBook       그 도서관이 소장하고 있는지
     * @param loanAvailable 빌릴 수 있는지. <b>조회일 기준 전날의 상태입니다.</b>
     */
    public record LoanStatus(boolean hasBook, boolean loanAvailable) {}

    // ---------------------------------------------------------------
    // 서지
    // ---------------------------------------------------------------

    /**
     * 도서 검색. 제목·저자·출판사·ISBN 을 따로 줄 수 있고 둘 이상 주면 AND 로 걸립니다.
     *
     * @param exactMatch 일치 검색 여부. 기본은 부분 일치입니다.
     */
    public List<BookInfo> searchBooks(BookQuery query, int page, ApiBudget.Priority priority) {
        Map<String, String> params = new LinkedHashMap<>(query.toParams());
        params.put("pageNo", String.valueOf(page));
        params.put("pageSize", String.valueOf(PAGE_SIZE));

        String xml = call("srchBooks", params, priority);
        return Data4LibraryResponse.items(xml, "doc").stream().map(BookInfo::from).toList();
    }

    /**
     * @param title      도서명
     * @param author     저자명
     * @param publisher  출판사
     * @param isbn13     13자리 ISBN
     * @param keyword    키워드. <b>매뉴얼 16절이 {@code title} 과 별개로 두고 있는 항목입니다.</b>
     *                   세미콜론으로 나누어 여러 개를 줄 수 있고, 그때는 <b>일치 검색만
     *                   제공된다</b>고 적혀 있습니다. {@code title} 과 어떻게 다른지는 실제로
     *                   불러 보기 전에는 알 수 없으므로, 우선 넘길 수 있게만 열어 둡니다.
     * @param exactMatch 일치 검색 여부. 주지 않으면 정보나루는 일치 검색이 아닌 결과를 줍니다.
     */
    public record BookQuery(String title, String author, String publisher,
                            String isbn13, String keyword, boolean exactMatch) {

        /** keyword 가 없던 시절의 호출부를 그대로 두기 위한 생성자입니다. */
        public BookQuery(String title, String author, String publisher,
                         String isbn13, boolean exactMatch) {
            this(title, author, publisher, isbn13, null, exactMatch);
        }

        public static BookQuery byTitle(String title) {
            return new BookQuery(title, null, null, null, false);
        }

        public static BookQuery byIsbn(String isbn13) {
            return new BookQuery(null, null, null, isbn13, true);
        }

        /** 제목만 바꾼 사본. 띄어쓰기를 달리해 다시 찾아볼 때 씁니다. */
        public BookQuery withTitle(String newTitle) {
            return new BookQuery(newTitle, author, publisher, isbn13, keyword, exactMatch);
        }

        Map<String, String> toParams() {
            Map<String, String> params = new LinkedHashMap<>();
            putIfPresent(params, "title", title);
            putIfPresent(params, "author", author);
            putIfPresent(params, "publisher", publisher);
            putIfPresent(params, "isbn13", isbn13);
            putIfPresent(params, "keyword", keyword);
            if (exactMatch) params.put("exactMatch", "true");
            if (params.isEmpty()) {
                // 조건이 하나도 없으면 전체 대출데이터를 훑게 되어 의미 없는 호출이 됩니다.
                throw new IllegalArgumentException("검색 조건을 최소 하나는 주어야 합니다.");
            }
            return params;
        }

        private static void putIfPresent(Map<String, String> params, String key, String value) {
            if (value != null && !value.isBlank()) params.put(key, value);
        }
    }

    // ---------------------------------------------------------------
    // 소장
    // ---------------------------------------------------------------

    /**
     * 한 소장 조회에서 넘겨볼 페이지 수 상한. 무한 반복을 막는 안전장치일 뿐입니다.
     *
     * <p>정보나루 참여 도서관이 전국 1,604곳이고 한 시도는 300곳 안팎이므로, 정상적인
     * 응답이라면 두세 쪽 안에 끝납니다. 이 상한에 닿는다는 것은 응답이 이상하다는 뜻입니다.
     */
    private static final int MAX_HOLDING_PAGES = 20;

    /**
     * 이 ISBN 을 소장한 도서관. <b>{@code region} 은 매뉴얼상 필수입니다.</b>
     * 전국을 한 번에 받는 방법이 없어 시도마다 따로 불러야 합니다.
     *
     * <p><b>반드시 끝까지 넘겨봐야 합니다.</b> 예전에는 첫 쪽만 보고 끝냈습니다. 응답에
     * {@code numFound}(전체 건수)가 함께 오는데 그것을 읽지 않았으므로, 소장 도서관이
     * 한 쪽에 안 들어가는 책은 <b>뒤쪽 도서관이 통째로 빠진 채</b> 답이 나갔습니다.
     * 빠진 도서관은 「그 도서관에는 없다」로 표시되고, 그것도 <b>「빠짐없이 확인했다」는
     * 표시를 달고</b> 나갑니다. 실제로 소장한 책을 없다고 답하는 것입니다.
     *
     * <p>더 나쁜 것은 이것이 <b>인기 있는 책일수록 심해진다</b>는 점입니다. 소장 도서관이
     * 많은 책이 곧 사람들이 많이 찾는 책이라, 가장 자주 쓰이는 검색에서 가장 자주 틀립니다.
     *
     * <p>{@code pageSize} 를 크게 주는 것으로는 해결되지 않습니다. 서버가 그 값을 그대로
     * 받아 준다는 보장이 없고, 실제로 더 작게 잘라 줘도 우리는 알 방법이 없습니다.
     * 그래서 {@code numFound} 를 기준으로 삼고, 그 값이 없으면 첫 쪽에 실제로 몇 건이
     * 왔는지를 한 쪽 분량으로 삼아 이어 받습니다.
     */
    public List<LibraryInfo> librariesHolding(String isbn13, String regionCode,
                                              ApiBudget.Priority priority) {
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException(
                    "libSrchByBook 은 region 이 필수입니다. 매뉴얼 13절을 보세요.");
        }
        List<LibraryInfo> out = new ArrayList<>();
        int numFound = -1;
        int perPage = -1;

        for (int page = 1; page <= MAX_HOLDING_PAGES; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("isbn", isbn13);
            params.put("region", regionCode);
            params.put("pageNo", String.valueOf(page));
            params.put("pageSize", String.valueOf(PAGE_SIZE));

            String xml = call("libSrchByBook", params, priority);
            var items = Data4LibraryResponse.items(xml, "lib");
            items.stream().map(LibraryInfo::from).forEach(out::add);

            if (page == 1) {
                numFound = intOrMinusOne(Data4LibraryResponse.scalar(xml, "numFound"));
                // 서버가 pageSize 를 그대로 따랐는지는 알 수 없습니다. 실제로 온 건수를
                // 한 쪽 분량으로 삼아야 서버가 더 작게 잘라 줘도 이어 받을 수 있습니다.
                perPage = items.size();
            }
            if (items.isEmpty()) return out;
            if (numFound >= 0 ? out.size() >= numFound : items.size() < perPage) return out;
        }
        return out;
    }

    private static int intOrMinusOne(String value) {
        try {
            return value == null || value.isBlank() ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@link HoldingsLookup} 이 쓰는 형태로 감쌉니다. */
    public HoldingsLookup.HoldingsClient asHoldingsClient(ApiBudget.Priority priority) {
        return (isbn13, regionCode) ->
                librariesHolding(isbn13, regionCode, priority).stream()
                        .map(LibraryInfo::libCode).toList();
    }

    // ---------------------------------------------------------------

    private String call(String endpoint, Map<String, String> params, ApiBudget.Priority priority) {
        // 실패할 것이 뻔한 요청은 보내지 않습니다. 예산도 쓰지 않습니다.
        AuthFailure known = authFailure;
        if (known != null && clock.instant().isBefore(known.until())) {
            throw new ApiErrorException(endpoint, known.error());
        }

        if (!budget.tryAcquire(SOURCE_CODE, priority)) {
            throw new BudgetExhaustedException(
                    "정보나루의 오늘 호출 예산을 다 썼습니다. 남은 항목은 캐시로만 답해야 합니다.");
        }
        StringBuilder url = new StringBuilder(BASE).append('/').append(endpoint)
                .append("?authKey=").append(encode(authKey));
        params.forEach((key, value) ->
                url.append('&').append(key).append('=').append(encode(value)));
        String body = transport.get(URI.create(url.toString()));

        // 정보나루는 오류도 HTTP 200 으로 돌려줍니다. 여기서 걸러 내지 않으면
        // 오류 본문이 "항목이 하나도 없는 정상 응답"으로 읽혀 화면에 미소장으로 나갑니다.
        var found = Data4LibraryResponse.errorOf(body);
        if (found.isPresent()) {
            var error = found.get();
            if (isAuthClass(error)) {
                authFailure = new AuthFailure(error, clock.instant().plus(AUTH_ERROR_BACKOFF));
            }
            throw new ApiErrorException(endpoint, error);
        }
        // 한 번이라도 제대로 답을 받았으면 인증 문제는 풀린 것입니다.
        authFailure = null;
        return body;
    }

    /**
     * 사람이 고쳐야 낫는 오류인지. 그 밖의 오류는 일시적일 수 있으므로 막지 않습니다.
     *
     * <p><b>{@code outOflimit} 도 여기에 듭니다.</b> 「너무 많이 불렀다」로 읽고 잠시 뒤
     * 다시 부르면 될 것 같지만, 실제 뜻은 <b>「등록한 IP 로 나가고 있지 않다」</b>일 때가
     * 많습니다. 등록하지 않은 IP 는 하루 500건이라 금방 닿습니다. 어느 쪽이든 사람이
     * 등록을 고치거나 날이 바뀌기 전에는 낫지 않습니다.
     *
     * <p>이것을 빼 두었더니 <b>고장이 스스로를 키웠습니다.</b> 한도에 걸린 상태에서 화면을
     * 한 번 열 때마다 도서관 마스터를 다시 받으려고 열여덟 번을 부르고, 그 열여덟 번이 전부
     * 실패하면서 호출 수만 올라갔습니다. 실패할 것이 뻔한 요청으로 남의 서버를 두드리는
     * 것이기도 합니다.
     */
    private static boolean isAuthClass(Data4LibraryResponse.ApiError error) {
        return "authErr".equals(error.code())
                || "vitalizationErr".equals(error.code())
                || "outOflimit".equals(error.code());
    }

    /**
     * 질의 문자열에 넣을 수 있게 인코딩합니다.
     *
     * <p><b>{@code URLEncoder} 가 공백을 {@code +} 로 바꾸는 것을 되돌립니다.</b> 그것은
     * HTML 폼 본문(application/x-www-form-urlencoded)의 규칙이고, 질의 문자열에서 {@code +}
     * 를 공백으로 되돌려 주는 것은 서버 마음입니다. 되돌리지 않는 서버에서는
     * {@code title=마의+산} 이 <b>「마의+산」이라는 글자를 그대로 찾는 검색</b>이 되어
     * 0건이 나옵니다.
     *
     * <p>사용자에게는 그것이 「그런 책이 없다」로 보입니다. 오류도 아니고 로그도 남지
     * 않으며, <b>제목에 공백이 없는 책은 멀쩡히 나오기 때문에</b> 검색 기능 자체가 고장
     * 났다고 의심하기도 어렵습니다. 「코스모스」는 되는데 「마의 산」은 안 되는 식입니다.
     *
     * <p>{@code %20} 은 질의 문자열에서든 경로에서든 언제나 공백입니다. {@code +} 를 받아
     * 주는 서버도 {@code %20} 은 함께 받아 주므로 이쪽이 모든 경우에 안전합니다.
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 예산 소진은 오류가 아니라 정상 상태입니다. 부르는 쪽은 이것을 잡아 캐시 응답으로
     * 돌아가야 하고, <b>절대 미소장으로 바꿔 표시하면 안 됩니다.</b>
     */
    public static class BudgetExhaustedException extends RuntimeException {
        public BudgetExhaustedException(String message) { super(message); }
    }

    /**
     * 정보나루가 본문에 오류를 담아 보낸 경우.
     *
     * <p><b>이것을 빈 결과로 바꾸면 안 됩니다.</b> 부르는 쪽은 이 예외를 잡아
     * "확인 불가"로 답해야 합니다. 미소장으로 표시하면 실제로 있는 책을 없다고
     * 답하게 됩니다.
     */
    public static class ApiErrorException extends RuntimeException {
        private final Data4LibraryResponse.ApiError error;

        ApiErrorException(String endpoint, Data4LibraryResponse.ApiError error) {
            super("정보나루 %s 가 오류를 돌려주었습니다: %s (%s)"
                    .formatted(endpoint, error.message(), error.code()));
            this.error = error;
        }

        public Data4LibraryResponse.ApiError error() { return error; }

        /** 인증키가 아직 활성화되지 않았습니다. 신청 승인을 기다려야 합니다. */
        public boolean isNotActivated() { return "vitalizationErr".equals(error.code()); }

        /** 인증키가 틀렸습니다. */
        public boolean isAuthFailure() { return "authErr".equals(error.code()); }
    }
}
