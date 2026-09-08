package kr.wimb.api;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.WorkClusterer;
import kr.wimb.bib.WorkMatcher;
import kr.wimb.data4library.BookInfo;
import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.holdings.HoldingsLookup;
import kr.wimb.index.SearchDoc;
import kr.wimb.index.SearchDocBuilder;
import kr.wimb.ingest.ApiBudget;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 검색어 하나를 받아 저작 단위 결과로 만듭니다.
 *
 * <p><b>지금은 검색할 때마다 정보나루에서 서지를 받아 그 자리에서 묶습니다.</b>
 * 최종 설계는 서지를 미리 적재해 색인에서 검색하는 것인데, 그 적재를 아직 돌리지 않았으므로
 * 같은 정규화·군집화 코드를 조회 시점에 쓰는 방식으로 먼저 동작하게 했습니다.
 *
 * <p>정규화와 저작 묶기는 최종 설계와 완전히 같은 코드를 씁니다. 바뀌는 것은 서지가
 * 어디서 오는지뿐이라, 적재를 돌리기 시작하면 이 클래스의 {@code fetchBooks} 만 색인 조회로
 * 갈아 끼우면 됩니다.
 */
@Service
public class BookSearchService {

    /** 표시는 전부 한국 시각 기준입니다. 저장은 UTC 이지만 사용자가 보는 날짜는 여기입니다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 한 검색에서 정보나루로부터 받아 묶을 서지 수. 너무 크면 응답이 느려집니다. */
    private static final int FETCH_PAGES = 1;

    /**
     * 화면에 돌려줄 저작 수.
     *
     * <p>예전에는 20이었습니다. 화면이 받은 것을 전부 그리고 저작마다 소장을 물어봤기
     * 때문에, <b>이 숫자가 곧 검색 한 번에 나가는 소장 조회 횟수</b>였습니다.
     *
     * <p>이제는 화면이 20개씩 나눠 보여 주고 <b>펼친 것만 소장을 물어봅니다.</b> 그래서
     * 이 숫자를 늘려도 조회 횟수는 늘지 않습니다. 늘리는 대신 정보나루를 다시 부르지 않고
     * 「더 보기」를 즉시 처리할 수 있습니다. 300건을 묶으면 저작이 100~200개쯤 되므로
     * 100이면 대부분의 검색에서 끝까지 넘겨볼 수 있습니다.
     */
    private static final int MAX_WORKS = 100;

    private final Data4LibraryClient client;
    private final HoldingsLookup holdingsLookup;
    private final AtomicInteger workIdSequence = new AtomicInteger(1);

    public BookSearchService(Data4LibraryClient client, HoldingsLookup holdingsLookup) {
        this.client = client;
        this.holdingsLookup = holdingsLookup;
    }

    /** 제목만으로 찾는 지름길. */
    public SearchResponse search(String query) {
        return search(Data4LibraryClient.BookQuery.byTitle(query));
    }

    /**
     * 조건으로 저작 목록을 만듭니다. <b>소장 조회는 하지 않습니다.</b>
     *
     * <p>예전에는 여기서 저작마다 {@code libSrchByBook} 을 불렀습니다. 저작이 200개면 호출도
     * 200번이고, 요청 간격이 120ms 라 그것만으로 24초가 걸렸습니다. 사용자는 그것을
     * 「검색이 안 된다」로 읽습니다.
     *
     * <p>그래서 여러 권 확인과 같은 방식으로 나눴습니다. 책 목록을 즉시 돌려주고, 소장은
     * 화면이 {@code POST /api/holdings} 로 한 권씩 물어 도착하는 대로 채웁니다.
     * <b>여기에 소장 조회를 다시 넣지 마세요.</b> 넣는 순간 검색이 다시 수십 초가 됩니다.
     *
     * <p>정렬 기준은 <b>제목</b>입니다. 제목 없이 저자나 출판사로만 찾으면 우리가 더 나은
     * 근거를 갖고 있지 않으므로 정보나루가 준 순서를 그대로 둡니다.
     */
    public SearchResponse search(Data4LibraryClient.BookQuery query) {
        List<BookInfo> found = client.searchBooks(query, 1, ApiBudget.Priority.USER);

        // 한 건도 못 찾았으면 띄어쓰기를 달리해 한 번만 더 찾아봅니다. 자세한 이유는
        // respacedTitle 에 적어 두었습니다. 0건일 때만이라 호출이 곱해지지 않습니다.
        String retried = found.isEmpty() ? respacedTitle(query.title()) : null;
        if (retried != null) {
            found = client.searchBooks(query.withTitle(retried), 1, ApiBudget.Priority.USER);
            if (found.isEmpty()) retried = null;
        }

        List<BookInfo> usable = withIsbn(found);
        List<WorkResult> ranked = rank(
                retried != null ? retried : query.title(), worksOf(usable));
        return new SearchResponse(
                ranked.stream().limit(MAX_WORKS).toList(),
                ranked.size(),
                found.size() - usable.size(),
                retried,
                LocalDate.now(SEOUL).toString());
    }

