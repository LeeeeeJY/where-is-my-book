package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/**
 * API 도메인의 {@code robots.txt} 가 실제로 나가는지 봅니다.
 *
 * <p><b>파일만 두고 확인하지 않으면 조용히 404 가 됩니다.</b> 그러면 크롤러는 막는 규칙이
 * 없는 것으로 보고 그대로 훑습니다. 화면의 도서관 링크가 {@code /api/go/{부호}} 로 나가는
 * {@code <a>} 태그라, 소장 도서관이 스무 곳이면 링크도 스무 개이고 그 하나하나가 우리 쪽
 * 호출 제한을 깎습니다. {@code /api/diagnose} 는 열 때마다 정보나루를 한 번 부릅니다.
 *
 * <p>그런데 <b>{@code /api/libraries} 까지 막으면 안 됩니다.</b> 구글은 자바스크립트를
 * 실행해 화면을 그려 보는데, 그 호출을 막으면 도서관 목록을 받지 못해 「API 서버에 연결하지
 * 못했습니다」 배너가 뜬 화면을 색인합니다. 그 문장이 검색 결과에 실리는 것이 크롤링 몇
 * 번보다 나쁩니다. 양쪽을 한꺼번에 지키는지 여기서 고정합니다.
 */
@SpringBootTest(properties = "wimb.data4library.auth-key=test-key-not-used-for-calls")
@AutoConfigureMockMvc
class ApiRobotsTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("robots.txt 가 실제로 나간다")
    void robotsIsServed() throws Exception {
        mvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("User-agent: *")));
    }

    @Test
    @DisplayName("크롤러에게 API 를 훑지 말라고 하되 도서관 목록은 열어 둔다")
    void crawlersAreKeptOutExceptForTheCatalog() throws Exception {
        mvc.perform(get("/robots.txt"))
                .andExpect(content().string(containsString("Disallow: /api/")))
                // 이 줄이 Disallow 보다 먼저 와야 첫 줄만 보는 크롤러도 목록을 받아 갑니다.
                .andExpect(content().string(containsString("Allow: /api/libraries")));
    }

    @Test
    @DisplayName("호출 제한은 robots.txt 를 막지 않는다")
    void theRateLimitDoesNotBlockRobots() throws Exception {
        // 인터셉터가 /api/** 에만 걸려 있습니다. 여기까지 걸면 크롤러가 규칙을 못 읽어
        // 막는 것이 없는 줄 알고 훑게 됩니다.
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/robots.txt")).andExpect(status().isOk());
        }
    }
}
