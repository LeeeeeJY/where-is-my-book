package kr.wimb.shelf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 서가 순서를 고정합니다. <b>이 시험이 이 기능의 명세입니다.</b>
 *
 * <p>순서가 틀리면 화면은 멀쩡해 보입니다. 원목 서가에 표지가 줄지어 서 있고 스크롤도
 * 됩니다. 틀렸다는 것은 <b>실제 도서관 서가 앞에 선 사람만</b> 알 수 있는데, 그때는
 * 이미 헛걸음한 뒤입니다. 그래서 눈으로 확인할 수 없는 자리를 여기서 붙들어 둡니다.
 */
class CallNumberOrderTest {

    /** 시험에서 값을 적기 쉽게 만든 자리. 필드 순서가 곧 비교 순서입니다. */
    private record Book(String roomCode, String separate, String classNo,
                        String bookCode, Integer volOrdinal, String copyCode)
            implements CallNumberOrder.Placed {

        /** 분류번호와 도서기호만 다른 책. 가장 자주 쓰는 모양입니다. */
        static Book at(String classNo, String bookCode) {
            return new Book("A", null, classNo, bookCode, null, null);
        }
    }

    // ── 3. 도서기호 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("도서기호는 한글부와 숫자부를 나눠서 견준다")
    class BookCodeOrder {

        @Test
        @DisplayName("김63ㄷ < 김64ㅅ")
        void numberDecidesWhenPrefixIsSame() {
            assertOrder(Book.at("813.6", "김63ㄷ"), Book.at("813.6", "김64ㅅ"));
        }

        @Test
        @DisplayName("정54ㅂ < 정54ㅍ")
        void trailingJamoDecidesWhenNumberIsSame() {
            assertOrder(Book.at("813.6", "정54ㅂ"), Book.at("813.6", "정54ㅍ"));
        }

        /**
         * <b>숫자를 글자로 견주면 여기서 뒤집힙니다.</b> {@code "김9ㄱ" > "김63ㄷ"} 이라
         * 아홉 번이 예순세 번 뒤로 갑니다. 서가에서는 바로 옆자리라 눈에 띄지 않고,
         * 한 자리 도서기호를 쓰는 저자에게서만 일어납니다.
         */
        @Test
        @DisplayName("한 자리 숫자가 두 자리 뒤로 가면 안 된다")
        void singleDigitComesBeforeTwoDigits() {
            assertOrder(Book.at("813.6", "김9ㄱ"), Book.at("813.6", "김63ㄷ"));
            assertOrder(Book.at("813.6", "김63ㄷ"), Book.at("813.6", "김100ㅁ"));
        }

        @Test
        @DisplayName("한글부가 다르면 가나다순이다")
        void prefixComesFirst() {
            assertOrder(Book.at("813.6", "김99ㅎ"), Book.at("813.6", "박11ㄱ"));
            assertOrder(Book.at("813.6", "박99ㅎ"), Book.at("813.6", "손11ㄱ"));
        }

        @Test
        @DisplayName("숫자가 없는 도서기호는 있는 것보다 앞이다")
        void missingNumberComesFirst() {
            assertOrder(Book.at("813.6", "김"), Book.at("813.6", "김1ㄱ"));
        }

        @Test
        @DisplayName("도서기호를 세 조각으로 나눈다")
        void parsesIntoThreeParts() {
            BookCode code = BookCode.parse("박36ㅈ");
            assertEquals("박", code.prefix());
            assertEquals(36, code.number());
            assertEquals("ㅈ", code.suffix());

            BookCode none = BookCode.parse("");
            assertEquals("", none.prefix());
            assertEquals(BookCode.NO_NUMBER, none.number());

            // 뒤에 또 나오는 숫자는 꼬리에 그대로 둡니다. 가운데 숫자와 성격이 다릅니다.
            assertEquals("ㄷ2", BookCode.parse("김63ㄷ2").suffix());
        }
    }

    // ── 3. 분류번호 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("분류번호는 소수점 아래를 자릿수 단위로 견준다")
    class ClassNoOrder {

        @Test
        @DisplayName("813.6 < 813.62 < 813.7")
        void deeperSubdivisionComesBeforeTheNextOne() {
            assertAscending(
                    Book.at("813.6", "김11ㄱ"),
                    Book.at("813.62", "김11ㄱ"),
                    Book.at("813.7", "김11ㄱ"));
        }

