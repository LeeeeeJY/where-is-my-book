package kr.wimb.opac;

import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * RSA 키 교환({@code TLS_RSA_*})만 내놓는 도서관 서버에 붙기 위한 설정입니다.
 *
 * <p>대구 통합도서관과 안양시립도서관은 TLS 1.2 에 {@code TLS_RSA_WITH_AES_256_GCM_SHA384} 계열만
 * 내놓습니다. 자바 21 은 그 계열을 {@code jdk.tls.disabledAlgorithms} 에 넣어 기본으로 꺼 두어서,
 * 브라우저로는 열리는 곳에 서버만 {@code handshake_failure} 로 붙지 못했습니다(2026-11-10,
 * {@code docs/가정과-검증상태.md} 2-27). 운영자가 2026-11-11 에 이 계열을 허용하기로 정했습니다.
 *
 * <p><b>그 설정은 JVM 전체에 걸립니다.</b> JSSE 가 클라이언트마다 따로 풀 방법을 주지 않기
 * 때문입니다. 그래서 푸는 것은 한 번에 하고, <b>쓰는 것을 묶습니다.</b> 이 프로그램이 만드는
 * {@code HttpClient} 는 전부 {@link #strictParameters()} 로 그 계열을 뺀 스위트만 내놓고,
 * {@link #HOSTS} 로 가는 요청만 {@link #legacyParameters()} 를 쓰는 클라이언트로 나갑니다.
 * 정보나루로 가는 연결은 그 계열을 제안조차 하지 않습니다. {@code LegacyTlsTest} 가
 * {@code HttpClient} 를 만드는 자리마다 둘 중 하나가 걸려 있는지 봅니다. <b>새 클라이언트를 만들 때
 * 파라미터를 빼먹으면 그 클라이언트가 조용히 그 계열까지 제안하게 됩니다.</b>
 *
 * <p>푸는 것이 잃는 것은 순방향 비밀성입니다. 그 연결을 기록해 둔 쪽이 나중에 그 도서관 서버의
 * 열쇠를 얻으면 기록을 풀어 볼 수 있습니다. 그 연결로 오가는 것은 공개된 검색 결과 화면뿐이고
 * 인증키도 개인정보도 실리지 않습니다.
 *
 * <p><b>호스트를 더할 때는 그 호스트가 실제로 그 계열만 내놓는지, 운영 서버의 나가는 IP 를
 * 받는지를 먼저 보세요.</b> 전남교육청({@code *.jne.go.kr})도 같은 계열만 내놓지만 미국 IP 가
 * 닿지 못해(2026-11-11, 운영 VM 에서 접속 시간 초과) 넣어도 얻는 것이 없어 넣지 않았습니다.
 * 조사 도구({@code scripts/opac-discover.py} 의 {@code LEGACY_TLS_HOSTS})와 같은 목록이어야
 * 도구가 확인한 규칙을 서버가 그대로 받습니다.
 */
public final class LegacyTls {

    /** 그 계열로만 붙을 수 있는 호스트. 여기 적힌 곳으로 가는 요청만 그 계열을 제안합니다. */
    public static final Set<String> HOSTS = Set.of("library.daegu.go.kr", "lib.anyang.go.kr");

    /** {@code false} 로 두면 풀지 않습니다. 그러면 위 호스트는 예전처럼 검색 결과로 내려갑니다. */
    public static final String SWITCH_ENV = "WIMB_OPAC_LEGACY_TLS";

    static final String PROPERTY = "jdk.tls.disabledAlgorithms";
    static final String ENTRY = "TLS_RSA_*";
    private static final String SUITE_PREFIX = "TLS_RSA_";

    private LegacyTls() {}

    /**
     * 프로그램이 뜨는 자리에서 <b>TLS 를 처음 쓰기 전에</b> 한 번 부릅니다. JSSE 는 꺼 둔 알고리즘
     * 목록을 처음 쓸 때 한 번 읽으므로, 그 뒤에 고치면 아무 일도 일어나지 않습니다.
     *
     * @return 풀었으면(또는 이미 풀려 있으면) 참, 스위치로 꺼 두었으면 거짓
     */
    public static boolean enableUnlessSwitchedOff() {
        String value = System.getenv(SWITCH_ENV);
        if (value != null && value.trim().equalsIgnoreCase("false")) return false;
        enable();
        return true;
    }

    static void enable() {
        String current = Security.getProperty(PROPERTY);
        if (current == null) return;
        String next = without(current);
        if (!next.equals(current)) Security.setProperty(PROPERTY, next);
    }

    /** 목록에서 {@code TLS_RSA_*} 한 항목만 뺍니다. 나머지는 JDK 가 정한 그대로 둡니다. */
    static String without(String property) {
        return Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty() && !entry.equalsIgnoreCase(ENTRY))
                .collect(Collectors.joining(", "));
    }

    /** 지금 이 JVM 이 그 계열을 허용하는 상태인지. {@code /api/status} 가 내보냅니다. */
    public static boolean enabled() {
        String current = Security.getProperty(PROPERTY);
        return current == null
                || Arrays.stream(current.split(",")).map(String::trim).noneMatch(ENTRY::equalsIgnoreCase);
    }

    public static boolean isLegacyHost(String host) {
        return host != null && HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** {@link #HOSTS} 말고는 전부 이 파라미터로 붙습니다. 그 계열을 뺀 스위트만 내놓습니다. */
    public static SSLParameters strictParameters() {
        SSLParameters parameters = defaults();
        parameters.setCipherSuites(strictSuites(parameters.getCipherSuites()));
        return parameters;
    }

    /** {@link #HOSTS} 로 가는 클라이언트만 씁니다. JVM 이 허용하는 스위트를 그대로 내놓습니다. */
    public static SSLParameters legacyParameters() {
        return defaults();
    }

    static String[] strictSuites(String[] suites) {
        return Arrays.stream(suites).filter(suite -> !suite.startsWith(SUITE_PREFIX)).toArray(String[]::new);
    }

    private static SSLParameters defaults() {
        try {
            return SSLContext.getDefault().getDefaultSSLParameters();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("기본 TLS 설정을 읽지 못했습니다", e);
        }
    }
}
