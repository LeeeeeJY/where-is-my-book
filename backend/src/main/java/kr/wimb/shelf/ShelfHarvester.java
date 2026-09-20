package kr.wimb.shelf;

import kr.wimb.data4library.BookInfo;
import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 도서관의 대주제 하나를 받아 <b>서가 순서로 정렬해 파일로 적어 둡니다.</b>
 *
 * <h2>왜 그때그때 받지 못하나</h2>
 *
 * <p>서가를 청구기호 순으로 보여 주려면 전체를 한 줄로 세워야 하는데, 정보나루는 두
 * 가지를 해 주지 않습니다. <b>정렬해서 주지 않고</b>({@code itemSrch} 에 정렬 항목이
 * 없습니다), <b>한 번에 몇백 권씩만 줍니다.</b> 그래서 받아 온 한 쪽은 그 갈래 안에서
 * 아무렇게나 뽑힌 500권이고, 그것만 정렬하면 한 줄에 서로 다른 서가의 책이 섭니다.
 * 「813.6 다음에 무엇이 꽂혀 있나」는 그 갈래를 다 보고 나서야 답할 수 있습니다.
 *
 * <p>그래서 한 번 세워 두면, 그 뒤로 그 서가를 몇 명이 몇 번을 열든 <b>정보나루
 * 호출이 한 건도 나가지 않습니다.</b>
 *
 * <h2>대주제 하나씩 세웁니다</h2>
 *
 * <p>도서관 전체는 15만~40만 권이라 한 번에 300회를 부르고, 전국 1,619곳이면 48만
 * 회입니다. 하루 한도가 25,000이라 스무 날이 걸리고 디스크도 65GB 라 들어가지
 * 않습니다. <b>대주제로 자르고 사람이 여는 것만 세우면</b> 그 둘이 함께 풀립니다.
 * 자세한 것은 {@link Kdc} 에 적어 두었습니다.
 *
 * <h2>등록된 IP 에서만 돌립니다</h2>
 *
 * <p>정보나루 한도는 등록한 IP 에서 나갈 때만 하루 30,000건이고 아니면 500건입니다.
 * 수집은 수천 번을 부르므로 <b>반드시 배포된 서버에서</b> 돌려야 합니다. GitHub
 * Actions 는 러너 IP 가 고정되지 않아 조용히 500건으로 떨어집니다.
 *
 * <h2>배경 작업입니다</h2>
 *
 * <p>{@link ApiBudget.Priority#BACKGROUND} 로 부릅니다. 사람이 기다리는 검색이 있으면
 * 그쪽이 먼저이고, 하루 한도의 80% 에 닿으면 수집이 먼저 멈춥니다. 수집은 내일 다시
 * 돌리면 되지만 검색은 그 사람이 지금 기다리고 있습니다.
 */
public class ShelfHarvester {

    private static final Logger log = LoggerFactory.getLogger(ShelfHarvester.class);

    /** 기준일은 한국 날짜입니다. 화면에 그대로 나갑니다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 한 조각에 담는 권수. 화면이 한 번에 받아 오는 단위입니다.
     *
     * <p>200권이면 JSON 이 45KB 안팎이고 gzip 으로 12KB 남짓입니다. 더 작게 자르면
     * 스크롤할 때 요청이 잦아지고, 더 크게 자르면 첫 화면이 늦습니다. 한 줄에 4권씩
     * 세우므로 200권은 50줄이고, 손가락으로 몇 번 밀 분량입니다.
     */
    static final int CHUNK = 200;

    /**
     * 한 번에 달라고 해 볼 쪽 크기.
     *
     * <p><b>매뉴얼은 기본값이 100 이라고만 적어 두고 상한을 말하지 않습니다.</b> 그래서
     * 큰 값으로 물어보고 <b>실제로 몇 건이 왔는지 세어</b> 정합니다. 500건을 받아 주면
     * 호출이 5분의 1로 줄고, 더 작게 잘라 주면 그 수를 한 쪽 분량으로 삼습니다.
     * 서버가 준 것보다 많이 왔다고 가정하면 그만큼이 통째로 빠지는데, 빠진 책은
     * 서가에 없으므로 <b>아무도 눈치채지 못합니다.</b> 소장 조회가 {@code numFound} 를
     * 다루는 규칙과 같은 모양입니다.
     */
    static final int WANTED_PAGE_SIZE = 500;

    /**
     * 한꺼번에 내보낼 쪽 수. 정보나루에 동시에 나가는 요청 수는 전송 계층이
     * {@code max-in-flight} 로 따로 묶으므로, 여기서는 그보다 넉넉하게 잡아도
     * 그 값을 넘지 않습니다.
     */
    private static final int PAGES_IN_FLIGHT = 12;

    /** 조각 사이를 끊는 글자. 어떤 값에도 나오지 않습니다. */
    private static final char PACK = '\u0000';

    private final Data4LibraryClient client;
    private final Path dataDir;
    private final Clock clock;

    public ShelfHarvester(Data4LibraryClient client, Path dataDir, Clock clock) {
        this.client = client;
        this.dataDir = dataDir;
        this.clock = clock;
    }

    /**
     * 그 도서관 장서를 전부 받아 서가 파일로 적습니다.
     *
     * <p><b>다 만든 뒤에 한 번에 갈아 끼웁니다.</b> 임시 자리에 적고 마지막에 이름을
     * 바꾸므로, 화면에 답하고 있는 서버가 반쯤 쓰인 서가를 읽는 일이 없습니다. 수집은
     * 십 분 넘게 걸리는데 그동안 서가가 비어 보이면 고장으로 읽힙니다.
     *
     * @return 적어 둔 서가의 요약
     */
    public ShelfMeta harvest(String libCode, Kdc kdc) throws IOException {
        return harvest(libCode, kdc, new Progress());
    }

    /**
     * @param progress 화면이 「서가를 세우는 중입니다 60%」를 그릴 수 있게 진행 상황을
     *                 여기에 적습니다. <b>진행률이 없으면 멈춘 것과 구별되지 않아</b>
     *                 사람이 새로 고치고, 그러면 같은 일을 다시 시작하게 됩니다
     */
    public ShelfMeta harvest(String libCode, Kdc kdc, Progress progress) throws IOException {
        long startedAt = System.nanoTime();

        int pageSize = probePageSize(libCode, kdc);
        int total = countOf(libCode, kdc);
        if (total <= 0) throw new IOException("장서 건수를 받지 못했습니다: 도서관 " + libCode);

        int pages = (total + pageSize - 1) / pageSize;
        progress.pages = pages;
        log.info("서가 수집을 시작합니다. 도서관 {}, {}, 장서 {}건, 쪽 크기 {}, {}쪽",
                libCode, kdc.label(), total, pageSize, pages);

        List<String> packed = collect(libCode, kdc, pages, pageSize, progress);
        // 서가 순서는 열쇠의 글자 순서 그대로입니다. 여기서 한 번만 세웁니다.
        packed.sort(null);

        ShelfMeta meta = write(libCode, kdc, packed, total);
        log.info("서가 수집을 마쳤습니다. 도서관 {}, {}, 복본 {}권, 자료실 {}곳, {}초",
                libCode, kdc.label(), meta.count(), meta.rooms().size(),
                (System.nanoTime() - startedAt) / 1_000_000_000L);
        return meta;
    }

    /**
     * 그 서가에 몇 권이 있는지만 물어봅니다. <b>한 번이면 됩니다.</b>
     *
     * <p>다시 세울 때가 되었는지 볼 때 먼저 부릅니다. 권수가 그대로면 다시 세우지
     * 않아 40회를 아낍니다. 신착과 폐기가 같은 수만큼 일어나면 못 잡지만, 그 경우는
     * 기한이 다 차면 어차피 다시 세웁니다.
     */
    public int countOf(String libCode, Kdc kdc) {
        return client.catalogCount(libCode, kdc.code(), ApiBudget.Priority.BACKGROUND);
    }

    /**
     * 차림표의 <b>확인한 날짜만</b> 고쳐 씁니다. 책은 그대로 둡니다.
     *
     * <p>권수를 물어봤더니 그대로였을 때 쓰는 길입니다. 다시 세우지 않고 이 날짜만
     * 올려 두면 기한이 다시 차기 전까지 묻지 않습니다. <b>기준일({@code asOf})은
     * 건드리지 않습니다.</b> 받아 온 적 없는 날짜를 받아 온 것처럼 말하게 됩니다.
     */
    public void rewriteCheckedAt(String libCode, Kdc kdc, ShelfMeta meta, String checkedAt)
            throws IOException {
        // 판 번호는 그대로 둡니다. 여기서 고치는 것은 「확인한 날」뿐이고, 순서를
        // 다시 적은 것이 아닙니다. 올려 버리면 예전 순서인 서가가 최신인 척합니다.
        ShelfMeta updated = new ShelfMeta(meta.libCode(), meta.kdc(), meta.asOf(), checkedAt,
                meta.chunkSize(), meta.count(), meta.reported(), meta.keyVersion(),
                meta.shapeVersion(), meta.rooms());
        Files.writeString(dataDir.resolve(libCode).resolve(kdc.slug()).resolve("meta.json"),
                ShelfJson.meta(updated), StandardCharsets.UTF_8);
    }

    /** 서가를 세우는 동안의 진행 상황. 화면이 이것으로 막대를 그립니다. */
    public static final class Progress {
        private volatile int pages;
        private volatile int donePages;
        private volatile int books;

        public int pages() { return pages; }
        public int donePages() { return donePages; }
        public int books() { return books; }

        /** 0~100. 쪽 수를 아직 모르면 0 입니다. */
        public int percent() {
            return pages <= 0 ? 0 : Math.min(100, donePages * 100 / pages);
        }
    }

    // ── 받아 오기 ────────────────────────────────────────────────────────

    /**
     * 쪽 크기를 실제로 재 봅니다. <b>달라는 대로 준다고 믿지 않습니다.</b>
     * 첫 쪽을 크게 물어보고 몇 건이 왔는지 셉니다.
     */
    private int probePageSize(String libCode, Kdc kdc) {
        List<BookInfo> first = client.catalogPage(libCode, kdc.code(), 1, WANTED_PAGE_SIZE,
                ApiBudget.Priority.BACKGROUND);
        int got = first.size();
        if (got >= WANTED_PAGE_SIZE) return WANTED_PAGE_SIZE;
        if (got <= 0) return WANTED_PAGE_SIZE;   // 빈 쪽이면 건수로 다시 판단합니다.
        log.info("쪽 크기 {}을 물었더니 {}건이 왔습니다. 그 수를 한 쪽 분량으로 씁니다.",
                WANTED_PAGE_SIZE, got);
        return got;
    }

    private List<String> collect(String libCode, Kdc kdc, int pages, int pageSize,
                                 Progress progress) throws IOException {
        List<String> packed = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int from = 1; from <= pages; from += PAGES_IN_FLIGHT) {
                List<Future<List<BookInfo>>> batch = new ArrayList<>();
                for (int page = from; page < from + PAGES_IN_FLIGHT && page <= pages; page++) {
                    int at = page;
                    batch.add(pool.submit(() -> client.catalogPage(libCode, kdc.code(), at,
                            pageSize, ApiBudget.Priority.BACKGROUND)));
                }
                for (Future<List<BookInfo>> future : batch) {
                    for (BookInfo book : get(future)) {
                        for (ShelfItem item : ShelfItem.of(book)) packed.add(pack(item));
                    }
                    progress.donePages++;
                    progress.books = packed.size();
                }
                if ((from / PAGES_IN_FLIGHT) % 20 == 0) {
                    log.info("  {}/{}쪽, 지금까지 {}권", Math.min(from + PAGES_IN_FLIGHT - 1, pages),
                            pages, packed.size());
                }
            }
        }
        return packed;
    }

    private static List<BookInfo> get(Future<List<BookInfo>> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("서가 수집이 중단되었습니다.", e);
        } catch (ExecutionException e) {
            // **한 쪽이라도 빠지면 서가에 구멍이 납니다.** 빠진 책은 목록에 없으므로
            // 아무도 눈치채지 못한 채 「그 자리에 없다」가 됩니다. 반쪽짜리를 내보내느니
            // 통째로 실패하는 편이 낫습니다.
            throw new IOException("장서 한 쪽을 받지 못했습니다.", e.getCause());
        }
    }

    // ── 메모리에 담는 모양 ───────────────────────────────────────────────

    /**
     * 한 권을 문자열 하나로 눌러 담습니다.
     *
     * <p><b>20만 권을 객체로 들고 있으면 힙이 모자랍니다.</b> 기계가 메모리 1GB 이고
     * 힙은 그 절반입니다. {@link ShelfItem} 을 그대로 쌓으면 한 권에 600바이트 가까이
     * 들어 20만 권이 120MB 인데, 소장 캐시와 도서관 마스터가 같은 힙에 있습니다.
     * 문자열 하나로 누르면 한 권이 250바이트 안팎이라 넉넉합니다.
     *
     * <p>맨 앞이 정렬 열쇠라 <b>이 문자열을 그냥 글자 순으로 세우면 서가 순서가
     * 됩니다.</b> 끊는 글자({@code \u0000})가 어떤 값보다도 작아서, 열쇠 하나가 다른
     * 열쇠의 앞머리일 때도 짧은 쪽이 먼저입니다.
     *
     * <p><b>그리고 칸 차례가 곧 동점을 가르는 차례입니다.</b> 청구기호가 똑같은 책이
     * 드물지 않아서(실측으로 한 자료실 1,558권 가운데 846권), 열쇠가 같을 때 비교가
     * 뒤 칸으로 흘러 들어갑니다. 지금은 자료실·초성·자료실이름·청구기호·갈래가 모두
     * 같은 것이 보통이라 <b>결국 {@link #FIND} 의 표제가 순서를 정합니다.</b>
     *
     * <p><b>칸을 옮기거나 끼워 넣을 때 이것을 함께 보세요.</b> 표제보다 앞에 잘 갈리는
     * 값을 두면 같은 기호의 책들이 조용히 다른 차례로 섭니다. 비교자에도 열쇠에도
     * 적혀 있지 않은 단계라 코드만 읽어서는 드러나지 않고, 서가 한가운데서만 보입니다.
     * {@code ShelfHarvesterTest} 의 「청구기호가 똑같으면 표제 차례로 세운다」가
     * 그것을 붙들어 둡니다.
     */
    private static String pack(ShelfItem item) {
        return String.join(String.valueOf(PACK),
                item.sortKey(),
                nullToEmpty(item.roomCode()),
                nullToEmpty(item.chosung()),
                nullToEmpty(item.roomName()),
                nullToEmpty(item.callText()),
                nullToEmpty(item.classNm()),
                ShelfFind.line(item),
                ShelfJson.item(item));
    }

    /**
     * 눌러 담은 조각의 자리. 이름을 붙여 두어야 숫자를 잘못 세지 않습니다.
     *
     * <p>{@code FIND} 는 <b>표제와 저자를 한 번 더 들고 다니는 자리</b>입니다. JSON
     * 조각에도 같은 글자가 들어 있지만, 그것을 다시 꺼내려면 여기서 JSON 을 읽어야
     * 합니다. 한 권에 여든 바이트쯤 더 드는데 서가 하나가 10만 권이라도 8MB 라,
     * 파싱하는 코드를 한 벌 더 두는 값보다 쌉니다.
     */
    private static final int SORT_KEY = 0, ROOM_CODE = 1, CHOSUNG = 2,
            ROOM_NAME = 3, CALL_TEXT = 4, CLASS_NM = 5, FIND = 6, JSON = 7;

    // ── 파일로 적기 ──────────────────────────────────────────────────────

    private ShelfMeta write(String libCode, Kdc kdc, List<String> packed, int reported)
            throws IOException {
        Path shelfDir = dataDir.resolve(libCode);
        Files.createDirectories(shelfDir);
        Path target = shelfDir.resolve(kdc.slug());
        Path staging = shelfDir.resolve(kdc.slug() + ".new");
        deleteTree(staging);
        Files.createDirectories(staging);

        List<ShelfMeta.Room> rooms = new ArrayList<>();
        int at = 0;
        while (at < packed.size()) {
            String roomKey = field(packed.get(at), ROOM_CODE);
            int end = at;
            while (end < packed.size() && field(packed.get(end), ROOM_CODE).equals(roomKey)) end++;

            rooms.add(writeRoom(staging, "r" + rooms.size(), packed.subList(at, end)));
            at = end;
        }

        String today = today().toString();
        ShelfMeta meta = new ShelfMeta(libCode, kdc.code(), today, today, CHUNK, packed.size(),
                reported, ShelfSortKey.VERSION, ShelfMeta.SHAPE_VERSION, List.copyOf(rooms));
        Files.writeString(staging.resolve("meta.json"), ShelfJson.meta(meta),
                StandardCharsets.UTF_8);

        // 마지막 한 걸음만 갈아 끼웁니다. 그 전까지는 예전 서가가 그대로 답합니다.
        Path retired = shelfDir.resolve(kdc.slug() + ".old");
        deleteTree(retired);
        if (Files.exists(target)) Files.move(target, retired, StandardCopyOption.ATOMIC_MOVE);
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(retired);
        return meta;
    }

    private ShelfMeta.Room writeRoom(Path staging, String slug, List<String> rows)
            throws IOException {
        Path dir = staging.resolve(slug);
        Files.createDirectories(dir);

        Map<String, Integer> chosungAt = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String chosung = field(rows.get(i), CHOSUNG);
            // **첫 자리만 담습니다.** 색인을 눌렀을 때 가는 곳이라 그 초성이 처음
            // 나오는 자리 하나면 충분합니다.
            if (!chosung.isEmpty()) chosungAt.putIfAbsent(chosung, i);
        }

        /*
          **찾기 색인을 조각과 함께 적습니다.** 한 줄이 서가의 한 자리이고 줄 번호가
          곧 자리 번호라, 나중에 찾을 때는 이 파일 한 번 훑기로 끝납니다. 조각을 읽을
          필요도 JSON 을 되읽을 필요도 없습니다. **빈 줄도 한 줄입니다.** 표제도 저자도
          없는 자료의 줄을 건너뛰면 그 뒤의 자리 번호가 전부 한 칸씩 밀리는데, 화면은
          그 번호로 조각을 찾아가므로 엉뚱한 책 앞에 서게 됩니다.
        */
        try (Writer out = Files.newBufferedWriter(
                dir.resolve(ShelfFind.FILE), StandardCharsets.UTF_8)) {
            for (String row : rows) {
                out.write(field(row, FIND));
                out.write('\n');
            }
        }

        int chunks = 0;
        for (int from = 0; from < rows.size(); from += CHUNK) {
            int to = Math.min(from + CHUNK, rows.size());
            try (Writer out = Files.newBufferedWriter(
                    dir.resolve(chunks + ".json"), StandardCharsets.UTF_8)) {
                out.write('[');
                for (int i = from; i < to; i++) {
                    if (i > from) out.write(',');
                    out.write(field(rows.get(i), JSON));
                }
                out.write(']');
            }
            chunks++;
        }

        String first = rows.get(0);
        String last = rows.get(rows.size() - 1);
        return new ShelfMeta.Room(slug, field(first, ROOM_CODE), field(first, ROOM_NAME),
                rows.size(), chunks,
                field(first, CALL_TEXT), field(last, CALL_TEXT), chosungAt, sectionsOf(rows));
    }

    /**
     * 갈래가 <b>어디서부터 어디까지 서 있는지</b>를 셉니다.
     *
     * <p>서가가 청구기호 순이고 분류번호가 그 앞자리를 정하므로 같은 갈래의 책은 한
     * 덩어리로 붙어 있습니다. 그래서 이어지는 구간만 찾으면 되고, 정보나루를 한 번도
     * 부르지 않습니다.
     *
     * <p><b>한 갈래가 여러 군데로 갈리는 일이 있어서 가장 긴 덩어리를 씁니다.</b>
     * 별치기호가 분류번호보다 <b>앞에서</b> 갈라기 때문입니다. 실측으로 부천 어느
     * 자료실의 「영미문학 &gt; 소설」이 별치 구역({@code Y서(아)})과 보통 서가({@code 843})
     * 두 군데에 나뉘어 서 있었습니다. 처음 나오는 자리를 적으면 대부분의 책이 있는
     * 곳이 아니라 <b>작은 구석으로 데려다줄 수 있습니다.</b>
     *
     * <p>권수는 흩어진 덩어리까지 합합니다. 화면이 그 수를 함께 보여 주므로, 가장 큰
     * 덩어리만 세면 실제보다 적게 말하게 됩니다.
     */
    private static List<ShelfMeta.Section> sectionsOf(List<String> rows) {
        // 이름 → [가장 긴 덩어리의 시작, 그 길이, 합한 권수]
        Map<String, int[]> found = new LinkedHashMap<>();
        int at = 0;
        while (at < rows.size()) {
            String name = field(rows.get(at), CLASS_NM);
            int end = at;
            while (end < rows.size() && field(rows.get(end), CLASS_NM).equals(name)) end++;

            // 분류명이 없는 자료는 갈래로 세우지 않습니다. 이름이 없으면 고를 수도 없고,
            // 「(없음)」 같은 말을 우리가 지어내면 정보나루가 주지 않은 갈래가 생깁니다.
            if (!name.isEmpty()) {
                int from = at;
                int[] best = found.computeIfAbsent(name, key -> new int[] {from, 0, 0});
                int length = end - at;
                if (length > best[1]) {
                    best[0] = at;
                    best[1] = length;
                }
                best[2] += length;
            }
            at = end;
        }

        List<ShelfMeta.Section> sections = new ArrayList<>(found.size());
        found.forEach((name, best) -> sections.add(
                new ShelfMeta.Section(name, best[0], best[2])));
        // 서가에 선 차례로 내보냅니다. 고르는 목록이 실제 서가와 같은 순서여야
        // 「여기 다음이 저기」가 눈에 들어옵니다.
        sections.sort(java.util.Comparator.comparingInt(ShelfMeta.Section::at));
        return List.copyOf(sections);
    }

    /** 눌러 담은 문자열에서 {@code index} 번째 조각을 꺼냅니다. */
    private static String field(String packed, int index) {
        int from = 0;
        for (int i = 0; i < index; i++) from = packed.indexOf(PACK, from) + 1;
        int to = packed.indexOf(PACK, from);
        return to < 0 ? packed.substring(from) : packed.substring(from, to);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
