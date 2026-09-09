package kr.wimb.bib;

/**
 * 권차. <b>순서를 정하는 서수와 사람에게 보여 줄 표기를 함께 들고 다닙니다.</b>
 *
 * <p>상·중·하로 나뉜 책은 서수로는 1·2·3 이지만 화면에 「1」「3」이라고 적으면 안 됩니다.
 * 상·하 두 권뿐인 책이 <b>「1」과 「3」</b>으로 나와서, 사용자는 2권이 어디 갔는지 찾게
 * 됩니다. 실제로 그렇게 나오고 있었습니다. 서수는 정렬과 「같은 권인지」 판정에만 쓰고,
 * 표기는 받은 대로 돌려줍니다.
 *
 * @param ordinal 순서. 상·중·하는 1·2·3 이고, 로마 숫자는 그 값입니다
 * @param label   사람에게 보여 줄 표기. 숫자로 온 권차는 {@code null} 이고 그때는 서수를
 *                그대로 적습니다. 「상」「중」「하」처럼 글자로 온 권차만 값이 있습니다
 */
public record Volume(int ordinal, String label) {

    public static Volume of(int ordinal) {
        return new Volume(ordinal, null);
    }

    /**
     * 표제 뒤에 붙일 말. 「1」 또는 「상」입니다.
     *
     * <p><b>「권」을 붙이지 않습니다.</b> 상·중·하로 나뉜 책에 붙이면 「상권」이 되는데, 그것은
     * 정보나루가 준 표기가 아니라 우리가 지어낸 말입니다. 숫자 쪽도 마찬가지로 「1」로만
     * 적어 두 경우가 같은 모양이 됩니다. 표제 끝이 이미 이 글자면 붙이지 않습니다.
     */
    public String mark() {
        return label != null ? label : String.valueOf(ordinal);
    }
}
