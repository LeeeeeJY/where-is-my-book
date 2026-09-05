package kr.wimb.ingest;

import java.util.List;
import java.util.Set;

/**
 * 소스 어댑터의 계약.
 *
 * <p>소스를 켜고 끄는 것이 설정 변경 수준이어야 합니다. 알라딘과 네이버가 두 달 사이에
 * 사라진 것을 보면, 소스가 없어지는 것은 예외 상황이 아니라 예정된 일입니다.
 */
public interface SourceAdapter {

    String code();

    Set<SourceRecord.Kind> produces();

    SourceCapabilities capabilities();

    /** 지금 처리할 일감 목록. 값싸고 부수 효과가 없어야 합니다. */
    List<IngestUnit> discover(DiscoveryContext context);

    /** 한 단위를 처리합니다. <b>처음부터 다시 실행해도 같은 결과여야 합니다.</b> */
    void fetch(IngestUnit unit, RecordSink sink) throws SourceException;

    /** 일감을 정할 때 필요한 바깥 정보. */
    record DiscoveryContext(java.time.LocalDate today, java.util.Map<String, String> lastSucceededVersion) {}

    class SourceException extends RuntimeException {
        public SourceException(String message) { super(message); }
        public SourceException(String message, Throwable cause) { super(message, cause); }
    }
}
