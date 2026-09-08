package kr.wimb.data4library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 주소에서 시도를 알아내는 부분.
 *
 * <p><b>여기서 틀리면 조용히 틀립니다.</b> 지역 코드는 화면의 묶음에만 쓰이는 것이 아니라
 * {@code libSrchByBook} 의 필수 파라미터로 나갑니다. 충청남도 도서관에 충청북도 코드를 보내면
 * 그 도서관은 응답에 없고, 화면에는 「미소장」으로 나옵니다. 실제로 있는 책을 없다고 답하는
 * 것이라, 링크가 깨지거나 오류가 나는 것보다 훨씬 나쁩니다.
 */
class RegionCodeTest {

    private static String codeOf(String address) {
        return RegionCode.ofSido(address).map(RegionCode::code).orElse("못 찾음");
    }

    @Test
    @DisplayName("남도와 북도를 헷갈리지 않는다")
    void doesNotConfuseNorthAndSouth() {
        // 앞 두 글자로 맞추던 시절에는 「충청」이 충청북도에 먼저 걸려 충남이 충북이 되었습니다.
        assertEquals("34", codeOf("충청남도 천안시 서북구"));
        assertEquals("33", codeOf("충청북도 청주시 상당구"));
        assertEquals("38", codeOf("경상남도 창원시 의창구"));
        assertEquals("37", codeOf("경상북도 포항시 남구"));
        assertEquals("36", codeOf("전라남도 순천시"));
        assertEquals("35", codeOf("전북특별자치도 전주시"));
    }

    @Test
    @DisplayName("줄임말로 적힌 주소도 알아본다")
    void understandsShortForms() {
        // 이걸 못 알아보면 「충남」이라는 별도 묶음이 생겨 같은 도가 둘로 갈립니다.
        assertEquals("34", codeOf("충남 천안시 서북구"));
        assertEquals("38", codeOf("경남 창원시"));
        assertEquals("37", codeOf("경북 포항시"));
        assertEquals("33", codeOf("충북 청주시"));
        assertEquals("35", codeOf("전북 전주시"));
        assertEquals("36", codeOf("전남 순천시"));
    }

    @Test
    @DisplayName("옛 이름으로 적힌 주소도 알아본다")
    void understandsOldNames() {
        // 강원도·제주도·전라북도가 특별자치도로 바뀌었지만 옛 표기가 섞여 들어옵니다.
        assertEquals("32", codeOf("강원도 춘천시"));
        assertEquals("32", codeOf("강원특별자치도 춘천시"));
        assertEquals("39", codeOf("제주도 제주시"));
        assertEquals("39", codeOf("제주특별자치도 제주시"));
        assertEquals("35", codeOf("전라북도 전주시"));
    }

    @Test
    @DisplayName("나머지 시도도 그대로 찾는다")
    void findsEveryOtherRegion() {
        assertEquals("11", codeOf("서울특별시 서초구 반포대로 201"));
        assertEquals("21", codeOf("부산광역시 해운대구"));
        assertEquals("22", codeOf("대구광역시 중구"));
        assertEquals("23", codeOf("인천광역시 미추홀구"));
        assertEquals("24", codeOf("광주광역시 서구"));
        assertEquals("25", codeOf("대전광역시 유성구"));
        assertEquals("26", codeOf("울산광역시 남구"));
        assertEquals("29", codeOf("세종특별자치시 한누리대로"));
        assertEquals("31", codeOf("경기도 성남시 분당구"));
    }

    @Test
    @DisplayName("모든 시도가 자기 이름으로 자기를 찾는다")
    void everyRegionFindsItself() {
        // 별칭을 하나 더할 때 다른 시도를 가로채지 않는지 여기서 걸립니다.
        for (RegionCode region : RegionCode.values()) {
            assertEquals(region.code(), codeOf(region.sido()),
                    region.sido() + " 가 자기 코드를 찾지 못합니다");
        }
    }

    @Test
    @DisplayName("알 수 없는 주소는 비어 있는 값을 준다")
    void unknownAddressIsEmpty() {
        // 억지로 아무 시도나 고르면 그 도서관의 소장 조회가 조용히 틀립니다.
        assertTrue(RegionCode.ofSido("").isEmpty());
        assertTrue(RegionCode.ofSido(null).isEmpty());
        assertTrue(RegionCode.ofSido("어딘가 이상한 주소").isEmpty());
    }
}
