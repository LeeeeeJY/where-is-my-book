package kr.wimb.shelf;

import java.util.Comparator;

/**
 * 책이 서가에 실제로 꽂히는 순서.
 *
 * <p><b>여섯 단계를 차례로 봅니다.</b> 앞 단계가 갈리면 뒤는 보지 않습니다.
 *
 * <ol>
 *   <li><b>자료실</b>(배가기호) — 층이 다르면 아예 다른 서가입니다</li>
 *   <li><b>별치기호</b> — 같은 자료실 안에서 따로 모아 둔 자리입니다</li>
 *   <li><b>분류번호</b> — 소수점 아래를 자릿수 단위로 견줍니다</li>
 *   <li><b>도서기호</b> — 한글부, 숫자부, 꼬리 자모를 따로 견줍니다</li>
 *   <li><b>권차</b> — 1권 다음이 2권입니다</li>
 *   <li><b>복본기호</b> — 같은 책이 여러 권이면 그 순서입니다</li>
 * </ol>
 *
 * <h2>판정은 여기 한 곳에서만 합니다</h2>
 *
 * <p>수집기가 파일에 적는 순서와 화면이 그리는 순서가 갈리면, 「더 보기」로 이어
 * 붙인 자리에서 책이 중복되거나 빠집니다. 그런 어긋남은 <b>서가 한가운데서만</b>
 * 드러나 눈에 띄지 않습니다. 그래서 {@link #comparator()} 와 {@link #sortKey} 가
 * <b>같은 순서를 내야 하고</b>, {@code CallNumberOrderTest} 가 그것을 고정합니다.
 *
 * <h2>열쇠를 쓰는 쪽이 본체입니다</h2>
 *
 * <p>실제로 도는 것은 {@link #sortKey} 입니다. 수집할 때 한 줄마다 계산해 파일에
 * 적어 두면 화면도 서버도 다시 정렬하지 않습니다. {@link #comparator()} 는 그 열쇠가
 * 맞는지 견주기 위한 것이고, 자바 안에서 정렬할 때 씁니다.
 *
 * <h2>여섯 단계가 다 같을 때는 표제가 가릅니다</h2>
 *
 * <p><b>여기까지가 여섯 단계이고, 실제 서가에는 일곱째가 있습니다.</b> 청구기호가
 * 똑같은 책이 드물지 않기 때문입니다. 실측으로 부천 어느 자료실은 1,558권 가운데
 * 846권(54%)이 남과 같은 기호를 쓰고, 그림책 전집 하나가 한 기호에 <b>248권</b>을
 * 걸고 있었습니다(2026-09-20).
 *
 * <p>그 일곱째는 여기 없습니다. {@link ShelfHarvester} 가 한 줄을
 * {@code 정렬열쇠 \0 자료실 \0 초성 \0 자료실이름 \0 청구기호 \0 갈래 \0 표제·저자 \0 JSON}
 * 으로 눌러 담고 <b>줄 전체를 글자로</b> 세우므로, 열쇠가 같으면 비교가 그 뒤로 흘러
 * 들어가 결국 표제가 순서를 정합니다. 운에 맡겨지는 것이 아니라 <b>늘 같은 순서가
 * 나옵니다.</b>
 *
 * <p><b>그러니 이 비교자만 보고 「가를 것이 없다」고 읽지 마세요.</b> 딸려 나온
 * 단계라 비교자에도 열쇠에도 적혀 있지 않고, 눌러 담는 칸 차례를 바꾸면 조용히
 * 달라집니다. {@code ShelfHarvesterTest} 의 「청구기호가 똑같으면 표제 차례로
 * 세운다」가 그것을 붙들어 둡니다.
 *
 * <p>다만 <b>글자 순서라 숫자에 약합니다.</b> 권차가 정보나루의 {@code vol} 에 없고
 * 표제에만 있으면 「10」이 「2」보다 앞에 섭니다. 같은 시험 파일의 「권차가 표제에만
 * 있으면…」이 그 한계를 적어 두었습니다.
 */
public final class CallNumberOrder {

    private CallNumberOrder() {}

