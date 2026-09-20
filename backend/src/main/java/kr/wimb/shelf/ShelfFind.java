package kr.wimb.shelf;

import kr.wimb.bib.BibNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Stream;

/**
 * 세워 둔 서가 안에서 <b>그 책이 몇 번째 자리인지</b> 찾습니다.
 *
 * <h2>검색이 아니라 길 찾기입니다</h2>
 *
 * <p>서가 화면에는 검색이 없습니다. 찾는 책이 있는 사람은 검색 탭으로 갑니다. 그런데
 * <b>서가 앞에 선 사람에게도 「그 자리로 가고 싶다」가 있습니다.</b> 수천 권짜리 서가를
 * 손가락으로 밀어 가로지를 수는 없고, 오른쪽 초성 색인은 도서기호 첫 글자까지만
 * 데려다줍니다. 여기서 하는 일은 <b>이 서가 안의 자리 번호를 돌려주는 것</b>이고,
 * 그 뒤는 화면이 그 자리로 옮겨 갑니다. 다른 도서관도 다른 갈래도 보지 않습니다.
 *
 * <h2>정보나루를 부르지 않습니다</h2>
 *
 * <p>수집할 때 자료실마다 {@code find.txt} 를 함께 적어 둡니다. <b>한 줄이 서가의 한
 * 자리이고 줄 번호가 곧 자리 번호입니다.</b> 그래서 찾는 일은 그 파일 한 번 훑기로
 * 끝나고, 호출도 색인 자료구조도 없습니다. 서가 조각을 읽을 필요조차 없습니다.
 *
 * <p>줄에 적는 것은 <b>화면에 보여 줄 글자 그대로</b>이고 정규화한 형태를 따로 적어
 * 두지 않습니다. 둘 다 적으면 파일이 두 배가 되는데, 정규화는 받아서 하면 몇 밀리초라
 * 치를 값이 아닙니다. 게다가 적어 둔 정규화 형태는 <b>규칙을 고쳐도 그대로 남아</b>
 * 화면과 어긋납니다.
 *
 * <h2>빈 줄도 한 줄입니다</h2>
 *
 * <p>표제도 저자도 없는 자료가 있는데, 그 줄을 건너뛰면 <b>그 뒤의 자리 번호가 전부
 * 한 칸씩 밀립니다.</b> 화면은 그 번호로 조각을 찾아가므로 엉뚱한 책 앞에 서게 되고,
 * 틀렸다는 것은 아무 데도 드러나지 않습니다. 줄 수는 서가의 권수와 반드시 같습니다.
 */
public final class ShelfFind {

    /** 자료실 폴더에 함께 놓는 찾기 색인. */
    static final String FILE = "find.txt";

    /** 줄 안에서 칸을 나누는 글자. 값에서는 미리 지웁니다. */
    private static final char TAB = '\t';

    /**
     * 이보다 짧은 말로는 찾지 않습니다.
     *
     * <p>한 글자는 수천 권에 걸려 아무 데도 데려다주지 못합니다. 그 자리는 이미
     * 초성 색인이 맡고 있습니다.
     */
    public static final int MIN_QUERY = 2;

    /** 한 번에 돌려주는 자리 수. */
    public static final int LIMIT = 20;

    private static final int NO_MATCH = -1;

    private ShelfFind() {}

    /**
     * 찾은 자리 하나.
     *
     * @param at 그 자료실에서 <b>몇 번째 자리인지</b>(0부터). 화면이 이 번호로 옮겨 갑니다
     */
    public record Hit(int at, String title, String author, String call) {}

    /**
     * @param total 이 서가에서 맞은 자리의 <b>전체 수</b>. {@link #hits} 는 그중 앞의 몇 개뿐입니다.
     *              화면이 「그 밖에 n권 더 있습니다」를 말할 수 있어야 사람이 더 좁혀
     *              넣을지 정합니다
     */
    public record Result(int total, List<Hit> hits) {}

    // ── 적을 때 ──────────────────────────────────────────────────────────

    /**
     * 서가의 한 자리를 색인의 한 줄로 적습니다.
     *
     * <p><b>읽는 쪽과 적는 쪽을 같은 파일에 둡니다.</b> 칸을 나누는 글자와 칸의 차례가
     * 두 곳에 흩어지면 한쪽만 고쳤을 때 조용히 어긋납니다. 그때 나오는 것은 오류가
     * 아니라 <b>엉뚱한 책</b>입니다.
     */
    static String line(ShelfItem item) {
        return text(item.title()) + TAB + text(item.author()) + TAB + text(item.callText());
    }

    /**
     * 줄 하나에 들어갈 수 있는 모양으로 다듬습니다.
     *
     * <p>제어 문자를 공백으로 바꿉니다. 도서관이 적어 넣은 값이라 줄바꿈이나 탭이 섞여
     * 들어올 수 있는데, 그러면 <b>한 자리가 두 줄이 되어 그 뒤가 전부 밀립니다.</b>
     */
    private static String text(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return out.toString().trim();
    }

