package kr.wimb.shelf;

import java.util.List;
import java.util.Map;

/**
 * 서가 하나의 차림표. 화면이 맨 처음 받는 것이고, 서버가 메모리에 들고 있는 것도
 * 이것뿐입니다. 책은 조각 파일에 있고 필요할 때만 읽습니다.
 *
 * @param libCode   도서관부호
 * @param kdc       이 서가의 대주제. 도서관 하나가 대주제마다 서가 하나씩 갖습니다
 * @param asOf      <b>장서 데이터 기준일.</b> 수집을 끝낸 한국 날짜입니다.
 *                  책마다 오는 등록일자({@code reg_date})와 다릅니다. 그쪽은 그 책이
 *                  들어온 날이고, 이것은 우리가 받아 온 날입니다. 화면 머리에 그대로
 *                  나가며, <b>실시간이 아니라는 것을 숨기지 않으려는 표시입니다</b>
 * @param checkedAt 마지막으로 <b>바뀌었는지 확인한</b> 날. {@link #asOf} 와 다릅니다.
 *                  권수만 물어봐서(1회) 그대로였으면 다시 세우지 않고 이 날짜만
 *                  올립니다. 그때 {@code asOf} 를 함께 올리면 <b>받아 온 적 없는
 *                  날짜를 받아 온 것처럼 말하게 됩니다.</b> 화면에 나가는 것은
 *                  {@code asOf} 이고, 다시 세울 때가 되었는지는 이 값으로 셉니다
 * @param chunkSize 조각 하나에 든 권수
 * @param count     서가에 세운 복본 수. 책 수가 아닙니다
 * @param reported  정보나루가 말한 장서 건수({@code numFound}).
 *                  <b>{@code count} 와 다른 것이 정상입니다.</b> 청구기호가 없는 자료는
 *                  서가에 세우지 않고, 복본이 있는 책은 여러 줄이 됩니다. 두 숫자를
 *                  함께 두는 이유는 <b>차이가 갑자기 벌어졌을 때 알아채기 위해서</b>입니다.
 *                  하나만 있으면 수집이 반쯤 되다 말았는지 원래 그런지 구별할 수 없습니다
 * @param keyVersion 이 서가를 세울 때 쓴 <b>순서 규칙의 판 번호</b>
 *                  ({@link ShelfSortKey#VERSION}). 순서는 수집할 때 계산해 파일에 적어
 *                  두므로, 규칙을 고쳐도 이미 세워 둔 서가는 예전 순서 그대로입니다.
 *                  번호가 다르면 서버가 낡은 것으로 보고 뒤에서 다시 세웁니다.
 *                  <b>이 값이 없던 때에 만든 차림표는 0 으로 읽혀 다시 세워집니다.</b>
 *                  의도한 동작입니다
 * @param shapeVersion 이 서가를 <b>어떤 모양으로 적었는지</b>의 판 번호
 *                  ({@link #SHAPE_VERSION}). 수집기가 파일에 적는 것이 늘어날 때마다
 *                  올립니다. 찾기 색인이 그랬고 갈래 구간이 그랬습니다.
 *                  <b>{@code keyVersion} 과 따로 두는 이유는 순서를 다시 적은 것과
 *                  적는 것이 늘어난 것이 다른 일이기 때문입니다.</b> 순서 판 번호를
 *                  대신 올리면 순서를 고치지도 않고 고쳤다고 말하게 됩니다.
 *                  <b>그리고 늘어날 때마다 불리언을 하나씩 두지 마세요.</b>
 *                  기능마다 하나씩 생기면 낡았는지 보는 자리가 다섯 곳으로 흩어지고,
 *                  그중 하나를 빠뜨리면 그 서가는 <b>영영</b> 예전 모양으로 남습니다.
 *                  이 값이 없던 때에 만든 차림표는 0 으로 읽혀 다시 세워집니다
 */
