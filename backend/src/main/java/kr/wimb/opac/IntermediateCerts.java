package kr.wimb.opac;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 중간 인증서를 보내지 않는 도서관 서버에 붙기 위한 설정입니다.
 *
 * <p>남양주·구미·울진을 비롯한 열 개 호스트(도서관 51곳)는 TLS 에서 자기 인증서만 보내고 그것을
 * 발급한 중간 인증서를 보내지 않습니다. 브라우저는 인증서에 적힌 주소에서 중간 인증서를 받아 와
 * 사슬을 잇지만, 자바는 {@code PKIX path building failed} 로 끊습니다(2026-11-11,
 * {@code docs/가정과-검증상태.md} 2-32). 자바에 같은 일을 시키는 설정
 * ({@code com.sun.security.enableAIAcaIssuers})은 JVM 전체에 걸리고, 상대가 내민 인증서에 적힌
 * 주소로 서버가 요청을 보내게 합니다. 운영자가 2026-11-13 에 그 대신 이 방식을 허용했습니다.
 *
 * <p><b>보태는 것은 중간 인증서이고, 신뢰의 뿌리는 그대로입니다.</b> {@code chain-certs.pem} 의
 * 인증서는 신뢰 앵커가 아니라 사슬을 짓는 재료로만 들어갑니다. 그 인증서 자신도 JDK 가 믿는 루트까지
 * 이어져야 하고 유효 기간 안이어야 합니다. 묶음에 엉뚱한 인증서가 섞여도 그것으로 믿게 되는 서버는
 * 없습니다. 호스트 이름 검사도 그대로 합니다. {@code IntermediateCertsTest} 가 실제 핸드셰이크로
 * 이 셋을 봅니다.
 *
 * <p><b>쓰는 쪽도 묶었습니다.</b> {@code chain-hosts.txt} 에 적힌 호스트로 가는 요청만 이 설정의
 * 클라이언트로 나갑니다({@link DetailResolver#httpFetcher()}). 나머지 OPAC 과 정보나루로 가는 연결은
 * 한 글자도 달라지지 않습니다. 호스트 목록은 그 파일 하나이고 조사 도구
 * ({@code scripts/opac-discover.py} 의 {@code tls_context})가 같은 파일을 읽습니다.
 *
 * <p><b>묶음은 낡습니다.</b> 도서관이 인증서를 갱신하면서 발급자가 바뀌면 묶음의 중간 인증서가 더는
 * 맞지 않고, 그 호스트는 조용히 예전처럼 검색 결과로 내려갑니다. 중간 인증서 자체에도 만료일이
 * 있습니다. {@code /api/status} 의 {@code opacChainCertsExpire} 가 가장 먼저 끝나는 날이고,
 * {@code /api/diagnose/opac/reach} 가 그 호스트에 지금 붙는지를 답합니다. 다시 만드는 것은
 * {@code scripts/opac-chain-certs.py} 입니다.
 *
 * <p>RSA 키 교환만 내놓는 호스트({@link LegacyTls#HOSTS})와 겹치게 두지 마세요. 요청 하나는 클라이언트
 * 하나로 나가므로 둘 다 필요한 호스트(속초)는 이 방식으로 붙지 않습니다.
 */
public final class IntermediateCerts {

    static final String HOSTS_RESOURCE = "/opac/chain-hosts.txt";
    static final String CERTS_RESOURCE = "/opac/chain-certs.pem";

    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]*?)-----END CERTIFICATE-----");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private IntermediateCerts() {}

    /** 파일은 클래스가 처음 쓰일 때 한 번 읽습니다. 형식이 틀리면 서버가 뜨지 않습니다. */
    private static final class Loaded {
        static final Set<String> HOSTS = parseHosts(read(HOSTS_RESOURCE));
        static final List<X509Certificate> CERTS = parseCerts(read(CERTS_RESOURCE));
    }

    /** 이 설정으로 붙는 호스트. 포트 없는 소문자 이름입니다. */
    public static Set<String> hosts() {
        return Loaded.HOSTS;
    }

    /** 묶음에 든 중간 인증서. 비어 있을 수 있습니다. */
    public static List<X509Certificate> bundled() {
        return Loaded.CERTS;
    }

    public static boolean isChainHost(String host) {
        return host != null && Loaded.HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** JDK 가 믿는 루트에 묶음의 중간 인증서를 재료로 보탠 설정. {@link #hosts()} 로 가는 클라이언트만 씁니다. */
    public static SSLContext context() {
        return contextWith(defaultAnchors(), Loaded.CERTS);
    }

    /**
     * {@code anchors} 만 믿고, {@code intermediates} 는 사슬을 잇는 데만 씁니다.
     *
     * <p>폐기 여부 검사는 JSSE 의 기본값과 같이 끕니다. {@code PKIXBuilderParameters} 는 기본이 켜짐이라
     * 그대로 두면 다른 클라이언트는 하지 않는 검사를 이 클라이언트만 하게 되고, 폐기 목록을 받으러
     * 서버가 남의 주소로 요청을 보냅니다.
     */
    static SSLContext contextWith(Collection<X509Certificate> anchors, Collection<X509Certificate> intermediates) {
        try {
            Set<TrustAnchor> trusted = anchors.stream()
                    .map(cert -> new TrustAnchor(cert, null))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(trusted, new X509CertSelector());
            parameters.setRevocationEnabled(false);
            parameters.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(List.copyOf(intermediates))));
            TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
            factory.init(new CertPathTrustManagerParameters(parameters));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("중간 인증서를 보탠 TLS 설정을 만들지 못했습니다", e);
        }
    }

    /** JDK 가 기본으로 믿는 루트. 다른 클라이언트가 쓰는 것과 같은 목록입니다. */
    static List<X509Certificate> defaultAnchors() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            List<X509Certificate> anchors = new ArrayList<>();
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) anchors.addAll(List.of(x509.getAcceptedIssuers()));
            }
            if (anchors.isEmpty()) throw new IllegalStateException("JDK 가 믿는 루트를 하나도 읽지 못했습니다");
            return anchors;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 가 믿는 루트를 읽지 못했습니다", e);
        }
    }

    /** {@code #} 뒤는 주석입니다. 스킴·포트·대문자가 섞인 줄은 조용히 안 맞는 대신 뜰 때 실패합니다. */
    static Set<String> parseHosts(String text) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String raw : text.split("\n")) {
            int hash = raw.indexOf('#');
            String line = (hash < 0 ? raw : raw.substring(0, hash)).trim();
            if (line.isEmpty()) continue;
            if (!HOST.matcher(line).matches()) {
                throw new IllegalStateException(HOSTS_RESOURCE + " 의 줄이 호스트 이름이 아닙니다"
                        + "(소문자로, 스킴과 포트 없이 적습니다): " + line);
            }
            hosts.add(line);
        }
        return Set.copyOf(hosts);
    }

    /** PEM 블록만 읽고 그 밖의 줄(주석)은 건너뜁니다. 블록이 인증서가 아니면 뜰 때 실패합니다. */
    static List<X509Certificate> parseCerts(String pem) {
        List<X509Certificate> certs = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Matcher block = PEM_BLOCK.matcher(pem);
            while (block.find()) {
                byte[] der = Base64.getMimeDecoder().decode(block.group(1));
                certs.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (CertificateException | IllegalArgumentException e) {
            throw new IllegalStateException(CERTS_RESOURCE + " 에 읽을 수 없는 인증서가 있습니다", e);
        }
        if (pem.contains("-----BEGIN") && certs.isEmpty()) {
            throw new IllegalStateException(CERTS_RESOURCE + " 에 PEM 머리는 있는데 읽힌 인증서가 없습니다");
        }
        return List.copyOf(certs);
    }

    /**
     * {@code /api/status} 에 실을 값. 묶음이 비었는지, 언제 낡는지를 서버에 들어가지 않고 봅니다.
     * 날짜는 한국 시각입니다.
     */
    public static Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opacChainHosts", Loaded.HOSTS.size());
        out.put("opacChainCerts", Loaded.CERTS.size());
        out.put("opacChainCertsExpire", Loaded.CERTS.stream()
                .map(cert -> cert.getNotAfter().toInstant())
                .min(Comparator.naturalOrder())
                .map(IntermediateCerts::seoulDate)
                .orElse(null));
        return out;
    }

    private static String seoulDate(Instant instant) {
        return instant.atZone(SEOUL).toLocalDate().toString();
    }

    private static String read(String resource) {
        try (InputStream in = IntermediateCerts.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource + " 가 없습니다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(resource + " 를 읽지 못했습니다", e);
        }
    }
}
