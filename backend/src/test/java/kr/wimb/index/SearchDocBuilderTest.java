package kr.wimb.index;

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

    @Test
    @DisplayName("판본 표기를 발행연도와 함께 보여 준다")
    void labelsEditions() {
        var docs = buildVia(List.of(
                book("9788983711892", "코스모스", "칼 세이건", "사이언스북스", 2006, 20000),
                book("9788983711908", "코스모스 (특별판)", "칼 세이건", "사이언스북스", 2010, 22000)));

        assertEquals(List.of("특별판 (2010)"), docs.get(0).editionLabels());
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
