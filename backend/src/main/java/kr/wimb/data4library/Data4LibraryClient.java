package kr.wimb.data4library;

import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.ingest.ApiBudget;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    public Data4LibraryClient(Transport transport, String authKey, ApiBudget budget) {
        this.transport = transport;
        this.authKey = authKey;
        this.budget = budget;
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
        if (!budget.tryAcquire(SOURCE_CODE, priority)) {
            throw new BudgetExhaustedException(
                    "정보나루의 오늘 호출 예산을 다 썼습니다. 남은 항목은 캐시로만 답해야 합니다.");
        }
        StringBuilder url = new StringBuilder(BASE).append('/').append(endpoint)
                .append("?authKey=").append(encode(authKey));
        params.forEach((key, value) ->
                url.append('&').append(key).append('=').append(encode(value)));
        return transport.get(URI.create(url.toString()));
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
}
