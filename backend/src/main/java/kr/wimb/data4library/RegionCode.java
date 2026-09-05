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
    SEOUL("11", "서울특별시"),
    BUSAN("21", "부산광역시"),
    DAEGU("22", "대구광역시"),
    INCHEON("23", "인천광역시"),
    GWANGJU("24", "광주광역시"),
    DAEJEON("25", "대전광역시"),
    ULSAN("26", "울산광역시"),
    SEJONG("29", "세종특별자치시"),
    GYEONGGI("31", "경기도"),
    GANGWON("32", "강원특별자치도"),
    CHUNGBUK("33", "충청북도"),
    CHUNGNAM("34", "충청남도"),
    JEONBUK("35", "전북특별자치도"),
    JEONNAM("36", "전라남도"),
    GYEONGBUK("37", "경상북도"),
    GYEONGNAM("38", "경상남도"),
    JEJU("39", "제주특별자치도");

    private final String code;
    private final String sido;

    RegionCode(String code, String sido) {
        this.code = code;
        this.sido = sido;
    }

    public String code() { return code; }

    /** 화면에 쓰는 시도명. 매뉴얼의 약칭(서울, 경기)이 아니라 정식 명칭입니다. */
    public String sido() { return sido; }

    public static Optional<RegionCode> ofCode(String code) {
        return Arrays.stream(values()).filter(r -> r.code.equals(code)).findFirst();
    }

    /**
     * 도서관 주소나 시도명에서 지역 코드를 찾습니다.
     * 매뉴얼의 약칭과 정식 명칭이 달라서 앞 두 글자로 맞춥니다(서울특별시 → 서울).
     */
    public static Optional<RegionCode> ofSido(String sido) {
        if (sido == null || sido.length() < 2) return Optional.empty();
        String head = sido.substring(0, 2);
        return Arrays.stream(values()).filter(r -> r.sido.startsWith(head)).findFirst();
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(RegionCode::code).toList();
    }
}
