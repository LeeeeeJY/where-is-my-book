package kr.wimb.opac;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 중간 인증서를 보태는 설정이 <b>보태기만 하는지</b>를 실제 핸드셰이크로 봅니다.
 *
 * <p>이 기계에 띄운 서버가 사슬이 끊긴 도서관 서버를 흉내 냅니다. 자기 인증서만 보내고 그것을 발급한
 * 중간 인증서는 보내지 않습니다. 남의 서버는 두드리지 않습니다.
 *
 * <p>인증서는 이 테스트만을 위해 만든 것입니다({@code src/test/resources/opac/chain/}).
 * {@code root.pem} 이 {@code intermediate.pem} 을, 그것이 {@code leaf-only.p12} 안의 {@code localhost}
 * 인증서를 발급했습니다. 유효 기간은 100년이고 루트와 중간 인증서의 개인 키는 버렸습니다. 다시
 * 만들려면 {@code openssl req -x509} 로 루트를, {@code openssl x509 -req -CA} 로 중간({@code CA:TRUE})과
 * 잎({@code subjectAltName=DNS:localhost})을 차례로 만들고 잎만 {@code openssl pkcs12 -export} 로
 * 묶습니다(암호 {@code changeit}).
 */
class IntermediateCertsTest {

    private static HttpsServer server;
    private static X509Certificate root;
    private static X509Certificate intermediate;

