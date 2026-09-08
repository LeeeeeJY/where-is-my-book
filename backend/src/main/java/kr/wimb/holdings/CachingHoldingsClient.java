package kr.wimb.holdings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (ISBN, 지역) 한 쌍의 소장 답을 한동안 기억해 두는 클라이언트.
 *
 * <p>소장 확인은 한 권에 판본 수 × 시도 수만큼 정보나루를 부르고, 요청 사이에 120ms 를
 * 두므로 스무 권이면 몇 초가 걸립니다. 그런데 <b>같은 책을 다시 묻는 일이 아주 흔합니다.</b>
 * 도서관을 하나 더 고르고 「확인」을 다시 누르면 ISBN 도 시도도 그대로이고, 새로 고침이나
 * 같은 책의 재검색도 그렇습니다. 그때마다 같은 답을 정보나루에 다시 물으면 사람은 그만큼
 * 다시 기다리고 하루 예산도 그만큼 새어 나갑니다.
 *
 * <p><b>실패는 기억하지 않습니다.</b> 예외는 그대로 올리고 아무것도 넣지 않습니다. 실패를
 * 기억하면 정보나루가 잠깐 흔들린 뒤에도 한동안 계속 「확인 불가」가 나옵니다.
 *
 * <p><b>빈 답은 기억합니다.</b> 「그 지역에는 소장한 곳이 없다」는 정상적인 답이고,
 * 화면은 그것을 빠짐없이 확인한 미소장으로 그립니다. 실패와는 타입에서부터 다릅니다.
 *
 * <p><b>받은 시각을 그대로 돌려줍니다.</b> 화면의 「n월 n일 조회 기준」이 이 값에서
 * 나옵니다. 캐시가 이것을 지금 시각으로 바꿔 말하면 옛 답이 새 답처럼 읽힙니다.
 *
 * <p><b>도서관부호 문자열을 공유합니다.</b> 부호는 여섯 자리인데 자바 String 객체 하나가
 * 40바이트를 넘게 쓰므로, 파싱한 문자열을 그대로 들고 있으면 같은 부호가 수만 번 중복
 * 저장됩니다. 실측에서 10만 항목(소장 179곳 기준)이 <b>900MB 힙에 담기지 않았고</b>,
 * 공유하면 같은 자료가 89.9MB 였습니다. 전국에 1,619곳뿐이라 목록에는 참조만 남습니다.
 *
 * <p>운영에서 쓸 소장 캐시는 DB 에 두고 만료시키지 않는 것이 최종 설계입니다(배경에서
 * 갱신하고 조회 시각을 함께 내보냅니다). 이것은 그 전까지의 <b>프로세스 안 캐시</b>이지만,
 * 재배포마다 통째로 잃지는 않도록 {@link #save}/{@link #load} 로 파일에 남깁니다.
 * {@link kr.wimb.api.HoldingCacheStore} 가 그것을 주기적으로 부릅니다.
 */
public final class CachingHoldingsClient implements HoldingsLookup.HoldingsClient {

    private final HoldingsLookup.HoldingsClient delegate;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final ConcurrentHashMap<String, Answer> cache = new ConcurrentHashMap<>();

    /**
     * 부호 문자열을 한 벌만 두는 곳. 전국 1,619곳이라 유한합니다.
     *
     * <p>{@link String#intern()} 을 쓰지 않는 것은 그것이 JVM 문자열 풀에 남아 회수가
     * 까다롭기 때문입니다. 여기 두면 캐시와 함께 살고 함께 사라집니다.
     */
    private final ConcurrentHashMap<String, String> codePool = new ConcurrentHashMap<>();

    /** 마지막 저장 이후 바뀐 것이 있는지. 없으면 파일을 다시 쓰지 않습니다. */
    private volatile boolean dirty;

    /**
     * @param ttl        한 답을 얼마나 믿을지. 정보나루의 소장 데이터가 하루 단위로 갱신되므로
     *                   몇 시간이면 충분하고, 더 길게 두면 새로 들어온 책을 그만큼 늦게 봅니다.
     * @param maxEntries 메모리 상한. 넘으면 만료된 것을 먼저 비우고, 그래도 넘으면 전부 비웁니다.
     *                   기계 메모리가 1GB 라 자라는 대로 두면 안 됩니다.
     */
    public CachingHoldingsClient(HoldingsLookup.HoldingsClient delegate, Duration ttl,
                                 int maxEntries, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    @Override
    public List<String> libCodesFor(String isbn13, String regionCode) {
        return answerFor(isbn13, regionCode).libCodes();
    }

    @Override
    public Answer answerFor(String isbn13, String regionCode) {
        String key = isbn13 + "/" + (regionCode == null ? "ALL" : regionCode);
        Instant now = clock.instant();

        Answer cached = cache.get(key);
        if (cached != null && isFresh(cached, now)) return cached;

        // 실패하면 여기서 예외가 올라가고 아무것도 기억하지 않습니다.
        Answer fresh = new Answer(share(delegate.libCodesFor(isbn13, regionCode)), now);
        if (cache.size() >= maxEntries) evict(now);
        cache.put(key, fresh);
        dirty = true;
        return fresh;
    }

    /** 지금 기억하고 있는 답의 수. 상한이 지켜지는지 보려는 용도입니다. */
    public int size() {
        return cache.size();
    }

    private boolean isFresh(Answer answer, Instant now) {
        return answer.fetchedAt().plus(ttl).isAfter(now);
    }

    private void evict(Instant now) {
        cache.entrySet().removeIf(entry -> !isFresh(entry.getValue(), now));
        if (cache.size() < maxEntries) return;

        // **만료된 것이 없는데도 가득 찼으면 오래된 것부터 덜어 냅니다.** 예전에는 통째로
        // 비웠는데, 파일 스냅샷이 생기면서 그 한 번이 여러 배포에 걸쳐 쌓은 것을 날리게
        // 되었습니다. 상한의 4분의 1을 덜어 내면 이 정리가 매 호출마다 걸리지 않습니다.
        int drop = Math.max(1, maxEntries / 4);
        cache.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().fetchedAt()))
                .limit(drop)
                .map(Map.Entry::getKey)
                .forEach(cache::remove);
    }

    /** 목록의 부호를 이미 들고 있는 문자열로 바꿔 끼웁니다. */
    private List<String> share(List<String> libCodes) {
        List<String> shared = new ArrayList<>(libCodes.size());
        for (String code : libCodes) shared.add(codePool.computeIfAbsent(code, c -> c));
        return List.copyOf(shared);
    }

    // ---------------------------------------------------------------
    // 파일 스냅샷
    // ---------------------------------------------------------------

    /** 파일 첫 줄. 형식이 바뀌면 이 값을 올려 예전 파일을 조용히 무시하게 합니다. */
    private static final String HEADER = "# wimb-holding-cache v1";

    /** 마지막 저장 이후 바뀐 것이 있는지. 없으면 쓰지 않습니다. */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 지금 기억하고 있는 답을 파일에 씁니다.
     *
     * <p>gzip 으로 감싸는 것은 디스크를 아끼려는 것이기도 하지만, 무료 등급 VM 의 표준 영구
     * 디스크가 쓰기 45 IOPS 밖에 되지 않아 쓰는 양을 줄이는 편이 낫기 때문입니다. 여섯
     * 자리 숫자가 늘어선 자료라 잘 줄어듭니다.
     *
     * <p><b>임시 파일에 쓴 뒤 옮깁니다.</b> 쓰는 도중에 컨테이너가 죽으면 반쯤 쓰인 파일이
     * 남는데, 다음에 뜰 때 그것을 읽으면 캐시가 통째로 날아간 것과 같아집니다.
     */
    public void save(Path path) throws IOException {
        dirty = false;
        List<String> lines = new ArrayList<>(cache.size());
        for (Map.Entry<String, Answer> e : cache.entrySet()) {
            lines.add(e.getKey() + '\t' + e.getValue().fetchedAt().getEpochSecond()
                    + '\t' + String.join(",", e.getValue().libCodes()));
        }

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out = new BufferedWriter(new OutputStreamWriter(
                new java.util.zip.GZIPOutputStream(Files.newOutputStream(tmp)),
                StandardCharsets.UTF_8))) {
            out.write(HEADER);
            out.write('\n');
            for (String line : lines) {
                out.write(line);
                out.write('\n');
            }
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 파일에서 읽어 들입니다. 없거나 읽을 수 없으면 빈 채로 시작합니다.
     *
     * <p><b>읽지 못한 것을 실패로 다루지 않습니다.</b> 캐시는 있으면 좋은 것이지 없으면
     * 안 되는 것이 아닙니다. 여기서 예외를 밖으로 내보내면 파일 하나 때문에 서버가 뜨지
     * 않게 되는데, 그때 잃는 것이 훨씬 큽니다.
     *
     * <p>이미 만료된 항목은 읽지 않습니다. 어차피 다음 조회에서 다시 부를 것이라, 들여 놓아
     * 봐야 상한만 차지합니다.
     *
     * @return 읽어 들인 항목 수
     */
    public int load(Path path) {
        if (!Files.isReadable(path)) return 0;
        Instant now = clock.instant();
        int loaded = 0;
        try (var in = new BufferedReader(new InputStreamReader(
                new java.util.zip.GZIPInputStream(Files.newInputStream(path)),
                StandardCharsets.UTF_8))) {
            if (!HEADER.equals(in.readLine())) return 0;
            String line;
            while ((line = in.readLine()) != null && cache.size() < maxEntries) {
                // 마지막 칸은 비어 있을 수 있습니다. 「그 지역에 소장한 곳이 없다」도 답이라
                // 남기므로, limit 을 -1 로 주어 끝의 빈 칸이 잘리지 않게 합니다.
                String[] parts = line.split("\t", -1);
                if (parts.length != 3) continue;
                var answer = new Answer(share(parts[2].isEmpty()
                        ? List.of() : List.of(parts[2].split(","))),
                        Instant.ofEpochSecond(Long.parseLong(parts[1])));
                if (!isFresh(answer, now)) continue;
                cache.put(parts[0], answer);
                loaded++;
            }
        } catch (IOException | RuntimeException e) {
            // 반쯤 쓰인 파일이거나 형식이 다른 파일입니다. 읽은 데까지만 쓰고 넘어갑니다.
            return loaded;
        }
        dirty = false;
        return loaded;
    }
}
