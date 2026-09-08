package kr.wimb.api;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.WorkMatcher;
import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.query.LineParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 여러 권을 한 번에 확인하는 화면의 첫 단계입니다. 줄을 해석하고 책을 확정합니다.
 *
 * <p><b>소장 조회는 여기서 하지 않습니다.</b> 줄 해석과 책 확정은 사용자가 목록을 붙여넣은
 * 직후에 끝나야 하고, 소장 조회는 그 뒤에 도착하는 대로 채워 넣어야 합니다. 캐시가 빈
 * 상태로 30권을 확인하면 수십 초가 걸리는데, 그동안 빈 화면을 보여 주면 사용자는 이 도구를
 * 다시 쓰지 않습니다.
 *
 * <p>후보 점수에 <b>소장 여부를 쓰지 않는 것</b>도 의도한 것입니다. 소장을 알려면 먼저 책을
 * 확정해야 하므로 순서가 맞지 않고, 후보마다 조회하면 호출이 몇 배로 늘어납니다.
 */
@Service
public class MultiCheckService {

    /** 모호한 줄에 펼쳐 보여 줄 후보 수. 더 늘리면 고르는 일이 일이 됩니다. */
    /**
     * 한 줄에 보여 줄 후보 수.
     *
     * <p><b>다섯은 너무 적었습니다.</b> 출판사가 다른 번역본을 갈라 놓은 뒤로 「레미제라블」
     * 같은 고전은 저작이 서른 개를 넘는데, 다섯만 보여 주면 <b>찾는 판이 목록에 아예
     * 없습니다.</b> 사용자는 그것을 「내 책이 없다」로 읽습니다. 화면이 처음에는 몇 개만
     * 보여 주고 나머지는 눌러서 펼치므로, 여기서 넉넉히 보내는 편이 낫습니다.
     */
    private static final int MAX_CANDIDATES = 24;

    /** 1위가 2위의 이만큼이면 확정으로 봅니다. */
    private static final double CONFIRM_RATIO = 2.0;

    private final BookSearchService searchService;

    public MultiCheckService(BookSearchService searchService) {
        this.searchService = searchService;
    }

    public enum LineStatus {
        /** 후보가 하나이거나 1위가 2위를 크게 앞섭니다. */
        CONFIRMED,
        /** 후보가 여럿이라 사용자가 골라야 합니다. */
        AMBIGUOUS,
        /** 물어봤는데 그런 책이 없습니다. 줄을 고칠 수 있게 해야 합니다. */
        NOT_FOUND,
        /**
         * 물어보지 못했습니다. <b>{@link #NOT_FOUND} 와 절대 섞으면 안 됩니다.</b>
         * 앞은 "그런 책이 없다"이고 이것은 "알 수 없다"입니다. 정보나루가 응답하지 않을 때
         * 이것을 결과 없음으로 표시하면, 멀쩡히 있는 책을 없다고 답하게 됩니다.
         */
        LOOKUP_FAILED,
        /** 줄 자체를 읽지 못했습니다. */
        UNREADABLE
    }

    /**
     * @param lineNo      사용자가 보는 줄 번호
     * @param raw         원본 문구. 그대로 되돌려 보여 줍니다
     * @param explanation 어떻게 해석했는지. <b>항상 보여 줍니다.</b> 사용자가 왜 이 결과가
     *                    나왔는지 알아야 스스로 고칠 수 있습니다
     * @param mergedFrom  같은 책으로 보고 합친 다른 줄 번호들
     * @param candidates  후보. 확정이면 한 건입니다
     */
    public record LineResult(
            int lineNo,
            String raw,
            LineStatus status,
            String explanation,
            List<Integer> mergedFrom,
            List<BookSearchService.WorkResult> candidates
    ) {}

    /**
     * @param truncated 50줄을 넘어 잘라 냈는지. 잘라 낸 사실을 숨기면 안 됩니다
     */
    public record ResolveResponse(List<LineResult> lines, boolean truncated) {}

    public ResolveResponse resolve(List<String> rawLines) {
        LineParser.Parsed parsed = LineParser.parse(rawLines);
        List<LineResult> results = new ArrayList<>(parsed.lines().size());
        for (LineParser.ParsedLine line : parsed.lines()) {
            results.add(resolveOne(line));
        }
        return new ResolveResponse(List.copyOf(results), parsed.truncated());
    }