    /**
     * 띄어쓰기를 달리한 제목. 다시 찾아볼 값이 없으면 {@code null} 입니다.
     *
     * <p><b>정보나루는 넣은 글자를 그대로 찾습니다.</b> 우리 정규화({@code normalizeKey})는
     * 받아온 뒤 순위를 매길 때만 쓰이므로 검색 자체에는 아무 영향이 없습니다. 그래서
     * 「마의 산」과 「마의산」이 서로 다른 검색이 되고, 도서관마다 표기가 갈리는 한국어
     * 서명에서는 한쪽으로만 찾으면 멀쩡히 있는 책을 놓칩니다.
     *
     * <p>사용자에게는 그것이 <b>「그런 책이 없다」로 보입니다.</b> 띄어쓰기를 바꿔 보라고
     * 안내만 하는 것은 우리가 할 수 있는 일을 사용자에게 미루는 것입니다.
     */
    static String respacedTitle(String title) {
        if (title == null) return null;
        String compact = title.replaceAll("\\s+", "");
        return compact.isEmpty() || compact.equals(title.trim()) ? null : compact;
    }

    /**
     * 제목이 검색어에 얼마나 맞는지로 다시 세우고 위에서 몇 개만 남깁니다.
     *
     * <p>정보나루가 주는 순서를 그대로 쓰면 「코스모스」를 찾았는데 「미크로코스모스 입문」이
     * 1등으로 나옵니다. 찾으려던 책이 안 보이는 것이 이 도구를 버리게 만드는 가장 큰
     * 이유이므로, 적어도 제목이 그대로 맞는 것은 위로 올립니다.
     *
     * <p>정규화는 {@link BibNormalizer#normalizeKey}를 씁니다. <b>적재·군집화와 같은 함수를
     * 써야</b> 「해리 포터」와 「해리포터」가 검색에서만 어긋나는 일이 없습니다.
     *
     * <p>같은 등급 안에서는 정보나루가 준 순서를 그대로 둡니다. 정렬이 안정적이라 그렇게
     * 되고, 우리가 더 나은 근거를 갖고 있지 않으므로 굳이 흔들지 않습니다.
     *
     * <p><b>여기서 자르지 않습니다.</b> 자르는 것은 부르는 쪽의 몫입니다. 그래야 전체가
     * 몇 개인지 셀 수 있고, 화면이 「n개 중 20개」라고 말할 수 있습니다.
     */
    static List<WorkResult> rank(String query, List<WorkResult> works) {
        String queryKey = BibNormalizer.normalizeKey(query == null ? "" : query);
        if (queryKey.isEmpty()) return works;
        return works.stream()
                .sorted(Comparator.comparingInt(work -> titleTier(queryKey, work.title())))
                .toList();
    }

    /** 작을수록 검색어에 잘 맞습니다. */
    private static int titleTier(String queryKey, String title) {
        String titleKey = BibNormalizer.normalizeKey(title == null ? "" : title);
        if (titleKey.equals(queryKey)) return 0;   // 「코스모스」 -> 「코스모스」
        if (titleKey.startsWith(queryKey)) return 1; // 「코스모스 : 특별판」
        if (titleKey.contains(queryKey)) return 2;   // 「뽐내는 코스모스」
        return 3;                                     // 저자나 출판사만 걸린 것
    }

