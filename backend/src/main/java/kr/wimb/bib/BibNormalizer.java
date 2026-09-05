package kr.wimb.bib;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서지 정규화. 이 프로젝트에서 모든 매칭 결정의 근거가 되는 곳입니다.
 *
 * <p><b>정규화 함수는 여기 하나뿐입니다.</b> 적재할 때와 질의할 때가 같은 함수를 호출해야
 * 합니다. 서로 다른 정규화를 적용하면 색인과 질의가 조용히 어긋나고, 검색이 안 되는데
 * 원인을 찾기가 매우 어려워집니다.
 *
 * <p><b>판단의 방향은 누락 쪽입니다.</b> 규칙을 조일지 풀지 고민되면 조입니다.
 * 누락은 사용자가 도서관 페이지에서 확인하면 바로잡히고 눈에 보이지만,
 * 오병합은 헛걸음을 만들고 아무도 눈치채지 못한 채 계속 남습니다.
 */
public final class BibNormalizer {

    private BibNormalizer() {}

    /** 표제부와 책임표시부의 경계. KORMARC 245의 $c 앞 구분 기호입니다. */
    private static final Pattern SOR_SPLIT = Pattern.compile("\\s+/\\s+");
    /** 대등표제 경계. */
    private static final Pattern PARALLEL_SPLIT = Pattern.compile("\\s+=\\s+");
    /** 부표제 경계. */
    private static final Pattern SUBTITLE_SPLIT = Pattern.compile("\\s+:\\s+");

    /**
     * 표제 꼬리의 권차 패턴. 위에서부터 먼저 맞는 것을 씁니다.
     * 숫자만 있는 경우를 두 자리로 제한한 것은 「코스모스 2020」 같은 연도를 권차로 오인하지
     * 않기 위해서입니다. 「1984」처럼 공백 없이 숫자로만 된 표제는 애초에 걸리지 않습니다.
     */
    private static final List<Pattern> VOLUME_PATTERNS = List.of(
            Pattern.compile("^(.*?)\\s*제\\s*(\\d{1,3})\\s*(?:권|부|편|화|집)$"),
            Pattern.compile("^(.*?)\\s+(\\d{1,3})\\s*(?:권|부|편|화|집)$"),
            Pattern.compile("^(.*?)\\s*\\((\\d{1,2})\\)$"),
            Pattern.compile("^(.*?)\\s*[Vv][Oo][Ll]\\.?\\s*(\\d{1,3})$"),
            Pattern.compile("^(.*?)\\s+(\\d{1,2})$")
    );
    private static final Pattern VOLUME_ROMAN =
            Pattern.compile("^(.*?)\\s+([IVX]{1,4})$");
    private static final Pattern VOLUME_SANG_HA =
            Pattern.compile("^(.*?)\\s*[(\\[]?\\s*(상|중|하)\\s*[)\\]]?권?$");

    /** 한글 뒤 괄호 안의 한자는 키에서 빼고 별칭으로 보관합니다. 「난중일기(亂中日記)」 */
    private static final Pattern HANJA_IN_PARENS =
            Pattern.compile("([가-힣]+)\\s*\\(\\s*([\\u4E00-\\u9FFF]{2,})\\s*\\)");

    private static final Pattern SERIES_IN_SUBTITLE =
            Pattern.compile("^(.*?)\\s*시리즈(?:\\s*\\d+)?$");

    private static final Map<String, Integer> ROMAN =
            Map.of("I", 1, "II", 2, "III", 3, "IV", 4, "V", 5,
                   "VI", 6, "VII", 7, "VIII", 8, "IX", 9, "X", 10);

    private static final Map<String, Integer> SANG_HA = Map.of("상", 1, "중", 2, "하", 3);

