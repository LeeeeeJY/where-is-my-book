package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>화면 문구의 독자는 방문자입니다.</b>
 *
 * <p>이 문구들은 원래 운영자 한 사람을 위해 쓰였습니다. 그때는 화면을 보는 사람이 우리뿐
 * 이었으니 맞는 선택이었지만, 주소를 공개하면 독자가 바뀝니다. 처음 온 사람에게
 * 「인증키를 확인해 주세요」는 아무 뜻도 없습니다.
 *
 * <p>여기서 고정하는 것은 <b>「이 문구를 처음 보는 사람이 읽어도 말이 되는가」</b>입니다.
 * 새 오류를 더할 때 운영자 어휘가 방문자 문구로 새어 나가는 것을 이 테스트가 잡습니다.
 */
class ApiErrorAdviceTest {

    private final ApiErrorAdvice advice = new ApiErrorAdvice();

    /** 방문자가 읽을 자리에 나오면 안 되는 말. 우리 사정이지 그 사람이 할 일이 아닙니다. */
    private static final List<String> OPERATOR_WORDS =
            List.of("인증키", "IP", "errCode", "캐시", "authKey");

    private static final List<String> CODES =
            List.of("outOflimit", "budgetExhausted", "authErr", "vitalizationErr", "알 수 없는코드");

    @Test
    @DisplayName("방문자 문구에는 운영자용 어휘가 섞이지 않는다")
    void visitorMessagesCarryNoOperatorJargon() {
        for (String code : CODES) {
            String message = ApiErrorAdvice.visitorMessage(code);
            for (String word : OPERATOR_WORDS) {
                assertFalse(message.contains(word),
                        code + " 문구에 「" + word + "」가 들어 있습니다: " + message);
            }
        }
        // null 은 코드를 모르는 경우입니다. 이것도 방문자가 봅니다.
        assertFalse(ApiErrorAdvice.visitorMessage(null).contains("인증키"));
    }

    @Test
    @DisplayName("어떤 실패든 「책이 없다」로 읽히지 않게 말한다")
    void visitorMessagesNeverReadAsTheBookBeingAbsent() {
        // **조회에 실패한 화면과 소장한 곳이 없는 화면은 사용자에게 똑같이 「안 나온다」로
        // 보입니다.** 그 둘을 갈라 놓는 것이 이 서비스가 존재하는 이유입니다.
        for (String code : CODES) {
            assertTrue(ApiErrorAdvice.visitorMessage(code).contains("책이 없다는 뜻이 아니"),
                    code + " 문구가 없는 책과 구별해 주지 않습니다");
        }
    }

    @Test
    @DisplayName("사람이 할 일이 다른 셋을 갈라 말한다")
    void visitorMessagesSeparateWhatThePersonShouldDo() {
        String today = ApiErrorAdvice.visitorMessage("outOflimit");
        String ours = ApiErrorAdvice.visitorMessage("authErr");
        String transient_ = ApiErrorAdvice.visitorMessage(null);

        // 오늘 몫을 다 쓴 것은 내일이면 낫고, 인증 계열은 기다려도 낫지 않으며(우리가
        // 고쳐야 합니다), 나머지는 잠시 뒤에 다시 하면 될 수 있습니다. 한 문구로
        // 뭉뚱그리면 낫지 않을 것을 계속 새로 고치게 됩니다.
        assertNotEquals(today, ours);
        assertNotEquals(ours, transient_);
        assertNotEquals(today, transient_);
        assertTrue(today.contains("내일"), "오늘 몫을 다 쓴 것은 내일이면 낫습니다");
        assertTrue(transient_.contains("잠시"), "잠깐 흔들린 것은 다시 해 보면 됩니다");
    }

    @Test
    @DisplayName("운영자용 안내는 버리지 않고 detail 에 남는다")
    void operatorGuidanceIsKeptRatherThanDropped() {
        // 화면에서 뺀 것이지 없앤 것이 아닙니다. 이유를 버리면 그다음은 전부 추측입니다.
        String detail = ApiErrorAdvice.operatorDetail("outOflimit", "일일 허용량을 초과하였습니다.");
        assertTrue(detail.contains("일일 허용량을 초과하였습니다."), "정보나루 원문이 사라졌습니다");
        assertTrue(detail.contains("IP"), "무엇을 봐야 하는지가 사라졌습니다");
        assertTrue(detail.contains("/api/diagnose"), "어디를 봐야 하는지가 사라졌습니다");
    }

    @Test
    @DisplayName("잘못된 요청(4xx)은 우리가 쓴 문구를 그대로 쓴다")
    void badRequestsKeepTheirOwnWording() {
        // 4xx 는 요청이 잘못된 것이라 우리가 쓴 문구가 곧 방문자에게 할 말입니다.
        var response = advice.status(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "검색 조건이 비어 있습니다."));

        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(response.getStatusCode().value()));
        assertEquals("검색 조건이 비어 있습니다.", body(response).get("reason"));
    }

    @Test
    @DisplayName("서버 쪽 실패(5xx)의 내부 문구는 방문자에게 보이지 않는다")
    void internalWordingDoesNotReachVisitorsOnServerErrors() {
        // /api/libraries 는 정보나루 예외의 getMessage() 를 그대로 실어 올립니다.
        var response = advice.status(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "정보나루 srchBooks 가 오류를 돌려주었습니다: 일일 허용량 초과 (outOflimit)"));

        Map<String, String> body = body(response);
        assertFalse(body.get("reason").contains("srchBooks"), "내부 문구가 화면까지 갔습니다");
        assertFalse(body.get("reason").contains("outOflimit"), "오류 코드가 화면까지 갔습니다");
        assertTrue(body.get("reason").contains("책이 없다는 뜻이 아니"));
        assertTrue(body.get("detail").contains("srchBooks"), "운영자가 볼 것까지 버렸습니다");
    }

    @Test
    @DisplayName("한도에 걸리면 Retry-After 와 함께 방문자 문구를 보낸다")
    void rateLimitAnswersWithRetryAfter() {
        var limit = new RateLimit(60, 60, 0, 100, Clock.systemUTC());
        assertTrue(limit.check("1.2.3.4", 60).allowed());
        var decision = limit.check("1.2.3.4", 60);
        assertFalse(decision.allowed());

        var response = advice.rateLimited(
                new RateLimit.LimitExceededException(decision, "요청이 너무 빠릅니다."));

        assertEquals(429, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getFirst("Retry-After"),
                "언제 다시 하면 되는지 말해 주지 않으면 곧바로 다시 두드립니다");
        assertEquals("clientRateLimit", body(response).get("code"));
    }

    @Test
    @DisplayName("방문자 문구와 같은 말을 detail 로 한 번 더 보내지 않는다")
    void detailIsOmittedWhenItWouldRepeatTheVisitorMessage() {
        var response = advice.status(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "검색 조건이 비어 있습니다."));
        assertFalse(body(response).containsKey("detail"));
    }

    private static Map<String, String> body(org.springframework.http.ResponseEntity<Map<String, String>> response) {
        Map<String, String> out = response.getBody();
        assertNotNull(out, "본문이 비어 있으면 화면은 상태 코드만 보게 됩니다");
        return out;
    }
}
