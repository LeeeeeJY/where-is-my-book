package kr.wimb.data4library;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Isbn;

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
        Integer loanCount
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
                asInt(fields.get("loan_count")));
    }

    /** 체크디지트까지 검증한 ISBN13. 판별할 수 없으면 비어 있습니다. */
    public Optional<String> canonicalIsbn13() {
        return Isbn.canonicalize(isbn13);
    }

    /**
     * 권차. API 가 준 값을 우선하고, 없을 때만 제목에서 뽑은 값을 씁니다.
     * 표제를 파싱하는 것보다 이 값이 믿을 만합니다.
     */
    public Optional<Integer> volumeNumber() {
        return Optional.ofNullable(BibNormalizer.volumeOrdinal(vol));
    }

    private static Integer asInt(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