        @Test
        @DisplayName("소수점이 없는 쪽이 있는 쪽보다 앞이다")
        void bareClassComesFirst() {
            assertAscending(
                    Book.at("813", "김11ㄱ"),
                    Book.at("813.6", "김11ㄱ"),
                    Book.at("814", "김11ㄱ"));
        }

        /**
         * <b>실수로 읽으면 여기서 뒤집힙니다.</b> 크기로는 0.9 가 0.10 보다 크지만,
         * 서가에서 {@code .10} 은 {@code .1} 바로 뒤이고 {@code .2} 앞입니다. 소수점
         * 아래가 크기가 아니라 갈래이기 때문입니다.
         */
        @Test
        @DisplayName(".10 은 .1 뒤이고 .2 앞이다. .9 뒤가 아니다")
        void decimalsAreDigitsNotMagnitude() {
            assertAscending(
                    Book.at("813.1", "김11ㄱ"),
                    Book.at("813.10", "김11ㄱ"),
                    Book.at("813.2", "김11ㄱ"),
                    Book.at("813.9", "김11ㄱ"));
        }

        @Test
        @DisplayName("정수부는 크기로 견준다. 99 가 100 보다 앞이다")
        void integerPartIsCompatedByMagnitude() {
            assertAscending(
                    Book.at("99", "김11ㄱ"),
                    Book.at("100", "김11ㄱ"),
                    Book.at("813", "김11ㄱ"));
        }

        @Test
        @DisplayName("앞에 붙은 0 은 값을 바꾸지 않는다")
        void leadingZerosDoNotMatter() {
            assertSame(Book.at("0813", "김11ㄱ"), Book.at("813", "김11ㄱ"));
        }

        @Test
        @DisplayName("분류번호가 없으면 맨 뒤다")
        void missingClassGoesLast() {
            assertOrder(Book.at("999.9", "김11ㄱ"), Book.at(null, "김11ㄱ"));
            assertOrder(Book.at("999.9", "김11ㄱ"), Book.at("", "김11ㄱ"));
        }

        @Test
        @DisplayName("숫자로 시작하지 않는 분류기호는 숫자 분류 전부의 뒤다")
        void nonNumericClassGoesAfterAllNumbers() {
            assertOrder(Book.at("999.9", "김11ㄱ"), Book.at("F", "김11ㄱ"));
        }
    }

    @Nested
    @DisplayName("전집은 권 차례로 선다")
    class VolumeInBookCode {

        /**
         * <b>전집은 권 번호가 도서기호 꼬리에 붙어 옵니다.</b> 「박65ㅌ1」「박65ㅌ2」…
         * 처럼요. 그 꼬리를 글자 그대로 견주면 <b>10권이 2권 앞에 섭니다.</b>
         * 「ㅌ10」의 「1」이 「ㅌ2」의 「2」보다 작아서입니다.
         *
         * <p>이 파일이 가운데 숫자에 대해 이미 경고하던 것과 같은 일인데 꼬리에만
         * 남아 있었습니다. 전집은 권수가 열을 넘는 일이 흔해서 <b>실제 서가와 가장
         * 크게 어긋나 보이는 자리</b>입니다.
         */
        @Test
        @DisplayName("꼬리의 권 번호를 크기로 견준다. 10권이 2권 뒤다")
        void volumeDigitsInSuffixSortBySize() {
            assertAscending(
                    Book.at("813.6", "박65ㅌ1"),
                    Book.at("813.6", "박65ㅌ2"),
                    Book.at("813.6", "박65ㅌ9"),
                    Book.at("813.6", "박65ㅌ10"),
                    Book.at("813.6", "박65ㅌ11"),
                    Book.at("813.6", "박65ㅌ20"));
        }

        /** 권 번호가 없는 것이 1권보다 앞입니다. 서가에서 본권이 먼저입니다. */
        @Test
        @DisplayName("권 번호가 없는 쪽이 앞이다")
        void noVolumeComesFirst() {
            assertOrder(Book.at("813.6", "박65ㅌ"), Book.at("813.6", "박65ㅌ1"));
        }

        /** 「c.2」처럼 숫자 뒤에 또 글자가 붙어도 숫자는 크기로 견줍니다. */
        @Test
        @DisplayName("숫자 뒤에 글자가 더 붙어도 크기로 견준다")
        void digitsFollowedByLettersStillSortBySize() {
            assertAscending(
                    Book.at("813.6", "박65ㅌ2가"),
                    Book.at("813.6", "박65ㅌ10가"));
        }
    }

