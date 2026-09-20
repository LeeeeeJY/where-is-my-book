package kr.wimb.data4library;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Isbn;
import kr.wimb.bib.Volume;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code srchBooks} 와 {@code itemSrch} 가 돌려주는 서지.
 * 항목 이름은 Open API Manual v20260210 의 응답 명세 그대로입니다.
 *
 * <p>매뉴얼을 읽고 바뀐 것 셋입니다.
 * <ul>
 *   <li>{@code addition_symbol} 로 부가기호가 <b>따로 옵니다.</b> ISBN 필드에 섞여 들어올
 *       것이라 보고 방어 코드를 넣었는데, 적어도 이 API 에서는 분리되어 있습니다.
 *       방어는 그대로 두되(다른 소스가 붙을 수 있으므로) 전제는 아닙니다.</li>
 *   <li>{@code vol} 로 권차가 <b>따로 옵니다.</b> 제목 꼬리에서 파싱하는 것보다 이 값이
 *       믿을 만하므로 있으면 우선합니다.</li>
 *   <li>{@code bookImageURL} 로 표지가 옵니다. 1차에서 표지를 포기할 필요가 없습니다.</li>
 * </ul>
 *
 * <p><b>{@code callNumbers} 는 {@code itemSrch} 만 줍니다.</b> 그리고 그 안의
 * {@code callNumber} 는 청구기호 문자열이 아니라 조각들을 담은 상자입니다.
 * {@link CallNumber} 에 실제 응답 모양을 적어 두었습니다.
 */
public record BookInfo(
        String bookname,
        String authors,
        String publisher,
        String publicationYear,
        String isbn13,
        String setIsbn13,
        String additionSymbol,
        String vol,
        String classNo,
        String classNm,
        String bookImageUrl,
        String bookDetailUrl,
        Integer loanCount,
        /**
         * 등록일자. {@code itemSrch} 만 줍니다. <b>장서 데이터 기준일과 혼동하지
         * 마세요.</b> 이것은 그 책이 그 도서관에 들어온 날이고, 기준일은 우리가 수집을
         * 끝낸 날입니다.
         */
        String regDate,
        /**
         * 복본마다의 청구기호 조각. {@code itemSrch}(2절)만 줍니다. 다른 엔드포인트에서는
         * 빈 목록입니다.
         *
         * <p><b>한 책에 여럿일 수 있습니다.</b> 복본이 둘이면 여기도 둘이고, 자료실이
         * 다르면 서가에서 서로 다른 자리에 꽂힙니다. 자세한 것은 {@link CallNumber} 를
         * 보세요.
         */
        List<CallNumber> callNumbers
) {
    public static BookInfo from(Map<String, String> fields) {
        return new BookInfo(
                fields.get("bookname"),
                fields.get("authors"),
                fields.get("publisher"),
                fields.get("publication_year"),
                fields.get("isbn13"),
                fields.get("set_isbn13"),
                fields.get("addition_symbol"),
                fields.get("vol"),
                fields.get("class_no"),
                fields.get("class_nm"),
                fields.get("bookImageURL"),
                fields.get("bookDtlUrl"),
                asInt(fields.get("loan_count")),
                fields.get("reg_date"),
                List.of());
    }

    /**
     * 청구기호 조각을 붙인 사본. {@code itemSrch} 만 이 값을 줍니다.
     *
     * <p><b>빈 상자는 걸러서 넣습니다.</b> 조각이 하나도 없는 {@code callNumber} 를
     * 그대로 들고 있으면 서가에 자리가 없는 복본이 목록에 들어갑니다.
     */
    public BookInfo withCallNumbers(List<CallNumber> values) {
        List<CallNumber> kept = values.stream().filter(c -> !c.isEmpty()).toList();
        return new BookInfo(bookname, authors, publisher, publicationYear, isbn13, setIsbn13,
                additionSymbol, vol, classNo, classNm, bookImageUrl, bookDetailUrl,
                loanCount, regDate, kept);
    }

    /** 체크디지트까지 검증한 ISBN13. 판별할 수 없으면 비어 있습니다. */
    public Optional<String> canonicalIsbn13() {
        return Isbn.canonicalize(isbn13);
    }

    /**
     * 권차. API 가 준 값을 우선하고, 없을 때만 제목에서 뽑은 값을 씁니다.
     * 표제를 파싱하는 것보다 이 값이 믿을 만합니다.
     *
     * <p><b>서수만이 아니라 표기까지 듭니다.</b> 「상」으로 온 값을 1로만 바꾸면 화면에
     * 「1권」으로 나가고, 상·하 두 권뿐인 책은 「1권」과 「3권」이 됩니다.
     */
    public Optional<Volume> volume() {
        return Optional.ofNullable(BibNormalizer.volume(vol));
    }

    /** 권차의 서수만. 정렬처럼 순서만 필요한 곳의 지름길입니다. */
    public Optional<Integer> volumeNumber() {
        return volume().map(Volume::ordinal);
    }

    private static Integer asInt(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