    /**
     * 서지를 받아 저작 단위로 묶습니다. <b>소장 조회는 하지 않습니다.</b>
     *
     * <p>여러 권 확인 화면이 이것을 먼저 부르고 화면을 그린 뒤, 소장 조회를 따로 부릅니다.
     * 그래야 사용자가 빈 화면을 보며 기다리지 않습니다.
     */
    public List<WorkResult> worksFor(Data4LibraryClient.BookQuery query) {
        List<BookInfo> books = new ArrayList<>();
        for (int page = 1; page <= FETCH_PAGES; page++) {
            var batch = client.searchBooks(query, page, ApiBudget.Priority.USER);
            books.addAll(batch);
            if (batch.isEmpty()) break;
        }
        return worksOf(withIsbn(books));
    }

    private List<WorkResult> worksOf(List<BookInfo> books) {
        if (books.isEmpty()) return List.of();

        Map<String, BookInfo> byIsbn = indexByIsbn(books);
        return clusterIntoWorks(books).stream()
                .map(doc -> WorkResult.of(doc, byIsbn))
                .toList();
    }

    /**
     * ISBN 을 판별할 수 없는 자료를 빼냅니다. 소장 조회가 ISBN 으로만 되기 때문에,
     * 남겨 두면 검색은 되는데 어느 도서관에 있는지 영영 알 수 없는 항목이 됩니다.
     *
     * <p><b>몇 건을 뺐는지는 세어서 화면까지 올립니다.</b> 조용히 빼면 사용자는 그것을
     * 「그런 책이 없다」로 읽습니다. 찾던 책이 하필 그 자료였을 때 아무 단서도 없이
     * 사라지는 것이라, 이 도구가 지키려는 「없다와 모른다를 섞지 않는다」와 같은 문제입니다.
     */
    private static List<BookInfo> withIsbn(List<BookInfo> books) {
        return books.stream().filter(b -> b.canonicalIsbn13().isPresent()).toList();
    }

    /** 정규화와 군집화는 최종 설계와 같은 코드를 씁니다. */
    private List<SearchDoc> clusterIntoWorks(List<BookInfo> books) {
        List<WorkClusterer.Input> inputs = books.stream()
                .map(book -> new WorkClusterer.Input(
                        book.canonicalIsbn13().orElseThrow(),
                        // **권차를 반드시 함께 넘깁니다.** 정보나루는 이것을 vol 로 따로
                        // 주는데, 빠뜨리면 「레미제라블」 1~5권이 표제가 같아 한 저작으로
                        // 합쳐집니다. 그러면 낱권을 고를 수 없고, 1권만 있는 도서관이
                        // 「레미제라블 있음」으로 나옵니다.
                        WorkMatcher.Candidate.of(book.bookname(), book.authors(),
                                book.publisher(), publicationYear(book),
                                book.canonicalIsbn13().orElse(null),
                                book.volumeNumber().orElse(null)),
                        null))
                .toList();

        var clustered = WorkClusterer.cluster(inputs, List.of(), workIdSequence::incrementAndGet);

        List<SearchDocBuilder.BookRecord> records = books.stream()
                .map(book -> SearchDocBuilder.BookRecord.of(
                        book.canonicalIsbn13().orElseThrow(),
                        book.bookname(), book.authors(), book.publisher(),
                        null, null, book.volumeNumber().orElse(null)))
                .toList();
        return SearchDocBuilder.build(records, clustered.workIdByRecord());
    }

    /**
     * 조회하지 않은 것과 조회하지 못한 것을 여기서 가릅니다.
     *
     * <p><b>둘을 한 갈래로 묶으면 안 됩니다.</b> 어느 쪽이든 소장 도서관 목록이 비어 있지만,
     * 앞은 물어볼 필요가 없었던 것이고 뒤는 물어보지 못한 것입니다. 뒤를 빈 결과로 돌려주면
     * 화면이 그것을 "고른 도서관에는 없습니다"로 그리게 되어, 실제로 있는 책을 없다고
     * 답하게 됩니다.
     */
    /** 물어볼 수 없는 도서관이 없는 경우. 기존 호출부와 시험을 위한 지름길입니다. */
    public HoldingResult holdingsOf(List<String> isbn13List, List<String> regionCodes,
                                    List<String> selectedLibs) {
        return holdingsOf(isbn13List, regionCodes, selectedLibs, 0);
    }

