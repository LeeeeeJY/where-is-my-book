package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * 컨텍스트 테스트 클래스가 끝날 때 <b>정보나루를 한 건도 부르지 않았는지</b> 봅니다.
 *
 * <p>{@link WimbContextTest} 가 달아 주므로 테스트마다 따로 적지 않습니다. 따로 적게 하면
 * 다음에 만드는 컨텍스트 테스트가 빠뜨립니다.
 *
 * <h2>셋을 봅니다</h2>
 *
 * <ul>
 *   <li><b>미리 받기가 시작되지 않았다.</b> 건수만 보면 안 됩니다. 미리 받기는 따로 도는
 *       스레드라, 켜져 있어도 <b>아직 첫 호출에 닿기 전</b>에 세면 0 입니다. 운이 좋으면
 *       통과하는 검사가 됩니다. 그래서 「시작하지 않는다」를 값으로 직접 묻습니다.</li>
 *   <li><b>원장이 0 이다.</b> 클라이언트는 요청을 만들기 전에 원장부터 깎으므로, 나가려던
 *       시도가 한 번이라도 있었으면 여기에 남습니다.</li>
 *   <li><b>전송 계층이 한 번도 불리지 않았다.</b> 원장을 거치지 않는 길이 생겨도
 *       정보나루로 나가는 문은 하나뿐이라 여기에는 남습니다.</li>
 * </ul>
 */
final class NoData4LibraryCalls implements TestExecutionListener {

    @Override
    public void afterTestClass(TestContext testContext) {
        // 컨텍스트가 뜨지 못했으면 볼 것이 없고, 그 실패는 테스트가 이미 말했습니다.
        // 여기서 getApplicationContext() 를 부르면 다시 띄우려 들어 같은 오류가 한 번 더 납니다.
        if (!testContext.hasApplicationContext()) return;
        ApplicationContext context = testContext.getApplicationContext();
        String test = testContext.getTestClass().getSimpleName();

        Thread warm = context.getBean(WimbController.class).startCatalogWarm();
        if (warm != null) {
            throw new AssertionError(test + ": 서버가 뜰 때의 도서관 목록 미리 받기가 테스트 "
                    + "컨텍스트에서 켜져 있습니다. wimb.catalog.warm-on-startup=false 가 "
                    + "먹지 않았습니다. 켜진 채로는 테스트를 돌릴 때마다 정보나루를 부릅니다.");
        }

        int used = context.getBean(ApiBudget.class).used(Data4LibraryClient.SOURCE_CODE);
        var attempted = context.getBean(RefusingTransport.class).attempted();
        if (used != 0 || !attempted.isEmpty()) {
            throw new AssertionError(test + ": 컨텍스트 테스트가 정보나루를 부르려 했습니다. "
                    + "원장에 적힌 건수 " + used + ", 전송 계층에 닿은 요청 " + attempted
                    + ". 컨텍스트 테스트는 조립과 주소 연결만 봅니다. 정보나루의 답이 필요한 "
                    + "시험은 가짜 Transport 로 클래스를 직접 만들어 쓰세요(WimbControllerTest).");
        }
    }
}
