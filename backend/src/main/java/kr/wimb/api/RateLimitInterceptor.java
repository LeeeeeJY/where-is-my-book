package kr.wimb.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 요청 하나에 무게를 매겨 {@link RateLimit} 에 물어보는 곳.
 *
 * <p>필터가 아니라 인터셉터인 것은 <b>거절 사유를 화면까지 실어 보내기 위해서</b>입니다.
 * 인터셉터에서 던진 예외는 {@link ApiErrorAdvice} 를 그대로 지나가므로 다른 오류와 같은
 * 모양({@code code}, {@code reason})으로 나갑니다. 필터에서 던지면 그 앞에서 끊겨
 * 화면에는 「API 오류 429」만 남고, 사용자는 무엇을 해야 하는지 알 수 없습니다.
 * 이유를 버리면 그다음은 전부 추측이라는 것을 이미 하루를 들여 배웠습니다.
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

    /**
     * 요청 하나가 정보나루를 <b>대략 몇 번 부를 수 있는지</b>.
     *
     * <p>정확한 값이 아니라 어림값입니다. 실제 횟수는 판본 수와 시도 수와 캐시 적중에 따라
     * 달라지는데, 그 값들은 요청 본문을 읽어야 알 수 있고 인터셉터는 본문을 읽기 전에
     * 돕니다. 그래서 <b>상한에 가깝게 잡습니다.</b> 적게 잡으면 가장 비싼 요청이 가장 싸게
     * 통과하는데, 그것이 정확히 막아야 하는 것입니다.
     *
     * <p>특히 {@code /api/check/resolve} 는 <b>한 번의 요청이 줄 수만큼 부릅니다.</b>
     * 50줄 상한에 줄마다 두세 번이면 백 번을 넘깁니다. 짧은 목록에는 과하게 매겨지지만,
     * 그 대가로 긴 목록을 되풀이해 던지는 것을 막습니다.
     */
    static int costOf(String path) {
        if (path == null) return 1;
        if (path.startsWith("/api/check/resolve")) return 100;
        if (path.startsWith("/api/loan")) return 20;
        if (path.startsWith("/api/search")) return 8;
        if (path.startsWith("/api/holdings")) return 4;
        // 둘러보기는 도서관마다 하루 한 번만 정보나루를 부르므로 대개 0회입니다. 다만
        // 캐시가 빌 때는 장서 건수·장서 한 쪽·책 정보·인기 목록으로 여러 번 나가므로,
        // 본문을 읽기 전에 정하는 값답게 상한에 가깝게 잡습니다.
        if (path.startsWith("/api/browse")) return 6;
        // 진단은 열 때마다 정보나루를 한 번 씁니다. 자동으로 새로 고치면 안 되는 화면이라
        // 사람이 누르는 속도보다 빠르면 막습니다.
        if (path.startsWith("/api/diagnose")) return 5;
        // /api/libraries, /api/status, /api/version, /api/go 는 정보나루를 부르지 않거나
        // (마스터 적재) 재시도 빗장으로 이미 묶여 있습니다. 그래도 0 은 아닙니다.
        // 무한정 두드리는 것 자체가 우리 서버의 몫을 씁니다.
        return 1;
    }

    private final RateLimit limit;
    private final boolean enabled;
    private final boolean behindProxy;

    public RateLimitInterceptor(RateLimit limit, boolean enabled, boolean behindProxy) {
        this.limit = limit;
        this.enabled = enabled;
        this.behindProxy = behindProxy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!enabled) return true;

        // **CORS 사전 요청(OPTIONS)은 세지 않습니다.** 정보나루를 한 번도 부르지 않는데,
        // Spring 은 사전 요청에도 등록된 인터셉터를 그대로 태웁니다. 그냥 두면 브라우저가
        // 보내는 사전 요청마다 본 요청과 같은 무게가 매겨져 `/api/check/resolve` 는 값이
        // 두 배가 됩니다.
        //
        // 더 나쁜 것은 **막혔을 때입니다.** 사전 요청을 우리가 거절하면 CORS 헤더가 붙기
        // 전에 끊기므로, 브라우저는 그것을 CORS 오류로 보고합니다. 화면에는 서버가 실어
        // 보낸 이유가 아니라 「API 서버에 연결하지 못했습니다」가 뜨고, 그러면 멀쩡한
        // 서버를 다시 띄우게 됩니다. 우리가 계속 갈라 놓으려는 바로 그 두 가지입니다.
        if (CorsUtils.isPreFlightRequest(request)) return true;

        RateLimit.Decision decision =
                limit.check(clientKey(request), costOf(request.getRequestURI()));
        if (decision.allowed()) return true;
        throw new RateLimit.LimitExceededException(decision, messageFor(decision));
    }

    /**
     * <b>사람이 할 일을 갈라 말합니다.</b> 「잠시 뒤 다시」와 「오늘은 끝」은 다른 상황인데
     * 한 문구로 뭉뚱그리면, 하루치를 다 쓴 사람이 30초마다 다시 눌러 보게 됩니다.
     */
    private static String messageFor(RateLimit.Decision decision) {
        if (decision.scope() == RateLimit.Scope.DAY) {
            return "이 주소에서 오늘 쓸 수 있는 조회 몫을 다 썼습니다. "
                    + "내일 다시 시도해 주세요. 확인하지 못한 것이지 책이 없다는 뜻은 아닙니다.";
        }
        return "요청이 너무 빠릅니다. " + decision.retryAfterSeconds()
                + "초 뒤에 다시 시도해 주세요. 확인하지 못한 것이지 책이 없다는 뜻은 아닙니다.";
    }

    /** 이 요청을 누구 몫으로 셀지. */
    String clientKey(HttpServletRequest request) {
        String ip = behindProxy ? forwardedFor(request) : null;
        if (ip == null) ip = request.getRemoteAddr();
        return groupKey(ip);
    }

    /**
     * {@code X-Forwarded-For} 의 <b>마지막</b> 값을 씁니다. 첫 번째가 아닙니다.
     *
     * <p>이 헤더는 프록시를 지날 때마다 <b>뒤에 덧붙습니다.</b> Caddy 도 그렇습니다. 그래서
     * 클라이언트가 헤더를 지어내서 보내면 그 값은 앞쪽에 남고, 우리 프록시가 실제로 본
     * 주소가 맨 뒤에 붙습니다. <b>흔히 하는 대로 첫 값을 쓰면 아무나 헤더 한 줄로 한도를
     * 빠져나갑니다.</b> 막으려고 만든 것이 아무것도 막지 못하게 됩니다.
     *
     * <p>거꾸로 프록시 없이 직접 열어 둔 서버에서는 이 헤더 전체가 남의 것이므로 믿으면
     * 안 됩니다. 그때는 {@code behind-proxy} 를 꺼야 하고, 끄면 이 함수를 부르지 않습니다.
     */
    private static String forwardedFor(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) return null;
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty()) return hop;
        }
        return null;
    }

    /**
     * 주소를 셀 단위로 바꿉니다.
     *
     * <p><b>IPv6 는 /64 로 묶습니다.</b> 요즘은 회선 하나에 /64 이상이 통째로 딸려 오므로,
     * 주소 하나씩 세면 같은 사람이 주소만 바꿔 가며 한도를 무한히 늘릴 수 있습니다.
     * 그러면 IPv6 사용자에게만 한도가 없는 셈이 됩니다.
     */
    static String groupKey(String raw) {
        String ip = stripPort(raw);
        if (ip.isEmpty()) return "unknown";
        if (!looksLikeIpv6(ip)) return ip;
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) return ip;
            StringBuilder out = new StringBuilder(ip.length());
            for (int i = 0; i < 8; i += 2) {
                if (i > 0) out.append(':');
                out.append(String.format("%02x%02x", bytes[i], bytes[i + 1]));
            }
            return out.append("::/64").toString();
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /** {@code [2001:db8::1]:443} 과 {@code 192.0.2.1:443} 에서 포트를 뗍니다. */
    private static String stripPort(String raw) {
        if (raw == null) return "";
        String ip = raw.trim();
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            return close > 0 ? ip.substring(1, close) : ip.substring(1);
        }
        int colon = ip.indexOf(':');
        // 콜론이 하나뿐이면 host:port 이고, 여럿이면 IPv6 주소 그 자체입니다.
        if (colon >= 0 && ip.indexOf(':', colon + 1) < 0) return ip.substring(0, colon);
        return ip;
    }

    /**
     * 16진수와 콜론(그리고 v4 사상 표기의 점)만으로 이루어졌는지 봅니다.
     *
     * <p><b>이 검사를 거치지 않고 {@link InetAddress#getByName} 을 부르면 안 됩니다.</b>
     * 리터럴이 아닌 값이 들어오면 그 함수가 DNS 를 찾아 나서는데, 그러면 남이 보낸 헤더
     * 한 줄로 우리 서버가 요청마다 이름 조회를 하게 됩니다.
     */
    private static boolean looksLikeIpv6(String ip) {
        if (ip.indexOf(':') < 0) return false;
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            boolean ok = c == ':' || c == '.' || (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }
}
