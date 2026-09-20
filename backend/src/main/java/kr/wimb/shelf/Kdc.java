package kr.wimb.shelf;

import java.util.List;
import java.util.Optional;

/**
 * KDC 대주제 열. <b>서가를 세우는 단위입니다.</b>
 *
 * <h2>왜 이 단위인가</h2>
 *
 * <p>도서관 하나가 15만~40만 권이라 통째로 세우면 한 번에 300회를 부릅니다. 전국
 * 1,619곳이면 48만 회이고 하루 한도가 25,000이라 <b>스무 날이 걸립니다.</b> 디스크도
 * 65GB 라 무료 등급 VM 의 30GB 에 들어가지 않습니다.
 *
 * <p>대주제로 자르면 한 서가가 5천~10만 권입니다. 그리고 <b>사람이 여는 서가만</b>
 * 세우므로 안 쓰는 도서관에는 한 건도 쓰지 않습니다.
 *
 * <h2>왜 세부주제가 아닌가</h2>
 *
 * <p>{@code dtl_kdc}(두 자리)로 자르면 서가가 더 작아져 기다림이 줄지만, <b>차림표를
 * 만들려고 100번을 물어봐야 합니다.</b> 어느 세부주제에 몇 권이 있는지는 물어봐야
 * 알 수 있어서입니다. 대주제는 열 개뿐이라 목록을 코드에 두면 되고, 실제 도서관
 * 서가의 표지판도 이 단위로 붙어 있습니다.
 *
 * <p>이름과 코드는 매뉴얼 59쪽의 「대주제 코드」 표 그대로입니다. <b>우리가 지어낸
 * 이름이 아닙니다.</b>
 */
public enum Kdc {
    GENERAL("0", "총류"),
    PHILOSOPHY("1", "철학"),
    RELIGION("2", "종교"),
    SOCIAL("3", "사회과학"),
    SCIENCE("4", "자연과학"),
    TECHNOLOGY("5", "기술과학"),
    ART("6", "예술"),
    LANGUAGE("7", "언어"),
    LITERATURE("8", "문학"),
    HISTORY("9", "역사");

    private final String code;
    private final String label;

    Kdc(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** {@code itemSrch} 의 {@code kdc} 파라미터에 그대로 넣는 값입니다. */
    public String code() {
        return code;
    }

    /** 화면에 그대로 나가는 이름입니다. */
    public String label() {
        return label;
    }

    /** 서가 파일이 놓이는 자리. 숫자만으로 폴더를 만들면 도서관부호와 헷갈립니다. */
    public String slug() {
        return "k" + code;
    }

    /**
     * 코드로 찾습니다. <b>모르는 코드는 받지 않습니다.</b> 주소로 들어온 값이 그대로
     * 파일 경로가 되므로, 거르는 것이 아니라 아는 것만 통과시키는 쪽입니다.
     */
    public static Optional<Kdc> of(String code) {
        if (code == null) return Optional.empty();
        String trimmed = code.trim();
        for (Kdc one : values()) {
            if (one.code.equals(trimmed) || one.slug().equals(trimmed)) return Optional.of(one);
        }
        return Optional.empty();
    }

    public static List<Kdc> all() {
        return List.of(values());
    }
}
