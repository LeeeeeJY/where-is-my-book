package kr.wimb.index;

import kr.wimb.bib.Volume;
import kr.wimb.bib.WorkClusterer;
import kr.wimb.bib.WorkMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SearchDocBuilderTest {

    private static SearchDocBuilder.BookRecord book(String isbn, String title, String authors) {
        return SearchDocBuilder.BookRecord.of(isbn, title, authors, null, null, null);
    }

    private static SearchDocBuilder.BookRecord book(String isbn, String title, String authors,
                                                    String publisher, int year, int price) {
        return SearchDocBuilder.BookRecord.of(isbn, title, authors, publisher,
                LocalDate.of(year, 1, 1), price);
    }

    /** 서지를 실제로 군집화한 뒤 색인을 만듭니다. 두 단계가 이어지는지까지 확인합니다. */
    private static List<SearchDoc> buildVia(List<SearchDocBuilder.BookRecord> books) {
        AtomicInteger next = new AtomicInteger(1000);
        List<WorkClusterer.Input> inputs = books.stream()
                .map(b -> new WorkClusterer.Input(b.isbn13(),
                        new WorkMatcher.Candidate(b.isbn13(), b.title(), b.contributors(),
                                "", b.pubDate() == null ? null : b.pubDate().getYear(), null),
                        null))
                .toList();
        var clustered = WorkClusterer.cluster(inputs, List.of(), next::incrementAndGet);
        return SearchDocBuilder.build(books, clustered.workIdByRecord());
    }

    @Test
    @DisplayName("한 저작의 모든 판본 ISBN 이 한 문서에 모인다")
    void collectsAllIsbnsOfWork() {
        // 이게 어긋나면 도서관이 가진 판본을 조회하지 못해 미소장으로 나옵니다.
        var docs = buildVia(List.of(
                book("9788983711892", "코스모스", "칼 세이건"),
                book("9788983711908", "코스모스 (특별판)", "칼세이건")));

        assertEquals(1, docs.size(), "두 판본이 한 저작으로 묶여야 합니다");
        assertEquals(List.of("9788983711892", "9788983711908"), docs.get(0).isbn13List());
    }

    @Test
    @DisplayName("판본마다 다른 저자 표기로 검색해도 걸린다")
    void searchTextCoversAllVariants() {
        // 대표 판본의 표기만 넣으면 다른 표기로 검색했을 때 결과가 없습니다.
        var docs = buildVia(List.of(
                book("9788983920775", "해리 포터와 마법사의 돌", "조앤 K. 롤링 지음 ; 강동혁 옮김"),
                book("9788983920782", "해리포터와 마법사의돌", "J.K.롤링 지음 ; 김혜원 옮김")));

        assertEquals(1, docs.size());
        String searchText = docs.get(0).searchText();
        assertTrue(searchText.contains("조앤k롤링"), "첫 판본의 저자 표기가 들어 있어야 합니다");
        assertTrue(searchText.contains("jk롤링"), "다른 판본의 저자 표기도 들어 있어야 합니다");
        assertTrue(searchText.contains("강동혁"), "역자로도 찾을 수 있어야 합니다");
    }

    @Test
    @DisplayName("각색본은 별개 문서가 된다")
    void adaptationsBecomeSeparateDocs() {
        var docs = buildVia(List.of(
                book("9788937460449", "데미안", "헤르만 헤세"),
                book("9788937460456", "데미안 (청소년판)", "헤르만 헤세")));

        assertEquals(2, docs.size());
    }

    @Test
    @DisplayName("서지 정보가 가장 잘 채워진 판본을 대표로 고른다")
    void picksMostCompleteRepresentative() {
        var docs = buildVia(List.of(
                book("9788983711892", "코스모스", "칼 세이건"),
                book("9788983711908", "코스모스", "칼 세이건", "사이언스북스", 2010, 22000)));

        assertEquals(1, docs.size());
        assertEquals("사이언스북스", docs.get(0).publisherDisplay());
        assertEquals(22000, docs.get(0).price());
    }

    /**
     * <b>상·하 두 권뿐인 책이 「1권」과 「3권」으로 나오고 있었습니다.</b> 순서를 위해 상·중·하를
     * 1·2·3 으로 세는 것은 맞지만, 그 숫자를 화면에 적으면 사용자가 없는 2권을 찾습니다.
     * <b>받은 글자를 그대로 붙이고 「권」도 덧붙이지 않습니다.</b> 정보나루가 준 표기가 「상」이면
     * 「상」이지 「상권」이 아닙니다.
     */
    @Test
    @DisplayName("권차는 받은 표기 그대로 붙이고 「권」을 덧붙이지 않는다")
    void showsSangHaAsWritten() {
        var docs = buildVia(List.of(
                // 정보나루가 vol 로 「상」「하」를 따로 준 경우.
                SearchDocBuilder.BookRecord.of("9788930040013", "토지", "박경리",
                        "나남", null, null, new Volume(1, "상")),
                SearchDocBuilder.BookRecord.of("9788930040037", "토지", "박경리",
                        "나남", null, null, new Volume(3, "하")),
                // 표제 꼬리에 들어 있는 경우.
                book("9788970000015", "삼국지 (중)", "이문열"),
                // 숫자 권차는 그대로입니다.
                SearchDocBuilder.BookRecord.of("9788970000022", "레미제라블", "빅토르 위고",
                        "민음사", null, null, Volume.of(1))));

        var titles = docs.stream().map(SearchDoc::titleDisplay).sorted().toList();
        assertEquals(List.of("레미제라블 1", "삼국지 중", "토지 상", "토지 하"), titles);
    }

    @Test
    @DisplayName("판본 표기를 발행연도와 함께 보여 준다")
    void labelsEditions() {
        var docs = buildVia(List.of(
                book("9788983711892", "코스모스", "칼 세이건", "사이언스북스", 2006, 20000),
                book("9788983711908", "코스모스 (특별판)", "칼 세이건", "사이언스북스", 2010, 22000)));

        assertEquals(List.of("특별판 (2010)"), docs.get(0).editionLabels());
    }

    /**
     * <b>표제·저자·출판사가 똑같은 줄이 둘 나가고 있었습니다.</b> 정보나루가 민음사 「이방인」의
     * 큰글자책 판을 「이방인 :큰글자책 」으로 주는데(2026-11-10), 그 표기가 부제로 갈려 나가
     * 표제에는 「이방인」만 남습니다. 저작은 따로 서 있으므로 소장이 섞이지는 않지만, 어느 줄이
     * 큰글자책인지 화면에서 알 길이 없었습니다.
     */
    @Test
    @DisplayName("부제로 갈려 나간 각색 표기는 이름표로 드러낸다")
    void labelsAdaptationsHiddenInTheSubtitle() {
        var docs = buildVia(List.of(
                book("9788937462665", "이방인 ", "지은이: 알베르 카뮈 ;옮긴이: 김화영"),
                book("9788937448836", "이방인 :큰글자책 ", "지은이: 알베르 카뮈 ;옮긴이: 김화영")));

        assertEquals(2, docs.size(), "큰글자책은 보통 판과 다른 저작입니다");
        var plain = docs.stream().filter(d -> d.isbn13List().contains("9788937462665"))
                .findFirst().orElseThrow();
        var largePrint = docs.stream().filter(d -> d.isbn13List().contains("9788937448836"))
                .findFirst().orElseThrow();

        assertEquals(List.of("큰글자책"), largePrint.editionLabels(),
                "받은 부제 그대로 이름표에 실어야 두 줄이 구별됩니다");
        assertEquals(List.of(), plain.editionLabels(), "보통 판에는 붙일 것이 없습니다");
        // **표제에는 붙이지 않습니다.** 화면이 표제를 그대로 도서관의 제목 검색 주소에 끼우므로,
        // 「이방인 : 큰글자책」이 되면 제목으로만 찾아지는 도서관에서 0건이 나옵니다.
        assertEquals("이방인", largePrint.titleDisplay());
    }

    @Test
    @DisplayName("각색 표기가 표제에 그대로 보이면 이름표에 다시 싣지 않는다")
    void doesNotRepeatWhatTheTitleAlreadyShows() {
        var docs = buildVia(List.of(book("9788970000039", "데미안(청소년판)", "헤르만 헤세")));

        assertEquals("데미안(청소년판)", docs.get(0).titleDisplay());
        assertEquals(List.of(), docs.get(0).editionLabels());
    }

    @Test
    @DisplayName("각색 표기가 긴 부제 안에 있으면 그 부제를 받은 글자 그대로 싣는다")
    void showsTheSubtitleAsReceived() {
        // 우리 목록의 낱말(「만화로보는」)은 견주려고 다듬은 표기입니다. 그것을 화면에 내면
        // 정보나루가 주지 않은 글자를 지어내는 것입니다.
        var docs = buildVia(List.of(
                book("9788970000046", "그리스 로마 신화 :만화로 보는 고전 ", "토마스 불핀치")));

        assertEquals(List.of("만화로 보는 고전"), docs.get(0).editionLabels());
    }

    @Test
    @DisplayName("각색 이름표가 판본 이름표보다 앞에 온다")
    void adaptationLabelComesBeforeEditionLabels() {
        var docs = buildVia(List.of(
                book("9788970000053", "이방인 (양장) :큰글자책", "알베르 카뮈", "민음사", 2026, 15000)));

        assertEquals(List.of("큰글자책", "양장 (2026)"), docs.get(0).editionLabels());
    }

    @Test
    @DisplayName("저작 번호가 없는 서지는 색인에 넣지 않는다")
    void skipsUnclusteredBooks() {
        // 조용히 넣으면 검색은 되는데 소장 조회가 언제나 비어 있는 문서가 생깁니다.
        var docs = SearchDocBuilder.build(
                List.of(book("9788983711892", "코스모스", "칼 세이건")),
                Map.of());

        assertTrue(docs.isEmpty());
    }

    @Test
    @DisplayName("바이그램을 함께 만들어 둔다")
    void generatesBigrams() {
        assertEquals("해리 리포 포터", Bigrams.of("해리포터"));
        assertEquals("", Bigrams.of(""));
        assertEquals("가", Bigrams.of("가"));

        var docs = buildVia(List.of(book("9788983711892", "코스모스", "칼 세이건")));
        assertTrue(docs.get(0).searchBigrams().startsWith("코스 스모 모스"));
    }

    @Test
    @DisplayName("같은 입력에 같은 색인을 만든다")
    void isDeterministic() {
        var books = List.of(
                book("9788983711908", "코스모스 (특별판)", "칼세이건"),
                book("9788983711892", "코스모스", "칼 세이건"));

        assertEquals(buildVia(books), buildVia(books));
    }
}
