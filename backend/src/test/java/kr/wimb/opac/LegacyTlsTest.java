package kr.wimb.opac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSA 키 교환 허용이 JVM 전체에 걸리므로, 그것을 실제로 쓰는 연결이 정해 둔 호스트로 묶여 있는지를
 * 붙들어 둡니다. 실제 핸드셰이크는 보지 않습니다. 남의 서버를 테스트마다 두드릴 일이 아니고,
 * 테스트 JVM 은 다른 테스트가 TLS 를 먼저 써 버려 설정이 먹는 시점을 보장할 수 없습니다.
 */
class LegacyTlsTest {

    @Test
    @DisplayName("꺼 둔 알고리즘 목록에서 TLS_RSA_* 한 항목만 뺀다")
    void removesOnlyTheRsaKeyExchangeEntry() {
        String jdkDefault = "SSLv3, TLSv1, TLSv1.1, DTLSv1.0, RC4, DES, MD5withRSA, DH keySize < 1024, "
                + "EC keySize < 224, 3DES_EDE_CBC, anon, NULL, ECDH, TLS_RSA_*, "
                + "rsa_pkcs1_sha1 usage HandshakeSignature";
        assertEquals("SSLv3, TLSv1, TLSv1.1, DTLSv1.0, RC4, DES, MD5withRSA, DH keySize < 1024, "
                + "EC keySize < 224, 3DES_EDE_CBC, anon, NULL, ECDH, "
                + "rsa_pkcs1_sha1 usage HandshakeSignature", LegacyTls.without(jdkDefault));
    }

    @Test
    @DisplayName("그 항목이 없는 목록은 그대로 둔다")
    void leavesOtherListsAlone() {
        assertEquals("SSLv3, RC4, NULL", LegacyTls.without("SSLv3, RC4, NULL"));
        assertEquals("SSLv3, RC4", LegacyTls.without("SSLv3,RC4,tls_rsa_*"));
    }

    @Test
    @DisplayName("엄격한 스위트에는 TLS_RSA_ 로 시작하는 것이 없다")
    void strictSuitesDropRsaKeyExchange() {
        String[] mixed = {
            "TLS_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
        };
        assertArrayEquals(new String[] {"TLS_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"},
                LegacyTls.strictSuites(mixed));
        for (String suite : LegacyTls.strictParameters().getCipherSuites()) {
            assertFalse(suite.startsWith("TLS_RSA_"), suite);
        }
        assertTrue(LegacyTls.strictParameters().getCipherSuites().length > 0, "스위트가 하나도 남지 않았습니다");
    }

    @Test
    @DisplayName("허용하는 호스트는 대구와 안양 둘뿐이고 대소문자를 가리지 않는다")
    void legacyHostsAreTheTwoWeMeasured() {
        assertEquals(java.util.Set.of("library.daegu.go.kr", "lib.anyang.go.kr"), LegacyTls.HOSTS);
        assertTrue(LegacyTls.isLegacyHost("Library.Daegu.go.kr"));
        assertFalse(LegacyTls.isLegacyHost("data4library.kr"));
        assertFalse(LegacyTls.isLegacyHost("evil.library.daegu.go.kr.example.com"));
        assertFalse(LegacyTls.isLegacyHost(null));
    }

    /**
     * 새 클라이언트를 만들면서 파라미터를 빼먹으면 그 클라이언트는 JVM 기본값, 곧 TLS_RSA_* 까지
     * 제안합니다. 오류도 경고도 나지 않으므로 여기서 잡습니다.
     */
    @Test
    @DisplayName("HttpClient 를 만드는 자리마다 TLS 파라미터가 걸려 있다")
    void everyHttpClientDeclaresItsTlsParameters() throws IOException {
        Pattern build = Pattern.compile("HttpClient\\s*\\.\\s*newBuilder\\s*\\(\\s*\\)([\\s\\S]*?)\\.build\\s*\\(\\s*\\)");
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("HttpClient.newHttpClient()"),
                        file + " 가 TLS 파라미터 없는 기본 클라이언트를 만듭니다");
                Matcher m = build.matcher(source);
                while (m.find()) {
                    found++;
                    String chain = m.group(1);
                    boolean declared = chain.contains("LegacyTls.strictParameters()")
                            || chain.contains("LegacyTls.legacyParameters()");
                    if (!declared) missing.add(file.toString());
                }
            }
        }
        assertTrue(found >= 4, "HttpClient 를 만드는 자리를 찾지 못했습니다. 찾은 수: " + found);
        assertTrue(missing.isEmpty(), "TLS 파라미터가 없는 HttpClient: " + missing);
    }

    @Test
    @DisplayName("그 계열을 제안하는 클라이언트는 상세 해석기 한 곳에만 있다")
    void onlyTheDetailResolverUsesLegacyParameters() throws IOException {
        List<String> users = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith("LegacyTls.java")) continue;
                if (Files.readString(file).contains("legacyParameters()")) users.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of("DetailResolver.java"), users);
    }
}
