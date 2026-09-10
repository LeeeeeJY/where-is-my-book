package kr.wimb.api;

import kr.wimb.data4library.BookInfo;
import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 둘러보기 화면이 쓰는 두 가지를 만듭니다. <b>「오늘의 이야기」한 권</b>과
 * <b>「요즘 많이 빌려 간 책」 목록</b>입니다.
 *
 * <p><b>둘 다 하루에 한 번만 정보나루를 부릅니다.</b> 답이 도서관마다 하나뿐이고 그날
 * 내내 같으므로, 방문자가 몇 명이든 호출 수가 늘지 않습니다. 소장 조회와 성격이 완전히
 * 다른 자리입니다. 소장은 (ISBN × 시도)마다 답이 달라 캐시가 커지지만, 여기는 열쇠가
 * 도서관부호 하나입니다.
 *
 * <h2>오늘의 이야기를 날짜가 정합니다</h2>
 *
 * <p>「다른 책 보기」 같은 단추를 두지 않습니다. <b>(한국 날짜 + 도서관부호)로 씨앗을
 * 만들어 그날 책을 정하므로, 새로 고쳐도 다른 기기에서 열어도 같은 책입니다.</b> 같은
 * 도서관을 고른 다른 사람도 같은 책을 봅니다. 브라우저에 저장하는 방법도 있지만 그러면
 * 사람마다 답이 달라 캐시가 듣지 않고, 개인정보처리방침의 저장소 항목도 함께 고쳐야
 * 합니다.
 *
 * <p>날짜 경계는 {@code Asia/Seoul} 자정입니다. 호출 예산의 경계와 같습니다.
 *
 * <h2>소장을 따로 묻지 않습니다</h2>
 *
 * <p>{@code itemSrch} 의 {@code type=ALL} 이 <b>그 도서관의 장서 목록</b>이라, 거기서
 * 뽑은 책은 이미 그 도서관 것입니다. 소장 조회를 한 번 더 붙일 수도 있지만 그러면
 * 「씨앗으로 그날 책이 정해진다」가 깨집니다. 어긋나는 책을 건너뛰기 시작하면 몇 번
 * 건너뛰었는지에 따라 답이 달라지고, 소장 조회가 실패한 날에는 그날 책이 아예 정해지지
 * 않습니다. <b>어긋남이 실제로 얼마나 되는지 모르는 채로 막는 코드를 넣지 않습니다.</b>
 * 돌려 보고 「있다더니 없더라」가 나오면 그때 붙이세요.
 */
@Service
public class BrowseService {

    /** 표시도 날짜 경계도 한국 시각 기준입니다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 대주제 8 이 문학입니다(매뉴얼 20절). 소설은 여기서 한 겹 더 걸러야 합니다. */
    private static final String KDC_LITERATURE = "8";

    /**
     * 장서를 한 번에 받아 오는 크기.
     *
     * <p><b>한 권만 받지 않는 이유가 있습니다.</b> 「소설」로 좁히는 파라미터가 없어서
     * 문학을 받아 {@link BookInfo#looksLikeNovel()} 로 걸러야 하는데, 한 권만 받으면
     * 그것이 시집일 때 다시 불러야 합니다. 백 권을 받아 두면 그 안에 소설이 거의 확실히
     * 있습니다.
     */
    private static final int PAGE_SIZE = 100;

    /**
     * 소설을 찾아 옮겨 볼 쪽 수의 상한.
     *
     * <p>문학을 받았는데 그 쪽이 통째로 시집·희곡인 경우를 대비합니다. <b>상한이 없으면
     * 분류가 이상한 도서관에서 호출이 끝없이 나갑니다.</b> 넘겨도 못 찾으면 그날은
     * 이야기를 내보내지 않습니다. 없는 것보다 아무 책이나 내보내는 편이 나쁩니다.
     */
    private static final int MAX_PAGE_TRIES = 3;

    private final Data4LibraryClient client;
    private final Clock clock;

    /** 도서관부호 → 그날 뽑아 둔 이야기. 날짜가 바뀌면 버립니다. */
    private final Map<String, Dated<Story>> stories = new ConcurrentHashMap<>();

