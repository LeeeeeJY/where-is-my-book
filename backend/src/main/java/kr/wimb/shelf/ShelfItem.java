package kr.wimb.shelf;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Contributor;
import kr.wimb.bib.TitleParts;
import kr.wimb.bib.Volume;
import kr.wimb.data4library.BookInfo;
import kr.wimb.data4library.CallNumber;

import java.util.ArrayList;
import java.util.List;

/**
 * 서가에 꽂힌 <b>복본 한 권</b>. 서가 화면의 한 자리가 이것입니다.
 *
 * <h2>한 책이 아니라 한 복본입니다</h2>
 *
 * <p>도서관이 같은 책을 두 권 가지고 있으면 {@code itemSrch} 는 {@code callNumber} 를
 * 둘 돌려주고, 자료실이 다르면 <b>서가에서 서로 다른 자리에 꽂힙니다.</b> 한 줄을 한
 * 책으로 잡으면 둘 중 하나는 서가에서 사라지는데, 사라진 쪽을 찾으러 간 사람은 그
 * 자리에 책이 없는 것을 보게 됩니다.
 *
 * <h2>{@code kr.wimb.data4library} 의 모양을 들고 나오지 않습니다</h2>
 *
 * <p>정보나루의 응답 구조를 아는 곳은 그 꾸러미 하나뿐이라는 규칙이 있습니다. 여기서
 * {@link BookInfo} 와 {@link CallNumber} 를 받아 <b>우리 말로 옮긴 뒤</b> 밖으로
 * 내보냅니다. 가정이 틀려도 피해가 그 경계에서 멈춥니다.
 *
 * @param isbn13     도서관 사이트로 넘길 때 씁니다
 * @param title      권차까지 붙인 표시용 표제
 * @param author     대표 저자 한 사람. 못 고르면 받은 값 그대로입니다
 * @param coverUrl   표지 주소. <b>없을 수 있습니다.</b> 화면이 대체 표지를 그립니다
 * @param callText   조립한 청구기호. 「813.6 김56ㅁ」
 * @param chosung    저자 초성. 도서기호 첫 글자에서 뽑고, 한글이 아니면 {@code null} 입니다
 * @param sortKey    서가 순서. {@link CallNumberOrder#sortKey} 가 만듭니다
 */
