package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WimbConfigurationTest {

    @Test
    @DisplayName("User-Agent 로 실제 요청을 만들 수 있다")
    void userAgentCanActuallyBuildARequest() {
        // 한글이 들어 있으면 자바가 여기서 IllegalArgumentException 을 던집니다.
        // 그러면 요청이 나가지도 않은 채 실패하는데, 화면에는 "확인 불가"로만 보여서
        // 원인을 찾는 데 한참 걸립니다. 실제로 그 상태로 한동안 돌고 있었습니다.
        assertDoesNotThrow(() -> HttpRequest.newBuilder(URI.create("https://data4library.kr/"))
                .header("User-Agent", WimbConfiguration.USER_AGENT)
                .GET().build());
    }

    @Test
    @DisplayName("User-Agent 는 ASCII 로만 이루어진다")
    void userAgentIsAscii() {
        String ua = WimbConfiguration.USER_AGENT;
        assertEquals(ua, new String(ua.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII),
                "헤더 값은 ASCII 만 허용합니다: " + ua);
        assertFalse(ua.isBlank(), "누구인지는 밝혀야 합니다");
    }
}