public record ShelfMeta(
        String libCode,
        String kdc,
        String asOf,
        String checkedAt,
        int chunkSize,
        int count,
        int reported,
        int keyVersion,
        int shapeVersion,
        List<Room> rooms
) {

    /**
     * 지금 수집기가 적는 차림표·색인의 모양.
     *
     * <ol>
     *   <li>찾기 색인({@code find.txt})</li>
     *   <li>갈래 구간({@link Room#sections})</li>
     * </ol>
     *
     * <p><b>적는 것을 늘렸으면 여기를 올리세요.</b> 수집할 때 한 번 적고 마는 값이라,
     * 올리지 않으면 이미 세워 둔 서가에는 그것이 없고 <b>권수가 그대로면 갱신도
     * 건너뛰므로 아무도 손대지 않는 한 영영 없습니다.</b> 화면에는 아무 이상이 없어
     * 보여서, 기능을 붙인 사람은 붙었다고 믿습니다.
     */
    public static final int SHAPE_VERSION = 2;

    /**
     * 자료실 한 곳. <b>서가를 고르는 단위입니다.</b> 층이 다르면 아예 다른 서가이므로
     * 한 화면에 이어 붙이지 않습니다.
     *
     * @param slug      파일이 놓인 폴더 이름. {@code r0}, {@code r1} 처럼 자리 번호입니다.
     *                  <b>자료실 이름을 폴더 이름으로 쓰지 않습니다.</b> 실제 값이
     *                  「[상동도서관]부천작가(1층)」처럼 대괄호와 괄호를 달고 있어서
     *                  파일 이름으로도 주소로도 그대로 쓸 수 없습니다
     * @param code      배가기호. 없을 수 있습니다
     * @param name      사람이 읽는 자료실 이름. 화면 머리에 나갑니다
     * @param count     이 자료실의 복본 수
     * @param chunks    조각 파일 수
     * @param firstCall 첫 책의 청구기호. 선반 라벨의 왼쪽입니다
     * @param lastCall  마지막 책의 청구기호. 선반 라벨의 오른쪽입니다
     * @param chosungAt 초성 → <b>그 초성이 처음 나오는 자리</b>. 오른쪽 색인이 이것으로
     *                  뜁니다. 없는 초성은 담기지 않으므로, 화면은 담기지 않은 줄을
     *                  흐리게 그립니다
     * @param sections  이 자료실에 <b>갈래가 어디서부터 어디까지 서 있는지</b>.
     *                  서가에 선 차례 그대로입니다
     */
    public record Room(
            String slug,
            String code,
            String name,
            int count,
            int chunks,
            String firstCall,
            String lastCall,
            Map<String, Integer> chosungAt,
            List<Section> sections
    ) {}

    /**
     * 한 갈래가 서 있는 자리.
     *
     * <p>서가가 청구기호 순이고 분류번호가 그 앞자리를 정하므로, <b>같은 갈래의 책은
     * 서가에서 한 덩어리로 붙어 있습니다.</b> 그래서 갈래를 고르는 것이 곧 「그 구역으로
     * 걸어가기」가 됩니다. 정보나루를 한 번도 부르지 않고 이미 받아 둔 값으로 만듭니다.
     *
     * @param name  정보나루가 준 분류명({@code class_nm}) 그대로입니다.
     *              「문학 &gt; 한국문학 &gt; 소설」처럼 옵니다.
     *              <b>우리가 분류번호로 갈래 이름을 지어내지 않습니다.</b> 표를 우리가
     *              들고 있으면 그 표가 틀리는 날 서가가 엉뚱한 이름을 답합니다
     * @param at    <b>가장 길게 이어지는 덩어리</b>가 시작하는 자리.
     *              한 갈래가 두 군데로 갈리는 일이 실제로 있습니다. 별치기호가
     *              분류번호보다 앞에서 갈라서, 「큰글자책 한국소설」이 보통 서가의
     *              한국소설과 따로 섭니다. <b>처음 나오는 자리를 적으면 큰글자책
     *              구석으로 데려다주게 됩니다.</b> 대부분의 책이 있는 쪽으로 보냅니다
     * @param count 흩어진 덩어리까지 <b>합한 권수</b>. 화면이 이것을 함께 보여 주므로,
     *              가장 큰 덩어리만 세면 실제보다 적게 말하게 됩니다
     */
    public record Section(String name, int at, int count) {}

    /** 확인한 지 며칠 지났는지 셉니다. 확인한 적이 없으면 수집일부터 셉니다. */
    public java.time.LocalDate checkedOn() {
        String at = checkedAt == null || checkedAt.isBlank() ? asOf : checkedAt;
        return java.time.LocalDate.parse(at);
    }
}
