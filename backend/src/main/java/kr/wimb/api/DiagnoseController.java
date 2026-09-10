package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import kr.wimb.opac.DetailResolver;
import kr.wimb.opac.OpacLink;
import kr.wimb.opac.OpacTemplates;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final OpacTemplates opacTemplates;
    private final DetailResolver detailResolver;

    public DiagnoseController(Data4LibraryClient client, ApiBudget budget,
                              OpacTemplates opacTemplates, DetailResolver detailResolver) {
        this.client = client;
        this.budget = budget;
        this.opacTemplates = opacTemplates;
        this.detailResolver = detailResolver;
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

    /**
     * 이 서버가 그 도서관의 OPAC 에 닿아 상세 링크를 뽑을 수 있는지 <b>지금 실제로</b> 해 봅니다.
     *
     * <p>상세 패턴은 국내 회선의 브라우저로 확인한 것인데, 서버는 미국 리전에서 자바스크립트
     * 없이 받습니다. 그 둘이 어긋나는 자리가 둘입니다. 해외 IP 를 막는 OPAC 이면 여기서
     * {@code FETCH_FAILED} 가 나오고, 결과를 자바스크립트로 그리는 OPAC 이면 {@code NO_MATCH}
     * 가 나옵니다. 화면에서는 둘 다 「검색 결과로 갔다」로만 보여 구별할 수 없으므로 이 진단이
     * 있어야 합니다. 기억해 둔 답은 쓰지 않습니다. 캐시가 답하면 닿는지를 확인한 것이 아닙니다.
     *
     * <p>이 호출은 그 도서관 서버로 요청을 한 번 보냅니다. 자동으로 돌리지 마세요.
     */
    @GetMapping("/diagnose/opac")
    public Map<String, Object> opac(@RequestParam String lib, @RequestParam String isbn) {
        if (lib.isBlank() || isbn.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lib 과 isbn 이 필요합니다.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("libCode", lib);
        out.put("isbn", isbn);
        out.put("kind", opacTemplates.kindFor(lib).name());
        var link = opacTemplates.bestFor(lib, isbn, null);
        if (link.isEmpty()) {
            out.put("status", "NO_RULE");
            out.put("note", "이 도서관에는 ISBN 검색 규칙이 없습니다. 홈페이지로 갑니다.");
            return out;
        }
        out.put("searchUrl", link.get().url());
        if (link.get().kind() != OpacLink.Kind.ISBN_SEARCH) {
            out.put("status", "NO_LOOKUP_NEEDED");
            out.put("note", "상세 주소를 미리 만들 수 있는 도서관이라 검색 결과를 받지 않습니다.");
            return out;
        }
        var pattern = opacTemplates.detailPatternFor(link.get().url());
        if (pattern.isEmpty()) {
            out.put("status", "NO_PATTERN");
            out.put("note", "이 OPAC 의 상세 패턴이 detail-patterns.csv 에 없습니다. 검색 결과로 갑니다.");
            return out;
        }
        out.put("patternKey", pattern.get().key());
        var outcome = detailResolver.probe(link.get().url(), pattern.get().regex());
        out.put("status", outcome.status().name());
        out.put("detailUrl", outcome.detailUrl());
        out.put("note", outcome.note());
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
