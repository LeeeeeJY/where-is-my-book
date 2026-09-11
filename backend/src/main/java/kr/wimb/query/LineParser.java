package kr.wimb.query;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Isbn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 붙여넣은 목록을 줄 단위로 해석합니다.
 *
 * <p><b>외부 호출이 전혀 없습니다.</b> 그래서 목록을 받자마자 화면을 그릴 수 있고,
 * 소장 조회 결과는 도착하는 대로 채워 넣게 됩니다. 빈 화면으로 기다리게 하지 않는 것이
 * 여러 권 확인에서 가장 중요합니다.
 *
 * <p>해석 결과에는 <b>어떻게 읽었는지를 사람이 읽을 수 있는 문장으로</b> 함께 담습니다.
 * 사용자가 왜 이 결과가 나왔는지 알아야 스스로 고칠 수 있습니다.
 */
public final class LineParser {

    /** 한 번에 확인할 수 있는 줄 수. 넘으면 잘라 내고 그 사실을 알립니다. */
    public static final int MAX_LINES = 50;

    private LineParser() {}

    public enum Kind {
        /** ISBN 으로 읽었습니다. 서지가 하나로 확정됩니다. */
        ISBN,
        /** 줄 전체를 제목으로 읽었습니다. */
        TITLE,
        /** 제목과 저자로 나눠 읽었습니다. */
        TITLE_AUTHOR,
        /**
         * 제목과 출판사로 나눠 읽었습니다.
         *
         * <p><b>{@link #TITLE_AUTHOR} 와 함께 만듭니다. 둘 중 어느 쪽인지 글자만 보고는
         * 알 수 없기 때문입니다.</b> 「마의 산 - 토마스 만」과 「마의 산 - 을유문화사」는
         * 생김새가 같아서, 뒤에 붙은 말이 저자인지 출판사인지 가를 근거가 줄 안에 없습니다.
         * 그래서 <b>여기서 가르지 않고 둘 다 만들어</b> 정보나루에 물어본 뒤
         * {@code MultiCheckService} 가 점수로 고릅니다.
         *
         * <p><b>고전 번역서에서 이것이 필요합니다.</b> 저작을 출판사별로 갈라 놓았으므로
         * 「마의 산 - 토마스 만」은 범우사·을유문화사·열린책들·동서문화사·지식을만드는지식이
         * 저마다 다른 후보로 나옵니다. 저자를 붙여도 후보가 줄지 않고, 판을 가르는 것은
         * 출판사입니다.
         */
        TITLE_PUBLISHER
    }

    /**
     * 한 줄을 읽는 방법 하나. <b>한 줄이 여러 개를 낳고, 고르는 것은 조회한 뒤입니다.</b>
     *
     * <p>줄 전체를 제목으로 보는 것이 늘 첫 번째입니다. 순서를 뒤집으면 「82년생 김지영,
     * 그 후」처럼 쉼표가 들어간 제목이 잘못 갈라집니다. 구분자가 있는 줄은 거기에 더해
     * <b>제목과 저자, 제목과 출판사 둘 다</b> 만듭니다. 뒤에 붙은 말이 어느 쪽인지 글자만
     * 보고는 알 수 없으므로, 여기서 고르지 않고 {@code MultiCheckService} 가 조회 결과의
     * 점수로 고릅니다.
     */
    public record Attempt(Kind kind, String isbn13, String title, String author,
                          String publisher, String explanation) {}

    /**
     * @param lineNo     사용자가 보는 줄 번호. 1부터 셉니다
     * @param raw        원본 문구. 화면에 그대로 되돌려 보여줍니다
     * @param attempts   해석 시도. 비어 있으면 읽지 못한 줄입니다
     * @param problem    읽지 못한 이유. 읽었으면 null
     * @param mergedFrom 같은 책으로 판정되어 이 줄에 합쳐진 다른 줄 번호들
     */
    public record ParsedLine(
            int lineNo, String raw, List<Attempt> attempts, String problem, List<Integer> mergedFrom
    ) {
        public boolean readable() {
            return !attempts.isEmpty();
        }
    }

    /**
     * @param lines     해석한 줄들. 중복은 합쳐져 있습니다
     * @param truncated {@link #MAX_LINES} 를 넘어 잘라 냈는지
     */
    public record Parsed(List<ParsedLine> lines, boolean truncated) {}