    // ── 찾을 때 ──────────────────────────────────────────────────────────

    /**
     * 견주기 위한 글자. <b>띄어쓰기와 구두점을 지웁니다.</b>
     *
     * <p>서가에 선 책의 표제는 도서관이 적어 넣은 그대로라 「레 미제라블」과
     * 「레미제라블」이 섞여 있습니다. 넣은 대로만 맞추면 <b>멀쩡히 꽂혀 있는 책을
     * 「이 서가에는 없다」고 답하게 됩니다.</b>
     *
     * <p>{@link BibNormalizer#normalizeKey} 가 아니라 {@link BibNormalizer#spellingKey}
     * 인 것에는 이유가 있습니다. 앞엣것은 「청소년판」 같은 표기를 떼어 내는데, 그것은
     * 같은 저작으로 묶을 때의 규칙입니다. <b>여기서 견주는 것은 사람이 서가에서 보고
     * 있는 글자</b>라, 화면에 「데미안 청소년판」이라 적혀 있으면 그대로 찾혀야 합니다.
     */
    public static String normalize(String raw) {
        return BibNormalizer.spellingKey(raw);
    }

    /**
     * 색인을 한 번 훑어 맞는 자리를 고릅니다.
     *
     * <p><b>들고 있는 것은 돌려줄 만큼뿐입니다.</b> 「소설」처럼 흔한 말은 한 서가에서
     * 수천 줄에 걸리는데, 그것을 전부 담았다가 마지막에 자르면 그 순간 힙이 몇십 MB
     * 늘어납니다. 기계가 1GB 라 그럴 여유가 없습니다. 그래서 <b>가장 뒤떨어지는 것을
     * 위에 둔 줄</b>을 {@code limit} 만큼만 들고, 넘치면 그 자리에서 버립니다.
     * 전체 수는 따로 셉니다.
     */
    static Result scan(Stream<String> lines, String query, int limit) {
        String needle = normalize(query);
        if (needle.length() < MIN_QUERY) return new Result(0, List.of());

        PriorityQueue<Scored> kept = new PriorityQueue<>(WORST_FIRST);
        int total = 0;
        int at = 0;
        for (Iterator<String> rows = lines.iterator(); rows.hasNext(); at++) {
            String[] parts = rows.next().split("\t", -1);
            int rank = rankOf(parts, needle);
            if (rank == NO_MATCH) continue;
            total++;
            kept.add(new Scored(rank, at, parts));
            if (kept.size() > limit) kept.poll();
        }

        List<Scored> best = new ArrayList<>(kept);
        best.sort(BEST_FIRST);
        return new Result(total, best.stream().map(Scored::toHit).toList());
    }

    /**
     * 얼마나 잘 맞는지. 작을수록 앞입니다.
     *
     * <p><b>그대로 맞은 것을 위에 둡니다.</b> 「토지」를 넣은 사람이 보고 싶은 것은
     * 「토지」이지 「토지 이용 계획」이 아닙니다. 저자를 그대로 적은 것이 표제의 일부로
     * 걸린 것보다 위인 이유도 같습니다. 「김영하」는 그 사람의 책들이 서 있는 자리를
     * 찾는 말입니다.
     *
     * <p>같은 등급 안에서는 <b>서가에 선 차례</b>입니다({@link #BEST_FIRST}). 낱권과
     * 복본이 흩어지지 않고 실제로 꽂힌 순서대로 나옵니다.
     */
    private static int rankOf(String[] parts, String needle) {
        String title = normalize(field(parts, TITLE));
        if (title.equals(needle)) return 0;

        String author = normalize(field(parts, AUTHOR));
        if (author.equals(needle)) return 1;
        if (title.startsWith(needle)) return 2;
        if (author.startsWith(needle)) return 3;
        if (title.contains(needle)) return 4;
        if (author.contains(needle)) return 5;
        return NO_MATCH;
    }

    /** 색인 줄의 칸 차례. */
    private static final int TITLE = 0, AUTHOR = 1, CALL = 2;

    /** 예전 모양으로 적힌 줄이 섞여 있어도 통째로 깨지지 않게 합니다. */
    private static String field(String[] parts, int index) {
        return index < parts.length ? parts[index] : "";
    }

    private record Scored(int rank, int at, String[] parts) {
        Hit toHit() {
            return new Hit(at, field(parts, TITLE), field(parts, AUTHOR), field(parts, CALL));
        }
    }

    private static final Comparator<Scored> BEST_FIRST =
            Comparator.comparingInt(Scored::rank).thenComparingInt(Scored::at);

    private static final Comparator<Scored> WORST_FIRST = BEST_FIRST.reversed();
}
