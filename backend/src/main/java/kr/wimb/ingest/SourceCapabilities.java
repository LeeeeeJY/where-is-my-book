package kr.wimb.ingest;

import java.time.Duration;

/**
 * 소스마다 다른 성질. 파이프라인이 이 값을 보고 동작을 바꿉니다.
 *
 * @param bulkSnapshot       true 면 파이프라인이 이번에 안 보인 행을 비활성으로 처리합니다.
 * @param dailyCallBudget    0 이면 파일 기반이라 한도가 없습니다.
 * @param minRequestInterval 상대 서버에 대한 예의이자 차단을 피하는 장치입니다.
 * @param trustScore         0~100. 소스 간 필드 충돌을 해소하는 기준입니다.
 *                           서지정보 API 90, 정보나루 55, 예스24 40 처럼 둡니다.
 */
public record SourceCapabilities(
        boolean bulkSnapshot,
        boolean incremental,
        int dailyCallBudget,
        Duration minRequestInterval,
        int trustScore
) {
    public SourceCapabilities {
        if (trustScore < 0 || trustScore > 100) {
            throw new IllegalArgumentException("trustScore 는 0~100 이어야 합니다: " + trustScore);
        }
    }

    public static SourceCapabilities httpApi(int dailyCallBudget, Duration minRequestInterval, int trustScore) {
        return new SourceCapabilities(false, true, dailyCallBudget, minRequestInterval, trustScore);
    }

    public static SourceCapabilities bulkFile(int trustScore) {
        return new SourceCapabilities(true, false, 0, Duration.ZERO, trustScore);
    }
}
