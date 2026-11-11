package kr.wimb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WimbApplication {
    public static void main(String[] args) {
        // TLS 를 처음 쓰기 전이어야 합니다. 그 뒤에 부르면 아무 일도 일어나지 않습니다(LegacyTls 참고).
        kr.wimb.opac.LegacyTls.enableUnlessSwitchedOff();
        SpringApplication.run(WimbApplication.class, args);
    }
}