    /**
     * 서가 순서를 글자 하나로 만든 열쇠. 이 열쇠를 글자로만 견주면 위 여섯 단계가
     * 그대로 나옵니다.
     *
     * @param roomCode    자료실. 배가기호({@code shelf_loc_code})를 쓰고 없으면 이름을 씁니다.
     *                    둘 다 없으면 <b>맨 뒤</b>입니다. 어느 자료실인지 모르는 책은
     *                    서가 사이에 끼워 넣을 자리가 없습니다
     * @param separate    별치기호. 없으면 <b>맨 앞</b>입니다. 별치가 없는 일반 서가가
     *                    기본이기 때문입니다
     * @param classNo     분류번호. 없으면 맨 뒤입니다
     * @param bookCode    도서기호
     * @param volOrdinal  권차의 서수. 없으면 {@code null} 이고 <b>맨 앞</b>입니다.
     *                    권차 없는 단권이 1권보다 앞에 서는 것이 서가와 같습니다
     * @param copyCode    복본기호. 없으면 맨 앞입니다
     */
    public static String sortKey(String roomCode, String separate, String classNo,
                                 String bookCode, Integer volOrdinal, String copyCode) {
        return String.join(ShelfSortKey.SEP,
                ShelfSortKey.textOrLast(roomCode),
                ShelfSortKey.text(separate),
                ShelfSortKey.classNo(classNo),
                BookCode.parse(bookCode).sortKey(),
                ShelfSortKey.number(volOrdinal == null ? -1 : volOrdinal),
                ShelfSortKey.number(firstNumberIn(copyCode)));
    }

    /**
     * 같은 순서를 내는 비교자. {@link #sortKey} 를 견주는 것과 결과가 같아야 합니다.
     *
     * <p><b>여섯 단계가 다 같으면 0 을 돌려주고 끝납니다.</b> 그것이 곧 「순서가
     * 없다」는 뜻은 아닙니다. 실제 서가에서는 표제가 한 번 더 가르는데, 그 단계는
     * 여기가 아니라 {@link ShelfHarvester} 가 줄을 눌러 담는 모양에 들어 있습니다.
     * 위의 클래스 설명을 보세요.
     *
     * <p>열쇠를 만들어 견주므로 둘이 갈릴 수가 없어 보이지만, <b>그것이 바로 이
     * 비교자가 존재하는 이유입니다.</b> 여기서 열쇠를 다시 쓰면 「열쇠가 맞는지」를
     * 열쇠로 확인하는 셈이 됩니다. 단계마다 값을 직접 견주어, 열쇠 만드는 규칙이
     * 틀렸을 때 시험이 그것을 잡게 합니다.
     */
    public static Comparator<Placed> comparator() {
        return Comparator
                .comparing((Placed p) -> ShelfSortKey.textOrLast(p.roomCode()))
                .thenComparing(p -> ShelfSortKey.text(p.separate()))
                .thenComparing(CallNumberOrder::classParts, CallNumberOrder::compareClassNo)
                .thenComparing(p -> BookCode.parse(p.bookCode()).prefix())
                .thenComparingLong(p -> BookCode.parse(p.bookCode()).number())
                .thenComparing(p -> BookCode.parse(p.bookCode()).suffix(),
                        CallNumberOrder::compareNaturally)
                .thenComparingLong(p -> p.volOrdinal() == null ? -1 : p.volOrdinal())
                .thenComparingLong(p -> firstNumberIn(p.copyCode()));
    }

    /** 비교자가 견주는 여섯 값. 서가에 꽂힌 자리를 정하는 것이 이것뿐입니다. */
    public interface Placed {
        String roomCode();
        String separate();
        String classNo();
        String bookCode();
        Integer volOrdinal();
        String copyCode();
    }

