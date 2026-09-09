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
 * 실패한 이유를 화면까지 그대로 실어 보냅니다.
 *
 * <p><b>이것이 없어서 하루를 추측으로 보냈습니다.</b> 정보나루는 무엇이 잘못됐는지 한국어로
 * 또박또박 알려 줍니다. 「1일 500건 이상 요청 시 IP 등록이 필요합니다」처럼 사람이 바로 손쓸
 * 수 있는 문장입니다. 그런데 그 문장이 서버에서 멈추고 화면에는 <b>「API 오류 503」</b>만
 * 떴습니다. Spring 의 기본 오류 본문은 {@code server.error.include-message} 가 {@code never}
 * 라 이유를 싣지 않기 때문입니다.
 *
 * <p>그래서 「검색이 안 된다」는 말만 남고, 원인을 코드에서 찾게 됩니다. 실제로 그렇게 여러
 * 곳을 헛짚었습니다. <b>서버가 이미 알고 있는 답을 화면이 버리고 있으면, 그다음은 전부
 * 추측입니다.</b>
 *
 * <p>정보나루의 오류 코드({@code errCode})도 함께 내보냅니다. 화면이 코드별로 할 일을
 * 갈라 말해 줄 수 있어야 하기 때문입니다. 인증키가 문제인지, IP 등록이 문제인지, 오늘 몫을
 * 다 쓴 것인지에 따라 사람이 해야 하는 일이 완전히 다릅니다.
 *
 * <p><b>인증키는 절대 싣지 마세요.</b> 정보나루의 오류 메시지에는 키가 들어 있지 않지만,
 * 여기에 예외의 원인 사슬이나 요청 URL 을 통째로 담기 시작하면 키가 새어 나갑니다.
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
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.error().code(), reasonFor(e));
    }

    /** 오늘 몫을 다 쓴 것은 오류가 아니라 정상 상태입니다. 다만 화면은 그 사실을 알아야 합니다. */
    @ExceptionHandler(Data4LibraryClient.BudgetExhaustedException.class)
    public ResponseEntity<Map<String, String>> budget(Data4LibraryClient.BudgetExhaustedException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "budgetExhausted", e.getMessage());
    }

    /**
     * 주소 하나가 너무 많이 부른 경우. <b>우리 서버도 정보나루도 고장 나지 않았습니다.</b>
     *
     * <p>{@code Retry-After} 를 함께 보냅니다. 「나중에 다시」라고만 하면 사람도 프로그램도
     * 언제가 나중인지 몰라 곧바로 다시 두드리고, 그러면 막으려던 것이 그대로 반복됩니다.
     *
     * <p><b>화면은 이것을 「미소장」이 아니라 「확인 불가」로 그려야 합니다.</b> 물어보지
     * 못한 것이지 그 책이 없다는 뜻이 아닙니다. 여기서 이 구분이 무너지면 실제로 소장한
     * 책을 없다고 답하게 되고, 그것이 이 도구를 못 쓰게 만드는 가장 빠른 길입니다.
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

    /** 컨트롤러가 직접 던진 것. 여기서도 이유를 본문에 실어 줍니다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return body(HttpStatus.valueOf(e.getStatusCode().value()), null, e.getReason());
    }

    /**
     * 사람이 읽고 바로 손쓸 수 있는 문장으로 바꿉니다.
     *
     * <p>정보나루의 원문을 그대로 두면 무엇을 해야 하는지까지는 알기 어렵습니다.
     * 특히 {@code outOflimit} 은 <b>「호출을 너무 많이 했다」가 아니라 「등록한 IP 로 나가고
     * 있지 않다」는 뜻일 때가 많습니다.</b> 등록하지 않은 IP 는 하루 500건이라 금방 닿습니다.
     * 서버를 옮기거나 다시 띄워 나가는 주소가 바뀌면 조용히 이 상태가 됩니다.
     */
    private static String reasonFor(Data4LibraryClient.ApiErrorException e) {
        String message = e.error().message();
        return switch (e.error().code()) {
            case "outOflimit" -> message + " — 서버의 나가는 IP 가 정보나루에 등록한 것과 "
                    + "같은지 확인해 주세요. 등록되지 않은 IP 는 하루 500건까지만 됩니다.";
            case "authErr" -> message + " — 인증키를 확인해 주세요.";
            case "vitalizationErr" -> message + " — 인증키가 아직 활성화되지 않았습니다.";
            default -> message;
        };
    }

    private static ResponseEntity<Map<String, String>> body(
            HttpStatus status, String code, String reason) {
        Map<String, String> out = new LinkedHashMap<>();
        if (code != null) out.put("code", code);
        out.put("reason", reason == null ? "이유를 알 수 없습니다." : reason);
        return ResponseEntity.status(status).body(out);
    }
}
