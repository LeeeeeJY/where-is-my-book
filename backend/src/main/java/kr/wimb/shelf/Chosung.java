package kr.wimb.shelf;

import java.util.List;

/**
 * 도서기호 첫 글자에서 <b>저자 초성</b>을 뽑습니다. 서가 오른쪽의 ㄱ~ㅎ 색인이
 * 이것으로 만들어집니다.
 *
 * <h2>왜 도서기호에서 뽑나</h2>
 *
 * <p>저자명 필드에서 뽑으면 서가 순서와 어긋납니다. 저자명은 「박문정 지음;강정희
 * 지도감수」처럼 여럿이 섞여 오고, 번역서는 원저자가 앞에 오지만 <b>서가에 꽂히는
 * 자리는 도서기호가 정합니다.</b> 색인을 눌러 간 자리와 그 자리에 실제로 있는 책이
 * 달라지면 색인이 거짓말을 하는 셈입니다. 도서기호의 첫 글자가 곧 그 자리입니다.
 *
 * <h2>쌍자음은 접습니다</h2>
 *
 * <p>「ㄲ」「ㄸ」「ㅃ」「ㅆ」「ㅉ」을 따로 세우면 색인이 열아홉 줄이 되는데, 그 다섯은
 * 실제 도서기호에 거의 나오지 않아 <b>대부분 빈 줄</b>이 됩니다. 손가락으로 짚는
 * 자리라 줄이 적을수록 낫고, 「까」를 찾는 사람이 「ㄱ」을 누르는 것은 자연스럽습니다.
 */
public final class Chosung {

    /** 한글 음절이 시작하는 자리. 「가」입니다. */
    private static final char SYLLABLE_BASE = 0xAC00;

    /** 한 초성이 거느리는 음절 수. 중성 21 × 종성 28 입니다. */
    private static final int PER_CHOSUNG = 588;

    /** 음절에서 초성을 뽑을 때 쓰는 열아홉 자. 순서가 유니코드 그대로입니다. */
    private static final char[] NINETEEN = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    };

    /**
     * 화면의 색인에 세우는 열넷. <b>이 순서가 곧 색인 줄의 순서입니다.</b>
     * 쌍자음을 접었으므로 열아홉이 아닙니다.
     */
    public static final List<String> INDEX = List.of(
            "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ",
            "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ");

    private Chosung() {}

    /**
     * 도서기호의 첫 글자에서 초성 한 자를 뽑습니다. 한글이 아니거나 비어 있으면
     * {@code null} 입니다.
     *
     * <p><b>한글이 아닌 도서기호가 실제로 있습니다.</b> 로마자를 쓰는 도서관이 있고
     * 숫자로 시작하는 것도 있습니다. 그런 책에 초성을 억지로 붙이면 색인을 눌러 간
     * 사람이 엉뚱한 곳에 도착합니다. <b>모르면 비웁니다.</b>
     */
    public static String of(String bookCode) {
        if (bookCode == null) return null;
        String value = bookCode.trim();
        if (value.isEmpty()) return null;

        char first = value.charAt(0);

        // 한글 음절. 「김」에서 「ㄱ」을 꺼냅니다.
        if (first >= SYLLABLE_BASE && first <= 0xD7A3) {
            return fold(NINETEEN[(first - SYLLABLE_BASE) / PER_CHOSUNG]);
        }
        // 자모가 그대로 온 경우. 도서기호 꼬리에 쓰이는 「ㄷ」 같은 글자입니다.
        // **받침에만 쓰이는 겹자음(ㄳ, ㄵ, ㄺ …)은 거릅니다.** 그 범위 안에 섞여
        // 있지만 초성이 될 수 없어서, 그대로 두면 색인에 없는 줄이 생깁니다.
        for (char candidate : NINETEEN) {
            if (candidate == first) return fold(first);
        }
        return null;
    }

    /** 쌍자음을 기본 자음으로 접습니다. */
    private static String fold(char chosung) {
        return switch (chosung) {
            case 'ㄲ' -> "ㄱ";
            case 'ㄸ' -> "ㄷ";
            case 'ㅃ' -> "ㅂ";
            case 'ㅆ' -> "ㅅ";
            case 'ㅉ' -> "ㅈ";
            default -> String.valueOf(chosung);
        };
    }
}
