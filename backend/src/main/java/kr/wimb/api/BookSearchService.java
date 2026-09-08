package kr.wimb.api;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Isbn;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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

    /** 빠진 자료를 몇 건까지 실어 보낼지. 전체 건수는 droppedNoIsbn 이 말합니다. */
    private static final int MAX_DROPPED_SHOWN = 20;

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

        // 어절 경계 때문에 놓친 판이 있으면 저자로 되찾습니다.
        int beforeRecovery = found.size();
        found = recoverByAuthor(query, found);
        boolean recovered = found.size() > beforeRecovery;

        List<BookInfo> usable = withIsbn(found);
        List<WorkResult> ranked = rank(
                retried != null ? retried : query.title(), worksOf(usable));
        // **책이 아닌 자료와 ISBN 을 판별하지 못한 책을 갈라 셉니다.** 섞으면 화면이
        // 「DVD 열여섯 건이 빠졌습니다」라고 알리게 되는데, 도움이 되지 않고 불안만 만듭니다.
        List<BookInfo> dropped = found.stream()
                .filter(book -> book.canonicalIsbn13().isEmpty())
                .filter(book -> !Isbn.isNotABookNumber(book.isbn13()))
                .toList();
        return new SearchResponse(
                ranked.stream().limit(MAX_WORKS).toList(),
                ranked.size(),
                found.size(),
                dropped.size(),
                droppedBooksOf(dropped),
                retried,
                recovered,
                LocalDate.now(SEOUL).toString());
    }

    /**
     * ISBN 을 판별하지 못해 뺀 자료가 <b>무엇인지</b>.
     *
     * <p>건수만으로는 사용자가 할 수 있는 일이 없습니다. 「몇 건 빠졌다」는 말은 찾던 책이
     * 하필 그 자료였는지 알려 주지 않으므로, 결국 <b>아무 단서도 없이 사라진 것과
     * 같습니다.</b> 표제와 출판사를 함께 내보내면 적어도 <b>「이 책이구나」 하고 도서관에서
     * 직접 찾아볼 수 있습니다.</b>
     *
     * <p>소장 조회는 ISBN 으로만 되므로 이 자료들을 저작 목록에 넣을 수는 없습니다.
     * <b>「어디에 있는지 모르는 책」과 「고른 도서관에 없는 책」을 섞지 않으려면 목록 바깥에
     * 두고 그 사실을 말해야 합니다.</b>
     *
     * <p><b>책이 아닌 자료는 여기 넣지 않습니다.</b> 정보나루의 도서 검색이 음반과 영상물을
     * 함께 돌려줍니다. 「해리포터」로 찾으면 워너브라더스 DVD 가 열여섯 건, 「레미제라블」로
     * 찾으면 유니버설픽쳐스 블루레이와 OST 가 열다섯 건 섞여 옵니다. 그것들을 세어
     * 알리면 「DVD 가 열여섯 건 빠졌습니다」가 되는데, 도움이 되지 않고 불안만 만듭니다.
     * {@code Isbn.isNotABookNumber} 가 갈라 냅니다.
     *
     * <p>반면 <b>「칼세이건 코스모스」(사이언스북스)처럼 진짜 책인데 정보나루의 ISBN 에
     * 오타가 나 있는 경우가 실제로 있습니다.</b> 체크디지트가 맞지 않아 소장을 물어볼 수
     * 없지만 사용자가 찾던 바로 그 책일 수 있으므로, 이쪽은 반드시 보여 줍니다.
     *
     * <p>많으면 응답만 커지므로 위에서 몇 개만 보냅니다. 전체 건수는 {@code droppedNoIsbn}
     * 이 이미 말하고 있습니다.
     */
    private static List<DroppedBook> droppedBooksOf(List<BookInfo> dropped) {
        return dropped.stream()
                .map(book -> new DroppedBook(
                        book.bookname(), book.authors(), book.publisher(), book.isbn13()))
                .limit(MAX_DROPPED_SHOWN)
                .toList();
    }

    /**
     * 제목으로 찾긴 했는데 <b>그 제목의 책이 하나도 없을 때</b>, 찾은 책의 저자로 한 번 더
     * 찾아 표제가 맞는 것을 더합니다.
     *
     * <p><b>정보나루의 제목 매칭은 어절의 앞에서부터 맞춥니다.</b> 실제로 불러 확인했습니다.
     * 「미제라블」은 「레 미제라블」을 찾아내지만 「제라블」과 「레 미제」는 0건입니다.
     * 그래서 <b>「레미제라블」은 어느 어절과도 맞지 않아 낱권을 한 권도 찾지 못합니다.</b>
     * 민음사에서 붙여 쓴 표기는 세트뿐이라 세트 하나만 걸리고, 사용자에게는 그것이
     * 「낱권이 없다」로 보입니다.
     *
     * <p><b>공백을 어디에 넣어야 하는지는 우리가 알 수 없습니다.</b> 「레미제라블」에서
     * 「레 미제라블」을 만들어 낼 방법이 없고, 앞 글자를 떼는 방법은 제목만으로 찾을 때
     * 196건이 나와 쓸 수 없습니다. 그런데 세트를 통해 <b>저자는 알게 되었으므로</b> 그것으로
     * 되찾습니다. 우리 정규화가 공백을 지우므로 「레미제라블」과 「레 미제라블」의 키가 같아지고,
     * 함께 딸려 온 「파리의 노트르담」은 키가 달라 걸러집니다.
     *
     * <p><b>조건을 두 번 잘못 걸었습니다. 둘 다 실측이 잡았습니다.</b>
     *
     * <p>처음에는 「제목이 그대로 맞은 책이 하나도 없을 때」로 걸었는데 <b>한 번도 발동하지
     * 않았습니다.</b> 「레미제라블」로 찾으면 그 제목의 책이 웅진씽크빅·가나출판사·어문각 등
     * 열다섯 개나 나옵니다. 없는 것은 「그 제목의 책」이 아니라 띄어 쓴 표기의 판이었습니다.
     *
     * <p>다음에는 「붙여 쓴 질의일 때만」으로 걸었는데 <b>반쪽만 고쳐졌습니다.</b>
     * 「레미제라블」은 띄어 쓴 판까지 찾아내게 되었지만, <b>「레 미제라블」로 찾으면 붙여 쓴
     * 판이 한 건도 나오지 않았습니다</b>(실측: 저작 155개 가운데 붙여 쓴 표기 0개).
     * 못 찾는 것은 양쪽 모두입니다.
     *
     * <p>그래서 <b>제목으로 찾을 때는 표기와 무관하게 부릅니다.</b> 제목 검색 한 번에 호출이
     * 하나 늘지만, 그러지 않으면 사용자가 넣은 띄어쓰기에 따라 있는 책이 사라집니다.
     * 저자를 알아내지 못하면 부르지 않으므로 0건 검색에서는 늘지 않습니다.
     */
    private List<BookInfo> recoverByAuthor(Data4LibraryClient.BookQuery query,
                                           List<BookInfo> found) {
        // 저자로 이미 찾고 있으면 되찾을 것이 없습니다.
        if (query.title() == null || query.title().isBlank() || query.author() != null) return found;
        // 한 건도 없으면 저자를 알아낼 수가 없습니다. 그건 respacedTitle 이 맡습니다.
        if (found.isEmpty()) return found;

        String wanted = BibNormalizer.parseTitle(query.title()).titleKeyCore();
        if (wanted.isEmpty()) return found;

        String author = primaryAuthorOf(found);
        if (author == null) return found;

        List<BookInfo> byAuthor;
        try {
            byAuthor = client.searchBooks(
                    new Data4LibraryClient.BookQuery(null, author, query.publisher(), null, false),
                    1, ApiBudget.Priority.USER);
        } catch (RuntimeException e) {
            // 되찾기는 덤입니다. 실패해도 원래 결과를 그대로 내보냅니다.
            return found;
        }

        Set<String> seen = found.stream()
                .map(b -> b.canonicalIsbn13().orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<BookInfo> merged = new ArrayList<>(found);
        for (BookInfo book : byAuthor) {
            if (!wanted.equals(titleKeyOf(book))) continue;
            String isbn = book.canonicalIsbn13().orElse(null);
            if (isbn == null || !seen.add(isbn)) continue;
            merged.add(book);
        }
        return merged;
    }

    private static String titleKeyOf(BookInfo book) {
        return BibNormalizer.parseTitle(book.bookname()).titleKeyCore();
    }

    /** 찾은 책들에서 가장 자주 나오는 주저자 표기. 정보나루에 그대로 넘길 값입니다. */
    private static String primaryAuthorOf(List<BookInfo> found) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BookInfo book : found) {
            var primary = BibNormalizer.primaryAuthor(
                    BibNormalizer.parseContributors(book.authors()));
            if (primary == null || primary.name() == null || primary.name().isBlank()) continue;
            counts.merge(primary.name().trim(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
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
     * @param foundBooks 정보나루가 돌려준 서지 건수. <b>우리가 거르기 전의 숫자입니다.</b>
     *                   찾는 책이 안 나올 때 이 숫자 하나로 어디를 봐야 하는지 갈립니다.
     *                   0이면 정보나루가 못 찾은 것이고, 0이 아닌데 저작이 0이면 우리가
     *                   버린 것이고, 저작이 있는데 화면에 없으면 화면 문제입니다.
     *                   <b>이것이 없으면 셋을 구별할 방법이 없어 추측하게 됩니다.</b>
     *                   실제로 「마의 산」이 안 나오는 이유를 두고 여러 번 헛짚었습니다.
     * @param droppedNoIsbn ISBN 을 판별할 수 없어 결과에서 뺀 자료 수. <b>화면이 이것을
     *                      밝혀야 합니다.</b> 조용히 빼면 사용자는 「그런 책이 없다」로 읽습니다.
     * @param retriedTitle 처음 제목으로 한 건도 못 찾아 <b>띄어쓰기를 달리해 다시 찾은</b>
     *                     경우 그 제목. 화면이 이것을 밝혀야 사용자가 자기가 넣은 것과
     *                     다른 결과를 보고 어리둥절하지 않습니다. 재시도가 없었으면 null.
     * @param asOf 이 검색을 한 날짜.
     */
    /**
     * @param recoveredByAuthor 제목으로는 걸리지 않던 판을 <b>저자로 되찾아 더했는지.</b>
     *                          화면이 이 사실을 밝혀야 합니다. 사용자가 넣은 제목과 다른
     *                          표기의 책이 목록에 섞여 있는 것이므로, 말하지 않으면 검색이
     *                          엉뚱한 것을 가져왔다고 읽힙니다.
     */
    /**
     * ISBN 을 판별하지 못해 뺀 자료 한 건.
     *
     * @param rawIsbn13 정보나루가 준 원문. 비어 있는지 잘못된 값인지 갈라 보려면 이것이
     *                  있어야 합니다. <b>없으면 왜 빠졌는지 영영 알 수 없습니다.</b>
     */
    public record DroppedBook(String title, String author, String publisher, String rawIsbn13) {}

    public record SearchResponse(List<WorkResult> works, int totalWorks, int foundBooks,
                                 int droppedNoIsbn, List<DroppedBook> droppedBooks,
                                 String retriedTitle,
                                 boolean recoveredByAuthor, String asOf) {}
}
