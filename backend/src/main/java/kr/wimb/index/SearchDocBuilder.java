package kr.wimb.index;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Contributor;
import kr.wimb.bib.TitleParts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 군집화 결과를 색인 문서로 만듭니다.
 *
 * <p>여기가 서지와 검색 화면을 잇는 지점입니다. 저작 하나가 문서 하나가 되고,
 * 그 저작에 묶인 모든 판본의 ISBN 과 표기가 한 문서에 모입니다.
 */
public final class SearchDocBuilder {

    private SearchDocBuilder() {}

    /** 색인에 넣을 서지 한 건. 정규화까지 끝난 상태로 들어옵니다. */
    public record BookRecord(
            String isbn13,
            TitleParts title,
            List<Contributor> contributors,
            String publisherRaw,
            LocalDate pubDate,
            Integer price
    ) {
        public static BookRecord of(String isbn13, String rawTitle, String rawAuthors,
                                    String publisher, LocalDate pubDate, Integer price) {
            return of(isbn13, rawTitle, rawAuthors, publisher, pubDate, price, null);
        }

        /** @param volNo 소스가 따로 준 권차. 표제에서 뽑지 못했을 때만 씁니다. */
        public static BookRecord of(String isbn13, String rawTitle, String rawAuthors,
                                    String publisher, LocalDate pubDate, Integer price,
                                    Integer volNo) {
            return new BookRecord(isbn13,
                    BibNormalizer.parseTitle(rawTitle).withVolNo(volNo),
                    BibNormalizer.parseContributors(rawAuthors),
                    publisher, pubDate, price);
        }

        String authorDisplay() {
            Contributor primary = BibNormalizer.primaryAuthor(contributors);
            return primary == null ? null : primary.name();
        }
    }

    /**
     * @param books          색인 대상 서지
     * @param workIdByIsbn   군집화가 배정한 저작 번호. {@code WorkClusterer.Result} 의 결과를 넘깁니다.
     */
    public static List<SearchDoc> build(List<BookRecord> books, Map<String, Integer> workIdByIsbn) {
        Map<Integer, List<BookRecord>> byWork = new LinkedHashMap<>();
        for (BookRecord book : books) {
            Integer workId = workIdByIsbn.get(book.isbn13());
            // 저작 번호가 없는 서지는 군집화에서 빠진 것이므로 색인에 넣지 않습니다.
            // 조용히 넣으면 검색은 되는데 소장 조회가 되지 않는 문서가 생깁니다.
            if (workId != null) byWork.computeIfAbsent(workId, k -> new ArrayList<>()).add(book);
        }

        List<SearchDoc> docs = new ArrayList<>(byWork.size());
        for (var entry : byWork.entrySet()) {
            docs.add(buildOne(entry.getKey(), entry.getValue()));
        }
        // 실행마다 같은 순서가 나오도록 정렬합니다. 색인 검증에서 문서 수만이 아니라
        // 내용을 비교할 때 순서가 흔들리면 비교가 되지 않습니다.
        docs.sort(Comparator.comparingInt(SearchDoc::workId));
        return docs;
    }

    private static SearchDoc buildOne(int workId, List<BookRecord> members) {
        BookRecord representative = pickRepresentative(members);

        // 소장 조회의 입력입니다. 하나라도 빠지면 그 판본을 소장한 도서관이 미소장으로 나옵니다.
        List<String> isbns = members.stream()
                .map(BookRecord::isbn13)
                .filter(java.util.Objects::nonNull)
                .distinct().sorted().toList();

        return new SearchDoc(
                workId,
                displayTitle(representative.title()),
                representative.authorDisplay(),
                representative.publisherRaw(),
                representative.pubDate(),
                representative.price(),
                isbns,
                editionLabels(members),
                searchText(members),
                Bigrams.of(searchText(members)));
    }

