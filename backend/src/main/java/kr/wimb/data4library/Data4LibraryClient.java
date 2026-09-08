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
 * <p>{@code bookExist}(11절)는 쓰지 않습니다. 대출 가능 여부를 돌려주는데, 그 값이 전날
 * 기준이라 믿고 갔다가 헛걸음하는 것이 이 도구를 못 쓰게 만드는 가장 큰 요인입니다.
 */
public final class Data4LibraryClient {

    public static final String SOURCE_CODE = "DATA4LIBRARY_API";
    private static final String BASE = "https://data4library.kr/api";

    /** 한 번에 받을 수 있는 최대치가 문서에 없어, 예시에 나온 300 을 넘지 않게 잡았습니다. */
    private static final int PAGE_SIZE = 300;

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
     * @param exactMatch 일치 검색 여부
     */
    public record BookQuery(String title, String author, String publisher,
                            String isbn13, boolean exactMatch) {

        public static BookQuery byTitle(String title) {
            return new BookQuery(title, null, null, null, false);
        }

        public static BookQuery byIsbn(String isbn13) {
            return new BookQuery(null, null, null, isbn13, true);
        }

        Map<String, String> toParams() {
            Map<String, String> params = new LinkedHashMap<>();
            putIfPresent(params, "title", title);
            putIfPresent(params, "author", author);
            putIfPresent(params, "publisher", publisher);
            putIfPresent(params, "isbn13", isbn13);
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
     * 이 ISBN 을 소장한 도서관. <b>{@code region} 은 매뉴얼상 필수입니다.</b>
     * 전국을 한 번에 받는 방법이 없어 시도마다 따로 불러야 합니다.
     */
    public List<LibraryInfo> librariesHolding(String isbn13, String regionCode,
                                              ApiBudget.Priority priority) {
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException(
                    "libSrchByBook 은 region 이 필수입니다. 매뉴얼 13절을 보세요.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("isbn", isbn13);
        params.put("region", regionCode);
        params.put("pageSize", String.valueOf(PAGE_SIZE));

        String xml = call("libSrchByBook", params, priority);
        return Data4LibraryResponse.items(xml, "lib").stream().map(LibraryInfo::from).toList();
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

    /** 사람이 고쳐야 낫는 오류인지. 그 밖의 오류는 일시적일 수 있으므로 막지 않습니다. */
    private static boolean isAuthClass(Data4LibraryResponse.ApiError error) {
        return "authErr".equals(error.code()) || "vitalizationErr".equals(error.code());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
