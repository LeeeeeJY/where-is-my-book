package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 실패한 이유를 화면까지 실어 보내되, <b>읽는 사람을 갈라 놓습니다.</b>
 *
 * <p>이유를 버리면 안 된다는 것은 하루를 들여 배웠습니다. 정보나루는 무엇이 잘못됐는지
 * 한국어로 또박또박 알려 주는데 Spring 의 기본 오류 본문은 그것을 싣지 않아서, 화면에는
 * <b>「API 오류 503」</b>만 떴고 원인을 코드에서 찾았습니다. <b>서버가 이미 알고 있는 답을
 * 화면이 버리면 그다음은 전부 추측입니다.</b>
 *
 * <p>그런데 그렇게 실어 보낸 문구는 <b>운영자 한 사람을 위해 쓴 것</b>이었습니다. 그때는
 * 화면을 보는 사람이 우리뿐이었으니 맞는 선택이었지만, 주소를 공개하는 순간 독자가
 * 바뀝니다. 처음 온 사람에게 <b>「인증키를 확인해 주세요」나 「나가는 IP 가 등록한 것과
 * 같은지 확인해 주세요」는 아무 뜻도 없습니다.</b> 자기가 뭘 잘못했나 싶게 만들거나,
 * 그대로 갈무리되어 돌아다닙니다.
 *
 * <p>그래서 응답에 둘을 함께 싣습니다.
 *
 * <ul>
 *   <li>{@code reason} — <b>방문자가 읽을 문구.</b> 화면이 그리는 것은 이것뿐입니다.
 *       그 사람이 할 수 있는 일만 말하고, <b>「책이 없다는 뜻이 아니다」를 반드시
 *       덧붙입니다.</b> 이 서비스에서 가장 하면 안 되는 일이 없는 책을 없다고 답하는
 *       것인데, 조회에 실패한 화면은 그것과 구별되지 않기 때문입니다.</li>
 *   <li>{@code detail} — <b>운영자용.</b> 정보나루가 준 원문과 무엇을 봐야 하는지가
 *       들어갑니다. 화면에 그리지 마세요. 버리지도 마세요.</li>
 *   <li>{@code code} — 정보나루의 {@code errCode}. 화면이 코드별로 할 일을 갈라 말할 수
 *       있게 남겨 둡니다.</li>
 * </ul>
 *
 * <p>운영자가 실제로 쓰는 통로는 {@code GET /api/diagnose} 입니다. 나가는 IP 와 남은
 * 예산을 말하고 정보나루를 한 번 불러 봅니다. 여기 있던 안내가 그쪽에 이미 다 있으므로,
 * 방문자 화면에서 빼도 잃는 것이 없습니다.
 *
 * <p><b>인증키는 절대 싣지 마세요.</b> 정보나루의 오류 메시지에는 키가 들어 있지 않지만,
 * 예외의 원인 사슬이나 요청 URL 을 통째로 담기 시작하면 키가 새어 나갑니다.
 */
@RestControllerAdvice
public class ApiErrorAdvice {

    /**
     * 정보나루가 오류를 돌려준 경우. <b>우리 서버가 고장 난 것이 아닙니다.</b>
     *
     * <p>그래서 500 이 아니라 503 입니다. 500 은 「우리가 고장」이라는 뜻이고, 실제로는
     * 물어보지 못한 것입니다. 화면은 이것을 「결과 없음」이 아니라 「확인 불가」로 그려야
     * 합니다. 없다고 답하면 실제로 있는 책을 놓치게 됩니다.
     */
    @ExceptionHandler(Data4LibraryClient.ApiErrorException.class)
    public ResponseEntity<Map<String, String>> data4Library(
            Data4LibraryClient.ApiErrorException e) {
        String code = e.error().code();
        return body(HttpStatus.SERVICE_UNAVAILABLE, code,
                visitorMessage(code), operatorDetail(code, e.error().message()));
    }