    private LineResult resolveOne(LineParser.ParsedLine line) {
        if (!line.readable()) {
            return new LineResult(line.lineNo(), line.raw(), LineStatus.UNREADABLE,
                    line.problem(), line.mergedFrom(), List.of());
        }

        // 해석 방법을 위에서부터 시도하고 결과가 나오면 멈춥니다.
        boolean lookupFailed = false;
        for (LineParser.Attempt attempt : line.attempts()) {
            Outcome outcome = lookUp(attempt);
            if (outcome.failed()) {
                // 조회를 못 한 것과 결과가 없는 것을 구분해 둡니다.
                lookupFailed = true;
                continue;
            }
            List<BookSearchService.WorkResult> works = outcome.works();
            if (works.isEmpty()) continue;

            List<BookSearchService.WorkResult> ranked = rank(works, attempt);
            LineStatus status = isConfirmed(ranked, attempt) ? LineStatus.CONFIRMED : LineStatus.AMBIGUOUS;
            List<BookSearchService.WorkResult> shown = status == LineStatus.CONFIRMED
                    ? List.of(ranked.get(0))
                    : ranked.subList(0, Math.min(MAX_CANDIDATES, ranked.size()));
            return new LineResult(line.lineNo(), line.raw(), status,
                    attempt.explanation(), line.mergedFrom(), List.copyOf(shown));
        }

        LineParser.Attempt last = line.attempts().get(line.attempts().size() - 1);
        // 한 번이라도 조회에 실패했다면 "그런 책이 없다"고 말할 수 없습니다.
        return new LineResult(line.lineNo(), line.raw(),
                lookupFailed ? LineStatus.LOOKUP_FAILED : LineStatus.NOT_FOUND,
                last.explanation(), line.mergedFrom(), List.of());
    }

    /** @param failed 조회 자체가 실패했는지. 빈 결과와 반드시 구분해야 합니다. */
    private record Outcome(List<BookSearchService.WorkResult> works, boolean failed) {}

    private Outcome lookUp(LineParser.Attempt attempt) {
        try {
            return new Outcome(searchService.worksFor(toQuery(attempt)), false);
        } catch (RuntimeException e) {
            // 한 줄의 조회가 실패해도 나머지 줄은 답을 만들어야 합니다.
            // 다만 그 줄을 결과 없음으로 내려보내면 안 됩니다.
            return new Outcome(List.of(), true);
        }
    }

    private static Data4LibraryClient.BookQuery toQuery(LineParser.Attempt attempt) {
        return switch (attempt.kind()) {
            case ISBN -> Data4LibraryClient.BookQuery.byIsbn(attempt.isbn13());
            case TITLE -> Data4LibraryClient.BookQuery.byTitle(attempt.title());
            case TITLE_AUTHOR ->
                    new Data4LibraryClient.BookQuery(attempt.title(), attempt.author(), null, null, false);
        };
    }

    private static boolean isConfirmed(List<BookSearchService.WorkResult> ranked,
                                       LineParser.Attempt attempt) {
        // ISBN 은 서지를 하나로 특정하므로 고르게 할 이유가 없습니다.
        if (attempt.kind() == LineParser.Kind.ISBN) return true;
        if (ranked.size() == 1) return true;
        double first = score(ranked.get(0), attempt);
        double second = score(ranked.get(1), attempt);
        return second <= 0 || first >= second * CONFIRM_RATIO;
    }

    private static List<BookSearchService.WorkResult> rank(
            List<BookSearchService.WorkResult> works, LineParser.Attempt attempt) {
        return works.stream()
                .sorted(Comparator.comparingDouble((BookSearchService.WorkResult w) ->
                        score(w, attempt)).reversed())
                .toList();
    }

    /**
     * 후보 점수. <b>제목 일치도, 저자 일치 여부, 판본 수만 봅니다.</b>
     *
     * <p>소장 여부는 쓰지 않습니다. 소장을 알려면 먼저 책을 확정해야 하므로 순서가 맞지
     * 않고, 후보마다 조회하면 호출이 몇 배로 늘어납니다.
     */
    static double score(BookSearchService.WorkResult work, LineParser.Attempt attempt) {
        if (attempt.kind() == LineParser.Kind.ISBN) return 1.0;

        // **권차를 떼고 견줍니다.** 표제에는 권차가 붙어 있어서(「레 미제라블 1권」),
        // 그대로 견주면 낱권이 「제목이 정확히 맞는 것」에서 빠지고 후보 목록 밖으로
        // 밀려납니다. 자세한 이유는 BibNormalizer.comparisonKey 에 적어 두었습니다.
        String wanted = BibNormalizer.comparisonKey(attempt.title());
        String got = BibNormalizer.comparisonKey(work.title());
        double titleScore = got.equals(wanted) ? 1.0 : WorkMatcher.trigramSimilarity(wanted, got);

        double authorScore = 0.0;
        if (attempt.author() != null && work.author() != null) {
            // 저자를 준 줄에서 저자가 맞으면 크게 올립니다. 같은 제목의 다른 책을 가르는
            // 것은 대개 저자입니다.
            authorScore = WorkMatcher.trigramSimilarity(
                    BibNormalizer.normalizeKey(attempt.author()),
                    BibNormalizer.normalizeKey(work.author())) * 0.5;
        }

        // 판본이 여럿 묶인 저작을 조금 올립니다. 널리 읽혀 여러 번 나온 책일 가능성이
        // 높고, 소장 조회에서도 잡힐 확률이 높습니다.
        double editionBonus = Math.min(work.isbn13List().size(), 5) * 0.02;

        return titleScore + authorScore + editionBonus;
    }
}
