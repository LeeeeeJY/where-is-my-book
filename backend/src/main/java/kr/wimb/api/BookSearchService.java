package kr.wimb.api;

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
import java.util.ArrayList;
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

    /** 한 검색에서 정보나루로부터 받아 묶을 서지 수. 너무 크면 응답이 느려집니다. */
    private static final int FETCH_PAGES = 1;

    private final Data4LibraryClient client;
    private final HoldingsLookup holdingsLookup;
    private final AtomicInteger workIdSequence = new AtomicInteger(1);

    public BookSearchService(Data4LibraryClient client, HoldingsLookup holdingsLookup) {
        this.client = client;
        this.holdingsLookup = holdingsLookup;
    }

    /**
     * @param query        검색어. 제목으로 봅니다.
     * @param regionCodes  선택한 도서관들이 걸친 시도 코드. 비어 있으면 소장 조회를 하지 않습니다.
     * @param selectedLibs 선택한 도서관부호
     */
    public SearchResponse search(String query, List<String> regionCodes, List<String> selectedLibs) {
        List<BookInfo> books = fetchBooks(query);
        if (books.isEmpty()) return new SearchResponse(List.of(), true, null);

        List<SearchDoc> docs = clusterIntoWorks(books);
        Map<String, BookInfo> byIsbn = indexByIsbn(books);

        List<WorkResult> results = new ArrayList<>(docs.size());
        boolean everythingChecked = true;

        for (SearchDoc doc : docs) {
            HoldingResult holdings = holdingsFor(doc, regionCodes, selectedLibs);
            if (!holdings.complete()) everythingChecked = false;
            results.add(WorkResult.of(doc, byIsbn, holdings));
        }

        // 선택한 도서관에 있는 것을 위로 올립니다. 실제로 빌릴 수 있는 책이 먼저 보여야 합니다.
        results.sort((a, b) -> Integer.compare(b.holdingLibCodes().size(), a.holdingLibCodes().size()));
        return new SearchResponse(results, everythingChecked, LocalDate.now().toString());
    }

    private List<BookInfo> fetchBooks(String query) {
        List<BookInfo> books = new ArrayList<>();
        for (int page = 1; page <= FETCH_PAGES; page++) {
            var batch = client.searchBooks(
                    Data4LibraryClient.BookQuery.byTitle(query), page, ApiBudget.Priority.USER);
            books.addAll(batch);
            if (batch.isEmpty()) break;
        }
        // ISBN 을 판별할 수 없는 자료는 소장 조회를 할 수 없으므로 결과에서 뺍니다.
        // 검색은 되는데 어느 도서관에 있는지 영영 알 수 없는 항목이 되기 때문입니다.
        return books.stream().filter(b -> b.canonicalIsbn13().isPresent()).toList();
    }

    /** 정규화와 군집화는 최종 설계와 같은 코드를 씁니다. */
    private List<SearchDoc> clusterIntoWorks(List<BookInfo> books) {
        List<WorkClusterer.Input> inputs = books.stream()
                .map(book -> new WorkClusterer.Input(
                        book.canonicalIsbn13().orElseThrow(),
                        WorkMatcher.Candidate.of(book.bookname(), book.authors(),
                                book.publisher(), publicationYear(book),
                                book.canonicalIsbn13().orElse(null)),
                        null))
                .toList();

        var clustered = WorkClusterer.cluster(inputs, List.of(), workIdSequence::incrementAndGet);

        List<SearchDocBuilder.BookRecord> records = books.stream()
                .map(book -> SearchDocBuilder.BookRecord.of(
                        book.canonicalIsbn13().orElseThrow(),
                        book.bookname(), book.authors(), book.publisher(),
                        null, null))
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
    private HoldingResult holdingsFor(SearchDoc doc, List<String> regionCodes,
                                      List<String> selectedLibs) {
        if (selectedLibs.isEmpty()) return HoldingResult.notRequested();
        if (regionCodes.isEmpty()) {
            // 고른 도서관은 있는데 그 도서관들이 어느 시도에 있는지 하나도 알아내지 못한
            // 경우입니다. libSrchByBook 은 region 이 필수라 조회 자체를 시작할 수 없습니다.
            // 주소가 비어 있거나 도서관 마스터에 없는 부호가 넘어오면 여기에 걸립니다.
            return HoldingResult.cannotAsk();
        }
        return lookupHoldings(doc, regionCodes, selectedLibs);
    }

    private HoldingResult lookupHoldings(SearchDoc doc, List<String> regionCodes,
                                         List<String> selectedLibs) {
        try {
            var result = holdingsLookup.lookup(doc.isbn13List(), regionCodes);
            // 선택한 도서관과 교집합만 남깁니다.
            List<String> matched = selectedLibs.stream()
                    .filter(result.libCodes()::contains)
                    .toList();

            // HoldingsLookup 은 판본 하나가 실패해도 나머지로 답을 만들기 때문에 예외를
            // 던지지 않습니다. 그래서 "한 판본도 확인하지 못했는지"를 여기서 따로 봅니다.
            // 이것을 빠뜨리면 전부 실패한 조회가 unreadable=false 로 나가고,
            // 화면이 그것을 미소장으로 그립니다.
            boolean nothingChecked =
                    result.unresolvedIsbns().size() >= doc.isbn13List().size();
            return new HoldingResult(matched, result.isComplete(), nothingChecked);
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
        static HoldingResult notRequested() {
            return new HoldingResult(List.of(), true, false);
        }

        /** 물어보고 싶었지만 조회를 시작할 수조차 없었던 경우. 반드시 확인 불가입니다. */
        static HoldingResult cannotAsk() {
            return new HoldingResult(List.of(), false, true);
        }
    }

    public record WorkResult(
            int workId,
            String title,
            String author,
            String publisher,
            String coverUrl,
            List<String> isbn13List,
            List<String> editionLabels,
            List<String> holdingLibCodes,
            /** 이 저작의 소장 여부를 빠짐없이 확인했는지. false 면 화면에 밝혀야 합니다. */
            boolean holdingsComplete,
            /** 조회 자체가 실패했는지. true 면 미소장이 아니라 확인 불가입니다. */
            boolean holdingsUnreadable
    ) {
        static WorkResult of(SearchDoc doc, Map<String, BookInfo> byIsbn, HoldingResult holdings) {
            String cover = doc.isbn13List().stream()
                    .map(byIsbn::get)
                    .filter(java.util.Objects::nonNull)
                    .map(BookInfo::bookImageUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst().orElse(null);

            return new WorkResult(doc.workId(), doc.titleDisplay(), doc.authorDisplay(),
                    doc.publisherDisplay(), cover, doc.isbn13List(), doc.editionLabels(),
                    holdings.libCodes(), holdings.complete(), holdings.unreadable());
        }
    }

    /**
     * @param asOf 소장 정보를 조회한 날짜. <b>화면에 반드시 표시해야 합니다.</b>
     *             사용자가 "미소장"을 "확실히 없다"로 읽을지 판단하는 근거입니다.
     */
    public record SearchResponse(List<WorkResult> works, boolean allHoldingsChecked, String asOf) {}
}
