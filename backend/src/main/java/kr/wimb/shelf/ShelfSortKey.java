package kr.wimb.shelf;

/**
 * 서가 순서를 <b>글자 비교 하나로</b> 낼 수 있게 값을 적는 방법.
 *
 * <h2>왜 열쇠를 미리 만들어 두나</h2>
 *
 * <p>서가 화면은 20만 줄을 순서대로 보여 줍니다. 브라우저가 그것을 정렬할 수는 없고,
 * 서버가 요청마다 정렬할 수도 없습니다. <b>수집할 때 한 번 정렬해 파일에 순서대로
 * 적어 두면</b> 화면은 그 파일을 자른 조각만 받으면 됩니다.
 *
 * <p>그러려면 순서가 <b>글자 비교만으로</b> 나와야 합니다. 자바의
 * {@link String#compareTo} 와 자바스크립트의 {@code <} 는 둘 다 UTF-16 코드 단위를
 * 견주므로, 이 열쇠로 정렬한 결과가 양쪽에서 같습니다.
 *
 * <h2>자릿수가 다른 숫자</h2>
 *
 * <p>그냥 적으면 {@code "9" > "63"} 입니다. 0 으로 채우는 방법이 흔하지만 <b>채울
 * 자릿수를 정해야 하고, 그 자릿수를 넘는 값이 오면 조용히 틀립니다.</b> 그래서
 * <b>자릿수를 앞에 적는 방법</b>을 씁니다. 63 은 {@code "263"}, 5 는 {@code "15"},
 * 100 은 {@code "3100"} 입니다.
 *
 * <pre>
 *   "15"   < "263"   →    5 <  63   ✓
 *   "263"  < "3100"  →   63 < 100   ✓
 * </pre>
 *
 * <p>아홉 자리를 넘는 수는 서가의 어느 값에도 나오지 않지만, 넘으면 자릿수 표시가
 * 두 글자가 되어 순서가 무너집니다. {@link #number} 가 그 경우를 막습니다.
 *
 * <h2>구분 문자</h2>
 *
 * <p>조각을 이을 때 {@code \u0001} 로 끊습니다. <b>실제 값에 쓰이는 어떤 글자보다도
 * 작아야 합니다.</b> 그래야 짧은 쪽이 긴 쪽의 앞머리일 때 짧은 쪽이 먼저 옵니다
 * (「가」가 「가나」보다 먼저). 공백이나 마침표로 끊으면 그 글자보다 작은 값이 들어올
 * 때 순서가 뒤집힙니다.
 */
final class ShelfSortKey {

    /** 조각 사이를 끊는 글자. 실제 값에 절대 나오지 않고 무엇보다 작습니다. */
    static final String SEP = "\u0001";

    /** 값이 없어 <b>맨 뒤</b>로 보낼 때. 어떤 글자보다도 큽니다. */
    static final String LAST = "￿";

    private ShelfSortKey() {}

    /**
     * 숫자를 자릿수 표시와 함께 적습니다. 음수는 「없음」으로 보고 빈 문자열을
     * 돌려주므로 <b>값이 있는 것들보다 앞</b>에 섭니다.
     */
    static String number(long value) {
        if (value < 0) return "";
        String digits = Long.toString(value);
        // 자릿수 표시가 한 글자를 넘으면 그 표시끼리의 비교가 깨집니다. 서가의 어느
        // 값도 여기 닿지 않지만, 닿으면 조용히 틀리는 쪽이 아니라 뭉뚱그리는 쪽을
        // 고릅니다. 아홉 자리가 넘는 것들은 전부 같은 자리로 봅니다.
        if (digits.length() > 9) return "9" + "9".repeat(9);
        return digits.length() + digits;
    }

