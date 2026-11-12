package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 컨텍스트 테스트에서 정보나루로 나가는 문을 대신하는 것. <b>부르면 거절하고, 불렸다는 것을
 * 적어 둡니다.</b>
 *
 * <p>가짜 응답을 돌려주지 않는 것은 일부러입니다. 컨텍스트 테스트가 보는 것은 조립과 주소
 * 연결이지 정보나루의 답이 아닙니다. 그럴듯한 빈 목록을 돌려주면 「정보나루를 부르고
 * 있다」는 사실이 테스트 통과 뒤에 가려집니다. 실패는 부르는 쪽에서 「물어보지 못했다」로
 * 다뤄지므로 운영에서 정보나루가 답하지 않을 때와 같은 길을 탑니다.
 */
final class RefusingTransport implements Data4LibraryClient.Transport {

    /** 부르려던 엔드포인트. 인증키가 든 질의 문자열은 남기지 않습니다. */
    private final List<String> attempted = new CopyOnWriteArrayList<>();

    @Override
    public String get(URI uri) {
        attempted.add(uri.getPath());
        throw new IllegalStateException(
                "컨텍스트 테스트에서는 정보나루를 부르지 않습니다: " + uri.getPath());
    }

    List<String> attempted() {
        return List.copyOf(attempted);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        /**
         * 운영의 전송 계층({@code WimbConfiguration.data4LibraryTransport})도 빈으로
         * 남아 있지만 {@code @Primary} 라 클라이언트는 이것을 받습니다. 운영 쪽은 만들어지기만
         * 하고 불리지 않습니다.
         */
        @Bean
        @Primary
        RefusingTransport refusingTransport() {
            return new RefusingTransport();
        }
    }
}
