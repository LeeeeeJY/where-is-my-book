package kr.wimb.api;

import kr.wimb.opac.DetailResolver;
import kr.wimb.opac.OpacHostTally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 호스트별 상세 집계가 <b>주소째로 이어져 있는지</b> 봅니다.
 *
 * <p>집계를 세는 것은 {@code DetailResolverTest} 가, 파일로 이어받는 것은
 * {@code OpacHostTallyStoreTest} 가 봅니다. 여기서 보는 것은 진단 주소가 서버가 세어 둔 바로 그
 * 집계를 읽는지입니다. 컨트롤러가 다른 해석기를 들고 있으면 주소는 200 을 주면서 늘 빈 목록입니다.
 *
 * <p>이 주소는 어디로도 요청을 보내지 않습니다. 정보나루를 부르지 않는 것은 {@link WimbContextTest}
 * 가 테스트 클래스가 끝날 때 봅니다.
 */
// 이 테스트만 쓰는 컨텍스트입니다. 집계는 컨텍스트가 사는 동안 쌓이므로, 다른 컨텍스트 테스트와
// 함께 쓰면 그쪽이 누른 링크가 여기의 호스트 수에 섞입니다. 값 자체는 어디서도 읽지 않습니다.
@WimbContextTest(properties = "wimb.test.context=opac-hosts-endpoint")
@AutoConfigureMockMvc
class OpacHostsEndpointTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DetailResolver detailResolver;

    @Test
    @DisplayName("서버가 세어 둔 것을 받지 못한 횟수가 많은 호스트부터 내보낸다")
    void servesWhatTheResolverTallied() throws Exception {
        var tally = detailResolver.hostTally();
        tally.recordAnswered("lib.fine.example.kr", OpacHostTally.Event.RESOLVED, 700, null);
        tally.recordAnswered("lib.fine.example.kr", OpacHostTally.Event.RESOLVED, 900, null);
        tally.record("lib.blocked.example.kr", OpacHostTally.Event.CONNECT_TIMEOUT,
                "HttpConnectTimeoutException: HTTP connect timed out");

        mvc.perform(get("/api/diagnose/opac/hosts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.since").isNotEmpty())
                .andExpect(jsonPath("$.hostCount").value(2))
                .andExpect(jsonPath("$.hosts[0].host").value("lib.blocked.example.kr"))
                .andExpect(jsonPath("$.hosts[0].connectTimeout").value(1))
                .andExpect(jsonPath("$.hosts[0].lastFailure")
                        .value("HttpConnectTimeoutException: HTTP connect timed out"))
                .andExpect(jsonPath("$.hosts[1].host").value("lib.fine.example.kr"))
                .andExpect(jsonPath("$.hosts[1].attempts").value(2))
                .andExpect(jsonPath("$.hosts[1].resolved").value(2))
                .andExpect(jsonPath("$.hosts[1].avgMillis").value(800));
    }
}
