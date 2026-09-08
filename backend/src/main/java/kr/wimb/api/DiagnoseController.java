package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「왜 아무것도 안 나오는가」에 한 번에 답하는 화면.
 *
 * <p><b>이것이 없어서 하루를 썼습니다.</b> 정보나루가 {@code outOflimit} 을 돌려주고 있었고
 * 그 뜻은 「등록한 IP 로 나가고 있지 않다」였는데, 그것을 알아내려면 서버에 들어가 curl 을
 * 쳐야 했습니다. 그동안 화면에는 「검색이 안 된다」만 보였고, 원인을 코드에서 찾았습니다.
 *
 * <p>가장 중요한 것이 <b>나가는 IP</b> 입니다. 정보나루에 등록하는 것이 이 주소인데,
 * <b>GCE 무료 등급 VM 은 기본이 임시 외부 IP 라 다시 띄우면 바뀝니다.</b> 바뀐 줄 모르면
 * 등록은 멀쩡한데 조용히 하루 500건으로 떨어지고, 증상은 「갑자기 아무것도 안 나온다」입니다.
 * 서버가 스스로 이 주소를 말하면 등록된 값과 눈으로 맞춰 보는 데 1초면 됩니다.
 *
 * <p><b>인증키는 절대 내보내지 않습니다.</b> 여기 실리는 것은 공개되어도 아무 문제가 없는
 * 값뿐입니다. 나가는 IP 는 우리가 부르는 상대 서버가 어차피 다 보고 있고, 정보나루의 오류
 * 코드와 메시지에는 키가 들어 있지 않습니다.
 */
@RestController
@RequestMapping("/api")
public class DiagnoseController {

    /** 나가는 주소를 알려 주는 곳. 답이 주소 한 줄뿐이라 파싱할 것이 없습니다. */
    private static final URI ECHO = URI.create("https://api.ipify.org");

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Data4LibraryClient client;
    private final ApiBudget budget;

    public DiagnoseController(Data4LibraryClient client, ApiBudget budget) {
        this.client = client;
        this.budget = budget;
    }

    /**
     * 지금 정보나루를 부를 수 있는지, 못 부른다면 왜인지.
     *
     * <p>정보나루를 <b>한 번</b> 부릅니다. 도서관 목록을 한 건만 달라고 하는 가장 가벼운
     * 요청입니다. 이 화면을 여는 것 자체가 예산을 쓰므로 자동으로 새로 고치지 마세요.
     */
    @GetMapping("/diagnose")
    public Map<String, Object> diagnose() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("egressIp", egressIp());
        out.put("callsUsedToday", budget.used(Data4LibraryClient.SOURCE_CODE));
        out.put("callsRemaining", budget.remaining(Data4LibraryClient.SOURCE_CODE));
        out.putAll(probe());
        return out;
    }

    /**
     * 정보나루가 지금 답하는지 실제로 물어봅니다.
     *
     * <p><b>「우리 예산이 남았다」와 「정보나루가 답한다」는 다릅니다.</b> 우리 원장은 우리가
     * 센 숫자일 뿐이라, 정보나루 쪽에서 IP 등록이 어긋나 있으면 원장이 아무리 넉넉해도
     * 한 건도 못 받습니다. 실제로 그 상태였고, 원장만 보고 있었으면 영영 몰랐을 것입니다.
     */
    private Map<String, Object> probe() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int count = client.libraries("11", ApiBudget.Priority.BACKGROUND).size();
            out.put("data4library", "ok");
            out.put("sampleCount", count);
        } catch (Data4LibraryClient.ApiErrorException e) {
            // 정보나루가 이유를 알려 준 경우입니다. 그 이유가 곧 사람이 할 일입니다.
            out.put("data4library", "error");
            out.put("errCode", e.error().code());
            out.put("reason", e.error().message());
        } catch (RuntimeException e) {
            out.put("data4library", "error");
            out.put("reason", e.getMessage());
        }
        return out;
    }

    /** 실패해도 진단 자체는 계속되어야 합니다. 못 알아낸 것과 오류를 구분해 적습니다. */
    private String egressIp() {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(ECHO).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body().trim() : "알아내지 못했습니다";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "알아내지 못했습니다";
        } catch (Exception e) {
            return "알아내지 못했습니다";
        }
    }
}
