package kr.wimb.data4library;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Isbn;
import kr.wimb.bib.Volume;

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
         * 인기대출 계열의 순위. <b>{@code loan_count} 가 오지 않는 자리가 있어서
         * 따로 듭니다.</b> 매뉴얼이 「도서관별 인기대출도서는 대출순위만 제공」이라고
         * 적어 두었고(9절), 15절 응답에는 {@code loan_count} 항목이 아예 없습니다.
         * 그런 자리에서는 대출건수로 순서를 맞추던 규칙을 쓸 수 없습니다.
         */
        Integer ranking,
        /**
         * 청구기호. {@code itemSrch}(2절)의 {@code callNumbers > callNumber} 에서 옵니다.
         * <b>서가에서 책을 찾을 때 실제로 쓰는 값입니다.</b> 한 겹 더 들어가 있어
         * {@link Data4LibraryResponse#itemsUnder} 로 따로 읽어 넣습니다.
         */
        String callNumber
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
                asInt(fields.get("ranking")),
                null);
    }

    /** 청구기호를 붙인 사본. {@code itemSrch} 만 이 값을 줍니다. */
    public BookInfo withCallNumber(String value) {
        return new BookInfo(bookname, authors, publisher, publicationYear, isbn13, setIsbn13,
                additionSymbol, vol, classNo, classNm, bookImageUrl, bookDetailUrl,
                loanCount, ranking, value);
    }

    /**
     * 이 자료가 소설인지. <b>매뉴얼에는 근거가 없습니다.</b> 세부주제({@code dtl_kdc})가
     * 두 자리까지라 {@code 81} 한국문학·{@code 83} 일본문학까지만 가르고, 소설은 KDC
     * 세 번째 자리({@code 813} 한국소설, {@code 843} 영미소설)이기 때문입니다.
     *
     * <p>그래서 {@code kdc=8} 로 문학을 받아 여기서 거릅니다. <b>도서관마다 분류 체계가
     * 달라 {@code class_no} 가 KDC 가 아닌 자료가 섞일 수 있으므로, 이 규칙은 실제 값을
     * 충분히 본 뒤에 다시 봐야 합니다.</b> 지금은 「8로 시작하고 세 번째 자리가 3」만
     * 봅니다. 아니면 거르는 쪽이 아니라 남기지 않는 쪽입니다. 소설이 아닌 것을 소설이라고
     * 내보내는 것보다 몇 권 놓치는 편이 낫습니다.
     */
    public boolean looksLikeNovel() {
        if (classNo == null || classNo.length() < 3) return false;
        return classNo.charAt(0) == '8' && classNo.charAt(2) == '3';
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