    /**
     * 문자열을 그대로 적되 {@code null} 과 빈 값은 빈 문자열입니다. <b>빈 값이 먼저
     * 옵니다.</b> 별치기호가 여기에 해당하는데, 별치가 없는 일반 서가가 기본이므로
     * 앞에 서는 것이 맞습니다.
     */
    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 글자와 숫자가 섞인 값을, <b>숫자 덩어리는 크기로</b> 견주게 적습니다.
     *
     * <p>도서기호 꼬리가 여기에 해당합니다. 전집은 권 번호가 그 꼬리에 붙어 오는데
     * (「박65ㅌ1」「박65ㅌ2」…), 글자 그대로 견주면 <b>「ㅌ10」이 「ㅌ2」보다 앞에
     * 섭니다.</b> 「1」이 「2」보다 작아서입니다. 전집은 권수가 열을 넘는 일이 흔해서
     * 실제 서가와 가장 크게 어긋나 보이는 자리입니다.
     *
     * <p>숫자 덩어리를 {@link #number} 로 적고 앞뒤를 구분 문자로 끊습니다. 구분
     * 문자가 어떤 글자보다도 작으므로 <b>숫자가 없는 쪽이 먼저</b>입니다(「ㅌ」이
     * 「ㅌ1」보다 앞). 서가에서 본권이 1권보다 앞에 서는 것과 같습니다.
     */
    static String natural(String raw) {
        String value = text(raw);
        if (value.isEmpty()) return "";

        StringBuilder out = new StringBuilder(value.length() + 8);
        int at = 0;
        while (at < value.length()) {
            char c = value.charAt(at);
            if (c < '0' || c > '9') {
                out.append(c);
                at++;
                continue;
            }
            int end = at;
            while (end < value.length() && isDigit(value.charAt(end))) end++;
            long parsed;
            try {
                parsed = Long.parseLong(value.substring(at, end));
            } catch (NumberFormatException e) {
                // 자리가 터무니없이 긴 값입니다. 크기를 지어내지 않고 글자로 둡니다.
                out.append(value, at, end);
                at = end;
                continue;
            }
            out.append(SEP).append(number(parsed)).append(SEP);
            at = end;
        }
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * 값이 없으면 <b>맨 뒤</b>로 보내는 문자열. 자료실과 분류번호가 여기에 해당합니다.
     * 어느 자료실인지 모르는 책, 분류번호가 없는 책은 서가에 꽂힐 자리가 없으므로
     * 앞에 끼워 넣지 않고 뒤에 모읍니다.
     */
    static String textOrLast(String value) {
        String trimmed = text(value);
        return trimmed.isEmpty() ? LAST : trimmed;
    }

    /**
     * 분류번호. <b>소수점 아래를 실수가 아니라 자릿수 단위로 견줍니다.</b>
     *
     * <p>실수로 읽으면 {@code 813.6} 과 {@code 813.62} 의 순서가 뒤집힙니다. 서가에서는
     * {@code 813.6} 다음이 {@code 813.62} 이고, 그다음이 {@code 813.7} 입니다. 소수점
     * 아래는 「값」이 아니라 「더 좁혀 들어간 갈래」라서 그렇습니다.
     *
     * <pre>
     *   813  <  813.6  <  813.62  <  813.7  <  814
     * </pre>
     *
     * <p>정수부만 자릿수 표시를 붙이고 소수부는 적힌 그대로 잇습니다. 그러면 위
     * 순서가 글자 비교로 그대로 나옵니다. 소수점({@code .} = 0x2E)이 숫자(0x30~)보다
     * 작아서 <b>「813」이 「813.6」보다 먼저</b>이고, 구분 문자가 소수점보다 더 작아서
     * 「813」 뒤에 아무 소수부도 없는 쪽이 여전히 먼저입니다.
     *
     * <p>숫자로 시작하지 않는 분류기호(로마자를 쓰는 도서관이 있습니다)는 <b>숫자
     * 분류 전부의 뒤</b>에 모읍니다. 섞어 넣을 근거가 없고, 뒤에 모으면 적어도 한
     * 덩어리로 보입니다.
     */
    static String classNo(String raw) {
        String value = text(raw);
        if (value.isEmpty()) return LAST;

        int end = 0;
        while (end < value.length() && value.charAt(end) >= '0' && value.charAt(end) <= '9') end++;
        // 숫자로 시작하지 않으면 숫자 분류 뒤로 보냅니다. '1' 이 '0' 보다 큽니다.
        if (end == 0) return "1" + value;

        String rest = value.substring(end);
        long integer;
        try {
            integer = Long.parseLong(value.substring(0, end));
        } catch (NumberFormatException e) {
            // 자리가 터무니없이 긴 값입니다. 분류번호일 수 없으므로 숫자 분류 뒤로 보냅니다.
            return "1" + value;
        }
        return "0" + number(integer) + rest;
    }
}
