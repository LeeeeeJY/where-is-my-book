package kr.wimb.shelf;

/**
 * 도서기호를 세 조각으로 나눈 것. 「김63ㄷ」이면 「김」, 63, 「ㄷ」입니다.
 *
 * <h2>왜 나눠야 하나</h2>
 *
 * <p><b>숫자를 글자로 견주면 순서가 무너집니다.</b> 문자열 그대로 비교하면
 * {@code "김9ㄱ" > "김63ㄷ"} 입니다. 아홉 번이 예순세 번 뒤로 가는 것인데, 서가에서는
 * 바로 옆자리라 눈에 띄지도 않습니다. 숫자 부분은 정수로 읽어야 합니다.
 *
 * <p>꼬리의 자모도 따로 들어야 합니다. 「정54ㅂ」과 「정54ㅍ」은 앞의 둘이 같고
 * 자모만 다른데, 그 자모가 표제의 첫 소리라 서가에서 실제로 순서를 정합니다.
 *
 * @param prefix 앞의 글자들. 대개 저자 성의 첫 글자입니다
 * @param number 가운데 숫자. 없으면 {@code -1} 입니다
 * @param suffix 숫자 뒤에 남은 글자들. 대개 표제 첫 자모 한 글자입니다
 */
public record BookCode(String prefix, long number, String suffix) {

    /** 숫자가 없는 도서기호. 서가에서는 숫자 있는 것보다 앞입니다. */
    public static final long NO_NUMBER = -1;

    /**
     * 도서기호 문자열을 나눕니다. {@code null} 이나 빈 문자열이면 빈 조각입니다.
     *
     * <p><b>첫 숫자 덩어리만 가운데 숫자로 읽습니다.</b> 뒤에 숫자가 또 나오면 그것은
     * 꼬리에 남깁니다. 「김63ㄷ2」의 2 는 권이나 복본을 가리키는 것이라 가운데 숫자와
     * 성격이 다릅니다. <b>다만 꼬리에 남긴 숫자도 크기로 견줍니다.</b> 성격이 다른 것과
     * 글자로 견줘도 되는 것은 다릅니다({@link #sortKey}).
     */
    public static BookCode parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return new BookCode("", NO_NUMBER, "");

        int start = 0;
        while (start < value.length() && !isDigit(value.charAt(start))) start++;
        if (start == value.length()) return new BookCode(value, NO_NUMBER, "");

        int end = start;
        while (end < value.length() && isDigit(value.charAt(end))) end++;

        String digits = value.substring(start, end);
        return new BookCode(value.substring(0, start), asLong(digits), value.substring(end));
    }

    /**
     * 정렬용 열쇠. <b>글자 비교만으로 위 규칙이 나오게 만듭니다.</b>
     *
     * <p>숫자는 {@link ShelfSortKey#number} 가 자릿수를 앞에 붙여 적으므로 자릿수가
     * 달라도 크기대로 늘어섭니다.
     *
     * <p><b>꼬리도 마찬가지입니다.</b> 전집은 권 번호가 꼬리에 붙어 오는데
     * (「박65ㅌ1」「박65ㅌ2」…), 글자 그대로 두면 10권이 2권 앞에 섭니다.
     * {@link ShelfSortKey#natural} 이 꼬리 안의 숫자 덩어리도 크기로 적습니다.
     */
    String sortKey() {
        return prefix + ShelfSortKey.SEP + ShelfSortKey.number(number)
                + ShelfSortKey.SEP + ShelfSortKey.natural(suffix);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static long asLong(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            // 자리가 터무니없이 긴 값입니다. 서가에서는 볼 수 없는 모양이라
            // 숫자가 없는 것으로 다룹니다. 지어낸 값으로 순서를 매기지 않습니다.
            return NO_NUMBER;
        }
    }
}
