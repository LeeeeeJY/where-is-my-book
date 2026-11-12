package kr.wimb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 컨텍스트를 올리는 테스트가 전부 {@link WimbContextTest} 를 거치는지 봅니다.
 *
 * <p>{@code @SpringBootTest} 를 그대로 붙인 테스트는 <b>진짜 정보나루로 요청을 보냅니다.</b>
 * 서버가 뜰 때의 도서관 목록 미리 받기가 테스트 컨텍스트에서도 돌기 때문입니다. 그 테스트는
 * 멀쩡히 통과하고 경고도 없어서, 막아 두지 않으면 다음에 만드는 컨텍스트 테스트가 조용히
 * 예전으로 돌아갑니다. {@code WimbContextTest} 의 주석에 사정이 적혀 있습니다.
 */
class ContextTestsStayOfflineTest {

    /** 주석을 직접 붙인 것만 찾습니다. {@code WimbContextTest} 를 거친 것은 걸리지 않습니다. */
    private static List<String> annotatedDirectlyWith(Class<? extends java.lang.annotation.Annotation> type) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(type, false));
        return scanner.findCandidateComponents("kr.wimb").stream()
                .map(candidate -> candidate.getBeanClassName())
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("@SpringBootTest 를 직접 붙인 테스트가 없다")
    void noTestUsesSpringBootTestDirectly() {
        assertEquals(List.of(), annotatedDirectlyWith(SpringBootTest.class),
                "@SpringBootTest 대신 @WimbContextTest 를 붙이세요. 그대로 두면 테스트를 돌릴 "
                        + "때마다 진짜 정보나루로 요청이 나갑니다");
    }

    /**
     * 위 검사가 <b>아무것도 못 찾아서</b> 통과하는 것이 아닌지 봅니다. 찾는 방법이 틀려도
     * 빈 목록이 나오고, 그러면 위 검사는 영영 통과합니다.
     */
    @Test
    @DisplayName("찾는 방법이 실제로 테스트 클래스를 찾아낸다")
    void theScanActuallyFindsTestClasses() {
        List<String> found = annotatedDirectlyWith(WimbContextTest.class);
        assertFalse(found.isEmpty(), "컨텍스트 테스트를 하나도 찾지 못했습니다. 찾는 방법이 틀렸습니다");
        assertEquals(true, found.contains(WimbStartupTest.class.getName()), found.toString());
    }
}
