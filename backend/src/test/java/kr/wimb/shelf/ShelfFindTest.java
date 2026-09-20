package kr.wimb.shelf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 서가 안에서 자리를 찾는 규칙. <b>여기서 틀리면 엉뚱한 책 앞에 서게 됩니다.</b>
 * 돌려주는 것이 자리 번호라, 한 칸만 어긋나도 화면은 아무 이상 없이 옆 책을 보여 줍니다.
 */
class ShelfFindTest {

    @Nested
    @DisplayName("자리 번호")
    class Positions {

        /**
         * <b>줄 번호가 곧 자리 번호입니다.</b> 표제도 저자도 없는 자료가 실제로 있는데,
         * 그 줄을 건너뛰면 뒤가 전부 한 칸씩 밀립니다.
         */
        @Test
        @DisplayName("빈 줄도 한 자리를 차지한다")
        void blankRowsStillHoldTheirSlot() {
            var found = ShelfFind.scan(Stream.of("", "\t\t", "토지\t박경리\t813.6 박14ㅌ"),
                    "토지", ShelfFind.LIMIT);

            assertEquals(1, found.total());
            assertEquals(2, found.hits().get(0).at(), "셋째 줄이면 세 번째 자리입니다");
        }

        @Test
        @DisplayName("찾은 자리의 표제와 청구기호를 함께 준다")
        void carriesWhatTheScreenShows() {
            var found = ShelfFind.scan(Stream.of("토지 1\t박경리\t813.6 박14ㅌ1"),
                    "토지", ShelfFind.LIMIT);

            ShelfFind.Hit hit = found.hits().get(0);
            assertEquals("토지 1", hit.title());
            assertEquals("박경리", hit.author());
            assertEquals("813.6 박14ㅌ1", hit.call());
        }
    }

    @Nested
    @DisplayName("견주는 글자")
    class Matching {

        /**
         * <b>띄어쓰기가 다르다고 못 찾으면 안 됩니다.</b> 서가에 선 표제는 도서관이
         * 적어 넣은 그대로라 「레 미제라블」과 「레미제라블」이 섞여 있습니다. 넣은
         * 대로만 맞추면 멀쩡히 꽂혀 있는 책을 「이 서가에는 없다」고 답하게 됩니다.
         */
        @Test
        @DisplayName("띄어쓰기와 구두점이 달라도 찾는다")
        void spacingAndPunctuationDoNotMatter() {
            Stream<String> shelf = Stream.of("레 미제라블 1\t빅토르 위고\t863 위17ㄹ1");

            assertEquals(1, ShelfFind.scan(shelf, "레미제라블", ShelfFind.LIMIT).total());
            assertEquals(1, ShelfFind.scan(
                    Stream.of("레미제라블\t빅토르 위고\t863 위17ㄹ"),
                    "레 미제라블", ShelfFind.LIMIT).total());
            assertEquals(1, ShelfFind.scan(
                    Stream.of("코스모스 :특별판\t칼 세이건\t443.1 세69ㅋ"),
                    "코스모스특별판", ShelfFind.LIMIT).total());
        }

        /**
         * 저자로도 찾습니다. <b>서가 앞에서 사람이 찾는 것은 그 저자의 자리</b>이기도
         * 합니다.
         */
        @Test
        @DisplayName("저자 이름으로도 찾는다")
        void findsByAuthorToo() {
            var found = ShelfFind.scan(Stream.of("아몬드\t손원평\t813.7 손66ㅇ"),
                    "손원평", ShelfFind.LIMIT);

            assertEquals(1, found.total());
            assertEquals("아몬드", found.hits().get(0).title());
        }

        /**
         * <b>「청소년판」을 떼지 않습니다.</b> 같은 저작으로 묶을 때는 떼는 것이 맞지만,
         * 여기서 견주는 것은 사람이 서가에서 보고 있는 글자입니다.
         */
        @Test
        @DisplayName("각색·판본 표기를 떼지 않는다")
        void keepsEditionWordsBecauseTheyAreOnTheSpine() {
            Stream<String> shelf = Stream.of("데미안 청소년판\t헤르만 헤세\t853 헤57ㄷ");

            assertEquals(1, ShelfFind.scan(shelf, "청소년판", ShelfFind.LIMIT).total());
        }

        /**
         * <b>한 글자로는 찾지 않습니다.</b> 수천 줄에 걸려 아무 데도 데려다주지 못하고,
         * 그 자리는 초성 색인이 이미 맡고 있습니다.
         */
        @Test
        @DisplayName("한 글자짜리 말은 아무것도 찾지 않는다")
        void refusesQueriesTooShortToLeadAnywhere() {
            Stream<String> shelf = Stream.of("토지\t박경리\t813.6 박14ㅌ");

            assertEquals(0, ShelfFind.scan(shelf, "토", ShelfFind.LIMIT).total());
            assertEquals(0, ShelfFind.scan(Stream.of("토지\t박경리\t813.6 박14ㅌ"),
                    "  ", ShelfFind.LIMIT).total());
        }
    }