    // P1. 앞머리의 목록 기호와 번호.
    // 숫자를 두 자리까지만 보는 것이 중요합니다. 네 자리까지 허용하면 「1984. 조지 오웰」의
    // 「1984.」를 목록 번호로 읽어 제목을 통째로 잃습니다.
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*[-*•·]?\\s*(\\d{1,2}[.)])?\\s*");

    private static final Pattern URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);

    /** 주소 안에 섞여 있는 ISBN13. 서점마다 파라미터 이름이 달라 값만 찾습니다. */
    private static final Pattern ISBN13_IN_TEXT = Pattern.compile("(97[89]\\d{10})");

    /** 제목과 그 뒤엣말을 나누는 자리. 마지막 것에서 나눕니다. */
    private static final List<String> SPLITTERS = List.of(" - ", " / ", " — ", ", ");

    /**
     * 조각이 셋 이상인지 셀 때 보는 구분자. <b>쉼표를 넣지 않았습니다.</b>
     *
     * <p>쉼표는 제목 안에 흔히 들어갑니다. 이것으로 조각을 세면 「총, 균, 쇠 - 재레드
     * 다이아몬드」가 네 조각이 되어 <b>앞부분만 제목으로 보는 해석이 「총」을 찾습니다.</b>
     * 한 글자로 검색해 아무것이나 데려오는 셈입니다. 쉼표로 나눈 목록(「마의 산, 토마스 만,
     * 을유문화사」)은 그래서 지금도 읽지 못합니다. <b>제목을 잘라 먹는 쪽보다 못 읽는 쪽이
     * 낫습니다.</b> 쉼표가 제목의 일부인지 칸 구분인지 가를 근거가 줄 안에 없습니다.
     */
    private static final List<String> STRONG_SPLITTERS = List.of(" - ", " / ", " — ");

    public static Parsed parse(List<String> rawLines) {
        List<ParsedLine> parsed = new ArrayList<>();
        int lineNo = 0;
        boolean truncated = false;

        for (String raw : rawLines) {
            if (raw == null || raw.isBlank()) continue;
            if (parsed.size() >= MAX_LINES) {
                truncated = true;
                break;
            }
            parsed.add(parseOne(++lineNo, raw.trim()));
        }
        return new Parsed(dedupe(parsed), truncated);
    }

    private static ParsedLine parseOne(int lineNo, String raw) {
        String body = LIST_MARKER.matcher(raw).replaceFirst("");
        if (body.isBlank()) {
            return unreadable(lineNo, raw, "번호나 기호만 있고 책 이름이 없습니다.");
        }

        // P2. 서점 주소는 안에 있는 ISBN 으로 읽습니다.
        if (URL.matcher(body).matches()) {
            Matcher found = ISBN13_IN_TEXT.matcher(body);
            if (found.find()) {
                return isbnLine(lineNo, raw, found.group(1), "주소에서 ISBN 을 찾았습니다");
            }
            return unreadable(lineNo, raw,
                    "주소에서 ISBN 을 찾지 못했습니다. 책 제목을 직접 넣어 주세요.");
        }

        // P3. 숫자만 남는 줄은 ISBN 으로 봅니다.
        String digits = body.replaceAll("[\\s-]", "");
        if (looksLikeIsbn(digits)) {
            Optional<String> canonical = Isbn.canonicalize(digits);
            if (canonical.isPresent()) {
                return isbnLine(lineNo, raw, canonical.get(),
                        digits.length() == 10 ? "ISBN10 을 13자리로 바꿨습니다" : "ISBN 으로 읽었습니다");
            }
            // 여기서 제목으로 넘기지 않습니다. 숫자 나열을 제목으로 찾으면 아무것도 나오지
            // 않는데, 사용자는 그것을 "이 책이 없다"로 읽게 됩니다.
            return unreadable(lineNo, raw,
                    "ISBN 처럼 보이지만 체크디지트가 맞지 않습니다. 자릿수를 확인해 주세요.");
        }

        // P4, P5. 줄 전체를 제목으로 먼저 보고, 그다음에 제목과 저자, 제목과 출판사로 나눠 봅니다.
        List<Attempt> attempts = new ArrayList<>();
        attempts.add(new Attempt(Kind.TITLE, null, body, null, null, "제목: " + body));

        // 뒤에 붙은 말이 저자인지 출판사인지는 줄만 보고 알 수 없으므로 둘 다 만듭니다.
        // 어느 쪽이 맞았는지는 정보나루에 물어본 결과의 점수가 말해 줍니다.
        splitTitleAndRest(body).ifPresent(split -> {
            attempts.add(new Attempt(Kind.TITLE_AUTHOR, null, split[0], split[1], null,
                    "제목: %s, 저자: %s (제목과 저자로 나눠 다시 찾음)".formatted(split[0], split[1])));
            attempts.add(new Attempt(Kind.TITLE_PUBLISHER, null, split[0], null, split[1],
                    "제목: %s, 출판사: %s (제목과 출판사로 나눠 다시 찾음)".formatted(split[0], split[1])));
        });

        // P6. 조각이 셋 이상이면 앞부분만 제목으로 보는 해석을 하나 더합니다. **맨 뒤에
        // 둡니다.** 저자나 출판사까지 맞은 해석이 있으면 그쪽이 점수로 이겨야 합니다.
        titleOfThreeOrMore(body).ifPresent(title -> attempts.add(new Attempt(
                Kind.TITLE, null, title, null, null,
                "제목: %s (앞부분만 제목으로 보고 다시 찾음)".formatted(title))));

        return new ParsedLine(lineNo, raw, List.copyOf(attempts), null, List.of());
    }

    /** 숫자만으로 이루어졌는지 봅니다. 한글 제목이 여기에 걸리는 일은 없습니다. */
    private static boolean looksLikeIsbn(String digits) {
        if (digits.length() == 13) return digits.chars().allMatch(Character::isDigit);
        if (digits.length() == 10) {
            String head = digits.substring(0, 9);
            char tail = Character.toUpperCase(digits.charAt(9));
            return head.chars().allMatch(Character::isDigit) && (Character.isDigit(tail) || tail == 'X');
        }
        return false;
    }

    /**
     * 제목과 <b>그 뒤에 붙은 말</b>로 나눕니다. 뒤엣것이 저자인지 출판사인지는 여기서
     * 정하지 않습니다. 가를 근거가 줄 안에 없기 때문입니다.
     */
    private static Optional<String[]> splitTitleAndRest(String body) {
        for (String splitter : SPLITTERS) {
            int at = body.lastIndexOf(splitter);
            if (at <= 0) continue;
            String title = body.substring(0, at).trim();
            String author = body.substring(at + splitter.length()).trim();
            if (!title.isEmpty() && !author.isEmpty()) {
                return Optional.of(new String[] {title, author});
            }
        }
        return Optional.empty();
    }

    /**
     * 조각이 <b>셋 이상</b>일 때의 첫 조각. 둘이면 비어 있습니다.
     *
     * <p>「마의 산 / 토마스 만 / 을유문화사」처럼 제목·저자·출판사를 한 줄에 적은 목록이
     * 실제로 흔합니다. 표에서 복사하면 그 모양이 됩니다. 그런데 {@link #splitTitleAndRest}
     * 는 <b>마지막</b> 구분자에서만 나누므로 저자가 제목 안으로 딸려 들어가고, 그 제목으로는
     * 한 건도 나오지 않습니다. 사용자에게는 그것이 <b>「그런 책이 없다」로 보입니다.</b>
     * 화면이 「제목: 마의 산 / 토마스 만」이라고 잘못 읽은 것까지 함께 보여 주었습니다.
     *
     * <p><b>세 칸을 제대로 받는 것은 일부러 하지 않았습니다.</b> 저자든 출판사든 하나만
     * 있으면 판을 가르는 데 충분하고, 둘을 다 좁혀 봐야 후보가 한두 개 줄 뿐입니다. 대신
     * 제목만이라도 제대로 읽어 <b>0건으로 나가지 않게</b> 합니다.
     */
    private static Optional<String> titleOfThreeOrMore(String body) {
        int first = Integer.MAX_VALUE;
        for (String splitter : STRONG_SPLITTERS) {
            int at = body.indexOf(splitter);
            if (at > 0) first = Math.min(first, at);
        }
        if (first == Integer.MAX_VALUE) return Optional.empty();

        boolean more = false;
        for (String splitter : STRONG_SPLITTERS) {
            if (body.indexOf(splitter, first + 1) > 0) {
                more = true;
                break;
            }
        }
        if (!more) return Optional.empty();

        String title = body.substring(0, first).trim();
        return title.isEmpty() ? Optional.empty() : Optional.of(title);
    }

    private static ParsedLine isbnLine(int lineNo, String raw, String isbn13, String how) {
        return new ParsedLine(lineNo, raw,
                List.of(new Attempt(Kind.ISBN, isbn13, null, null, null, how + ": " + isbn13)),
                null, List.of());
    }

    private static ParsedLine unreadable(int lineNo, String raw, String problem) {
        return new ParsedLine(lineNo, raw, List.of(), problem, List.of());
    }

    /**
     * P6. 같은 책을 가리키는 줄을 합칩니다.
     *
     * <p>합친 줄의 원본 문구는 버리지 않고 줄 번호로 남깁니다. 사용자가 목록에서 무엇이
     * 사라졌는지 알 수 있어야 하기 때문입니다.
     */
    private static List<ParsedLine> dedupe(List<ParsedLine> lines) {
        Map<String, ParsedLine> byKey = new LinkedHashMap<>();
        List<ParsedLine> out = new ArrayList<>();

        for (ParsedLine line : lines) {
            String key = dedupeKey(line);
            if (key == null) {
                out.add(line);
                continue;
            }
            ParsedLine existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, line);
                out.add(line);
                continue;
            }
            List<Integer> merged = new ArrayList<>(existing.mergedFrom());
            merged.add(line.lineNo());
            ParsedLine replacement = new ParsedLine(existing.lineNo(), existing.raw(),
                    existing.attempts(), existing.problem(), List.copyOf(merged));
            byKey.put(key, replacement);
            out.set(out.indexOf(existing), replacement);
        }
        return List.copyOf(out);
    }

    /** 읽지 못한 줄은 합치지 않습니다. 고쳐야 할 줄이 조용히 사라지면 안 됩니다. */
    private static String dedupeKey(ParsedLine line) {
        if (!line.readable()) return null;
        Attempt first = line.attempts().get(0);
        if (first.kind() == Kind.ISBN) return "isbn:" + first.isbn13();
        return "title:" + BibNormalizer.normalizeKey(first.title());
    }
}