    // ── 3 vs 4. 분류번호가 도서기호보다 먼저 ────────────────────────────

    @Nested
    @DisplayName("분류번호가 도서기호보다 먼저다")
    class ClassNoBeatsBookCode {

        /**
         * <b>서가는 저자 이름순이 아닙니다.</b> 먼저 갈래로 묶이고, <b>그 안에서만</b>
         * 저자 순입니다. 도서기호가 저자 성에서 나오다 보니 한 갈래를 훑으면 ㄱ, ㄴ, ㄷ
         * 으로 보이는데, 그것은 같은 분류번호 구간 안의 이야기입니다.
         *
         * <p>이 둘이 뒤바뀌면 화면은 <b>아무 오류 없이</b> 저자 이름순 목록이 됩니다.
         * 실제 서가에 가서 그 자리를 보면 그 책이 없는데, 그 사실은 도서관까지 가 본
         * 사람만 압니다. 그래서 여기서 못 박습니다.
         */
        @Test
        @DisplayName("분류번호가 작으면 도서기호가 뒤여도 앞이다")
        void smallerClassNoComesFirstEvenWithLaterBookCode() {
            // 「김」이 「강」보다 뒤지만, 811 이 813 보다 앞이므로 먼저입니다.
            assertOrder(Book.at("811.6", "김57ㄱ"), Book.at("813.6", "강12ㄴ"));
            assertOrder(Book.at("813.7", "고21ㅂ"), Book.at("843", "강33ㅅ"));
        }

        /**
         * 실제 문학 서가에 섞여 있는 모양 그대로입니다. 저자 이름순으로 세우면
         * 강12ㄴ · 강33ㅅ · 고21ㅂ · 김10ㅁ · 김57ㄱ 이 되는데, 그것이 <b>아닙니다.</b>
         */
        @Test
        @DisplayName("섞어 놓으면 갈래로 먼저 묶이고 그 안에서 저자 순이다")
        void shelfGroupsByClassThenAuthor() {
            assertAscending(
                    Book.at("811.6", "김57ㄱ"),
                    Book.at("813.6", "강12ㄴ"),
                    Book.at("813.6", "김10ㅁ"),
                    Book.at("813.7", "고21ㅂ"),
                    Book.at("843", "강33ㅅ"));
        }
    }

    // ── 1~2. 자료실과 별치 ───────────────────────────────────────────────

    @Nested
    @DisplayName("자료실과 별치기호가 분류번호보다 먼저다")
    class RoomAndSeparate {

        /**
         * 층이 다르면 아예 다른 서가입니다. <b>분류번호가 아무리 작아도</b> 다른
         * 자료실의 책보다 앞에 설 수는 없습니다.
         */
        @Test
        @DisplayName("자료실이 다르면 분류번호를 보지 않는다")
        void roomBeatsClassNo() {
            var first = new Book("AAA", null, "999.9", "힣99ㅎ", null, null);
            var second = new Book("AAB", null, "000.1", "가11ㄱ", null, null);
            assertOrder(first, second);
        }

        @Test
        @DisplayName("자료실을 모르는 책은 맨 뒤다")
        void unknownRoomGoesLast() {
            var known = new Book("ZZZ", null, "813.6", "김11ㄱ", null, null);
            var unknown = new Book(null, null, "000.1", "가11ㄱ", null, null);
            assertOrder(known, unknown);
        }

        /** 별치가 없는 일반 서가가 기본이므로 앞에 섭니다. */
        @Test
        @DisplayName("별치기호가 없는 쪽이 앞이다")
        void noSeparateShelfComesFirst() {
            var general = new Book("A", null, "999.9", "힣99ㅎ", null, null);
            var separate = new Book("A", "아", "000.1", "가11ㄱ", null, null);
            assertOrder(general, separate);
        }
    }

    // ── 5~6. 권차와 복본 ────────────────────────────────────────────────

    @Nested
    @DisplayName("권차와 복본기호가 마지막을 정한다")
    class VolumeAndCopy {

        @Test
        @DisplayName("1권 다음이 2권이고, 10권이 2권 뒤다")
        void volumesRunInOrder() {
            assertAscending(
                    new Book("A", null, "813.6", "박14ㅌ", 1, null),
                    new Book("A", null, "813.6", "박14ㅌ", 2, null),
                    new Book("A", null, "813.6", "박14ㅌ", 10, null));
        }