    /** 오늘 몫을 다 쓴 것은 오류가 아니라 정상 상태입니다. 다만 화면은 그 사실을 알아야 합니다. */
    @ExceptionHandler(Data4LibraryClient.BudgetExhaustedException.class)
    public ResponseEntity<Map<String, String>> budget(
            Data4LibraryClient.BudgetExhaustedException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "budgetExhausted",
                visitorMessage("budgetExhausted"), e.getMessage());
    }

    /**
     * 주소 하나가 너무 많이 부른 경우. <b>우리 서버도 정보나루도 고장 나지 않았습니다.</b>
     *
     * <p>{@code Retry-After} 를 함께 보냅니다. 「나중에 다시」라고만 하면 사람도 프로그램도
     * 언제가 나중인지 몰라 곧바로 다시 두드리고, 그러면 막으려던 것이 그대로 반복됩니다.
     *
     * <p>이 문구는 {@link RateLimitInterceptor} 가 처음부터 방문자를 향해 쓴 것이라 그대로
     * 내보냅니다. <b>화면은 이것을 「미소장」이 아니라 「확인 불가」로 그려야 합니다.</b>
     */
    @ExceptionHandler(RateLimit.LimitExceededException.class)
    public ResponseEntity<Map<String, String>> rateLimited(RateLimit.LimitExceededException e) {
        String code = e.decision().scope() == RateLimit.Scope.DAY
                ? "clientDailyLimit" : "clientRateLimit";
        Map<String, String> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(e.decision().retryAfterSeconds()))
                .body(out);
    }

    /**
     * 컨트롤러가 직접 던진 것.
     *
     * <p><b>4xx 와 5xx 는 읽는 사람이 다릅니다.</b> 4xx 는 요청이 잘못된 것이라 우리가 쓴
     * 문구가 곧 방문자에게 할 말입니다(「검색 조건이 비어 있습니다」). 5xx 는 우리 사정이라
     * 내부 문구가 섞여 들어옵니다. 실제로 {@code /api/libraries} 는 정보나루 예외의
     * {@code getMessage()} 를 그대로 실어 「정보나루 srchBooks 가 오류를 돌려주었습니다:
     * … (outOflimit)」 같은 문장이 나옵니다. 그것을 방문자에게 보여 줄 이유가 없습니다.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        if (status.is4xxClientError()) {
            return body(status, null, e.getReason(), null);
        }
        return body(status, null, visitorMessage(null), e.getReason());
    }

    /**
     * 방문자가 읽을 문구. <b>그 사람이 할 수 있는 일만 말합니다.</b>
     *
     * <p>셋을 갈라 놓는 것이 중요합니다. 오늘 몫을 다 쓴 것은 <b>내일이면 낫고</b>, 인증
     * 계열은 <b>기다려도 낫지 않으며</b>(우리가 고쳐야 합니다), 나머지는 <b>잠시 뒤에 다시
     * 하면 될 수 있습니다.</b> 한 문구로 뭉뚱그리면 낫지 않을 것을 계속 새로 고치게 되거나,
     * 잠깐 흔들린 것을 두고 영영 안 되는 줄 알고 떠납니다.
     *
     * <p>그리고 <b>어느 경우에도 「책이 없다」로 읽히면 안 됩니다.</b> 조회에 실패한 화면과
     * 소장한 곳이 없는 화면은 사용자에게 똑같이 「안 나온다」로 보입니다. 그 둘을 갈라
     * 놓는 것이 이 서비스가 존재하는 이유이므로, 문구에서도 갈라 말합니다.
     */
    static String visitorMessage(String code) {
        return switch (code == null ? "" : code) {
            case "outOflimit", "budgetExhausted" -> "오늘 확인할 수 있는 횟수를 다 썼습니다. "
                    + "책이 없다는 뜻이 아니니 내일 다시 시도해 주세요.";
            case "authErr", "vitalizationErr" -> "도서관 정보를 받아 오는 곳에 연결하지 못하고 "
                    + "있습니다. 책이 없다는 뜻이 아니고, 고쳐야 하는 것은 이 서비스 쪽입니다.";
            default -> "지금은 도서관 정보를 확인할 수 없습니다. "
                    + "책이 없다는 뜻이 아니니 잠시 뒤에 다시 시도해 주세요.";
        };
    }

    /**
     * 운영자가 읽을 것. <b>화면에 그리지 마세요.</b>
     *
     * <p>정보나루의 원문을 그대로 두면 무엇을 해야 하는지까지는 알기 어렵습니다.
     * 특히 {@code outOflimit} 은 <b>「호출을 너무 많이 했다」가 아니라 「등록한 IP 로 나가고
     * 있지 않다」는 뜻일 때가 많습니다.</b> 등록하지 않은 IP 는 하루 500건이라 금방 닿습니다.
     * 서버를 옮기거나 다시 띄워 나가는 주소가 바뀌면 조용히 이 상태가 됩니다.
     */
    static String operatorDetail(String code, String message) {
        return switch (code == null ? "" : code) {
            case "outOflimit" -> message + " — 서버의 나가는 IP 가 정보나루에 등록한 것과 "
                    + "같은지 확인해 주세요(GET /api/diagnose). 등록되지 않은 IP 는 하루 "
                    + "500건까지만 됩니다.";
            case "authErr" -> message + " — 인증키를 확인해 주세요.";
            case "vitalizationErr" -> message + " — 인증키가 아직 활성화되지 않았습니다.";
            default -> message;
        };
    }

    private static ResponseEntity<Map<String, String>> body(
            HttpStatus status, String code, String reason, String detail) {
        Map<String, String> out = new LinkedHashMap<>();
        if (code != null) out.put("code", code);
        out.put("reason", reason == null ? visitorMessage(null) : reason);
        // 운영자용 항목은 값이 있을 때만 싣습니다. 방문자용과 같은 문장을 두 번 보내
        // 화면이 어느 쪽을 그려야 하는지 헷갈리게 만들 이유가 없습니다.
        if (detail != null && !detail.isBlank() && !detail.equals(reason)) out.put("detail", detail);
        return ResponseEntity.status(status).body(out);
    }
}