    private static final Map<String, Contributor.Role> ROLE_MAP = Map.ofEntries(
            Map.entry("지음", Contributor.Role.AUTHOR),
            Map.entry("저자", Contributor.Role.AUTHOR),
            Map.entry("공저", Contributor.Role.AUTHOR),
            Map.entry("저", Contributor.Role.AUTHOR),
            Map.entry("글", Contributor.Role.AUTHOR),
            Map.entry("씀", Contributor.Role.AUTHOR),
            Map.entry("著", Contributor.Role.AUTHOR),
            Map.entry("원작", Contributor.Role.ORIGINAL_AUTHOR),
            Map.entry("옮김", Contributor.Role.TRANSLATOR),
            Map.entry("옮긴이", Contributor.Role.TRANSLATOR),
            Map.entry("번역", Contributor.Role.TRANSLATOR),
            Map.entry("역", Contributor.Role.TRANSLATOR),
            Map.entry("譯", Contributor.Role.TRANSLATOR),
            Map.entry("엮음", Contributor.Role.EDITOR),
            Map.entry("엮은이", Contributor.Role.EDITOR),
            Map.entry("편저", Contributor.Role.EDITOR),
            Map.entry("편역", Contributor.Role.EDITOR),
            Map.entry("편", Contributor.Role.EDITOR),
            Map.entry("編", Contributor.Role.EDITOR),
            Map.entry("그림", Contributor.Role.ILLUSTRATOR),
            Map.entry("그린이", Contributor.Role.ILLUSTRATOR),
            Map.entry("만화", Contributor.Role.ILLUSTRATOR),
            Map.entry("사진", Contributor.Role.PHOTOGRAPHER),
            Map.entry("감수", Contributor.Role.SUPERVISOR),
            Map.entry("해설", Contributor.Role.COMMENTATOR),
            Map.entry("각색", Contributor.Role.ADAPTER),
            Map.entry("기획", Contributor.Role.PLANNER)
    );

    /** 두 글자 이상 역할어. 이름 안에 우연히 들어갈 일이 없어 목록 구분자로도 씁니다. */
    private static final Pattern MULTI_ROLE = Pattern.compile(
            "(" + String.join("|", Lexicons.ROLE_WORDS.stream()
                    .filter(w -> w.length() >= 2).toList()) + ")");

    // ---------------------------------------------------------------
    // 공유 정규화 함수
    // ---------------------------------------------------------------

    /**
     * 매칭과 검색에 쓰는 정규화 키를 만듭니다. 적재와 질의가 모두 이 함수를 부릅니다.
     *
     * <p>공백을 전부 지우는 것이 단일 규칙 중 효과가 가장 큽니다. 국내 도서 데이터는
     * 같은 책도 자료마다 띄어쓰기가 달라서, 공백을 남기면 같은 책이 갈라집니다.
     */
    public static String normalizeKey(String s) {
        return normalize(s).key();
    }

    /** 정규화 결과와, 그 과정에서 떼어 낸 표기들. */
    public record Normalized(String key, List<String> editionTokens,
                             List<String> adaptationTokens, List<String> aliasKeys) {}