        @Test
        @DisplayName("권차가 없는 단권이 1권보다 앞이다")
        void noVolumeComesFirst() {
            assertOrder(
                    new Book("A", null, "813.6", "박14ㅌ", null, null),
                    new Book("A", null, "813.6", "박14ㅌ", 1, null));
        }

        @Test
        @DisplayName("복본기호는 c.2 든 복2 든 숫자로 읽는다")
        void copyCodeIsReadAsNumber() {
            assertAscending(
                    new Book("A", null, "813.6", "박14ㅌ", 1, null),
                    new Book("A", null, "813.6", "박14ㅌ", 1, "c.2"),
                    new Book("A", null, "813.6", "박14ㅌ", 1, "c.10"));
            assertSame(
                    new Book("A", null, "813.6", "박14ㅌ", 1, "c.2"),
                    new Book("A", null, "813.6", "박14ㅌ", 1, "복2"));
        }
    }

    // ── 열쇠와 비교자가 갈리지 않는다 ──────────────────────────────────

    /**
     * <b>이 시험이 가장 중요합니다.</b> 실제로 도는 것은 {@link CallNumberOrder#sortKey}
     * 입니다. 수집기가 그 열쇠로 정렬해 파일에 적고, 화면은 그 파일을 자른 조각을
     * 받습니다. 그런데 사람이 읽고 고치는 것은 비교자 쪽이라, 둘이 갈리면
     * <b>고친 규칙이 화면에 반영되지 않은 채 아무 오류 없이 돕니다.</b>
     */
    @Nested
    @DisplayName("열쇠로 정렬한 결과가 비교자로 정렬한 결과와 같다")
    class KeyMatchesComparator {

        @Test
        @DisplayName("손으로 고른 어려운 값들에서 같다")
        void agreesOnHandPickedValues() {
            assertSameOrder(tricky());
        }

        /**
         * 손으로 고른 값만으로는 놓치는 조합이 있습니다. 값들을 섞어 가며 여러 번
         * 견줍니다. <b>씨앗을 고정해</b> 실패했을 때 같은 순서를 다시 만들 수 있게 합니다.
         */
        @Test
        @DisplayName("섞어 놓아도 같다")
        void agreesOnShuffledValues() {
            List<Book> books = new ArrayList<>(tricky());
            Random random = new Random(20260920L);
            for (int round = 0; round < 200; round++) {
                Collections.shuffle(books, random);
                assertSameOrder(books);
            }
        }

        private void assertSameOrder(List<Book> input) {
            List<Book> byComparator = new ArrayList<>(input);
            byComparator.sort(CallNumberOrder.comparator());

            List<Book> byKey = new ArrayList<>(input);
            byKey.sort(java.util.Comparator.comparing(CallNumberOrderTest::keyOf));

            assertEquals(byComparator.stream().map(CallNumberOrderTest::keyOf).toList(),
                    byKey.stream().map(CallNumberOrderTest::keyOf).toList(),
                    "열쇠와 비교자가 다른 순서를 냈습니다");
        }
    }

    /** 자리마다 어려운 값을 하나씩 섞어 둔 묶음. */
    private static List<Book> tricky() {
        return List.of(
                new Book("A", null, "813", "김9ㄱ", null, null),
                new Book("A", null, "813", "김63ㄷ", null, null),
                new Book("A", null, "813", "김64ㅅ", null, null),
                new Book("A", null, "813", "김100ㅁ", null, null),
                new Book("A", null, "813.1", "정54ㅂ", null, null),
                new Book("A", null, "813.10", "정54ㅍ", null, null),
                new Book("A", null, "813.2", "정54ㅂ", 1, null),
                new Book("A", null, "813.2", "정54ㅂ", 2, null),
                new Book("A", null, "813.2", "정54ㅂ", 10, null),
                new Book("A", null, "813.2", "정54ㅂ", 2, "c.2"),
                new Book("A", null, "813.6", "박14ㅌ", null, null),
                new Book("A", null, "813.62", "박14ㅌ", null, null),
                new Book("A", null, "813.7", "박14ㅌ", null, null),
                new Book("A", null, "99", "가1ㄱ", null, null),
                new Book("A", null, "100", "가1ㄱ", null, null),
                new Book("A", null, "0813", "김63ㄷ", null, null),
                new Book("A", null, "F", "가1ㄱ", null, null),
                new Book("A", null, null, "가1ㄱ", null, null),
                new Book("A", null, "813", "", null, null),
                new Book("A", "아", "000.1", "가1ㄱ", null, null),
                new Book("A", "참", "000.1", "가1ㄱ", null, null),
                new Book("AAB", null, "000.1", "가1ㄱ", null, null),
                new Book(null, null, "000.1", "가1ㄱ", null, null),
                new Book("A", null, "813", "힣99ㅎ", null, null));
    }

