package kr.wimb.ingest;

import java.net.URI;

/**
 * 재시도할 수 있는 최소 단위입니다.
 *
 * <p>단위를 잘게 나누는 것이 중요합니다. 서지 백필은 발행일 구간 하나, 벌크는 도서관 하나입니다.
 * 단위가 크면 중간에 실패했을 때 처음부터 다시 해야 하는데, 3,000회 요청짜리 백필에서는
 * 그것이 곧 완주 실패를 뜻합니다.
 *
 * @param inputSha256 응답이나 파일의 해시. ingest_run 의 멱등성 제약에 쓰여
 *                    같은 것을 다시 적재하는 일이 자동으로 건너뛰기가 됩니다.
 */
public record IngestUnit(
        String sourceCode,
        String scopeKey,
        String sourceVersion,
        Mode mode,
        URI input,
        String inputSha256
) {
    public enum Mode { FULL_SNAPSHOT, INCREMENTAL, BACKFILL }
}
