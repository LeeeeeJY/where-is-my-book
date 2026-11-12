package kr.wimb.api;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨텍스트를 통째로 올리는 테스트는 {@code @SpringBootTest} 대신 <b>이것</b>을 붙입니다.
 *
 * <h2>왜 따로 두나</h2>
 *
 * <p>{@code @SpringBootTest} 를 그대로 쓰면 <b>테스트가 진짜 정보나루로 요청을 보냅니다.</b>
 * 서버는 뜨자마자 도서관 목록을 미리 받는데({@code WimbController.warmCatalog}), 그것이
 * 테스트 컨텍스트에서도 똑같이 돕니다. 키가 가짜라 운영 키의 건수에는 들지 않지만,
 * {@code ./gradlew test} 한 번마다, 그리고 이미지를 만드는 GitHub Actions 빌드마다 컨텍스트
 * 수만큼 남의 서버를 두드립니다. 실패도 경고도 없어서 아무도 모릅니다. 실제로 세 테스트가
 * 그렇게 돌고 있었고, HTTP 클라이언트의 기록을 켜고서야 {@code data4library.kr:443} 으로
 * 나가는 연결이 컨텍스트마다 하나씩 보였습니다(2026-11-12).
 *
 * <h2>무엇을 하나</h2>
 *
 * <ol>
 *   <li><b>미리 받기를 끕니다</b>({@code wimb.catalog.warm-on-startup=false}).
 *       운영의 기본값은 그대로 켜짐입니다.</li>
 *   <li><b>정보나루로 나가는 문을 막습니다</b>({@link RefusingTransport}). 미리 받기를 꺼도
 *       테스트가 {@code /api/libraries} 같은 주소를 부르면 요청이 나가는데, 그것까지
 *       막습니다. 부르면 실패하고, 불렸다는 사실이 남습니다.</li>
 *   <li><b>테스트 클래스가 끝날 때 한 건도 부르지 않았는지 봅니다</b>
 *       ({@link NoData4LibraryCalls}). 테스트마다 따로 적지 않아도 됩니다.</li>
 * </ol>
 *
 * <p>인증키 이름이 「호출에 쓰이지 않는 키」인데, 이제 실제로 그렇습니다.
 *
 * <p>{@code ContextTestsStayOfflineTest} 가 {@code @SpringBootTest} 를 직접 붙인 테스트가
 * 없는지 봅니다. 규칙을 주석으로만 두면 다음 컨텍스트 테스트가 조용히 예전으로 돌아갑니다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
// **@SpringBootTest 의 properties 가 아니라 여기에 둡니다.** 아래 properties() 가 그 자리를
// 덮어쓰는 별칭이라, 거기에 두면 테스트가 제 설정을 하나 더하는 순간 이 둘이 사라집니다.
// @TestPropertySource 는 그것과 따로 더해지고 우선순위도 더 높습니다.
@TestPropertySource(properties = {
        "wimb.data4library.auth-key=test-key-not-used-for-calls",
        "wimb.catalog.warm-on-startup=false",
        // 호출 원장 파일은 컨텍스트마다 새 이름입니다. 이름이 하나면 앞선 실행이 남긴 그날의
        // 건수를 이어받아(개발 기계의 backend/data 에 있는 진짜 원장도 마찬가지입니다), 아래의
        // 「한 건도 부르지 않았다」 검사가 부르지도 않은 호출로 실패합니다. 원장은 적힌 것이
        // 있을 때만 파일을 쓰므로, 테스트가 통과하는 동안에는 파일이 생기지 않습니다.
        "wimb.data4library.ledger-path=build/tmp/test-call-ledger-${random.uuid}.tsv"})
@Import(RefusingTransport.Config.class)
@TestExecutionListeners(
        listeners = NoData4LibraryCalls.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface WimbContextTest {

    /** 이 테스트만 쓰는 설정. {@code @SpringBootTest(properties = …)} 와 같습니다. */
    @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {};
}