    @BeforeAll
    static void startServerThatSendsOnlyItsOwnCertificate() throws Exception {
        root = fixtureCert("root.pem");
        intermediate = fixtureCert("intermediate.pem");

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = IntermediateCertsTest.class.getResourceAsStream("/opac/chain/leaf-only.p12")) {
            keys.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        // 주소를 정하지 않고 묶습니다. localhost 가 ::1 로 풀리는 기계에서도 붙어야 합니다.
        server = HttpsServer.create(new InetSocketAddress(0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    private static X509Certificate fixtureCert(String name) throws IOException {
        try (InputStream in = IntermediateCertsTest.class.getResourceAsStream("/opac/chain/" + name)) {
            return IntermediateCerts.parseCerts(new String(in.readAllBytes(), StandardCharsets.UTF_8)).get(0);
        }
    }

    /** 운영의 클라이언트와 같은 모양입니다. 프록시만 끕니다(이 기계의 서버로 가는 요청입니다). */
    private static String get(String host, Collection<X509Certificate> anchors,
                              Collection<X509Certificate> intermediates) throws Exception {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(HttpClient.Builder.NO_PROXY)
                .sslContext(IntermediateCerts.contextWith(anchors, intermediates))
                .sslParameters(LegacyTls.strictParameters())
                .build()) {
            URI url = URI.create("https://" + host + ":" + server.getAddress().getPort() + "/");
            return http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    @Test
    @DisplayName("중간 인증서를 보내지 않는 서버에는 루트를 믿어도 붙지 못한다")
    void brokenChainFailsWithoutTheIntermediate() {
        // 고치려는 바로 그 상태입니다. 여기가 붙으면 아래 테스트가 아무것도 증명하지 못합니다.
        assertThrows(SSLHandshakeException.class, () -> get("localhost", List.of(root), List.of()));
    }

    @Test
    @DisplayName("빠진 중간 인증서를 보태면 붙는다")
    void theIntermediateCompletesTheChain() throws Exception {
        assertEquals("ok", get("localhost", List.of(root), List.of(intermediate)));
    }

    @Test
    @DisplayName("보탠 인증서는 신뢰의 뿌리가 아니다: 루트를 믿지 않으면 보태도 붙지 못한다")
    void intermediatesAreNotTrustAnchors() {
        // JDK 가 믿는 루트에는 이 테스트의 루트가 없습니다. 묶음의 인증서가 앵커로 쓰인다면 여기가 붙습니다.
        // 묶음에 엉뚱한 인증서가 섞여도 그것으로 믿게 되는 서버가 없어야 합니다.
        assertThrows(SSLHandshakeException.class,
                () -> get("localhost", IntermediateCerts.defaultAnchors(), List.of(intermediate)));
    }

    @Test
    @DisplayName("사슬을 이어 줘도 호스트 이름 검사는 그대로 한다")
    void hostnameIsStillVerified() {
        // 인증서는 localhost 의 것입니다. 같은 서버를 다른 이름으로 부르면 끊어야 합니다.
        assertThrows(SSLHandshakeException.class,
                () -> get("127.0.0.1", List.of(root), List.of(intermediate)));
    }

    @Test
    @DisplayName("호스트 파일은 소문자 이름만 받고, 스킴이나 포트가 섞이면 뜨지 않는다")
    void hostsFileIsStrict() {
        assertEquals(java.util.Set.of("lib.nyj.go.kr", "lib.gumi.go.kr"),
                IntermediateCerts.parseHosts("# 주석\nlib.nyj.go.kr\n\n  lib.gumi.go.kr  # 구미\n"));
        // 조용히 안 맞는 줄보다 뜰 때 실패하는 편이 낫습니다. 포트가 붙은 줄은 어느 요청과도 맞지 않습니다.
        assertThrows(IllegalStateException.class, () -> IntermediateCerts.parseHosts("lib.nonsan.go.kr:8443"));
        assertThrows(IllegalStateException.class, () -> IntermediateCerts.parseHosts("https://lib.nyj.go.kr"));
        assertThrows(IllegalStateException.class, () -> IntermediateCerts.parseHosts("Lib.Nyj.go.kr"));
        assertThrows(IllegalStateException.class, () -> IntermediateCerts.parseHosts("*.go.kr"));
    }

    @Test
    @DisplayName("호스트는 대소문자를 가리지 않고 견주고, 닮은 이름에 속지 않는다")
    void hostMatching() {
        assertFalse(IntermediateCerts.hosts().isEmpty());
        String host = IntermediateCerts.hosts().iterator().next();
        assertTrue(IntermediateCerts.isChainHost(host.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(IntermediateCerts.isChainHost("evil." + host + ".example.com"));
        assertFalse(IntermediateCerts.isChainHost("data4library.kr"));
        assertFalse(IntermediateCerts.isChainHost(null));
    }

    @Test
    @DisplayName("호스트 파일의 호스트는 전부 규칙 표에 있고, RSA 키 교환 호스트와 겹치지 않는다")
    void hostsAreOnesWeHaveRulesFor() throws IOException {
        String templates;
        try (InputStream in = getClass().getResourceAsStream("/opac/templates.csv")) {
            templates = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String host : IntermediateCerts.hosts()) {
            // 규칙이 없는 호스트는 서버가 갈 일이 없습니다. 남아 있으면 지운 규칙의 흔적입니다.
            assertTrue(templates.contains("://" + host + "/") || templates.contains("://" + host + ":"),
                    host + " 로 가는 규칙이 templates.csv 에 없습니다");
            // 요청 하나는 클라이언트 하나로 나갑니다. 둘 다에 적으면 한쪽 설정이 조용히 빠집니다.
            assertFalse(LegacyTls.isLegacyHost(host), host + " 가 LegacyTls.HOSTS 에도 있습니다");
        }
    }

    @Test
    @DisplayName("묶음에는 중간 인증서만 있다: 루트도 서버 인증서도 아니다")
    void bundleHoldsOnlyIntermediates() {
        // 묶음이 비어 있어도 통과합니다. 채워진 날부터 지킵니다.
        for (X509Certificate cert : IntermediateCerts.bundled()) {
            String who = cert.getSubjectX500Principal().getName();
            assertTrue(cert.getBasicConstraints() >= 0, "CA 인증서가 아닙니다: " + who);
            assertFalse(cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()),
                    "루트(자기 서명) 인증서입니다. 신뢰의 뿌리는 JDK 의 것을 씁니다: " + who);
        }
        assertEquals(IntermediateCerts.bundled().size(), IntermediateCerts.stats().get("opacChainCerts"));
        assertEquals(IntermediateCerts.hosts().size(), IntermediateCerts.stats().get("opacChainHosts"));
    }

    @Test
    @DisplayName("묶음 파일은 주석을 건너뛰고 읽고, 깨진 블록이 있으면 뜨지 않는다")
    void bundleParsing() throws IOException {
        String pem;
        try (InputStream in = getClass().getResourceAsStream("/opac/chain/intermediate.pem")) {
            pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(List.of(), IntermediateCerts.parseCerts("# 아직 비어 있습니다\n"));
        assertEquals(List.of(intermediate, intermediate),
                IntermediateCerts.parseCerts("# 남양주: CN=어느 발급자, 만료 2030-01-01\n" + pem + "\n# 다음\n" + pem));
        assertThrows(IllegalStateException.class, () -> IntermediateCerts.parseCerts(
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"));
        assertThrows(IllegalStateException.class, () -> IntermediateCerts.parseCerts(
                "-----BEGIN CERTIFICATE-----\n잘린 블록"));
    }

    @Test
    @DisplayName("수집 도구가 쓴 묶음을 그대로 읽는다")
    void readsWhatTheCollectorWrites() throws IOException {
        // scripts/opac-chain-certs.py 의 write_bundle 이 intermediate.pem 한 장으로 쓴 파일입니다.
        // 한글 주석이 섞여 있고 줄 길이가 openssl 이 쓰는 것과 다릅니다. 도구의 쓰기를 고치면 이 파일도
        // 다시 만드세요. 도구는 통과시켰는데 서버가 못 읽는 묶음은 서버가 뜨지 않게 합니다.
        String written;
        try (InputStream in = getClass().getResourceAsStream("/opac/chain/written-by-tool.pem")) {
            written = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(written.contains("#   쓰는 호스트: lib.example.kr, lib.example.kr:8443"));
        assertEquals(List.of(intermediate), IntermediateCerts.parseCerts(written));
    }

    /**
     * 이 설정은 정해 둔 호스트로 가는 요청에만 쓰여야 합니다. 다른 클라이언트가 가져다 쓰면 그 연결의
     * 사슬 검사가 달라지는데 오류도 경고도 나지 않습니다.
     */
    @Test
    @DisplayName("중간 인증서를 보탠 설정을 쓰는 곳은 상세 해석기 한 곳뿐이다")
    void onlyTheDetailResolverUsesTheChainContext() throws IOException {
        List<String> users = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith("IntermediateCerts.java")) continue;
                if (Files.readString(file).contains("IntermediateCerts.context()")) users.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of("DetailResolver.java"), users);
        String resolver = Files.readString(Path.of("src/main/java/kr/wimb/opac/DetailResolver.java"));
        assertTrue(resolver.contains("IntermediateCerts.isChainHost(url.getHost()) ? chain : strict"),
                "그 클라이언트로 가는 요청을 호스트로 가르는 자리가 사라졌습니다");
    }
}