    // ── 초성 색인 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("초성은 도서기호 첫 글자에서 뽑는다")
    class ChosungIndex {

        @Test
        @DisplayName("음절에서 초성을 꺼낸다")
        void takesChosungFromSyllable() {
            assertEquals("ㄱ", Chosung.of("김63ㄷ"));
            assertEquals("ㅂ", Chosung.of("박36ㅈ"));
            assertEquals("ㅅ", Chosung.of("손66ㅇ"));
            assertEquals("ㅎ", Chosung.of("힣99ㅎ"));
        }

        @Test
        @DisplayName("쌍자음은 기본 자음으로 접는다")
        void doubleConsonantsFoldToTheBase() {
            // 색인이 열넷이라, 접지 않으면 색인에 없는 줄이 생깁니다.
            assertEquals("ㄱ", Chosung.of("까11ㄱ"));
            assertEquals("ㄷ", Chosung.of("따11ㄱ"));
            assertEquals("ㅅ", Chosung.of("쌍11ㄱ"));
            assertEquals("ㅈ", Chosung.of("짜11ㄱ"));
        }

        @Test
        @DisplayName("자모가 그대로 와도 읽는다")
        void readsBareJamo() {
            assertEquals("ㄷ", Chosung.of("ㄷ11"));
        }

        /**
         * <b>모르면 비웁니다.</b> 한글이 아닌 도서기호에 초성을 억지로 붙이면 색인을
         * 눌러 간 사람이 엉뚱한 곳에 도착합니다.
         */
        @Test
        @DisplayName("한글이 아니면 비운다")
        void leavesNonHangulEmpty() {
            assertNull(Chosung.of("K56s"));
            assertNull(Chosung.of("123"));
            assertNull(Chosung.of(""));
            assertNull(Chosung.of(null));
            // 받침에만 쓰이는 겹자음은 초성이 될 수 없습니다.
            assertNull(Chosung.of("ㄳ11"));
        }

        @Test
        @DisplayName("색인은 쌍자음을 뺀 열넷이다")
        void indexHasFourteenRows() {
            assertEquals(14, Chosung.INDEX.size());
            assertEquals("ㄱ", Chosung.INDEX.get(0));
            assertEquals("ㅎ", Chosung.INDEX.get(13));
            // 뽑아낸 초성은 반드시 색인 줄 가운데 하나여야 합니다.
            for (String code : List.of("김63ㄷ", "까11ㄱ", "박36ㅈ", "ㄷ11", "힣99ㅎ")) {
                assertTrue(Chosung.INDEX.contains(Chosung.of(code)), code);
            }
        }
    }

    // ── 거들기 ───────────────────────────────────────────────────────────

    private static String keyOf(Book book) {
        return CallNumberOrder.sortKey(book.roomCode(), book.separate(), book.classNo(),
                book.bookCode(), book.volOrdinal(), book.copyCode());
    }

    /** 앞의 것이 서가에서 먼저여야 합니다. 비교자와 열쇠 <b>양쪽</b>으로 봅니다. */
    private static void assertOrder(Book first, Book second) {
        assertTrue(CallNumberOrder.comparator().compare(first, second) < 0,
                "비교자: " + first + " 가 " + second + " 보다 앞이어야 합니다");
        assertTrue(keyOf(first).compareTo(keyOf(second)) < 0,
                "열쇠: " + first + " 가 " + second + " 보다 앞이어야 합니다");
    }

    private static void assertAscending(Book... books) {
        for (int i = 0; i + 1 < books.length; i++) assertOrder(books[i], books[i + 1]);
    }

    private static void assertSame(Book left, Book right) {
        assertEquals(0, CallNumberOrder.comparator().compare(left, right), "비교자");
        assertEquals(keyOf(left), keyOf(right), "열쇠");
    }
}
