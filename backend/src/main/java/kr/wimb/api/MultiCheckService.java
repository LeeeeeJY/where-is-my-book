package kr.wimb.api;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.WorkMatcher;
import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.query.LineParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

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

    /**
     * 한 줄에 보여 줄 후보 수. <b>한 권 검색이 돌려주는 수와 같습니다.</b>
     *
     * <p><b>다섯은 너무 적었고, 스물넷도 모자랐습니다.</b> 출판사가 다른 번역본을 갈라 놓은
     * 뒤로 「레미제라블」 같은 고전은 저작이 서른 개를 넘는데, 거기서 자르면 <b>찾는 판이
     * 목록에 아예 없습니다.</b> 사용자는 그것을 「내 책이 없다」로 읽습니다. 스물넷일 때는
     * 같은 책을 한 권 검색으로 찾으면 나오는 판이 여기서는 안 나왔고, 낱권 묶음의 일부만
     * 잘려 「마의 산」 을유문화사 판이 1권과 3권만 남았습니다. 화면이 고른 것 하나만 보여
     * 주고 나머지는 눌러서 펼치므로, 여기서 넉넉히 보내는 편이 낫습니다.
     */
    private static final int MAX_CANDIDATES = BookSearchService.MAX_WORKS;

    /** 1위가 2위의 이만큼이면 확정으로 봅니다. */
    private static final double CONFIRM_RATIO = 2.0;

    /**
     * 줄 확정을 동시에 몇 줄까지 진행할지.
     *
     * <p>줄마다 정보나루를 두세 번 부르고 정보나루의 서지 검색은 한 번에 3~4초가 걸립니다.
     * 서른 줄을 차례로 돌리면 몇 분이 되고, 사용자는 그 시간을 빈 화면으로 기다립니다.
     * 정보나루에 한꺼번에 나가는 요청 수는 전송 계층이 따로 묶어 두므로, 여기서 겹치는 것은
     * <b>왕복 시간만</b>이고 정보나루에 더 몰아치는 것이 아닙니다.
     */
    private static final int RESOLVE_CONCURRENCY = 6;

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
        return new ResolveResponse(resolveAll(parsed.lines()), parsed.truncated());
    }

    /**
     * 줄들을 몇 개씩 겹쳐서 확정합니다. <b>결과 순서는 줄 순서 그대로입니다.</b>
     *
     * <p>줄 하나의 실패는 {@link #resolveOne} 안에서 이미 그 줄의 상태로 바뀌므로, 여기까지
     * 올라오는 예외는 코드의 결함입니다. 그것은 감추지 않고 그대로 올립니다.
     */
    private List<LineResult> resolveAll(List<LineParser.ParsedLine> lines) {
        if (lines.size() <= 1) {
            return lines.stream().map(this::resolveOne).toList();
        }
        Semaphore permits = new Semaphore(RESOLVE_CONCURRENCY);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<LineResult>> futures = new ArrayList<>(lines.size());
            for (LineParser.ParsedLine line : lines) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return resolveOne(line);
                    } finally {
                        permits.release();
                    }
                }));
            }
            return futures.stream().map(MultiCheckService::await).toList();
        }
    }

    /**
     * 결과를 기다립니다. 줄 하나의 조회 실패는 {@link #lookUp} 안에서 이미 그 줄의
     * 상태로 바뀌므로, 여기까지 올라오는 예외는 코드의 결함입니다. 감추지 않고 올립니다.
     */
    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("줄 확정이 중단되었습니다.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("줄 확정에 실패했습니다.", cause);
        }
    }

    private LineResult resolveOne(LineParser.ParsedLine line) {
        if (!line.readable()) {
            return new LineResult(line.lineNo(), line.raw(), LineStatus.UNREADABLE,
                    line.problem(), line.mergedFrom(), List.of());
        }

        /*
         * 해석 방법을 위에서부터 시도하되, **결과가 나왔다고 무조건 멈추지 않습니다.**
         *
         * 줄 전체를 제목으로 먼저 보는 것은 「코스모스 - 특별판」처럼 제목에 구분자가 든
         * 책을 지키기 위해서입니다. 그런데 정보나루의 제목 매칭이 관대해서, 저자를 붙인
         * 줄도 무언가를 찾아냅니다. 실제로 「좀머 씨 이야기 - 파트리크 쥐스킨트」가
         * <b>「파트리크 쥐스킨트 작품집 (전5권)」으로 확정</b>되었습니다. 작품집 표제에
         * 그 말들이 다 들어 있어서입니다. 찾던 책이 아닌데 확정까지 되어, 사용자는 고를
         * 기회조차 없었습니다.
         *
         * 그래서 <b>제목이 그대로 맞았을 때만 거기서 멈추고</b>, 아니면 다음 해석도 해 본
         * 뒤 더 나은 쪽을 씁니다. 제목만 넣은 줄은 첫 시도에서 맞으므로 호출이 늘지 않습니다.
         *
         * <p><b>남은 해석은 회전 하나로 묶어 동시에 물어봅니다.</b> 구분자가 있는 줄은
         * 제목과 저자, 제목과 출판사 둘로 해석되는데, 차례로 부르면 해석마다 정보나루의
         * 응답 시간이 그대로 쌓입니다. 실측으로 {@code srchBooks} 한 번이 3.7초라 해석을
         * 하나 늘릴 때마다 모호한 줄에 4초가 붙습니다. 해석들은 서로의 결과를 필요로 하지
         * 않으므로 함께 내보내면 걸리는 시간이 늘지 않고, 정보나루에 한꺼번에 나가는 요청
         * 수는 전송 계층이 따로 묶어 둡니다.
         */
        List<LineParser.Attempt> attempts = line.attempts();
        List<Scored> scored = new ArrayList<>(attempts.size());

        // 1회전. 줄 전체를 제목으로 봅니다.
        boolean lookupFailed = collect(scored, attempts.subList(0, 1), List.of(lookUp(attempts.get(0))));

        // 2회전. 첫 해석에서 제목이 그대로 맞지 않았을 때에만 나머지를 동시에 물어봅니다.
        Scored best = bestOf(scored);
        if (best == null || best.top() < GOOD_ENOUGH) {
            List<LineParser.Attempt> rest = attempts.subList(1, attempts.size());
            lookupFailed |= collect(scored, rest, lookUpTogether(rest));
            best = bestOf(scored);
        }

        if (best != null) return resultOf(line, best.attempt(), best.ranked());

        // 한 번이라도 조회에 실패했다면 "그런 책이 없다"고 말할 수 없습니다.
        return new LineResult(line.lineNo(), line.raw(),
                lookupFailed ? LineStatus.LOOKUP_FAILED : LineStatus.NOT_FOUND,
                explanationWhenNotFound(attempts), line.mergedFrom(), List.of());
    }

    /**
     * 못 찾았을 때 화면에 보여 줄 해석. <b>가장 그럴듯하게 읽은 것을 고릅니다.</b>
     *
     * <p>사용자가 줄을 고칠 수 있으려면 우리가 어떻게 읽었는지를 알아야 하는데, 해석이
     * 여럿이므로 하나를 골라야 합니다. <b>출판사 해석은 고르지 않습니다.</b> 그것은 저자
     * 해석과 짝으로 만든 것이고, 둘 중 하나만 보여 준다면 흔한 쪽이 저자입니다. 맨 뒤의
     * 해석을 그냥 쓰면 「총, 균, 쇠 - 재레드 다이아몬드」가 못 찾았을 때 <b>「출판사:
     * 재레드 다이아몬드」</b>로 나갑니다. 사람 이름을 출판사라고 말하는 셈입니다.
     *
     * <p>조각이 셋 이상인 줄에서는 맨 뒤에 붙은 안전망(앞부분만 제목)이 골라집니다.
     * 우리가 마지막으로 시도한 읽기이자 사용자에게 가장 설명이 되는 읽기입니다.
     */
    private static String explanationWhenNotFound(List<LineParser.Attempt> attempts) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            if (attempts.get(i).kind() != LineParser.Kind.TITLE_PUBLISHER) {
                return attempts.get(i).explanation();
            }
        }
        return attempts.get(attempts.size() - 1).explanation();
    }

    /**
     * 한 해석의 조회 결과를 점수까지 매겨 둔 것.
     *
     * @param ranked 순위를 매긴 후보들
     * @param top    첫 후보의 점수. 해석끼리 견주는 값입니다
     */
    private record Scored(LineParser.Attempt attempt,
                          List<BookSearchService.WorkResult> ranked, double top) {}

    /**
     * 조회 결과에 점수를 매겨 모읍니다.
     *
     * @return 조회 <b>자체가</b> 실패한 것이 있었는지. 결과가 비어 있는 것과 반드시
     *         구분해야 합니다. 앞은 "알 수 없다"이고 뒤는 "그런 책이 없다"입니다
     */
    private static boolean collect(List<Scored> into, List<LineParser.Attempt> attempts,
                                   List<Outcome> outcomes) {
        boolean failed = false;
        for (int i = 0; i < attempts.size(); i++) {
            Outcome outcome = outcomes.get(i);
            if (outcome.failed()) {
                failed = true;
                continue;
            }
            if (outcome.works().isEmpty()) continue;
            LineParser.Attempt attempt = attempts.get(i);
            List<BookSearchService.WorkResult> ranked = rank(outcome.works(), attempt);
            into.add(new Scored(attempt, ranked, score(ranked.get(0), attempt)));
        }
        return failed;
    }

    /**
     * 가장 점수가 높은 해석. <b>점수가 같으면 먼저 시도한 것을 씁니다.</b>
     *
     * <p>붙여 넣는 목록은 「제목 - 저자」가 「제목 - 출판사」보다 훨씬 흔하므로, 둘이
     * 똑같이 맞았다면 저자 쪽으로 읽은 것이 사용자의 의도에 가깝습니다.
     */
    private static Scored bestOf(List<Scored> scored) {
        Scored best = null;
        for (Scored candidate : scored) {
            if (best == null || candidate.top() > best.top()) best = candidate;
        }
        return best;
    }

    /** @param failed 조회 자체가 실패했는지. 빈 결과와 반드시 구분해야 합니다. */
    private record Outcome(List<BookSearchService.WorkResult> works, boolean failed) {}

    /**
     * 해석 여럿을 <b>동시에</b> 물어봅니다. 결과 순서는 준 순서 그대로입니다.
     *
     * <p>차례로 부르는 자리를 새로 만들면 그 자리마다 정보나루의 응답 시간이 통째로
     * 붙습니다. 여기서 겹치는 것은 <b>왕복 시간만</b>이고, 정보나루에 한꺼번에 나가는
     * 요청 수는 전송 계층이 {@code WIMB_DATA4LIBRARY_MAX_IN_FLIGHT} 로 묶어 둡니다.
     */
    private List<Outcome> lookUpTogether(List<LineParser.Attempt> attempts) {
        if (attempts.isEmpty()) return List.of();
        if (attempts.size() == 1) return List.of(lookUp(attempts.get(0)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Outcome>> futures = attempts.stream()
                    .map(attempt -> executor.submit(() -> lookUp(attempt)))
                    .toList();
            return futures.stream().map(MultiCheckService::await).toList();
        }
    }

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
            // 출판사도 정보나루에 그대로 넘깁니다. 실측으로 `publisher=민음사` 와
            // `publisher=을유문화사` 가 실제로 좁혀 주었습니다(가정과-검증상태 2-3, 2-14).
            case TITLE_PUBLISHER ->
                    new Data4LibraryClient.BookQuery(attempt.title(), null, attempt.publisher(), null, false);
        };
    }

    /**
     * 이 해석에서 멈춰도 되는 점수.
     *
     * <p>{@code score} 의 제목 몫이 1.0 이면 표제가 그대로 맞았다는 뜻입니다. 저자까지
     * 맞으면 보너스가 더 붙으므로 이 문턱을 넘습니다. 넘지 못하면 다른 해석도 해 봅니다.
     */
    private static final double GOOD_ENOUGH = 1.0;

    private LineResult resultOf(LineParser.ParsedLine line, LineParser.Attempt attempt,
                                List<BookSearchService.WorkResult> ranked) {
        LineStatus status = isConfirmed(ranked, attempt) ? LineStatus.CONFIRMED : LineStatus.AMBIGUOUS;
        List<BookSearchService.WorkResult> shown = status == LineStatus.CONFIRMED
                ? List.of(ranked.get(0))
                : ranked.subList(0, Math.min(MAX_CANDIDATES, ranked.size()));
        return new LineResult(line.lineNo(), line.raw(), status,
                attempt.explanation(), line.mergedFrom(), List.copyOf(shown));
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

    /**
     * 점수 내림차순으로 세우고, <b>점수가 같으면 대출건수가 많은 것을 앞에 둡니다.</b>
     * 결과가 여러 검색을 합친 것이라 정보나루가 준 순서를 그대로 둘 수 없고, 한 권 검색의
     * 같은 등급 정렬과 기준을 맞춥니다.
     *
     * <p><b>낱권 묶음은 한 권 검색과 같은 규칙으로 붙여 둡니다</b>({@link
     * BookSearchService#sortGrouped}). 점수만으로 세우면 같은 판의 1권과 2권이 대출건수에
     * 따라 다른 출판사 사이로 흩어지고, 상한에서 자르면 덜 빌린 권만 빠집니다. 예전에는
     * 「첫 후보 하나를 고르는 곳이라 권차 순서가 뜻을 갖지 않는다」고 보았는데, 화면이 후보
     * 전체를 펼쳐 보여 주게 되면서 순서가 그대로 사용자에게 읽힙니다. 첫 후보는 가장 잘 맞는
     * 묶음의 첫 권이 되므로 한 권 검색의 첫 줄과 같습니다.
     */
    private static List<BookSearchService.WorkResult> rank(
            List<BookSearchService.WorkResult> works, LineParser.Attempt attempt) {
        record Strength(double score, int loans) {}
        Comparator<Strength> order = Comparator.comparingDouble(Strength::score).reversed()
                .thenComparing(Comparator.comparingInt(Strength::loans).reversed());
        return BookSearchService.sortGrouped(works,
                work -> new Strength(score(work, attempt), work.loanCount()), order);
    }

    /**
     * 후보 점수. <b>제목 일치도, 저자나 출판사의 일치 여부, 대출건수만 봅니다.</b>
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

        // **저자와 출판사는 같은 자리를 나눠 씁니다.** 한 해석은 둘 중 하나만 들고 있고
        // (줄에 붙은 말이 어느 쪽인지 모르므로 해석을 둘 다 만듭니다), 맞은 쪽이 이깁니다.
        // 같은 제목의 다른 책을 가르는 것은 대개 저자이지만, **고전 번역서에서는
        // 출판사입니다.** 저작을 출판사별로 갈라 놓았으므로 「마의 산 - 토마스 만」은
        // 저자를 붙여도 후보가 하나도 줄지 않습니다.
        double sideScore = 0.0;
        if (attempt.author() != null && work.author() != null) {
            sideScore = WorkMatcher.trigramSimilarity(
                    BibNormalizer.normalizeKey(attempt.author()),
                    BibNormalizer.normalizeKey(work.author())) * 0.5;
        } else if (attempt.publisher() != null && work.publisher() != null) {
            // **출판사는 `normalizePublisher` 로 견줍니다.** 법인격 표기를 떼므로
            // 「민음사」와 「(주)민음사」가 같아집니다. `normalizeKey` 로 견주면 갈립니다.
            sideScore = WorkMatcher.trigramSimilarity(
                    BibNormalizer.normalizePublisher(attempt.publisher()),
                    BibNormalizer.normalizePublisher(work.publisher())) * 0.5;
        }

        // **널리 읽힌 책을 조금 올립니다.** 예전에는 판본 수를 썼는데, 같은 제목의 저작
        // 가운데 사용자가 찾는 것은 판본이 많은 쪽이 아니라 **많이 빌려 간 쪽**입니다.
        // 실제로 한 권 검색과 여러 권 확인이 같은 제목에 서로 다른 저작을 앞에 세워,
        // 같은 책인데 소장 도서관이 다르게 나왔습니다. 한 권 검색이 같은 등급 안에서
        // 대출건수로 세우므로, 여기서도 대출건수를 쓰면 두 화면의 첫 후보가 같아집니다.
        // 로그를 취해 0.1 을 넘지 않게 묶습니다. 제목이 그대로 맞는 것(1.0)을 뒤집을 만큼
        // 커지면 안 됩니다.
        double popularityBonus = popularityBonus(work.loanCount());

        return titleScore + sideScore + popularityBonus;
    }

    /** 대출건수 10만 건에서 0.1 이 되는 완만한 보너스. 0건이면 0 입니다. */
    static double popularityBonus(int loanCount) {
        if (loanCount <= 0) return 0.0;
        return 0.1 * Math.min(1.0, Math.log10(1 + loanCount) / 5.0);
    }
}
