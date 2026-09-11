package kr.wimb.opac;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용자가 도서관 이름을 누른 순간, ISBN 검색 결과를 받아 <b>그 책의 상세 링크</b>를 뽑아
 * 줍니다.
 *
 * <p>왜 미리 만들 수 없는가. 상세 주소는 대부분 도서관 내부 키를 씁니다(노원
 * {@code /bookDetail/MO/629661,530431,…}, 김해 {@code book_key=150671418}, 대구 {@code regNo}).
 * 책마다 다르고 우리가 미리 알 방법이 없어 {@code {isbn13}} 자리표로는 못 만듭니다. 대신 ISBN
 * 검색 결과 안에는 그 링크가 있으므로, <b>누를 때 그 페이지를 받아 링크를 꺼내면</b> 상세로
 * 갈 수 있습니다. 어떤 모양의 링크를 꺼낼지는 {@code detail-patterns.csv} 가 OPAC 시스템마다
 * 정규식으로 들고 있습니다.
 *
 * <p><b>못 찾으면 검색 결과로 내려갑니다.</b> 조용히 실패하면 사용자는 검색 결과 화면을 보게
 * 되는데, 그것은 지금까지의 동작 그대로라 나빠지는 것은 없습니다. 실패의 종류는 갈라 세어
 * {@code /api/status} 로 내보내고, {@code /api/diagnose/opac} 이 한 도서관을 실제로 해 봅니다.
 * <b>서버가 어느 OPAC 에 닿는지는 배포된 곳의 나가는 IP 에 달렸습니다.</b> 국내 공공도서관
 * 상당수가 해외 IP 를 막으므로 미국 리전 VM 에서는 닿지 않는 곳이 있을 수 있고, 그것은 코드가
 * 아니라 진단으로 확인해야 합니다.
 *
 * <h2>남의 서버라는 것</h2>
 *
 * <p>사용자가 누른 한 번이 곧 그 도서관 서버로 가는 요청 한 번입니다. 그래서 (검색 주소 →
 * 상세 주소) 답을 기억해 두고, 같은 호스트에는 한꺼번에 둘까지만 나가며, 그 이상은 기다리지
 * 않고 검색 결과로 보냅니다. {@code robots.txt} 가 막은 경로는 받지 않습니다. 실패는 기억하지
 * 않고(정보나루 소장 캐시와 같은 규칙), 「링크가 없었다」는 한 시간만 기억합니다.
 *
 * <h2>서버는 자바스크립트를 돌리지 않습니다</h2>
 *
 * <p>여기서 받는 것은 HTML 원문입니다. 결과를 자바스크립트로 그리는 OPAC 은 원문에 링크가
 * 없어 패턴이 잡히지 않습니다. 그래서 조사 스크립트({@code scripts/opac-browse.py})는 패턴을
 * 확인할 때 브라우저가 아니라 <b>서버와 같은 방식(자바스크립트 없이, 같은 User-Agent)</b>으로
 * 받아 봅니다. {@link #USER_AGENT} 를 바꾸면 스크립트의 {@code UA} 도 같이 바꿔야 합니다.
 * 다른 UA 로 확인한 패턴은 서버에서 다른 페이지를 받을 수 있습니다.
 */
public final class DetailResolver {

    /** 조사 스크립트({@code scripts/opac-discover.py} 의 {@code UA})와 <b>같아야 합니다.</b> */
    public static final String USER_AGENT =
            "wimb-opac-check/1.0 (+https://github.com/LeeeeeJY/where-is-my-book)";

    static final Duration RESOLVED_TTL = Duration.ofDays(7);
    static final Duration NO_MATCH_TTL = Duration.ofHours(1);
    static final Duration ROBOTS_TTL = Duration.ofDays(1);
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_ENTRIES = 20_000;
    static final int PER_HOST_IN_FLIGHT = 2;
    static final int MAX_BODY_BYTES = 1_500_000;

    public enum Status {
        /** 상세 링크를 찾았습니다. */
        RESOLVED,
        /** 페이지는 받았는데 패턴에 맞는 링크가 없습니다. 그 판이 없거나 패턴이 안 맞는 것입니다. */
        NO_MATCH,
        /** 페이지를 받지 못했습니다. 시간 초과, 접속 거부, 200 이 아닌 응답. 기억하지 않습니다. */
        FETCH_FAILED,
        /** robots.txt 가 그 경로를 막았습니다. 받지 않았습니다. */
        BLOCKED_BY_ROBOTS,
        /** 그 호스트로 이미 둘이 나가 있어 기다리지 않았습니다. */
        BUSY
    }

    /**
     * @param detailUrl 찾은 상세 주소. {@code RESOLVED} 일 때만 있습니다.
     * @param note      진단용 한 줄. 상태 코드, 예외 이름, 거른 이유.
     * @param fromCache 기억해 둔 답인지
     */
    public record Outcome(Status status, String detailUrl, String note, boolean fromCache) {
        static Outcome of(Status status, String note) {
            return new Outcome(status, null, note, false);
        }
    }

    /** 받은 페이지. {@code finalUri} 는 리다이렉트를 따라간 뒤의 주소라 상대 링크의 기준입니다. */
    public record Page(int status, String body, URI finalUri) {}

    /** 실제 HTTP. 테스트는 가짜를 끼웁니다. */
    @FunctionalInterface
    public interface Fetcher {
        Page fetch(URI url) throws IOException, InterruptedException;
    }

    private record Cached(String detailUrl, Instant insertedAt, Instant expiresAt) {}

    private record Robots(List<String> disallows, Instant expiresAt) {}

    private final Fetcher fetcher;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Robots> robots = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> perHost = new ConcurrentHashMap<>();
    private final AtomicLong resolved = new AtomicLong();
    private final AtomicLong missed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();

    public DetailResolver(Fetcher fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /** 기억해 둔 답이 있으면 그것을, 없으면 받아서 찾습니다. */
    public Outcome resolve(String searchUrl, Pattern pattern) {
        Instant now = clock.instant();
        Cached hit = cache.get(searchUrl);
        if (hit != null && hit.expiresAt().isAfter(now)) {
            cacheHits.incrementAndGet();
            return hit.detailUrl() == null
                    ? new Outcome(Status.NO_MATCH, null, "기억해 둔 답: 링크 없음", true)
                    : new Outcome(Status.RESOLVED, hit.detailUrl(), "기억해 둔 답", true);
        }
        return fetchAndRemember(searchUrl, pattern);
    }

    /**
     * 기억해 둔 답을 쓰지 않고 실제로 받아 봅니다. 진단용입니다. 「지금 서버가 그 OPAC 에
     * 닿는가」를 알아야 하는 자리라, 캐시가 답하면 아무것도 확인한 것이 아닙니다.
     */
    public Outcome probe(String searchUrl, Pattern pattern) {
        return fetchAndRemember(searchUrl, pattern);
    }

    private Outcome fetchAndRemember(String searchUrl, Pattern pattern) {
        URI search;
        try {
            search = URI.create(searchUrl);
        } catch (IllegalArgumentException e) {
            failed.incrementAndGet();
            return Outcome.of(Status.FETCH_FAILED, "주소가 아닙니다: " + searchUrl);
        }
        String host = search.getHost();
        if (host == null) {
            failed.incrementAndGet();
            return Outcome.of(Status.FETCH_FAILED, "호스트가 없습니다: " + searchUrl);
        }
        if (disallowed(search)) {
            failed.incrementAndGet();
            return Outcome.of(Status.BLOCKED_BY_ROBOTS, "robots.txt 가 막은 경로입니다");
        }

        Semaphore permits = perHost.computeIfAbsent(host.toLowerCase(Locale.ROOT),
                k -> new Semaphore(PER_HOST_IN_FLIGHT));
        boolean acquired;
        try {
            acquired = permits.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.of(Status.BUSY, "중단되었습니다");
        }
        if (!acquired) {
            failed.incrementAndGet();
            return Outcome.of(Status.BUSY, "그 호스트로 이미 " + PER_HOST_IN_FLIGHT + "개가 나가 있습니다");
        }
        try {
            Page page = fetcher.fetch(search);
            if (page.status() != 200) {
                failed.incrementAndGet();
                return Outcome.of(Status.FETCH_FAILED, "HTTP " + page.status());
            }
            Outcome outcome = extract(page, pattern, search);
            remember(searchUrl, outcome);
            return outcome;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.incrementAndGet();
            return Outcome.of(Status.FETCH_FAILED, "중단되었습니다");
        } catch (Exception e) {
            // 시간 초과, 접속 거부, TLS. 실패는 기억하지 않습니다. 잠깐 흔들린 뒤에도 한동안
            // 검색 결과로만 보내게 되기 때문입니다.
            failed.incrementAndGet();
            return Outcome.of(Status.FETCH_FAILED, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            permits.release();
        }
    }

    /** 받은 HTML 에서 패턴의 첫 번째 링크를 꺼내 절대 주소로 만듭니다. */
    private Outcome extract(Page page, Pattern pattern, URI search) {
        Matcher m = pattern.matcher(page.body() == null ? "" : page.body());
        if (!m.find()) {
            missed.incrementAndGet();
            return Outcome.of(Status.NO_MATCH, "패턴에 맞는 링크가 없습니다");
        }
        String href = unescape(m.group(1)).strip();
        String lower = href.toLowerCase(Locale.ROOT);
        if (href.isEmpty() || lower.startsWith("javascript:") || lower.startsWith("mailto:")
                || href.startsWith("#")) {
            missed.incrementAndGet();
            return Outcome.of(Status.NO_MATCH, "링크가 주소가 아닙니다: " + href);
        }
        URI base = page.finalUri() != null ? page.finalUri() : search;
        URI target;
        try {
            target = resolve(base, href.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            missed.incrementAndGet();
            return Outcome.of(Status.NO_MATCH, "링크를 주소로 만들지 못했습니다: " + href);
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || target.getHost() == null) {
            missed.incrementAndGet();
            return Outcome.of(Status.NO_MATCH, "http 주소가 아닙니다: " + target);
        }
        if (!sameOrganisation(target.getHost(), base.getHost())) {
            // 패턴이 배너나 외부 링크를 잡은 것입니다. 남의 사이트로 보내지 않습니다.
            missed.incrementAndGet();
            return Outcome.of(Status.NO_MATCH, "다른 기관의 주소입니다: " + target.getHost());
        }
        if (target.toString().equals(search.toString())) {
            missed.incrementAndGet();
            return Outcome.of(Status.NO_MATCH, "검색 결과 자기 주소입니다");
        }
        resolved.incrementAndGet();
        return new Outcome(Status.RESOLVED, target.toString(), "찾았습니다", false);
    }

    /**
     * 상대 링크를 브라우저와 같은 규칙(RFC 3986)으로 풉니다.
     *
     * <p>자바의 {@link URI#resolve(String)} 는 RFC 2396 이라 <b>{@code ?query} 만 있는 상대 링크에서
     * 마지막 경로 조각을 버립니다.</b> {@code search00.do?a=1} 에 대고 {@code ?b=2} 를 풀면
     * {@code /site/search/?b=2} 가 되는데, 브라우저와 조사 스크립트의 {@code urljoin} 은
     * {@code search00.do?b=2} 로 풉니다. 실제로 도봉의 상세 링크가 {@code ?manage_code=...} 로
     * 시작해서, 스크립트가 확인한 주소와 서버가 만드는 주소가 여기서 갈렸습니다. 다른 모양의
     * 링크는 두 규칙이 같으므로 그대로 자바에 맡깁니다.
     */
    static URI resolve(URI base, String href) {
        if (!href.startsWith("?")) return base.resolve(href);
        String path = base.getRawPath() == null || base.getRawPath().isEmpty() ? "/" : base.getRawPath();
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + href);
    }

    private void remember(String searchUrl, Outcome outcome) {
        Instant now = clock.instant();
        if (outcome.status() == Status.RESOLVED) {
            cache.put(searchUrl, new Cached(outcome.detailUrl(), now, now.plus(RESOLVED_TTL)));
        } else if (outcome.status() == Status.NO_MATCH) {
            cache.put(searchUrl, new Cached(null, now, now.plus(NO_MATCH_TTL)));
        } else {
            return;
        }
        if (cache.size() > MAX_ENTRIES) evictOldestQuarter();
    }

    /** 통째로 비우지 않습니다. 오래된 것부터 4분의 1씩입니다. 소장 캐시와 같은 규칙입니다. */
    private synchronized void evictOldestQuarter() {
        if (cache.size() <= MAX_ENTRIES) return;
        List<Map.Entry<String, Cached>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Comparator.comparing(e -> e.getValue().insertedAt()));
        for (int i = 0; i < entries.size() / 4; i++) cache.remove(entries.get(i).getKey());
    }

    /** {@code User-agent: *} 와 우리 UA 에 걸린 {@code Disallow} 를 하루 동안 기억합니다. */
    private boolean disallowed(URI search) {
        String origin = search.getScheme() + "://" + search.getRawAuthority();
        Instant now = clock.instant();
        Robots rules = robots.get(origin);
        if (rules == null || !rules.expiresAt().isAfter(now)) {
            rules = new Robots(fetchDisallows(URI.create(origin + "/robots.txt")), now.plus(ROBOTS_TTL));
            robots.put(origin, rules);
        }
        String path = search.getRawPath() == null || search.getRawPath().isEmpty() ? "/" : search.getRawPath();
        for (String rule : rules.disallows()) {
            if (path.startsWith(rule)) return true;
        }
        return false;
    }

    private List<String> fetchDisallows(URI robotsUrl) {
        try {
            Page page = fetcher.fetch(robotsUrl);
            if (page.status() != 200 || page.body() == null) return List.of();
            return parseDisallows(page.body());
        } catch (Exception e) {
            // 못 받으면 제한이 없는 것으로 봅니다. 조사 스크립트와 같은 규칙입니다.
            return List.of();
        }
    }

    static List<String> parseDisallows(String body) {
        if (body.substring(0, Math.min(200, body.length())).toLowerCase(Locale.ROOT).contains("<html")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        boolean applies = false;
        for (String raw : body.split("\n")) {
            String line = raw.split("#", 2)[0].strip();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                String agent = value.toLowerCase(Locale.ROOT);
                applies = agent.equals("*") || agent.startsWith("wimb-opac-check");
            } else if (key.equals("disallow") && applies && !value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /** {@code &amp;} 같은 HTML 엔티티. href 에 흔히 들어갑니다. */
    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    /**
     * 같은 기관인지. 검색이 다른 서브도메인에 있는 것은 정당하므로({@code lib.x.kr} 의 상세가
     * {@code opac.x.kr} 에 있는 식) 호스트가 같은지가 아니라 등록 가능한 도메인이 같은지를 봅니다.
     * 조사 스크립트의 {@code registrable_domain} 과 같은 규칙입니다.
     */
    static boolean sameOrganisation(String a, String b) {
        if (a == null || b == null) return false;
        return registrableDomain(a).equals(registrableDomain(b));
    }

    private static final java.util.Set<String> SECOND_LEVEL_KR = java.util.Set.of(
            "go", "or", "co", "re", "ac", "ne", "pe", "hs", "ms", "es", "sc", "kg");

    static String registrableDomain(String host) {
        String[] parts = host.toLowerCase(Locale.ROOT).split(":")[0].split("\\.");
        List<String> labels = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) labels.add(p);
        if (labels.size() <= 2) return String.join(".", labels);
        int n = labels.size();
        if (labels.get(n - 1).equals("kr") && SECOND_LEVEL_KR.contains(labels.get(n - 2))) {
            return String.join(".", labels.subList(n - 3, n));
        }
        return String.join(".", labels.subList(n - 2, n));
    }

    /** {@code /api/status} 에 실을 숫자들. 재배포 뒤 상세로 가는 링크가 줄었을 때 어디서 새는지 봅니다. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opacDetailResolved", resolved.get());
        out.put("opacDetailMissed", missed.get());
        out.put("opacDetailFailed", failed.get());
        out.put("opacDetailCacheHits", cacheHits.get());
        out.put("opacDetailCacheEntries", cache.size());
        return out;
    }

    public int cacheSize() {
        return cache.size();
    }

    // ── 실제 HTTP ────────────────────────────────────────────────────────────

    /** 운영에서 쓰는 Fetcher. 리다이렉트를 따라가고, 본문은 선언된 인코딩으로 읽습니다. */
    public static Fetcher httpFetcher() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(FETCH_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ko")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = response.body();
            if (raw.length > MAX_BODY_BYTES) raw = java.util.Arrays.copyOf(raw, MAX_BODY_BYTES);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new Page(response.statusCode(), decode(raw, contentType), response.uri());
        };
    }

    private static final Pattern CHARSET_HEADER = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_META = Pattern.compile("charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 한글 OPAC 은 EUC-KR 이 아직 남아 있어 선언을 보고 고릅니다. 선언이 틀린 곳도 있어서
     * 엄격하게 읽어 보고 깨지면 다음 후보로 넘어갑니다. 조사 스크립트의 {@code decode} 와
     * 같은 순서입니다.
     */
    static String decode(byte[] raw, String contentType) {
        List<String> candidates = new ArrayList<>();
        Matcher header = CHARSET_HEADER.matcher(contentType == null ? "" : contentType);
        if (header.find()) {
            candidates.add(header.group(1));
        } else {
            String head = new String(raw, 0, Math.min(raw.length, 4096), StandardCharsets.US_ASCII);
            Matcher meta = CHARSET_META.matcher(head);
            if (meta.find()) candidates.add(meta.group(1));
        }
        candidates.add("UTF-8");
        candidates.add("EUC-KR");
        candidates.add("MS949");
        for (String name : candidates) {
            Optional<String> decoded = strictDecode(raw, name);
            if (decoded.isPresent()) return decoded.get();
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static Optional<String> strictDecode(byte[] raw, String charsetName) {
        try {
            Charset charset = Charset.forName(charsetName);
            return Optional.of(charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString());
        } catch (CharacterCodingException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
