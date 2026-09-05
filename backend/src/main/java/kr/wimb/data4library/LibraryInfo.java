package kr.wimb.data4library;

import java.util.Map;
import java.util.Optional;

/**
 * {@code libSrch} 와 {@code libSrchByBook} 이 돌려주는 도서관 정보.
 * 항목 이름은 Open API Manual v20260210 의 응답 명세 그대로입니다.
 *
 * <p><b>위도와 경도가 여기 들어 있는 것이 중요합니다.</b> 계획서는 위경도를 얻으려고
 * 공공데이터포털의 전국도서관표준데이터를 받아 이름과 주소로 대조하는 단계를 두었는데,
 * 표준데이터에는 도서관부호가 없어 그 대조가 계획 전체에서 가장 큰 위험이었습니다.
 * 정보나루가 코드와 위경도를 함께 주므로 그 단계가 통째로 필요 없어집니다.
 */
public record LibraryInfo(
        String libCode,
        String libName,
        String address,
        String tel,
        String fax,
        Double latitude,
        Double longitude,
        String homepage,
        String closed,
        String operatingTime,
        Integer bookCount
) {
    public static LibraryInfo from(Map<String, String> fields) {
        return new LibraryInfo(
                fields.get("libCode"),
                fields.get("libName"),
                fields.get("address"),
                fields.get("tel"),
                fields.get("fax"),
                asDouble(fields.get("latitude")),
                asDouble(fields.get("longitude")),
                fields.get("homepage"),
                fields.get("closed"),
                fields.get("operatingTime"),
                asInt(fields.get("BookCount")));
    }

    /** 주소 앞머리에서 시도를 뽑아 지역 코드로 바꿉니다. 소장 조회에 region 이 필요합니다. */
    public Optional<RegionCode> region() {
        return address == null ? Optional.empty() : RegionCode.ofSido(address.trim());
    }

    private static Double asDouble(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;   // 값이 이상해도 도서관 자체를 버리지는 않습니다.
        }
    }

    private static Integer asInt(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
