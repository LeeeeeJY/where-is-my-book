package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.web.cors.CorsConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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


    /**
     * 운영에 넣을 허용 주소가 실제로 무엇을 통과시키고 무엇을 막는지.
     *
     * <p>이 값은 <b>서버 바깥에 있습니다.</b> 환경 변수로 들어오므로 컴파일도 검사도
     * 되지 않고, 틀려도 서버는 멀쩡히 뜹니다. 드러나는 자리가 브라우저뿐이라 화면에는
     * 「API 서버에 연결하지 못했습니다」로만 보이는데, <b>서버는 멀쩡하고 주소도
     * 맞습니다.</b> 그러면 멀쩡한 서버를 다시 띄우게 됩니다.
     */
    private static CorsConfiguration asConfigured(String... patterns) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(patterns));
        config.setAllowedMethods(List.of("GET", "POST"));
        return config;
    }

    /** `scripts/vm/wimb.env.example` 이 실제로 권하는 값입니다. */
    private static String[] recommendedOrigins() throws IOException {
        Path example = Path.of("..", "scripts", "vm", "wimb.env.example");
        assertTrue(Files.exists(example), "서버 설정 본보기가 있어야 합니다: " + example);
        String line = Files.readAllLines(example).stream()
                .filter(one -> one.startsWith("WIMB_CORS_ALLOWED_ORIGINS="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("본보기에 허용 주소가 없습니다"));
        return line.substring(line.indexOf('=') + 1).split(",");
    }

    @Test
    @DisplayName("권하는 값이 운영 주소와 프리뷰 주소를 모두 통과시킨다")
    void recommendedOriginsLetBothProductionAndPreviewIn() throws IOException {
        CorsConfiguration config = asConfigured(recommendedOrigins());

        // 운영. 이것이 막히면 사이트가 통째로 멈춥니다.
        assertNotNull(config.checkOrigin("https://where-is-my-book.vercel.app"));

        // 프리뷰. Vercel 이 배포마다 주소를 새로 만들므로 사람이 따라갈 수 없습니다.
        // 실제로 이 PR 의 프리뷰가 받은 주소입니다.
        assertNotNull(config.checkOrigin(
                "https://where-is-my-book-git-claude-libr-70a91a-leees-projects-5c7652c0.vercel.app"));
        assertNotNull(config.checkOrigin("https://where-is-my-book-abc123.vercel.app"));
    }

    @Test
    @DisplayName("권하는 값이 남의 주소는 막는다")
    void recommendedOriginsKeepStrangersOut() throws IOException {
        CorsConfiguration config = asConfigured(recommendedOrigins());

        assertNull(config.checkOrigin("https://evil.example.com"));

        // **뒤에 덧붙인 주소를 막는지가 핵심입니다.** 별표를 쓰면서 `.vercel.app` 으로
        // 끝맺지 않으면 「우리 주소로 시작하기만 하면」 통과하게 되어, 아무나
        // where-is-my-book.vercel.app.남의도메인 을 띄워 놓고 우리 API 를 부릅니다.
        assertNull(config.checkOrigin("https://where-is-my-book.vercel.app.evil.com"));
        assertNull(config.checkOrigin("https://where-is-my-book-abc.vercel.app.evil.com"));

        // 다른 프로젝트의 프리뷰도 남입니다. https://*.vercel.app 으로 넓히면 이것이
        // 통과하는데, 그러면 누가 부를 수 있는지를 목록만 보고는 알 수 없어집니다.
        assertNull(config.checkOrigin("https://someone-elses-app.vercel.app"));
    }

    @Test
    @DisplayName("글자가 그대로 같기를 요구하면 프리뷰가 막힌다")
    void exactOriginsWouldBlockPreviews() {
        // **`allowedOrigins` 가 아니라 `allowedOriginPatterns` 를 쓰는 이유입니다.**
        // 앞엣것으로 두면 아래가 통과하지 못하는데, 그 사실이 서버 쪽에서는 아무
        // 흔적도 남기지 않습니다.
        CorsConfiguration exact = new CorsConfiguration();
        exact.setAllowedOrigins(List.of("https://where-is-my-book.vercel.app"));
        assertNull(exact.checkOrigin("https://where-is-my-book-abc123.vercel.app"));
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
