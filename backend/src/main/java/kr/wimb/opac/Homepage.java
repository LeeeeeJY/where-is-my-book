package kr.wimb.opac;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * 정보나루가 준 홈페이지 주소를 <b>실제로 보낼 수 있는 것만</b> 골라냅니다.
 *
 * <p><b>정보나루는 홈페이지가 없는 곳에 {@code -} 를 줍니다.</b> 그리고 스킴을 빼고 주는
 * 곳도 있습니다({@code lib.yongin.go.kr/dongcheon}). 그것을 그대로 {@code Location} 헤더에
 *실으면 브라우저가 <b>상대 주소로 읽어 우리 서버 안의 없는 경로로 갑니다.</b> 실제 목록
 * 1,619곳 가운데 17곳이 그랬고, 운영에서 확인한 결과는 이렇습니다.
 *
 * <pre>
 * 고현초꿈키움도서관 → https://where-is-my-book.duckdns.org/api/go/-
 * 동천도서관         → https://where-is-my-book.duckdns.org/api/go/lib.yongin.go.kr/dongcheon
 * </pre>
 *
 * <p>도서관을 눌렀는데 도서관이 아니라 우리 서버의 오류 화면이 뜨는 것이고, <b>동천도서관은
 * 주소가 멀쩡한데도 못 갑니다.</b> 그래서 스킴만 빠진 것은 살리고(진짜 도서관 주소를 버릴
 * 이유가 없습니다) 나머지는 「보낼 주소가 없다」로 답합니다. 엉뚱한 곳으로 조용히 보내는
 * 것보다 못 간다고 말하는 편이 낫습니다.
 */
public final class Homepage {

    /** 「없음」을 뜻하는 자리표. 주소처럼 생겼지만 주소가 아닙니다. */
    private static final Set<String> PLACEHOLDERS = Set.of("-", "_", "없음", "N/A", "n/a");

    /**
     * 앞머리의 스킴. {@code ://} 로만 찾으면 {@code mailto:} 처럼 슬래시 없는 스킴을 놓쳐서
     * {@code http://mailto:...} 라는 이상한 주소를 만듭니다.
     *
     * <p>콜론 뒤가 숫자면 스킴이 아니라 <b>포트</b>로 봅니다({@code lib.example.kr:8080}).
     * 실제 목록에 그런 홈페이지는 아직 없지만, 스킴이 빠진 주소를 살리기로 한 이상 포트가
     * 붙은 것도 함께 살아야 앞뒤가 맞습니다.
     */
    private static final java.util.regex.Pattern SCHEME =
            java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:(?![0-9])");

    private Homepage() {}

    /** 보낼 수 있는 절대 주소. 없으면 비어 있습니다. */
    public static Optional<String> usable(String raw) {
        if (raw == null) return Optional.empty();
        String url = raw.strip();
        if (url.isEmpty() || PLACEHOLDERS.contains(url)) return Optional.empty();

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // 우리가 다룰 수 있는 것은 http 계열뿐입니다. 다른 스킴이 적혀 있으면 손대지 않습니다.
            if (SCHEME.matcher(url).find()) return Optional.empty();
            url = "http://" + url;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            // 호스트에 점이 없으면 도메인이 아닙니다(localhost 같은 것). 밖으로 보낼 수 없습니다.
            return host != null && host.contains(".") ? Optional.of(url) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