    public static Normalized normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Normalized("", List.of(), List.of(), List.of());
        }
        List<String> aliases = new ArrayList<>();

        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

        // 한글 뒤 괄호 한자는 키에서 빼되 별칭으로 살립니다.
        // 이래야 「난중일기(亂中日記)」와 「亂中日記」가 같은 후보 묶음에 들어갑니다.
        Matcher hanja = HANJA_IN_PARENS.matcher(s);
        StringBuilder withoutHanja = new StringBuilder();
        while (hanja.find()) {
            aliases.add(stripAll(hanja.group(2)));
            hanja.appendReplacement(withoutHanja, Matcher.quoteReplacement(hanja.group(1)));
        }
        hanja.appendTail(withoutHanja);
        s = withoutHanja.toString();

        String stripped = stripAll(s);
        // 표기를 떼어 내기 전 형태도 별칭으로 남깁니다.
        // 판본 표기가 실제 표제의 일부인 책이 후보에서 빠지지 않게 합니다.
        String beforeTokenRemoval = stripped;

        List<String> adaptations = new ArrayList<>();
        for (String token : Lexicons.ADAPTATION_TOKENS) {
            if (stripped.contains(token)) {
                adaptations.add(token);
                stripped = stripped.replace(token, "");
            }
        }
        List<String> editions = new ArrayList<>();
        for (String token : Lexicons.EDITION_TOKENS) {
            if (stripped.contains(token)) {
                editions.add(token);
                stripped = stripped.replace(token, "");
            }
        }
        if (!stripped.equals(beforeTokenRemoval)) aliases.add(beforeTokenRemoval);

        return new Normalized(stripped, List.copyOf(editions),
                List.copyOf(adaptations), dedupe(aliases));
    }

    /** 공백과 구두점을 모두 제거합니다. */
    private static String stripAll(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (Lexicons.PUNCTUATION.indexOf(c) >= 0) continue;
            out.append(c);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------
    // 표제 분해
    // ---------------------------------------------------------------

    public static TitleParts parseTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return new TitleParts("", null, null, null, null,
                    List.of(), List.of(), "", "", List.of(), null);
        }
        String work = Normalizer.normalize(rawTitle, Normalizer.Form.NFKC).trim();

        // A1. 책임표시부를 떼어 냅니다. 이걸 먼저 하지 않으면 저자 이름을 정규화하게 됩니다.
        String sor = null;
        String[] sorSplit = SOR_SPLIT.split(work, 2);
        if (sorSplit.length == 2) {
            work = sorSplit[0].trim();
            sor = sorSplit[1].trim();
        }

        // A2. 부표제를 먼저 떼어 냅니다. 대등표제 안에 콜론이 오는 경우보다
        //     부표제가 대등표제 뒤에 오는 경우가 훨씬 흔합니다.
        String subtitle = null;
        int lastColon = lastIndexOf(work, SUBTITLE_SPLIT);
        if (lastColon >= 0) {
            String head = work.substring(0, lastColon).trim();
            String tail = work.substring(lastColon).replaceFirst("^\\s+:\\s+", "").trim();
            if (!head.isEmpty() && !tail.isEmpty()) {
                work = head;
                subtitle = tail;
            }
        }

        // A3. 대등표제를 떼어 냅니다.
        String parallelTitle = null;
        String[] parallelSplit = PARALLEL_SPLIT.split(work, 2);
        if (parallelSplit.length == 2) {
            work = parallelSplit[0].trim();
            parallelTitle = parallelSplit[1].trim();
        }

        // A4. 권차는 표제 꼬리에서만 뽑습니다. 부표제의 숫자는 시리즈 번호일 때가 많습니다.
        Integer volNo = null;
        VolumeMatch vm = extractVolume(work);
        if (vm != null) {
            work = vm.remainder();
            volNo = vm.volNo();
        }

        String seriesTitle = null;
        if (subtitle != null) {
            Matcher m = SERIES_IN_SUBTITLE.matcher(subtitle);
            if (m.matches() && !m.group(1).isBlank()) seriesTitle = m.group(1).trim();
        }

        Normalized core = normalize(work);
        Normalized full = normalize(subtitle == null ? work : work + subtitle);

        List<String> aliases = new ArrayList<>(core.aliasKeys());
        if (parallelTitle != null) aliases.add(normalizeKey(parallelTitle));
        if (seriesTitle != null) aliases.add(normalizeKey(seriesTitle + work));

        return new TitleParts(
                work, subtitle, parallelTitle, seriesTitle, volNo,
                core.editionTokens(), core.adaptationTokens(),
                core.key(), full.key(), dedupe(aliases), sor);
    }

    private record VolumeMatch(String remainder, int volNo) {}

    private static VolumeMatch extractVolume(String title) {
        for (Pattern p : VOLUME_PATTERNS) {
            Matcher m = p.matcher(title);
            if (m.matches()) {
                VolumeMatch vm = accept(m.group(1), Integer.parseInt(m.group(2)));
                if (vm != null) return vm;
            }
        }
        Matcher roman = VOLUME_ROMAN.matcher(title);
        if (roman.matches()) {
            Integer n = ROMAN.get(roman.group(2).toUpperCase(Locale.ROOT));
            if (n != null) {
                VolumeMatch vm = accept(roman.group(1), n);
                if (vm != null) return vm;
            }
        }
        Matcher sangHa = VOLUME_SANG_HA.matcher(title);
        if (sangHa.matches()) {
            VolumeMatch vm = accept(sangHa.group(1), SANG_HA.get(sangHa.group(2)));
            if (vm != null) return vm;
        }
        return null;
    }

    /** 권차를 떼고 남은 표제에 글자가 하나도 없으면 권차가 아니라 표제 자체입니다. */
    private static VolumeMatch accept(String remainder, int volNo) {
        String r = remainder.trim();
        boolean hasLetter = r.codePoints().anyMatch(Character::isLetter);
        return hasLetter ? new VolumeMatch(r, volNo) : null;
    }

    private static int lastIndexOf(String s, Pattern p) {
        Matcher m = p.matcher(s);
        int at = -1;
        while (m.find()) at = m.start();
        return at;
    }

    // ---------------------------------------------------------------
    // 책임표시 분해
    // ---------------------------------------------------------------

    /**
     * 저자 필드를 사람 단위로 나눕니다.
     *
     * <p>세미콜론으로 먼저 나눕니다. <b>쉼표는 목록 구분자가 아니라 도치 구분자</b>이기
     * 때문입니다. 「롤링, 조앤 캐슬린」은 두 사람이 아니라 성을 앞세운 한 사람입니다.
     * 다만 「홍길동 지음, 김철수 옮김」처럼 역할어 뒤에 쉼표가 오는 형태는 실제로 목록이므로,
     * 역할어를 만나면 거기서 끊습니다.
     */
    public static List<Contributor> parseContributors(String rawAuthors) {
        List<Contributor> out = new ArrayList<>();
        if (rawAuthors == null || rawAuthors.isBlank()) return out;

        String normalized = Normalizer.normalize(rawAuthors, Normalizer.Form.NFKC);
        for (String segment : normalized.split("[;；]")) {
            parseSegment(segment, out);
        }
        return out;
    }

    private static void parseSegment(String segment, List<Contributor> out) {
        String rest = segment;
        Matcher m = MULTI_ROLE.matcher(rest);
        int from = 0;
        while (m.find(from)) {
            String name = cleanName(rest.substring(from, m.start()));
            if (!name.isEmpty()) {
                out.add(new Contributor(name, ROLE_MAP.getOrDefault(
                        m.group(1), Contributor.Role.AUTHOR)));
            }
            from = m.end();
            if (from >= rest.length()) return;
        }
        String tail = rest.substring(from);
        if (tail.isBlank()) return;

        // 남은 조각의 끝에 한 글자 역할어가 붙어 있으면 뗍니다.
        // 앞에 구분자가 있을 때만 떼는 것은, 이름의 마지막 글자와 구분되지 않기 때문입니다.
        for (String role : Lexicons.ROLE_WORDS) {
            if (role.length() != 1) continue;
            String trimmed = tail.stripTrailing();
            if (trimmed.length() >= 2 && trimmed.endsWith(role)) {
                char before = trimmed.charAt(trimmed.length() - 2);
                if (Character.isWhitespace(before) || before == ',' || before == ':') {
                    String name = cleanName(trimmed.substring(0, trimmed.length() - 1));
                    if (!name.isEmpty()) {
                        out.add(new Contributor(name, ROLE_MAP.getOrDefault(
                                role, Contributor.Role.AUTHOR)));
                    }
                    return;
                }
            }
        }
        String name = cleanName(tail);
        if (!name.isEmpty()) out.add(new Contributor(name, Contributor.Role.AUTHOR));
    }

    private static String cleanName(String s) {
        return s.replaceAll("^[\\s,、:]+", "").replaceAll("[\\s,、:]+$", "").trim();
    }

    /** 저작 매칭 키에 쓸 저자. 없으면 null입니다. */
    public static Contributor primaryAuthor(List<Contributor> contributors) {
        return contributors.stream().filter(Contributor::isKeyRole).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------
    // 출판사
    // ---------------------------------------------------------------

    private static final Pattern LEGAL_FORM = Pattern.compile(
            "\\(주\\)|㈜|주식회사|\\(유\\)|유한회사|\\(재\\)|재단법인|\\(사\\)|사단법인|^도서출판");

    /**
     * 출판사 정규화. <b>출판사는 저작 매칭 키에 넣지 않습니다.</b>
     * 같은 책이 출판사를 옮겨 재출간되는 경우가 흔하고, 그것은 묶고 싶은 대상이기 때문입니다.
     * 저자가 비어 있을 때의 보조 근거로만 씁니다.
     */
    public static String normalizePublisher(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
        s = LEGAL_FORM.matcher(s).replaceAll("");
        return normalizeKey(s);
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values.stream()
                .filter(v -> v != null && !v.isBlank()).toList()));
    }
}
