package kr.wimb.opac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 정보나루가 준 홈페이지 주소 가운데 실제로 보낼 수 있는 것 고르기.
 *
 * <p>여기서 걸러 내지 못한 값은 {@code Location} 헤더에 그대로 실려 나가고, 브라우저가
 * 상대 주소로 읽어 <b>우리 서버 안의 없는 경로</b>로 갑니다. 도서관을 눌렀는데 도서관이
 * 아니라 우리 오류 화면이 뜨는 것입니다. 실제 값으로 고정합니다.
 */
class HomepageTest {

    @Test
    @DisplayName("멀쩡한 주소는 그대로 쓴다")
    void keepsUsableUrls() {
        assertEquals("https://www.nl.go.kr", Homepage.usable("https://www.nl.go.kr").orElseThrow());
        assertEquals("http://phlib.pohang.go.kr/",
                Homepage.usable("http://phlib.pohang.go.kr/").orElseThrow());
    }

    @Test
    @DisplayName("정보나루의 「없음」 자리표는 주소가 아니다")
    void rejectsPlaceholders() {
        // 실제 목록에서 열한 곳이 이 값을 홈페이지로 가지고 있었습니다.
        assertTrue(Homepage.usable("-").isEmpty());
        assertTrue(Homepage.usable(" - ").isEmpty());
        assertTrue(Homepage.usable("없음").isEmpty());
        assertTrue(Homepage.usable("").isEmpty());
        assertTrue(Homepage.usable(null).isEmpty());
    }

    @Test
    @DisplayName("스킴만 빠진 진짜 주소는 살린다")
    void addsMissingScheme() {
        // 「동천도서관」과 「보정도서관」이 실제로 이렇게 옵니다. 주소가 멀쩡한데 버리면
        // 갈 수 있는 곳을 못 가게 됩니다.
        assertEquals("http://lib.yongin.go.kr/dongcheon",
                Homepage.usable("lib.yongin.go.kr/dongcheon").orElseThrow());
        assertEquals("http://www.dcasia.or.kr", Homepage.usable("www.dcasia.or.kr").orElseThrow());
    }

    @Test
    @DisplayName("우리가 다룰 수 없는 것은 보내지 않는다")
    void rejectsWhatWeCannotSend() {
        assertTrue(Homepage.usable("ftp://lib.example.kr").isEmpty());
        assertTrue(Homepage.usable("mailto:lib@example.kr").isEmpty());
        // 점이 없으면 도메인이 아닙니다. 밖으로 보낼 수 없습니다.
        assertTrue(Homepage.usable("http://localhost").isEmpty());
        assertTrue(Homepage.usable("도서관").isEmpty());
    }
}