    /** 도서관부호 → 그날 받아 둔 인기대출 목록. */
    private final Map<String, Dated<List<PopularGroup>>> popular = new ConcurrentHashMap<>();

    /**
     * <b>{@code @Autowired} 를 지우지 마세요.</b> Spring 이 생성자를 알아서 고르는 것은
     * 생성자가 하나일 때뿐입니다. 아래에 시계를 받는 생성자가 있으므로 둘이 되고, 표시가
     * 없으면 기본 생성자를 찾다가 실패해 <b>서버가 아예 뜨지 못합니다.</b>
     * {@code WimbController} 가 같은 이유로 같은 표시를 달고 있고, 여기서도 실제로
     * {@code WimbStartupTest} 가 이것을 잡았습니다.
     */
    @Autowired
    public BrowseService(Data4LibraryClient client) {
        this(client, Clock.systemUTC());
    }

    /** 날짜가 바뀌는 것을 시험할 수 있도록 시계를 받는 생성자입니다. */
    BrowseService(Data4LibraryClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    private record Dated<T>(LocalDate date, T value) {}

    /**
     * 오늘 날짜(한국 시각).
     *
     * <p><b>{@code clock.withZone(SEOUL)} 을 쓰지 않습니다.</b> 그러면 넘겨받은 시계가
     * {@code withZone} 을 제대로 다뤄야 경계가 맞는데, 그것을 우리가 보장할 수 없습니다.
     * 시계에서는 순간만 받고 시간대는 여기서 붙입니다. 실제로 이 자리를 테스트가 잡았습니다.
     */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }

    /**
     * @param callNumber 없을 수 있습니다. 매뉴얼만으로는 청구기호의 모양을 확정할 수 없어
     *                   확실히 읽히는 경우에만 채웁니다. 화면이 없는 경우를 다룹니다.
     * @param detailUrl  정보나루 책 정보. {@code itemSrch} 는 이것을 주지 않으므로
     *                   ISBN 으로 {@code srchBooks} 를 한 번 더 불러 얻습니다. 그 호출도
     *                   하루 한 번뿐입니다.
     */
    public record Story(String title, String authors, String publisher, String publicationYear,
                        String isbn13, String imageUrl, String callNumber, String detailUrl,
                        String classNm, int poolSize, String date) {}

    /** @param label 화면에 그대로 나가는 이름입니다. 빈 묶음은 아예 담기지 않습니다. */
    public record PopularGroup(String key, String label, List<PopularBook> books) {}

    public record PopularBook(int rank, String title, String authors, String publisher,
                              String publicationYear, String isbn13, String imageUrl,
                              String detailUrl) {}

    /**
     * 그 도서관의 오늘 이야기. 없으면 비어 있습니다.
     *
     * <p>정보나루가 답하지 않으면 <b>예외를 올리지 않고 빈 값을 돌려줍니다.</b> 둘러보기는
     * 곁들이 화면이라, 이것 때문에 검색까지 막히면 안 됩니다. 화면은 목록이 비면 조용히
     * 안내 한 줄만 보여 줍니다.
     */
    public Optional<Story> storyOf(String libCode) {
        LocalDate today = today();
        Dated<Story> known = stories.get(libCode);
        if (known != null && known.date().equals(today)) return Optional.ofNullable(known.value());

        Story built;
        try {
            built = pickStory(libCode, today);
        } catch (RuntimeException e) {
            // 실패는 기억하지 않습니다. 정보나루가 잠깐 흔들린 뒤에도 하루 내내
            // 빈 화면이 이어지면 안 됩니다. 소장 캐시가 지키는 규칙과 같습니다.
            return Optional.empty();
        }
        stories.put(libCode, new Dated<>(today, built));
        return Optional.ofNullable(built);
    }

