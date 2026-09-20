package kr.wimb.shelf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 서가 수집을 <b>사람이 부르는 명령</b>으로 돌립니다.
 *
 * <pre>{@code
 * docker run --rm -v wimb-data:/data -e D4L_AUTH_KEY=... \
 *     ghcr.io/…/wimb:latest --wimb.shelf.harvest=141321
 * }</pre>
 *
 * <h2>왜 화면에서 부를 수 있게 하지 않나</h2>
 *
 * <p>우리 API 에는 인증이 없습니다. 주소는 번들에 박혀 있어 감출 수 없으므로, 수집을
 * 부를 수 있는 통로를 열어 두면 <b>아무나 정보나루 호출 수천 건을 태울 수 있습니다.</b>
 * 하루 한도가 한 번에 사라지고 그동안 검색이 전부 「확인 불가」가 됩니다.
 *
 * <p>명령줄로만 부르면 그 통로 자체가 없습니다. 서버에 들어갈 수 있는 사람만 돌릴 수
 * 있고, 그 사람은 이미 무엇이든 할 수 있는 사람입니다.
 *
 * <h2>자동으로 돌리지 않습니다</h2>
 *
 * <p>장서는 하루 사이에 크게 바뀌지 않습니다. 그런데 자동으로 돌면 <b>실패했을 때
 * 아무도 모릅니다.</b> 반쯤 받다 만 서가가 조용히 자리를 차지하거나, 정보나루가
 * 흔들린 날 하루 예산이 헛호출로 사라집니다. 사람이 돌리면 적어도 결과를 봅니다.
 *
 * <h2>수집이 끝나면 그대로 멈춥니다</h2>
 *
 * <p>수집용으로 띄운 컨테이너가 계속 떠 있으면, 서비스하는 컨테이너와 <b>같은 예산
 * 원장을 서로 모르는 채</b> 나눠 쓰게 됩니다. 일을 마치면 나갑니다.
 */
@Component
public class ShelfHarvestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShelfHarvestRunner.class);

    /** 수집할 도서관을 적는 자리. 없으면 이 클래스는 아무 일도 하지 않습니다. */
    static final String PROPERTY = "wimb.shelf.harvest";

    private final ShelfHarvester harvester;

    /**
     * 수집할 도서관부호를 쉼표로 이어 적습니다. 비어 있으면 아무 일도 하지 않습니다.
     *
     * <p>명령줄({@code --wimb.shelf.harvest=141321})로도 환경
     * 변수({@code WIMB_SHELF_HARVEST=141321})로도 줄 수 있습니다. 도커로 띄울 때는
     * 환경 변수가 편합니다.
     */
    private final String asked;

    public ShelfHarvestRunner(ShelfHarvester harvester,
                              @Value("${" + PROPERTY + ":}") String asked) {
        this.harvester = harvester;
        this.asked = asked;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (asked == null || asked.isBlank()) return;

        int failed = 0;
        for (String libCode : asked.split(",")) {
            String code = libCode.trim();
            if (code.isEmpty()) continue;
            try {
                ShelfMeta meta = harvester.harvest(code);
                log.info("도서관 {}: 복본 {}권, 자료실 {}곳, 기준일 {}. 정보나루가 말한 장서는 {}건입니다.",
                        code, meta.count(), meta.rooms().size(), meta.asOf(), meta.reported());
            } catch (Exception e) {
                // **한 곳이 실패해도 나머지는 받습니다.** 다만 나갈 때 알립니다.
                // 조용히 성공한 척하면 반쯤 만들어진 서가를 배포하게 됩니다.
                log.error("도서관 {} 서가를 만들지 못했습니다: {}", code, e.toString());
                failed++;
            }
        }

        // 스크립트가 결과를 볼 수 있게 종료 코드로 알립니다. 로그만 남기면
        // 자동화가 실패를 성공으로 읽습니다.
        System.exit(failed == 0 ? 0 : 1);
    }
}