    /**
     * @param unaskableLibs 고른 도서관 가운데 <b>시도를 알아내지 못해 물어볼 수조차 없는</b>
     *                      곳의 수. 0이 아니면 빠짐없이 확인한 것이 아니므로 절대
     *                      {@code complete} 로 답하면 안 됩니다. 물어보지 않은 도서관은
     *                      결과에 있을 수 없어 그대로 「없다」로 나가고, 실제로 소장한
     *                      책을 놓치게 됩니다.
     */
    public HoldingResult holdingsOf(List<String> isbn13List, List<String> regionCodes,
                                    List<String> selectedLibs, int unaskableLibs) {
        if (selectedLibs.isEmpty()) return HoldingResult.notRequested();
        if (isbn13List.isEmpty()) {
            // 조회할 판본이 하나도 없으면 확인한 것이 아닙니다.
            return HoldingResult.cannotAsk();
        }
        if (regionCodes.isEmpty()) {
            // 고른 도서관은 있는데 그 도서관들이 어느 시도에 있는지 하나도 알아내지 못한
            // 경우입니다. libSrchByBook 은 region 이 필수라 조회 자체를 시작할 수 없습니다.
            // 주소가 비어 있거나 도서관 마스터에 없는 부호가 넘어오면 여기에 걸립니다.
            return HoldingResult.cannotAsk();
        }
        return lookupHoldings(isbn13List, regionCodes, selectedLibs, unaskableLibs);
    }

    private HoldingResult lookupHoldings(List<String> isbn13List, List<String> regionCodes,
                                         List<String> selectedLibs, int unaskableLibs) {
        try {
            var result = holdingsLookup.lookup(isbn13List, regionCodes);
            // 선택한 도서관과 교집합만 남깁니다.
            List<String> matched = selectedLibs.stream()
                    .filter(result.libCodes()::contains)
                    .toList();

            // HoldingsLookup 은 판본 하나가 실패해도 나머지로 답을 만들기 때문에 예외를
            // 던지지 않습니다. 그래서 "한 판본도 확인하지 못했는지"를 여기서 따로 봅니다.
            // 이것을 빠뜨리면 전부 실패한 조회가 unreadable=false 로 나가고,
            // 화면이 그것을 미소장으로 그립니다.
            boolean nothingChecked = result.unresolvedIsbns().size() >= isbn13List.size();
            // 물어볼 수 없었던 도서관이 하나라도 있으면 빠짐없이 확인한 것이 아닙니다.
            // 이것을 빠뜨리면 그 도서관이 조용히 「없음」으로 나가고, 화면은 그것을
            // 미소장으로 그립니다. 물어보지 않고 없다고 답하는 것입니다.
            boolean complete = result.isComplete() && unaskableLibs == 0;
            return new HoldingResult(matched, complete, nothingChecked);
        } catch (RuntimeException e) {
            // 조회 실패는 미소장이 아닙니다. 화면에 "확인 불가"로 표시해야 합니다.
            return new HoldingResult(List.of(), false, true);
        }
    }

    private static Map<String, BookInfo> indexByIsbn(List<BookInfo> books) {
        Map<String, BookInfo> out = new LinkedHashMap<>();
        for (BookInfo book : books) {
            book.canonicalIsbn13().ifPresent(isbn -> out.putIfAbsent(isbn, book));
        }
        return out;
    }