    /** 그 도서관의 인기대출 목록. 못 받으면 빈 목록입니다. */
    public List<PopularGroup> popularOf(String libCode) {
        LocalDate today = today();
        Dated<List<PopularGroup>> known = popular.get(libCode);
        if (known != null && known.date().equals(today)) return known.value();

        List<PopularGroup> built;
        try {
            built = client.popularByLibrary(libCode, ApiBudget.Priority.USER).entrySet().stream()
                    .map(e -> new PopularGroup(e.getKey().name(), e.getKey().label(),
                            e.getValue().stream().map(BrowseService::toPopularBook).toList()))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
        popular.put(libCode, new Dated<>(today, built));
        return built;
    }

    private static PopularBook toPopularBook(BookInfo info) {
        return new PopularBook(
                info.ranking() == null ? 0 : info.ranking(),
                info.bookname(), info.authors(), info.publisher(), info.publicationYear(),
                info.canonicalIsbn13().orElse(info.isbn13()),
                info.bookImageUrl(), info.bookDetailUrl());
    }

    private Story pickStory(String libCode, LocalDate today) {
        int total = client.catalogCount(libCode, KDC_LITERATURE, ApiBudget.Priority.USER);
        if (total <= 0) return null;

        long seed = seedOf(today, libCode);
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int firstPage = (int) Math.floorMod(seed, pages) + 1;

        for (int step = 0; step < MAX_PAGE_TRIES; step++) {
            // 쪽을 옮기는 것도 씨앗이 정합니다. 무작위로 옮기면 그날 답이 재현되지 않습니다.
            int page = ((firstPage - 1 + step) % pages) + 1;
            List<BookInfo> novels = new ArrayList<>(
                    client.catalogPage(libCode, KDC_LITERATURE, page, PAGE_SIZE,
                                    ApiBudget.Priority.USER).stream()
                            .filter(BookInfo::looksLikeNovel)
                            .filter(b -> b.canonicalIsbn13().isPresent())
                            .toList());
            if (novels.isEmpty()) continue;

            BookInfo chosen = novels.get((int) Math.floorMod(seed, novels.size()));
            return toStory(chosen, total, today);
        }
        return null;
    }

    private Story toStory(BookInfo info, int poolSize, LocalDate today) {
        String isbn = info.canonicalIsbn13().orElse(info.isbn13());
        return new Story(info.bookname(), info.authors(), info.publisher(), info.publicationYear(),
                isbn, info.bookImageUrl(), info.callNumber(), detailUrlOf(isbn), info.classNm(),
                poolSize, today.toString());
    }

    /**
     * 정보나루 책 정보 주소. <b>{@code itemSrch} 가 {@code bookDtlUrl} 을 주지 않습니다.</b>
     *
     * <p>서가에서 무작위로 뽑은 소설은 사용자가 그 책을 전혀 모르므로, 책 정보로 갈 길이
     * 없으면 읽을지 정할 수가 없습니다. 그래서 ISBN 으로 {@code srchBooks} 를 한 번 더
     * 부릅니다. 이야기가 하루에 한 권이라 이 호출도 도서관당 하루 한 번입니다.
     *
     * <p>못 받아도 이야기는 그대로 내보냅니다. 링크 하나 때문에 그날 책이 없어지면 안 됩니다.
     */
    private String detailUrlOf(String isbn13) {
        if (isbn13 == null || isbn13.isBlank()) return null;
        try {
            return client.searchBooks(Data4LibraryClient.BookQuery.byIsbn(isbn13), 1,
                            ApiBudget.Priority.USER).stream()
                    .map(BookInfo::bookDetailUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 그날 그 도서관의 씨앗.
     *
     * <p><b>{@link String#hashCode()} 를 쓰지 않습니다.</b> 자바가 값을 바꾸지 않겠다고
     * 약속한 적은 있지만, 여기서 필요한 것은 「같은 날 같은 도서관이면 같은 책」이라는
     * 눈에 보이는 성질이라 우리가 직접 정합니다. FNV-1a 로 바이트를 훑습니다.
     */
    static long seedOf(LocalDate date, String libCode) {
        byte[] bytes = (date.toString() + '|' + libCode).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** {@code /api/status} 가 내보내는 값입니다. 하루치가 실제로 쌓이는지 봅니다. */
    public Map<String, Integer> cacheSizes() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("browseStories", stories.size());
        out.put("browsePopular", popular.size());
        return out;
    }
}
