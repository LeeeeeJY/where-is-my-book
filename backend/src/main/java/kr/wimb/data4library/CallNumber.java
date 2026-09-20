package kr.wimb.data4library;

import java.util.Map;

/**
 * 복본 하나의 청구기호 조각들. {@code itemSrch}(매뉴얼 2절)의
 * {@code docs > doc > callNumbers > callNumber} 가 돌려주는 그대로입니다.
 *
 * <h2>{@code callNumber} 는 잎이 아니라 상자입니다</h2>
 *
 * <p>매뉴얼의 응답 명세만 보면 {@code callNumber} 가 「도서 청구기호」라고 적혀 있어
 * 그 자리에 청구기호 문자열이 올 것처럼 읽힙니다. <b>그렇지 않습니다.</b> 매뉴얼 6~7쪽의
 * 실제 응답 화면을 확인한 결과 자기 글자는 없고 아래 여섯 조각만 자식으로 들어 있습니다.
 *
 * <pre>{@code
 * <callNumbers>
 *   <callNumber>
 *     <separate_shelf_code/>
 *     <separate_shelf_name/>
 *     <book_code>박36ㅈ</book_code>
 *     <shelf_loc_code>AAJ</shelf_loc_code>
 *     <shelf_loc_name>[상동도서관]부천작가(1층)</shelf_loc_name>
 *     <copy_code/>
 *   </callNumber>
 * </callNumbers>
 * }</pre>
 *
 * <p>그래서 <b>청구기호는 받아 오는 값이 아니라 우리가 조립하는 값입니다.</b>
 * {@code class_no} 와 {@code book_code} 를 이어 「818 박36ㅈ」을 만듭니다.
 *
 * <p>예전 코드는 이 요소의 자기 글자만 읽었고(자식이 있으면 비웁니다), 상자에는 자기
 * 글자가 없으므로 <b>모든 도서관에서 언제나 null 이었습니다.</b> 배포 서버로 여섯 곳을
 * 물어 여섯 곳 모두 null 인 것을 확인했습니다(2026-09-20). 「값이 오는 도서관과 오지
 * 않는 도서관이 둘 다 있다」고 적어 두었던 것은 사실이 아니었습니다.
 *
 * <h2>한 책에 여럿일 수 있습니다</h2>
 *
 * <p>{@code callNumbers} 는 복본마다 하나씩 담습니다. 같은 책을 두 권 가진 도서관은
 * {@code callNumber} 가 둘이고, 자료실이 다르면 <b>서가에서 서로 다른 자리에
 * 꽂힙니다.</b> 그래서 서가 화면에서는 한 줄이 한 책이 아니라 <b>한 복본</b>입니다.
 *
 * @param separateShelfCode 별치기호. 일반 서가면 비어 있습니다
 * @param separateShelfName 별치기호명
 * @param bookCode          도서기호. 「박36ㅈ」처럼 저자 첫 글자 + 숫자 + 표제 첫 자모입니다
 * @param shelfLocCode      배가기호. 자료실을 가리키는 짧은 코드입니다
 * @param shelfLocName      배가기호명. 「[상동도서관]부천작가(1층)」처럼 사람이 읽는 자료실 이름입니다
 * @param copyCode          복본기호. 같은 자리에 여러 권이 있을 때만 옵니다
 */
public record CallNumber(
        String separateShelfCode,
        String separateShelfName,
        String bookCode,
        String shelfLocCode,
        String shelfLocName,
        String copyCode
) {
    public static CallNumber from(Map<String, String> fields) {
        return new CallNumber(
                fields.get("separate_shelf_code"),
                fields.get("separate_shelf_name"),
                fields.get("book_code"),
                fields.get("shelf_loc_code"),
                fields.get("shelf_loc_name"),
                fields.get("copy_code"));
    }

    /**
     * 아무 조각도 읽지 못했는지. <b>빈 상자는 버립니다.</b> 조각이 하나도 없으면 서가에서
     * 어디에 꽂혔는지 말해 주는 것이 없어, 그 복본을 목록에 넣어 봐야 자리를 정할 수
     * 없습니다.
     */
    public boolean isEmpty() {
        return blank(separateShelfCode) && blank(separateShelfName) && blank(bookCode)
                && blank(shelfLocCode) && blank(shelfLocName) && blank(copyCode);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
