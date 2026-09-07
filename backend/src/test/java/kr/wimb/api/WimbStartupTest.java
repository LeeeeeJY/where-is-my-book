package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 서버가 실제로 뜨는지 확인합니다.
 *
 * <p>다른 테스트는 클래스를 직접 만들어 쓰기 때문에 Spring 이 그것을 조립할 수 있는지는
 * 확인하지 않습니다. 그래서 <b>단위 테스트가 전부 통과하는데도 서버는 시작조차 못 하는
 * 상태</b>가 만들어질 수 있고, 실제로 그렇게 배포되었습니다. 컨트롤러에 생성자를 하나 더
 * 추가하면서 {@code @Autowired} 를 붙이지 않았더니 Spring 이 기본 생성자를 찾다가 실패했고,
 * 컨테이너가 시작하다 죽는 형태로만 드러났습니다.
 *
 * <p>이 테스트는 컨텍스트를 통째로 올려 그것을 잡습니다. 느리지만, 배포한 뒤에야 알게 되는
 * 것보다는 낫습니다.
 */
@SpringBootTest(properties = "wimb.data4library.auth-key=test-key-not-used-for-calls")
class WimbStartupTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("Spring 컨텍스트가 올라간다")
    void contextLoads() {
        assertNotNull(context, "컨텍스트가 올라오지 않으면 서버도 뜨지 못합니다");
    }

    @Test
    @DisplayName("컨트롤러가 빈으로 만들어진다")
    void controllerIsInstantiated() {
        assertNotNull(context.getBean(WimbController.class),
                "생성자가 둘인데 @Autowired 가 없으면 여기서 걸립니다");
    }
}
