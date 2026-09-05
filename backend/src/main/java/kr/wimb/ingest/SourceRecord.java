package kr.wimb.ingest;

import java.util.Map;

/**
 * 소스가 내보내는 레코드 하나.
 *
 * <p><b>어댑터는 본 테이블에 직접 쓰지 않습니다.</b> 소스 형태의 레코드를 내보내기만 하고,
 * 정규화와 저장은 파이프라인이 담당합니다. 이 불변식 하나가 소스를 갈아 끼울 수 있게 만듭니다.
 * 소스 고유 필드가 도메인 모델이나 색인 스키마에 나타나면 그 순간 교체 가능성이 사라집니다.
 *
 * @param fields     소스가 쓰는 필드명 그대로. 여기서 이름을 바꾸지 않습니다.
 * @param rawPayload 원본 한 줄 또는 한 객체. 파서를 고쳐서 다시 처리할 때 씁니다.
 */
public record SourceRecord(
        Kind kind,
        String sourceCode,
        String sourceRecordId,
        String scopeKey,
        Map<String, String> fields,
        String rawPayload
) {
    public enum Kind { LIBRARY, BIB, HOLDING, OPAC_TEMPLATE }

    public String field(String name) {
        return fields.get(name);
    }
}
