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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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

    /**
     * 입력 그대로의 검색에서 받아 올 최대 쪽 수. <b>첫 쪽이 가득 찼을 때만</b> 둘째 쪽을 받습니다.
     *
     * <p>정보나루는 대출건수 순으로 돌려주므로 많이 읽히는 판은 첫 쪽에 있습니다. 그런데 소장
     * 조회는 저작에 묶인 ISBN 전체로 나가고, <b>목록에 없는 판은 물어보지도 못합니다.</b>
     * 「코스모스」처럼 300건이 넘는 제목에서 옛 판이 둘째 쪽에 있으면, 그 판만 가진 도서관이
     * 「없음」으로 나갑니다. 한 쪽을 더 받는 값으로 그 구멍을 절반으로 줄입니다. 첫 쪽이 덜
     * 찼으면 더 받을 것이 없으므로 부르지 않습니다.
     */
    private static final int MAX_PAGES_AS_TYPED = 2;

    /** 결과에서 발견한 다른 띄어쓰기 표기로 다시 찾아볼 상한. 호출이 곱해지지 않게 묶어 둡니다. */
    private static final int MAX_ALTERNATE_SPELLINGS = 2;

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
    /** 한 번에 돌려주는 저작 수. 여러 권 검색의 후보도 같은 상한을 씁니다. */
    static final int MAX_WORKS = 100;

    /** 빠진 자료를 몇 건까지 실어 보낼지. 전체 건수는 droppedNoIsbn 이 말합니다. */
    private static final int MAX_DROPPED_SHOWN = 20;

    /**
     * 한 소장 조회에서 물어볼 판본 수 상한. 한 요청이 하루 예산을 통째로 쓰지 못하게 막습니다.
     *
     * <p>넘치면 <b>거절하지 않고 앞의 것만 묻되 빠짐없이 확인했다고 하지 않습니다.</b> 예전에는
     * 400 으로 거절했는데, 화면은 그것을 「확인 불가」로 그려 판본이 많은 책일수록 아무것도
     * 알려 주지 못했습니다. 일부라도 확인해서 찾았으면 「있음」이고, 못 찾았으면 「확인하지
     * 못한 판본이 있다」로 나가는 편이 사람에게 쓸모가 있습니다.
     */
    public static final int MAX_EDITIONS_PER_LOOKUP = 20;

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
     * 조건으로 서지를 받아 옵니다.
     *
     * <p><b>한 권 검색과 여러 권 확인이 같은 코드를 쓰게 하려고 여기 하나로 모읍니다.</b>
     * 예전에는 띄어쓰기 재시도와 저자 되찾기가 {@code search} 안에만 있었고,
     * {@code worksFor} 는 그냥 받아 왔습니다. 그래서 <b>여러 권 확인에서는 「레미제라블」로
     * 민음사 「레 미제라블」 낱권을 한 권도 찾지 못했습니다.</b> 같은 검색어인데 화면에 따라
     * 결과가 달랐던 것입니다. 개선이 한쪽 경로에만 들어가면 반드시 이렇게 갈립니다.
     *
     * <p><b>띄어쓰기가 다른 검색은 같은 결과를 받아야 합니다.</b> 정보나루는 넣은 글자를 어절
     * 단위로 그대로 찾으므로 「레미제라블」과 「레 미제라블」이 서로 다른 검색이고, 예전에는
     * 어느 쪽으로 넣었는지에 따라 목록이 달랐습니다. 사용자에게는 그것이 「어떤 때는 있고
     * 어떤 때는 없는 책」으로 보입니다. 그래서 정규화 키가 같은 표기는 <b>모두</b> 찾아 합칩니다.
     *
     * <ol>
     *   <li>입력 그대로. 첫 쪽이 가득 찼으면 둘째 쪽까지.</li>
     *   <li>띄어쓰기를 뺀 표기. 예전에는 0건일 때만 했는데, 세트 한 건이 걸려 0건이 아닌
     *       「레미제라블」에서 발동하지 않았습니다. 입력에 공백이 있으면 늘 함께 찾습니다.</li>
     *   <li>찾은 책의 저자로 되찾기. 공백을 어디에 넣어야 하는지는 우리가 알 수 없지만,
     *       저자는 첫 검색으로 알게 되므로 그것으로 표제 키가 같은 판을 더합니다.</li>
     *   <li>그렇게 모인 결과에서 <b>같은 글자를 달리 띄어 쓴 표기</b>를 발견하면 그 표기로도
     *       찾습니다. 「레미제라블」로 시작했더라도 되찾기가 「레 미제라블」을 보여 주면 그
     *       표기로 한 번 더 찾아, 「레 미제라블」로 시작한 사람과 같은 목록을 받습니다.</li>
     * </ol>
     *
     * <p>2~4는 덤이라 실패해도 1의 결과를 그대로 내보냅니다. 1이 실패하면 검색 전체가 실패한
     * 것이고, 화면은 그것을 「확인 불가」로 그립니다.
     *
     * <p><b>서로 기다릴 필요가 없는 호출은 동시에 내보냅니다.</b> 정보나루의 서지 검색은 한 번에
     * 3~4초가 걸려서, 넷을 차례로 부르면 그것만으로 15초입니다. 입력 그대로와 붙여 쓴 표기는
     * 서로 무관하므로 1회전에 함께, 둘째 쪽과 저자 되찾기는 첫 쪽의 결과가 있어야 하므로 2회전에
     * 함께, 다른 표기들은 3회전에 함께 부릅니다. 회전 셋이면 최대 10초 안팎, 보통은 두 회전으로
     * 끝납니다.
     */
    private Fetched fetchBooks(Data4LibraryClient.BookQuery query) {
        Collected found = new Collected();
        List<String> alsoSearched = new ArrayList<>();
        List<String> searchedSpellings = new ArrayList<>();
        if (query.title() != null) searchedSpellings.add(query.title());
        String compact = respacedTitle(query.title());
        if (compact != null) searchedSpellings.add(compact);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 1회전: 입력 그대로의 첫 쪽과 띄어쓰기를 뺀 표기.
            Future<List<BookInfo>> firstPage = executor.submit(
                    () -> client.searchBooks(query, 1, ApiBudget.Priority.USER));
            Future<Optional<List<BookInfo>>> compactBooks = compact == null ? null
                    : executor.submit(() -> searchQuietly(query.withTitle(compact)));

            List<BookInfo> first = await(firstPage);
            found.addAll(first);
            if (compactBooks != null) {
                await(compactBooks).ifPresent(books -> {
                    found.addAll(books);
                    alsoSearched.add(compact);
                });
            }

            // 2회전: 첫 쪽이 가득 찼으면 둘째 쪽, 그리고 저자로 되찾기.
            Future<Optional<List<BookInfo>>> secondPage =
                    first.size() >= Data4LibraryClient.PAGE_SIZE && MAX_PAGES_AS_TYPED >= 2
                            ? executor.submit(() -> searchQuietly(query, 2)) : null;
            Data4LibraryClient.BookQuery authorQuery = authorQueryFor(query, found);
            Future<Optional<List<BookInfo>>> byAuthor = authorQuery == null ? null
                    : executor.submit(() -> searchQuietly(authorQuery));

            if (secondPage != null) await(secondPage).ifPresent(found::addAll);
            boolean recovered = byAuthor != null
                    && await(byAuthor).map(books -> addRecovered(query, found, books)).orElse(false);

            // 3회전: 결과에서 본 다른 띄어쓰기 표기.
            List<String> spellings = alternateSpellings(query.title(), found.books(), searchedSpellings);
            List<Future<Optional<List<BookInfo>>>> bySpelling = new ArrayList<>();
            for (String spelling : spellings) {
                bySpelling.add(executor.submit(() -> searchQuietly(query.withTitle(spelling))));
            }
            for (int i = 0; i < spellings.size(); i++) {
                String spelling = spellings.get(i);
                await(bySpelling.get(i)).ifPresent(books -> {
                    found.addAll(books);
                    alsoSearched.add(spelling);
                });
            }

            return new Fetched(found.books(), List.copyOf(alsoSearched), recovered);
        }
    }

    /** 덤 호출의 결과를 기다립니다. 입력 그대로의 첫 쪽이 실패한 예외는 그대로 올립니다. */
    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("검색이 중단되었습니다.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("검색에 실패했습니다.", cause);
        }
    }

    /** {@link #fetchBooks} 의 결과. 무엇을 더 해서 찾았는지까지 함께 들고 다닙니다. */
    private record Fetched(List<BookInfo> books, List<String> alsoSearchedTitles, boolean recovered) {}

    /** 덤으로 하는 검색. 실패하면 비어 있는 값을 주고, 부른 쪽은 원래 결과를 그대로 씁니다. */
    private Optional<List<BookInfo>> searchQuietly(Data4LibraryClient.BookQuery query) {
        return searchQuietly(query, 1);
    }

    private Optional<List<BookInfo>> searchQuietly(Data4LibraryClient.BookQuery query, int page) {
        try {
            return Optional.of(client.searchBooks(query, page, ApiBudget.Priority.USER));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public SearchResponse search(Data4LibraryClient.BookQuery query) {
        Fetched fetched = fetchBooks(query);
        List<BookInfo> found = fetched.books();

        List<BookInfo> usable = withIsbn(found);
        List<WorkResult> ranked = rank(query.title(), worksOf(usable));
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
                fetched.alsoSearchedTitles(),
                fetched.recovered(),
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
     * 제목으로 찾은 뒤, 찾은 책의 저자로 한 번 더 찾아 표제가 맞는 것을 더합니다.
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
     *
     * @return 저자로 되찾을 질의. 되찾을 수 없으면 null 입니다.
     */
    private static Data4LibraryClient.BookQuery authorQueryFor(Data4LibraryClient.BookQuery query,
                                                                Collected found) {
        // 저자로 이미 찾고 있으면 되찾을 것이 없습니다.
        if (query.title() == null || query.title().isBlank() || query.author() != null) return null;
        // 한 건도 없으면 저자를 알아낼 수가 없습니다.
        if (found.isEmpty()) return null;
        if (BibNormalizer.comparisonKey(query.title()).isEmpty()) return null;

        String author = primaryAuthorOf(found.books());
        if (author == null) return null;
        return new Data4LibraryClient.BookQuery(null, author, query.publisher(), null, false);
    }

    /**
     * 저자로 찾은 것 가운데 표제 키가 맞는 것만 더합니다.
     *
     * @return 되찾아 더한 것이 있는지. 화면이 이 사실을 밝힙니다.
     */
    private static boolean addRecovered(Data4LibraryClient.BookQuery query, Collected found,
                                        List<BookInfo> byAuthor) {
        String wanted = BibNormalizer.comparisonKey(query.title());
        boolean added = false;
        for (BookInfo book : byAuthor) {
            if (!wanted.equals(titleKeyOf(book))) continue;
            // 소장을 물어볼 수 없는 자료는 되찾아도 쓸 곳이 없습니다.
            if (book.canonicalIsbn13().isEmpty()) continue;
            if (found.add(book)) added = true;
        }
        return added;
    }

    private static String titleKeyOf(BookInfo book) {
        return BibNormalizer.comparisonKey(book.bookname());
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
     * 띄어쓰기를 뺀 제목. 다시 찾아볼 값이 없으면 {@code null} 입니다.
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
     * 찾은 책들 가운데 <b>검색어와 글자는 같은데 띄어쓰기만 다른</b> 표제 표기를 골라냅니다.
     * 이미 찾아본 표기는 뺍니다. 자주 나온 표기부터 돌려줍니다.
     *
     * <p>「레미제라블」로 시작하면 저자 되찾기가 민음사의 「레 미제라블」을 데려오는데, 그것은
     * 그 저자의 판만입니다. 「레 미제라블」이라는 표기 자체로 다시 찾아야 다른 저자의
     * 각색본이나 어린이판까지, <b>「레 미제라블」로 시작한 사람이 받는 것과 같은 목록</b>이
     * 됩니다. 어느 표기로 넣었느냐에 따라 목록이 달라지는 것을 여기서 끝냅니다.
     *
     * <p>{@link BibNormalizer#spellingKey} 로 견줍니다. 「코스모스(특별판)」은 「코스모스」와
     * 저작 키는 같지만 글자가 다르고, 어절 앞머리가 「코스모스」라 그 검색에 이미 걸려 있어
     * 다시 물어볼 이유가 없습니다.
     */
    static List<String> alternateSpellings(String title, List<BookInfo> books,
                                           List<String> alreadySearched) {
        if (title == null || title.isBlank()) return List.of();
        String wanted = BibNormalizer.spellingKey(title);
        if (wanted.isEmpty()) return List.of();

        Set<String> seen = new HashSet<>();
        for (String searched : alreadySearched) seen.add(spellingId(searched));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BookInfo book : books) {
            String proper = BibNormalizer.parseTitle(book.bookname()).titleProper();
            if (proper == null || proper.isBlank()) continue;
            String spelling = collapseSpaces(proper);
            if (!wanted.equals(BibNormalizer.spellingKey(spelling))) continue;
            if (seen.contains(spellingId(spelling))) continue;
            counts.merge(spelling, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(MAX_ALTERNATE_SPELLINGS)
                .toList();
    }

    private static String collapseSpaces(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    /** 같은 표기인지 볼 때는 공백 묶음과 대소문자를 무시합니다. */
    private static String spellingId(String s) {
        return collapseSpaces(s).toLowerCase(Locale.ROOT);
    }

    /**
     * 제목이 검색어에 얼마나 맞는지로 다시 세웁니다.
     *
     * <p>정보나루가 주는 순서를 그대로 쓰면 「코스모스」를 찾았는데 「미크로코스모스 입문」이
     * 1등으로 나옵니다. 찾으려던 책이 안 보이는 것이 이 도구를 버리게 만드는 가장 큰
     * 이유이므로, 적어도 제목이 그대로 맞는 것은 위로 올립니다.
     *
     * <p>정규화는 {@link BibNormalizer#normalizeKey}를 씁니다. <b>적재·군집화와 같은 함수를
     * 써야</b> 「해리 포터」와 「해리포터」가 검색에서만 어긋나는 일이 없습니다.
     *
     * <p><b>같은 등급 안에서는 대출건수가 많은 것을 앞에 둡니다.</b> 예전에는 정보나루가 준
     * 순서를 그대로 두었는데, 이제 결과가 여러 번의 검색을 합친 것이라 「어느 표기로
     * 넣었는가」에 따라 그 순서가 달랐습니다. 정보나루도 기본을 대출건수 순으로 주므로
     * 뜻이 달라지는 것은 아니고, 표기와 무관하게 <b>같은 순서</b>가 나오게 됩니다.
     *
     * <p><b>다만 대출건수로 세우는 것은 묶음 사이의 순서입니다.</b> 낱권은 대출건수가 제각각이라
     * 그대로 두면 3권 다음에 1권이 나옵니다. 그래서 <b>표제 키와 출판사가 같은 것을 한 묶음</b>
     * 으로 보고, 묶음 사이는 그 묶음에서 가장 큰 대출건수로, <b>묶음 안은 권차 순</b>으로
     * 세웁니다. 찾던 낱권 묶음이 위로 올라오는 성질을 잃지 않으면서 1권 2권 3권이 차례로
     * 읽힙니다. 한 묶음은 표제 키가 같으므로 등급도 같아서, 묶음이 등급을 가로지르지 않습니다.
     *
     * <p><b>출판사를 묶음 키에 넣는 이유:</b> 「레미제라블」에는 민음사 낱권도 있고 열린책들
     * 낱권도 있는데 표제 키가 같습니다. 표제만으로 묶어 권차 순으로 세우면 <b>민음사 1권,
     * 열린책들 1권, 민음사 2권처럼 출판사가 번갈아 나오는 목록</b>이 되어 지금보다 나빠집니다.
     * 저작을 묶을 때 출판사가 다르면 합치지 않는 규칙을 여기서도 그대로 씁니다. 출판사를
     * 모르는 것끼리는 한 묶음으로 둡니다. 모르는 것을 근거로 쪼개지 않는 것도 같은 규칙입니다.
     *
     * <p><b>권차가 없는 세트와 합본은 묶음의 맨 뒤입니다.</b> 찾는 사람은 대개 낱권을
     * 원하고, 세트 ISBN 은 도서관이 낱권으로 등록하는 일이 많아 미소장으로 나오기 쉽습니다.
     * 묶음의 첫 줄은 가장 눈에 띄는 자리라 거기에 헛일이 되기 쉬운 것을 두지 않습니다.
     *
     * <p><b>「더 보기」 경계는 보정하지 않습니다.</b> 낱권 묶음이 스무 번째 자리에 걸치면
     * 뒷권이 다음 묶음으로 넘어갑니다. 그런데 묶음 정렬 자체가 찾던 낱권을 앞자리로 올리므로
     * 경계에 걸리는 것은 대개 찾던 책이 아니고, 소장 정렬이 낱권을 훨씬 자주 갈라 놓습니다.
     * 순서는 그대로 살아 있어서 뒤엉키지 않습니다. 보정하려면 묶음 키를 응답에 실어야 하고
     * 화면의 묶음 크기가 변하는데, 드물게 걸리는 일에 치를 값이 아닙니다.
     *
     * <p><b>여기서 자르지 않습니다.</b> 자르는 것은 부르는 쪽의 몫입니다. 그래야 전체가
     * 몇 개인지 셀 수 있고, 화면이 「n개 중 20개」라고 말할 수 있습니다.
     */
    static List<WorkResult> rank(String query, List<WorkResult> works) {
        String queryKey = BibNormalizer.normalizeKey(query == null ? "" : query);
        if (queryKey.isEmpty()) return works;

        // 등급이 낮을수록, 같은 등급이면 대출건수가 많을수록 앞입니다.
        record Strength(int tier, int loans) {}
        Comparator<Strength> order = Comparator.comparingInt(Strength::tier)
                .thenComparing(Comparator.comparingInt(Strength::loans).reversed());
        return sortGrouped(works,
                work -> new Strength(titleTier(queryKey, work.title()), work.loanCount()), order);
    }

    /**
     * 낱권 묶음을 흩뜨리지 않고 세웁니다. <b>한 권 검색과 여러 권 검색이 함께 씁니다.</b>
     *
     * <p>묶음 사이의 순서는 {@code order} 가 정하되, 묶음의 자리는 그 묶음에서 <b>가장 앞서는
     * 판</b>이 정합니다. 낱권 하나가 덜 빌린다고 그 묶음 전체가 아래로 내려가면 안 됩니다.
     * 묶음 안은 권차 순이고, 권차가 없는 세트와 합본은 묶음의 맨 뒤입니다. 마지막으로 ISBN
     * 순을 두는 것은, 「먼저 받은 순서」에 맡기면 어느 표기로 검색했는지에 따라 순서가
     * 달라지기 때문입니다.
     *
     * <p>여러 권 검색이 이것을 쓰지 않고 점수만으로 세웠을 때는 <b>같은 판의 낱권이 대출건수에
     * 따라 흩어졌고, 상한에서 자르면 덜 빌린 권이 빠졌습니다.</b> 「마의 산」을 넣었을 때
     * 을유문화사 판이 1권과 3권만 남고 2권이 사라진 것이 그것입니다.
     *
     * @param strength 판마다 한 번만 계산하는 세기. 비교자 안에서 표제를 뜯으면 정렬하는 동안
     *                 같은 표제를 수십 번 다시 파싱하므로 미리 계산해 둡니다
     * @param order    세기의 순서. 작을수록 앞입니다
     */
    static <K> List<WorkResult> sortGrouped(
            List<WorkResult> works, Function<WorkResult, K> strength, Comparator<K> order) {
        record Sortable<K>(WorkResult work, K strength, String group, Integer volNo, String isbn) {}
        List<Sortable<K>> rows = works.stream()
                .map(work -> new Sortable<>(
                        work,
                        strength.apply(work),
                        groupKey(work),
                        BibNormalizer.parseTitle(work.title()).volNo(),
                        work.isbn13List().isEmpty() ? "" : work.isbn13List().get(0)))
                .toList();

        Map<String, K> leader = new HashMap<>();
        for (Sortable<K> row : rows) {
            leader.merge(row.group(), row.strength(), (a, b) -> order.compare(a, b) <= 0 ? a : b);
        }

        Comparator<Sortable<K>> full = Comparator
                .comparing((Sortable<K> row) -> leader.get(row.group()), order)
                // 세기가 같은 묶음이 둘이면 여기서 갈라 놓아야 두 묶음이 섞이지 않습니다.
                .thenComparing((Sortable<K> row) -> row.group())
                .thenComparing((Sortable<K> row) -> row.volNo(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                // **묶음 키는 표제와 출판사뿐이라 저자가 다른 별개의 책이 한 묶음에 들 수
                // 있습니다.** 권차가 같은(대개 둘 다 없는) 것끼리는 낱권 사이가 아니므로
                // 세기 순으로 세웁니다. 그래야 「코스모스」가 둘일 때 많이 빌린 쪽이, 저자를
                // 준 줄에서는 저자가 맞는 쪽이 먼저 옵니다.
                .thenComparing((Sortable<K> row) -> row.strength(), order)
                .thenComparing((Sortable<K> row) -> row.isbn());

        return rows.stream().sorted(full).map(Sortable::work).toList();
    }

    /**
     * 낱권을 한 묶음으로 보는 키. <b>권차를 뗀 표제 키와 출판사</b>입니다.
     *
     * <p>권차를 떼는 데 {@link BibNormalizer#comparisonKey} 를 쓰므로 등급을 매길 때와 같은
     * 키입니다. 그래서 한 묶음은 반드시 한 등급 안에 들어갑니다.
     */
    private static String groupKey(WorkResult work) {
        return BibNormalizer.comparisonKey(work.title())
                + '\u0000' + BibNormalizer.normalizePublisher(work.publisher());
    }

    /**
     * 작을수록 검색어에 잘 맞습니다.
     *
     * <p><b>권차를 떼고 견줍니다.</b> 표제에는 권차를 붙여 주는데(갈라 놓은 저작을 표제에
     * 드러내야 하므로 {@code displayTitle} 이 「레 미제라블 1권」을 만듭니다), 그 표제를
     * 그대로 견주면 낱권이 전부 「제목이 덜 맞는 것」으로 밀립니다. 실제로 「레미제라블」을
     * 찾았을 때 <b>민음사 낱권이 62~67위였고</b>, 후보 목록에 아예 들지 못했습니다.
     * 권차 없는 판들이 앞자리를 다 차지한 것입니다.
     *
     * <p>규칙 둘이 부딪친 자리입니다. 「갈라 놓았으면 표제에 드러내라」와 「제목이 맞는 것을
     * 위로 올려라」가 같은 문자열을 서로 다르게 봅니다. <b>표제는 사람이 읽는 것이고 순위는
     * 기계가 견주는 것이므로, 견줄 때는 키를 씁니다.</b>
     */
    private static int titleTier(String queryKey, String title) {
        String titleKey = BibNormalizer.comparisonKey(title);
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
        return worksOf(withIsbn(fetchBooks(query).books()));
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
                                book.volume().orElse(null)),
                        null))
                .toList();

        var clustered = WorkClusterer.cluster(inputs, List.of(), workIdSequence::incrementAndGet);

        List<SearchDocBuilder.BookRecord> records = books.stream()
                .map(book -> SearchDocBuilder.BookRecord.of(
                        book.canonicalIsbn13().orElseThrow(),
                        book.bookname(), book.authors(), book.publisher(),
                        null, null, book.volume().orElse(null)))
                .toList();
        return SearchDocBuilder.build(records, clustered.workIdByRecord());
    }

    /**
     * 여러 검색에서 받은 서지를 <b>같은 자료가 두 번 들어가지 않게</b> 모읍니다.
     *
     * <p>표기를 달리해 여러 번 찾으면 같은 판이 여러 번 옵니다. 그대로 두면 군집화에 같은
     * ISBN 이 두 번 들어가고 {@code foundBooks} 와 {@code droppedNoIsbn} 도 부풀어, 화면이
     * 「정보나루가 600건을 줬다」고 말하게 됩니다.
     */
    private static final class Collected {
        private final List<BookInfo> books = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        /** @return 새 자료여서 실제로 더했는지 */
        boolean add(BookInfo book) {
            String key = book.canonicalIsbn13().orElseGet(() ->
                    "raw:" + blankToEmpty(book.isbn13()) + "|" + blankToEmpty(book.bookname())
                            + "|" + blankToEmpty(book.publisher()));
            if (!seen.add(key)) return false;
            books.add(book);
            return true;
        }

        void addAll(List<BookInfo> batch) {
            for (BookInfo book : batch) add(book);
        }

        boolean isEmpty() {
            return books.isEmpty();
        }

        List<BookInfo> books() {
            return List.copyOf(books);
        }

        private static String blankToEmpty(String s) {
            return s == null ? "" : s.trim();
        }
    }

    /** 물어볼 수 없는 도서관이 없는 경우. 기존 호출부와 시험을 위한 지름길입니다. */
    public HoldingResult holdingsOf(List<String> isbn13List, List<String> regionCodes,
                                    List<String> selectedLibs) {
        return holdingsOf(isbn13List, regionCodes, selectedLibs, 0);
    }

    /**
     * 조회하지 않은 것과 조회하지 못한 것을 여기서 가릅니다.
     *
     * <p><b>둘을 한 갈래로 묶으면 안 됩니다.</b> 어느 쪽이든 소장 도서관 목록이 비어 있지만,
     * 앞은 물어볼 필요가 없었던 것이고 뒤는 물어보지 못한 것입니다. 뒤를 빈 결과로 돌려주면
     * 화면이 그것을 "고른 도서관에는 없습니다"로 그리게 되어, 실제로 있는 책을 없다고
     * 답하게 됩니다.
     *
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
        // 판본이 상한을 넘으면 앞의 것만 묻고, 그 사실을 complete 에 반영합니다.
        boolean truncated = isbn13List.size() > MAX_EDITIONS_PER_LOOKUP;
        List<String> asked = truncated ? isbn13List.subList(0, MAX_EDITIONS_PER_LOOKUP) : isbn13List;
        return lookupHoldings(asked, regionCodes, selectedLibs, unaskableLibs, truncated);
    }

    private HoldingResult lookupHoldings(List<String> isbn13List, List<String> regionCodes,
                                         List<String> selectedLibs, int unaskableLibs,
                                         boolean truncated) {
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
            boolean complete = result.isComplete() && unaskableLibs == 0 && !truncated;
            LocalDate asOf = result.oldestFetchedAt() == null
                    ? null : LocalDate.ofInstant(result.oldestFetchedAt(), SEOUL);
            return new HoldingResult(matched, complete, nothingChecked, asOf);
        } catch (RuntimeException e) {
            // 조회 실패는 미소장이 아닙니다. 화면에 "확인 불가"로 표시해야 합니다.
            return new HoldingResult(List.of(), false, true, null);
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
     * @param asOf       이 답이 <b>언제 받은 것인지</b>(한국 날짜). 캐시에서 나온 답이면 캐시된
     *                   날짜이고, 여러 답이 섞였으면 가장 오래된 날짜입니다. 아무 답도 받지
     *                   못했으면 null 입니다. 화면이 「n월 n일 조회 기준」으로 보여 줍니다.
     */
    public record HoldingResult(List<String> libCodes, boolean complete, boolean unreadable,
                                LocalDate asOf) {
        /** 고른 도서관이 없어 물어볼 필요가 없었던 경우. 미소장이 아닙니다. */
        public static HoldingResult notRequested() {
            return new HoldingResult(List.of(), true, false, null);
        }

        /** 물어보고 싶었지만 조회를 시작할 수조차 없었던 경우. 반드시 확인 불가입니다. */
        public static HoldingResult cannotAsk() {
            return new HoldingResult(List.of(), false, true, null);
        }
    }

    /**
     * @param loanCount 묶인 판본 가운데 가장 큰 대출건수. 정보나루가 서지마다 주는 값입니다.
     *                  같은 등급 안에서 순서를 정하는 데 씁니다. 모르면 0 입니다.
     */
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
            List<String> editionLabels,
            int loanCount
    ) {
        static WorkResult of(SearchDoc doc, Map<String, BookInfo> byIsbn) {
            String cover = firstNonBlank(doc, byIsbn, BookInfo::bookImageUrl);
            String detail = firstNonBlank(doc, byIsbn, BookInfo::bookDetailUrl);
            int loans = doc.isbn13List().stream()
                    .map(byIsbn::get)
                    .filter(java.util.Objects::nonNull)
                    .map(BookInfo::loanCount)
                    .filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .max().orElse(0);

            return new WorkResult(doc.workId(), doc.titleDisplay(), doc.authorDisplay(),
                    doc.publisherDisplay(), toHttps(cover), toHttps(detail),
                    doc.isbn13List(), doc.editionLabels(), loans);
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
     * ISBN 을 판별하지 못해 뺀 자료 한 건.
     *
     * @param rawIsbn13 정보나루가 준 원문. 비어 있는지 잘못된 값인지 갈라 보려면 이것이
     *                  있어야 합니다. <b>없으면 왜 빠졌는지 영영 알 수 없습니다.</b>
     */
    public record DroppedBook(String title, String author, String publisher, String rawIsbn13) {}

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
     * @param foundBooks 정보나루가 돌려준 서지 건수(같은 자료는 한 번만 셉니다). <b>우리가
     *                   거르기 전의 숫자입니다.</b> 찾는 책이 안 나올 때 이 숫자 하나로 어디를
     *                   봐야 하는지 갈립니다. 0이면 정보나루가 못 찾은 것이고, 0이 아닌데
     *                   저작이 0이면 우리가 버린 것이고, 저작이 있는데 화면에 없으면 화면
     *                   문제입니다. <b>이것이 없으면 셋을 구별할 방법이 없어 추측하게 됩니다.</b>
     *                   실제로 「마의 산」이 안 나오는 이유를 두고 여러 번 헛짚었습니다.
     * @param droppedNoIsbn ISBN 을 판별할 수 없어 결과에서 뺀 자료 수. <b>화면이 이것을
     *                      밝혀야 합니다.</b> 조용히 빼면 사용자는 「그런 책이 없다」로 읽습니다.
     * @param alsoSearchedTitles 넣은 제목 말고 <b>함께 찾아본 다른 띄어쓰기 표기</b>. 화면이
     *                           이것을 밝혀야 사용자가 자기가 넣은 것과 다른 표기의 책을 보고
     *                           어리둥절하지 않습니다. 없으면 빈 목록입니다.
     * @param recoveredByAuthor 제목으로는 걸리지 않던 판을 <b>저자로 되찾아 더했는지.</b>
     *                          화면이 이 사실을 밝혀야 합니다. 사용자가 넣은 제목과 다른
     *                          표기의 책이 목록에 섞여 있는 것이므로, 말하지 않으면 검색이
     *                          엉뚱한 것을 가져왔다고 읽힙니다.
     * @param asOf 이 검색을 한 날짜.
     */
    public record SearchResponse(List<WorkResult> works, int totalWorks, int foundBooks,
                                 int droppedNoIsbn, List<DroppedBook> droppedBooks,
                                 List<String> alsoSearchedTitles,
                                 boolean recoveredByAuthor, String asOf) {}
}