    /**
     * 글자와 숫자가 섞인 값을 <b>숫자 덩어리는 크기로</b> 견줍니다.
     *
     * <p>{@link ShelfSortKey#natural} 과 같은 순서를 내야 합니다. 열쇠를 다시 쓰지 않고
     * 여기서 값을 직접 걸어가는 것이 이 비교자가 있는 이유입니다.
     */
    private static int compareNaturally(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            boolean leftDigit = isDigit(left.charAt(i));
            boolean rightDigit = isDigit(right.charAt(j));

            // 한쪽만 숫자면, 숫자 쪽이 뒤입니다. 열쇠가 숫자 앞에 구분 문자를 넣어
            // 어떤 글자보다도 작게 만드는 것과 같은 결과입니다(「ㅌ」이 「ㅌ1」보다 앞).
            if (leftDigit != rightDigit) return leftDigit ? 1 : -1;

            if (!leftDigit) {
                int diff = Character.compare(left.charAt(i), right.charAt(j));
                if (diff != 0) return diff;
                i++;
                j++;
                continue;
            }

            int leftEnd = i;
            while (leftEnd < left.length() && isDigit(left.charAt(leftEnd))) leftEnd++;
            int rightEnd = j;
            while (rightEnd < right.length() && isDigit(right.charAt(rightEnd))) rightEnd++;

            int diff = compareDigits(left.substring(i, leftEnd), right.substring(j, rightEnd));
            if (diff != 0) return diff;
            i = leftEnd;
            j = rightEnd;
        }
        // 앞머리가 같으면 짧은 쪽이 먼저입니다.
        return Integer.compare(left.length() - i, right.length() - j);
    }

    /** 자릿수가 터무니없이 길면 크기를 지어내지 않고 글자로 견줍니다. */
    private static int compareDigits(String left, String right) {
        try {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        } catch (NumberFormatException e) {
            return left.compareTo(right);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ── 분류번호 견주기 ──────────────────────────────────────────────────

    private static String classParts(Placed p) {
        return ShelfSortKey.text(p.classNo());
    }

    /**
     * 분류번호를 <b>소수점 아래까지 자릿수 단위로</b> 견줍니다.
     *
     * <p>실수로 읽으면 {@code 813.6} 과 {@code 813.62} 가 뒤집힙니다. 소수점 아래는
     * 크기가 아니라 <b>더 좁혀 들어간 갈래</b>라서, 앞자리부터 하나씩 견주고 먼저
     * 끝나는 쪽이 앞입니다. {@code .9} 와 {@code .10} 의 순서가 이 차이를 잘
     * 보여 줍니다. 크기로는 0.9 가 크지만 서가에서는 {@code .10} 이 {@code .1} 바로
     * 뒤, {@code .2} 앞자리입니다.
     *
     * <pre>
     *   813  &lt;  813.1  &lt;  813.10  &lt;  813.2  &lt;  813.6  &lt;  813.62  &lt;  813.7  &lt;  814
     * </pre>
     *
     * <p><b>앞머리 숫자만 크기로 보고 나머지는 글자로 봅니다.</b> 소수점 아래를 글자로
     * 견주는 것이 곧 자릿수 단위 비교이고, 짧은 쪽이 앞머리이면 짧은 쪽이 먼저입니다.
     * {@link ShelfSortKey#classNo} 가 열쇠를 만드는 방법과 일부러 같게 맞춰 두었습니다.
     */
    private static int compareClassNo(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            // 분류번호가 없는 책은 맨 뒤입니다. 둘 다 없으면 같은 자리입니다.
            return Boolean.compare(left.isEmpty(), right.isEmpty());
        }

        int leftDigits = leadingDigits(left);
        int rightDigits = leadingDigits(right);

        // 숫자로 시작하지 않는 분류기호(로마자를 쓰는 도서관이 있습니다)는 숫자 분류
        // 전부의 뒤에 모읍니다. 섞어 넣을 근거가 없고, 뒤에 모으면 한 덩어리로 보입니다.
        if (leftDigits == 0 || rightDigits == 0) {
            if (leftDigits != 0) return -1;
            if (rightDigits != 0) return 1;
            return left.compareTo(right);
        }

        int head = compareAsNumber(left.substring(0, leftDigits), right.substring(0, rightDigits));
        if (head != 0) return head;

        return left.substring(leftDigits).compareTo(right.substring(rightDigits));
    }

    /** 앞머리 숫자가 몇 글자인지. 숫자로 시작하지 않으면 0 입니다. */
    private static int leadingDigits(String value) {
        int end = 0;
        while (end < value.length() && value.charAt(end) >= '0' && value.charAt(end) <= '9') end++;
        return end;
    }

    /** 자릿수가 적은 쪽이 작고, 자릿수가 같으면 글자 그대로 견줍니다. */
    private static int compareAsNumber(String left, String right) {
        String a = stripZeros(left);
        String b = stripZeros(right);
        int length = Integer.compare(a.length(), b.length());
        return length != 0 ? length : a.compareTo(b);
    }

    private static String stripZeros(String digits) {
        int at = 0;
        while (at < digits.length() - 1 && digits.charAt(at) == '0') at++;
        return digits.substring(at);
    }

    /**
     * 복본기호 안의 첫 숫자. 「c.2」도 「복2」도 2 입니다. 숫자가 없으면 {@code -1} 이고
     * 맨 앞입니다.
     */
    static long firstNumberIn(String raw) {
        if (raw == null) return -1;
        return BookCode.parse(raw).number();
    }
}
