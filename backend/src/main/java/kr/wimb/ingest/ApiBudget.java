package kr.wimb.ingest;

/**
 * 일일 호출 한도를 지키는 원장.
 *
 * <p>정보나루의 한도는 IP 를 등록해도 1일 30,000건이 상한입니다. 소장 조회가 사용자 요청
 * 처리 중에 일어나므로, 배경 갱신이 예산을 다 써 버리면 사람이 기다리는 요청이 실패합니다.
 * 그래서 우선순위를 나눕니다.
 *
 * <p><b>이 원장을 거치지 않는 호출 경로를 만들지 마세요.</b> 한 곳이라도 새면 원장이
 * 실제 잔량과 어긋나고, 그때부터는 아무것도 보장하지 못합니다.
 */
public interface ApiBudget {

    enum Priority {
        /** 사람이 기다리고 있는 요청. 예산이 남아 있는 한 통과시킵니다. */
        USER,
        /** 캐시 갱신 같은 배경 작업. 예산에 여유가 있을 때만 돕니다. */
        BACKGROUND
    }

    /** 호출 한 번을 예산에서 뺍니다. 실패하면 호출하지 않습니다. */
    boolean tryAcquire(String sourceCode, Priority priority);

    int used(String sourceCode);

    int remaining(String sourceCode);
}