    /**
     * 화면에 보여 줄 대표 판본을 고릅니다.
     *
     * <p>서지 정보가 가장 잘 채워진 것을 우선하고, 같으면 최근 발행분을 씁니다.
     * 최근 것이 지금 도서관에 있을 가능성이 높기 때문입니다. 끝으로 ISBN 순으로
     * 정해서 실행마다 결과가 흔들리지 않게 합니다.
     */
    private static BookRecord pickRepresentative(List<BookRecord> members) {
        return members.stream()
                .max(Comparator
                        .comparingInt(SearchDocBuilder::completeness)
                        .thenComparing(BookRecord::pubDate,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(BookRecord::isbn13,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .orElseThrow();
    }

    private static int completeness(BookRecord book) {
        int score = 0;
        if (book.authorDisplay() != null && !book.authorDisplay().isBlank()) score++;
        if (book.publisherRaw() != null && !book.publisherRaw().isBlank()) score++;
        if (book.pubDate() != null) score++;
        if (book.price() != null) score++;
        if (book.title().subtitle() != null) score++;
        return score;
    }

    /**
     * 화면에 보여 줄 표제. <b>권차가 표제에 없으면 붙여 줍니다.</b>
     *
     * <p>정보나루가 권차를 {@code vol} 로 따로 주는 책은 표제가 다섯 권 모두 「레미제라블」로
     * 똑같습니다. 저작은 권차로 갈라 놓았는데 표제가 같으면, 화면에는 구별할 수 없는 줄이
     * 다섯 개 늘어서고 사용자는 어느 것이 몇 권인지 모릅니다. 갈라 놓은 보람이 없습니다.
     *
     * <p>표제 끝이 이미 그 숫자로 끝나면 붙이지 않습니다. 「미움받을 용기 2」가
     * 「미움받을 용기 2 2권」이 되면 안 됩니다.
     */
    private static String displayTitle(TitleParts title) {
        Integer vol = title.volNo();
        String proper = title.titleProper() == null ? "" : title.titleProper().trim();
        if (vol == null || proper.isEmpty() || proper.endsWith(String.valueOf(vol))) {
            return title.titleProper();
        }
        return proper + " " + vol + "권";
    }

    /** 「특별판 (2010)」처럼 어떤 판본들이 있는지 보여 줍니다. */
    private static List<String> editionLabels(List<BookRecord> members) {
        Set<String> labels = new LinkedHashSet<>();
        for (BookRecord book : members) {
            for (String token : book.title().editionTokens()) {
                labels.add(book.pubDate() == null
                        ? token
                        : token + " (" + book.pubDate().getYear() + ")");
            }
        }
        return List.copyOf(labels);
    }

    /**
     * 검색 대상 텍스트를 만듭니다.
     *
     * <p><b>모든 판본의 표기를 모읍니다.</b> 판본마다 저자 표기가 다른 경우가 흔한데
     * (「조앤 K. 롤링」과 「J.K.롤링」), 대표 판본의 표기만 넣으면 다른 표기로 검색했을 때
     * 걸리지 않습니다.
     *
     * <p>정규화는 {@link BibNormalizer#normalizeKey}를 씁니다. 질의도 같은 함수를 부르므로
     * 색인과 질의가 어긋나지 않습니다.
     */
    private static String searchText(List<BookRecord> members) {
        Set<String> parts = new LinkedHashSet<>();
        for (BookRecord book : members) {
            TitleParts title = book.title();
            addNormalized(parts, title.titleKeyCore());
            addNormalized(parts, title.titleKeyFull());
            for (String alias : title.aliasKeys()) addNormalized(parts, alias);
            addNormalized(parts, title.parallelTitle());
            addNormalized(parts, title.seriesTitle());
            // 역자까지 넣습니다. 「김화영 옮김 페스트」로 찾는 사람이 있습니다.
            for (Contributor contributor : book.contributors()) {
                addNormalized(parts, contributor.name());
            }
            addNormalized(parts, BibNormalizer.normalizePublisher(book.publisherRaw()));
        }
        return String.join("", parts);
    }

    private static void addNormalized(Set<String> parts, String raw) {
        if (raw == null || raw.isBlank()) return;
        String normalized = BibNormalizer.normalizeKey(raw);
        if (!normalized.isBlank()) parts.add(normalized);
    }
}