    @Nested
    @DisplayName("세우는 차례")
    class Order {

        /**
         * <b>그대로 맞은 것이 먼저입니다.</b> 「토지」를 넣은 사람이 보고 싶은 것은
         * 「토지」이지 「토지 이용 계획」이 아닙니다.
         */
        @Test
        @DisplayName("제목이 그대로 맞은 것을 맨 앞에 세운다")
        void exactTitlesComeFirst() {
            var found = ShelfFind.scan(Stream.of(
                    "토지 이용 계획\t김철수\t539 김12ㅌ",
                    "토지 소유권\t이영희\t365 이64ㅌ",
                    "토지\t박경리\t813.6 박14ㅌ"), "토지", ShelfFind.LIMIT);

            assertEquals(3, found.total());
            assertEquals("토지", found.hits().get(0).title());
        }

        /**
         * 같은 등급 안에서는 <b>서가에 선 차례</b>입니다. 낱권과 복본이 흩어지지 않고
         * 실제로 꽂힌 순서대로 나와야 옆으로 걸어가며 볼 수 있습니다.
         */
        @Test
        @DisplayName("같은 등급 안에서는 서가에 선 차례다")
        void tiesKeepShelfOrder() {
            var found = ShelfFind.scan(Stream.of(
                    "토지 1\t박경리\t813.6 박14ㅌ1",
                    "토지 2\t박경리\t813.6 박14ㅌ2",
                    "토지 3\t박경리\t813.6 박14ㅌ3"), "토지", ShelfFind.LIMIT);

            assertEquals(List.of(0, 1, 2), found.hits().stream().map(ShelfFind.Hit::at).toList());
        }

        @Test
        @DisplayName("저자를 그대로 적은 것이 제목에 섞여 걸린 것보다 앞이다")
        void anExactAuthorBeatsAPartialTitle() {
            var found = ShelfFind.scan(Stream.of(
                    "칼 세이건 평전\t윌리엄 파운드스톤\t990 파66ㅋ",
                    "코스모스\t칼 세이건\t443.1 세69ㅋ"), "칼세이건", ShelfFind.LIMIT);

            assertEquals("코스모스", found.hits().get(0).title(),
                    "그 저자의 자리를 찾는 말입니다");
        }
    }

    /**
     * <b>돌려줄 만큼만 들고 있습니다.</b> 「소설」처럼 흔한 말은 한 서가에서 수천 줄에
     * 걸리는데, 전부 담았다가 마지막에 자르면 그 순간 힙이 몇십 MB 늘어납니다.
     * 기계가 1GB 라 그럴 여유가 없습니다.
     */
    @Test
    @DisplayName("맞은 것이 많아도 돌려주는 것은 상한까지이고 전체 수는 따로 센다")
    void keepsOnlyWhatItReturnsButStillCountsEverything() {
        Stream<String> shelf = IntStream.range(0, 500)
                .mapToObj(i -> "소설집 " + i + "\t아무개\t813.6 아12ㅅ");

        var found = ShelfFind.scan(shelf, "소설집", 20);

        assertEquals(500, found.total(), "몇 권이 걸렸는지는 사람이 알아야 합니다");
        assertEquals(20, found.hits().size());
        assertEquals(0, found.hits().get(0).at(), "앞자리부터 돌려줍니다");
        assertEquals(19, found.hits().get(19).at());
    }

    /**
     * <b>줄바꿈과 탭은 값에서 지웁니다.</b> 도서관이 적어 넣은 값이라 섞여 들어올 수
     * 있는데, 그러면 한 자리가 두 줄이 되어 <b>그 뒤의 자리 번호가 전부 밀립니다.</b>
     */
    @Test
    @DisplayName("제어 문자가 든 표제도 한 줄에 담긴다")
    void controlCharactersNeverSplitARow() {
        String line = ShelfFind.line(item("토\n지\t상", "박\r경리", "813.6 박14ㅌ"));

        assertFalse(line.contains("\n"), line);
        assertEquals(2, line.chars().filter(c -> c == '\t').count(), "칸은 셋입니다: " + line);

        var found = ShelfFind.scan(Stream.of(line), "토 지 상", ShelfFind.LIMIT);
        assertEquals(1, found.total());
    }

    private static ShelfItem item(String title, String author, String call) {
        return new ShelfItem("9788937437267", title, author, "마로니에북스", "2012",
                null, "813.6", "문학", "박14ㅌ", call, "A", "종합자료실", null, null, "ㅂ", "k");
    }
}
