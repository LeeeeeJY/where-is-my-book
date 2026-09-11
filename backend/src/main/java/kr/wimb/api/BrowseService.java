package kr.wimb.api;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Contributor;
import kr.wimb.bib.Isbn;
import kr.wimb.bib.TitleParts;
import kr.wimb.bib.Volume;
import kr.wimb.data4library.BookInfo;
import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BrowseService.class);

    /**
     * 예외를 로그에 적을 한 줄로 만들되 <b>인증키를 가립니다.</b>
     *
     * <p>정보나루가 준 오류({@code ApiErrorException})에는 키가 들어가지 않지만, 전송
     * 계층이 던지는 예외는 주소를 통째로 메시지에 담을 수 있고 그 주소에는 {@code authKey}
     * 가 붙어 있습니다. <b>로그는 남에게 넘어가기 쉬운 자리라</b> 여기서 한 번 거릅니다.
     */
    static String withoutKey(RuntimeException e) {
        return e.toString().replaceAll("(?i)(authKey=)[^&\\s]*", "$1***");
    }

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
            // **다만 삼키더라도 남기기는 합니다.** 화면은 조용히 비는데 이유가 아무 데도
            // 남지 않으면 다음에 같은 증상이 왔을 때 처음부터 다시 재야 합니다.
            log.warn("오늘의 이야기를 받지 못했습니다(도서관 {}): {}", libCode, withoutKey(e));
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
                            e.getValue().stream()
                                    // **인기대출 목록에도 음반과 영상물이 섞여 옵니다.** 실제로
                                    // 어느 도서관의 청소년 1위가 「훌라걸스 감독판 (dts) (3disc)」
                                    // 이었습니다. 검색에서 걸러 두는 것과 같은 값이라 여기서도
                                    // 같은 판정을 씁니다. 880 으로 시작하는 대한민국 EAN 이지
                                    // ISBN 이 아닙니다.
                                    .filter(b -> !Isbn.isNotABookNumber(b.isbn13()))
                                    .map(BrowseService::toPopularBook).toList()))
                    // 영상물을 걸러 낸 뒤 빈 묶음이 될 수 있습니다.
                    .filter(g -> !g.books().isEmpty())
                    .toList();
        } catch (RuntimeException e) {
            // 여기가 조용해서 실제로 한 번 헛짚었습니다. 화면에 목록이 통째로 비었는데
            // 이유가 어디에도 없어, 도서관 스물두 곳을 부르고 응답 시간을 재어 가며
            // 「2절은 되는데 15절만 안 된다」를 손으로 갈라내야 했습니다. 한 줄이면
            // 끝날 일이었습니다.
            log.warn("인기대출 목록을 받지 못했습니다(도서관 {}): {}", libCode, withoutKey(e));
            return List.of();
        }
        popular.put(libCode, new Dated<>(today, built));
        return built;
    }

    private static PopularBook toPopularBook(BookInfo info) {
        return new PopularBook(
                info.ranking() == null ? 0 : info.ranking(),
                displayTitle(info), displayAuthor(info),
                info.publisher(), info.publicationYear(),
                info.canonicalIsbn13().orElse(info.isbn13()),
                info.bookImageUrl(), info.bookDetailUrl());
    }

    /**
     * 화면에 보여 줄 표제. <b>정보나루가 준 글자를 그대로 쓰면 안 됩니다.</b>
     *
     * <p>서명 필드에 KORMARC 245 의 구분 기호가 섞여 들어와 「페인트 :이희영 장편소설」이나
     * 「주식하는 마음 :주식투자의 운과 실력…」처럼 부제가 콜론 뒤에 붙은 채 나옵니다. 검색
     * 결과는 {@code BibNormalizer} 가 갈라 주는데 둘러보기만 그대로 두면 <b>같은 화면에서
     * 두 목록의 표기가 다릅니다.</b>
     *
     * <p>권차는 붙여 줍니다. 안 붙이면 「초한지」가 4위와 6위에 두 번 나오는 것처럼 보여
     * 같은 책이 중복된 줄 알게 됩니다. {@code SearchDocBuilder.displayTitle} 과 같은 규칙입니다.
     */
    private static String displayTitle(BookInfo info) {
        TitleParts parts = BibNormalizer.parseTitle(info.bookname());
        String proper = parts.titleProper() == null ? "" : parts.titleProper().trim();
        if (proper.isEmpty()) return info.bookname();

        Volume vol = info.volume().orElse(parts.volume());
        if (vol == null || proper.endsWith(vol.mark())) return proper;
        return proper + " " + vol.mark();
    }

    /**
     * 저자 표시. 역할어와 세미콜론이 그대로 오면 「사토 케이 저;사가노 아오이 일러스트;서범주 역」
     * 처럼 한 줄이 길어집니다. 대표 저자만 남기고, 못 고르면 받은 값을 그대로 둡니다.
     * <b>모르면 지어내지 않고 원문을 보여 줍니다.</b>
     */
    private static String displayAuthor(BookInfo info) {
        Contributor primary = BibNormalizer.primaryAuthor(
                BibNormalizer.parseContributors(info.authors()));
        return primary == null ? info.authors() : primary.name();
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
                            .filter(b -> !Isbn.isNotABookNumber(b.isbn13()))
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
        return new Story(displayTitle(info), displayAuthor(info),
                info.publisher(), info.publicationYear(),
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
            // 링크가 없는 것은 정상이기도 해서 debug 입니다. 화면이 없는 경우를 다룹니다.
            log.debug("책 정보 주소를 받지 못했습니다(ISBN {}): {}", isbn13, withoutKey(e));
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