public record ShelfItem(
        String isbn13,
        String title,
        String author,
        String publisher,
        String pubYear,
        String coverUrl,
        String classNo,
        String classNm,
        String bookCode,
        String callText,
        String roomCode,
        String roomName,
        String separateName,
        String copyCode,
        String chosung,
        String sortKey
) {

    /**
     * 서지 한 건을 <b>복본 수만큼</b> 펼칩니다. 청구기호가 하나도 없으면 빈 목록입니다.
     *
     * <p><b>청구기호가 없는 책은 서가에 세우지 않습니다.</b> 어디에 꽂혔는지 모르는
     * 책을 순서 어딘가에 끼워 넣으면, 그 자리를 보고 찾아간 사람이 헛걸음합니다.
     * 목록에서 빠지는 것은 눈에 보이지 않지만 틀린 자리는 발걸음을 만듭니다.
     */
    public static List<ShelfItem> of(BookInfo book) {
        if (book.callNumbers().isEmpty()) return List.of();

        String isbn = book.canonicalIsbn13().orElse(book.isbn13());
        if (isbn == null || isbn.isBlank()) return List.of();

        String title = displayTitle(book);
        String author = displayAuthor(book);
        Integer volOrdinal = book.volume().map(Volume::ordinal).orElse(null);

        List<ShelfItem> out = new ArrayList<>(book.callNumbers().size());
        for (CallNumber call : book.callNumbers()) {
            String room = pick(call.shelfLocCode(), call.shelfLocName());
            String separate = pick(call.separateShelfCode(), call.separateShelfName());
            out.add(new ShelfItem(
                    isbn, title, author, trim(book.publisher()), trim(book.publicationYear()),
                    trim(book.bookImageUrl()), trim(book.classNo()), trim(book.classNm()),
                    trim(call.bookCode()),
                    callText(book.classNo(), call),
                    room, trim(call.shelfLocName()), trim(call.separateShelfName()),
                    trim(call.copyCode()),
                    Chosung.of(call.bookCode()),
                    CallNumberOrder.sortKey(room, separate, book.classNo(),
                            call.bookCode(), volOrdinal, call.copyCode())));
        }
        return out;
    }

    /**
     * 사람이 읽는 청구기호. <b>정보나루는 이것을 조립해서 주지 않습니다.</b>
     * {@code class_no} 와 {@code book_code} 가 따로 오고, 별치와 복본이 있으면
     * 앞뒤에 붙습니다. 서가의 책등에 붙은 라벨과 같은 차례로 적습니다.
     *
     * <p><b>없는 조각을 지어내지 않습니다.</b> 분류번호도 도서기호도 없으면 빈 값을
     * 돌려주고 화면이 그 경우를 다룹니다. 없는 것보다 틀린 청구기호가 나쁩니다.
     * 서가에서 헛걸음하게 만드는 값입니다.
     */
    static String callText(String classNo, CallNumber call) {
        List<String> parts = new ArrayList<>(4);
        addIfPresent(parts, call.separateShelfCode());
        addIfPresent(parts, classNo);
        addIfPresent(parts, call.bookCode());
        addIfPresent(parts, call.copyCode());
        return String.join(" ", parts);
    }

    /**
     * 자료실 열쇠. <b>코드를 먼저 씁니다.</b> 이름은 도서관이 표기를 바꾸기 쉽고
     * 「[상동도서관]부천작가(1층)」처럼 길어서, 파일 이름과 열쇠로 쓰기에 코드가 낫습니다.
     * 코드가 없는 도서관에서는 이름이 유일한 근거이므로 그때만 이름을 씁니다.
     */
    private static String pick(String code, String name) {
        String first = trim(code);
        return first.isEmpty() ? trim(name) : first;
    }

    /**
     * 화면에 보여 줄 표제. <b>받은 글자를 그대로 쓰면 안 됩니다.</b> 서명 필드에
     * KORMARC 245 의 구분 기호가 섞여 들어와 「페인트 :이희영 장편소설」처럼 부제가
     * 콜론 뒤에 붙은 채 옵니다. 검색 화면이 쓰는 것과 같은 함수로 갈라 놓아야 두 화면의
     * 표기가 같아집니다.
     *
     * <p>권차는 붙입니다. 안 붙이면 서가에 「토지」가 스무 권 늘어선 채로 보여서 어느
     * 것이 몇 권인지 알 수 없습니다.
     */
    static String displayTitle(BookInfo book) {
        TitleParts parts = BibNormalizer.parseTitle(book.bookname());
        String proper = parts.titleProper() == null ? "" : parts.titleProper().trim();
        if (proper.isEmpty()) return trim(book.bookname());

        Volume vol = book.volume().orElse(parts.volume());
        if (vol == null || proper.endsWith(vol.mark())) return proper;
        return proper + " " + vol.mark();
    }

    /**
     * 대표 저자 한 사람. 역할어와 세미콜론이 그대로 오면 「사토 케이 저;사가노 아오이
     * 일러스트;서범주 역」처럼 한 줄이 길어집니다. <b>못 고르면 받은 값을 그대로
     * 둡니다.</b> 모르면 지어내지 않고 원문을 보여 줍니다.
     */
    static String displayAuthor(BookInfo book) {
        Contributor primary = BibNormalizer.primaryAuthor(
                BibNormalizer.parseContributors(book.authors()));
        return primary == null ? trim(book.authors()) : primary.name();
    }

    private static void addIfPresent(List<String> parts, String value) {
        String trimmed = trim(value);
        if (!trimmed.isEmpty()) parts.add(trimmed);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
