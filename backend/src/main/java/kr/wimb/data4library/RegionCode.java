package kr.wimb.data4library;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 정보나루 지역 코드. Open API Manual v20260210 의 20절에서 그대로 옮겼습니다.
 *
 * <p>{@code libSrchByBook} 의 {@code region} 은 <b>필수 항목</b>입니다. 따라서 소장 조회는
 * 선택한 도서관들이 걸친 시도 수만큼 호출이 곱해집니다. 전국을 한 번에 받는 방법은 없습니다.
 */
public enum RegionCode {
    SEOUL("11", "서울특별시", "서울"),
    BUSAN("21", "부산광역시", "부산"),
    DAEGU("22", "대구광역시", "대구"),
    INCHEON("23", "인천광역시", "인천"),
    GWANGJU("24", "광주광역시", "광주"),
    DAEJEON("25", "대전광역시", "대전"),
    ULSAN("26", "울산광역시", "울산"),
    SEJONG("29", "세종특별자치시", "세종"),
    GYEONGGI("31", "경기도", "경기"),
    GANGWON("32", "강원특별자치도", "강원"),
    CHUNGBUK("33", "충청북도", "충북", "충청북"),
    CHUNGNAM("34", "충청남도", "충남", "충청남"),
    JEONBUK("35", "전북특별자치도", "전북", "전라북"),
    JEONNAM("36", "전라남도", "전남", "전라남"),
    GYEONGBUK("37", "경상북도", "경북", "경상북"),
    GYEONGNAM("38", "경상남도", "경남", "경상남"),
    JEJU("39", "제주특별자치도", "제주");

    private final String code;
    private final String sido;
    private final List<String> aliases;

    RegionCode(String code, String sido, String... aliases) {
        this.code = code;
        this.sido = sido;
        this.aliases = List.of(aliases);
    }

    public String code() { return code; }

    /** 화면에 쓰는 시도명. 매뉴얼의 약칭(서울, 경기)이 아니라 정식 명칭입니다. */
    public String sido() { return sido; }

    public static Optional<RegionCode> ofCode(String code) {
        return Arrays.stream(values()).filter(r -> r.code.equals(code)).findFirst();
    }

    /**
     * 도서관 주소나 시도명에서 지역 코드를 찾습니다.
     *
     * <p><b>앞 두 글자로 맞추면 안 됩니다.</b> 예전에 그렇게 했다가 「충청남도」가 「충청」으로
     * 잘려 <b>충청북도에 먼저 걸렸습니다.</b> 경상남도도 마찬가지로 경상북도가 되었습니다.
     * 목록이 어긋나는 정도로 끝나지 않고, {@code libSrchByBook} 에 <b>틀린 지역 코드가
     * 나가서</b> 그 도서관이 응답에 없고 화면에는 「미소장」으로 보입니다. 실제로 있는 책을
     * 없다고 답하는, 이 도구가 가장 피해야 하는 실패입니다.
     *
     * <p>그래서 별칭을 명시적으로 적어 두고 그것으로만 맞춥니다. 정식 명칭(충청남도)과
     * 줄임말(충남)이 둘 다 들어옵니다. 「전라북도」는 「전북특별자치도」로 바뀌었지만 옛 표기가
     * 섞여 들어올 수 있어 함께 둡니다.
     */
    public static Optional<RegionCode> ofSido(String sido) {
        if (sido == null || sido.isBlank()) return Optional.empty();
        String head = sido.strip().split("\\s+")[0];
        return Arrays.stream(values())
                .filter(r -> r.matches(head))
                .findFirst();
    }

    private boolean matches(String token) {
        if (token.startsWith(sido)) return true;
        for (String alias : aliases) {
            if (token.startsWith(alias)) return true;
        }
        return false;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(RegionCode::code).toList();
    }
}
