package kr.wimb.ingest;

/**
 * 어댑터가 결과를 흘려보내는 곳.
 *
 * <p>{@link #reject}가 예외와 분리되어 있는 것이 의도적입니다. 변환할 수 없는 레코드
 * (ISBN 이 없는 자료 등)는 정상 범위에 있으므로 건수만 세어 남기고 넘어갑니다.
 * <b>한 줄의 형식이 이상하다고 그 구간 전체의 적재가 중단되면 안 됩니다.</b>
 */
public interface RecordSink {

    void accept(SourceRecord record);

    void reject(String rawLine, String reasonCode, String detail);

    void progress(long rowsRead);

    /** 테스트와 시험 실행에 쓰는 최소 구현. */
    static RecordSink noop() {
        return new RecordSink() {
            public void accept(SourceRecord record) {}
            public void reject(String rawLine, String reasonCode, String detail) {}
            public void progress(long rowsRead) {}
        };
    }
}