    private static Integer publicationYear(BookInfo book) {
        try {
            return book.publicationYear() == null ? null : Integer.valueOf(book.publicationYear());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param complete   모든 판본을 빠짐없이 확인했는지
     * @param unreadable 조회 자체가 실패했는지. 미소장과 반드시 구분해야 합니다.
     */
    public record HoldingResult(List<String> libCodes, boolean complete, boolean unreadable) {
        /** 고른 도서관이 없어 물어볼 필요가 없었던 경우. 미소장이 아닙니다. */
        public static HoldingResult notRequested() {
            return new HoldingResult(List.of(), true, false);
        }

        /** 물어보고 싶었지만 조회를 시작할 수조차 없었던 경우. 반드시 확인 불가입니다. */
        public static HoldingResult cannotAsk() {
            return new HoldingResult(List.of(), false, true);
        }
    }

    public record WorkResult(
            int workId,
            String title,
            String author,
            String publisher,
            String coverUrl,
            /**
             * 정보나루의 그 책 상세 페이지. 도서관 주소 규칙이 없을 때 홈페이지 대신
             * 여기로 보냅니다. 홈페이지는 그 책에 대해 아무것도 말해 주지 않습니다.
             */
            String detailUrl,
            List<String> isbn13List,
            List<String> editionLabels
    ) {
        static WorkResult of(SearchDoc doc, Map<String, BookInfo> byIsbn) {
            String cover = firstNonBlank(doc, byIsbn, BookInfo::bookImageUrl);
            String detail = firstNonBlank(doc, byIsbn, BookInfo::bookDetailUrl);

            return new WorkResult(doc.workId(), doc.titleDisplay(), doc.authorDisplay(),
                    doc.publisherDisplay(), toHttps(cover), toHttps(detail),
                    doc.isbn13List(), doc.editionLabels());
        }

        private static String firstNonBlank(SearchDoc doc, Map<String, BookInfo> byIsbn,
                                            java.util.function.Function<BookInfo, String> field) {
            return doc.isbn13List().stream()
                    .map(byIsbn::get)
                    .filter(java.util.Objects::nonNull)
                    .map(field)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
        }

        /**
         * <b>표지 주소가 {@code http://} 로 옵니다.</b> 사이트는 HTTPS 라서 브라우저가
         * 이런 자원을 조용히 막습니다(혼합 콘텐츠). 오류도 뜨지 않고 빈자리만 남기 때문에
         * 「표지가 원래 없나 보다」로 보입니다. 실제로 그 상태로 돌고 있었습니다.
         *
         * <p>주소만 바꿔서 안 열리는 서버가 있을 수 있으므로 화면에서 실패를 감춥니다.
         */
        private static String toHttps(String url) {
            if (url == null || !url.startsWith("http://")) return url;
            return "https://" + url.substring("http://".length());
        }

    }

    /**
     * <b>소장 항목이 없는 것이 의도적입니다.</b> 소장은 {@code POST /api/holdings} 한 곳에서만
     * 답합니다. 검색 응답에도 소장을 실으면 「물어본 적 없음」과 「물어봤는데 없음」이 같은
     * 빈 목록으로 나가고, 화면이 그것을 「고른 도서관에는 없습니다」로 그리게 됩니다.
     * 실제로 있는 책을 없다고 답하는 것이라 이 도구의 전제가 무너집니다.
     *
     * @param works 위에서 {@value #MAX_WORKS} 개까지. 화면이 이것을 20개씩 나눠 보여 줍니다.
     * @param totalWorks 자르기 전의 전체 저작 수. 화면이 「n개 중 몇 개를 보고 있는지」를
     *                   말하려면 필요합니다. 이것이 없으면 사용자는 지금 보는 것이 전부인지
     *                   잘린 것인지 알 수 없습니다.
     * @param droppedNoIsbn ISBN 을 판별할 수 없어 결과에서 뺀 자료 수. <b>화면이 이것을
     *                      밝혀야 합니다.</b> 조용히 빼면 사용자는 「그런 책이 없다」로 읽습니다.
     * @param retriedTitle 처음 제목으로 한 건도 못 찾아 <b>띄어쓰기를 달리해 다시 찾은</b>
     *                     경우 그 제목. 화면이 이것을 밝혀야 사용자가 자기가 넣은 것과
     *                     다른 결과를 보고 어리둥절하지 않습니다. 재시도가 없었으면 null.
     * @param asOf 이 검색을 한 날짜.
     */
    public record SearchResponse(List<WorkResult> works, int totalWorks, int droppedNoIsbn,
                                 String retriedTitle, String asOf) {}
}
