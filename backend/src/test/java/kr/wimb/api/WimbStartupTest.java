package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @DisplayName("실제 설정에서 OPAC 규칙이 꺼져 있고, 규칙 표는 그대로 실려 있다")
    void opacLinksAreDisabledButRulesAreLoaded() {
        var templates = context.getBean(kr.wimb.opac.OpacTemplates.class);

        // **@Value 의 폴백은 켜짐입니다.** application.yml 의 키 이름에 오타가 나면 그 폴백이
        // 먹어서 **조용히 켜진 채로 배포됩니다.** 확인하지 않은 규칙 315곳이 그대로 화면에
        // 나가는데, 오류도 경고도 나지 않고 링크가 열리기까지 하므로 눌러 본 사람만 압니다.
        // 그래서 실제 설정 파일을 읽어 올린 컨텍스트에서 값을 봅니다.
        assertFalse(templates.linksEnabled(),
                "확인하지 않은 규칙은 화면으로 내보내지 않습니다. 확인을 마치고 켤 때 이 검사도 "
                        + "함께 고치세요. 켜는 것은 의도한 결정이어야 합니다");
        // 꺼도 표는 읽습니다. 0이면 이미지에서 규칙 파일을 잃은 것이고, 예전에 .gitignore 가
        // CSV 를 삼킨 적이 있습니다. /api/status 가 이 둘을 함께 내보내는 이유입니다.
        assertTrue(templates.size() > 0, "규칙 표를 잃으면 보완할 기반도 없어집니다");
    }
}
