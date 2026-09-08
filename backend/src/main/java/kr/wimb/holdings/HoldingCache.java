package kr.wimb.holdings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 소장 조회 결과를 (ISBN, 시도) 단위로 들고 있는 캐시.
 *
 * <p><b>버리는 데이터가 아니라 쌓이는 데이터입니다.</b> {@code libSrchByBook} 은 도서관을
 * 지정해 묻는 것이 아니라 그 시도에서 그 책을 소장한 도서관을 통째로 돌려주므로, 한 번
 * 받아 두면 이후에는 어떤 도서관 조합으로 물어보든 교집합만 다시 계산하면 됩니다.
 * 도서관 체크를 바꿔 가며 몇 번을 눌러도 정보나루를 다시 부를 이유가 없습니다.
 *
 * <p><b>도서관부호 문자열을 반드시 공유합니다.</b> 부호는 여섯 자리인데 자바 String 객체
 * 하나가 40바이트를 넘게 쓰므로, 파싱한 문자열을 그대로 들고 있으면 같은 부호가 수만 번
 * 중복 저장됩니다. 실측에서 10만 행(소장 179곳 기준)이 <b>900MB 를 넘겨 담기지 않았고</b>,
 * 공유하면 같은 자료가 90MB 였습니다. 전국에 1,619곳뿐이라 목록에는 참조만 남습니다.
 *
 * <p><b>신선도 한계를 넘겨도 항목을 버리지 않습니다.</b> 다시 부를 나이가 되었다는 뜻일
 * 뿐이고, 다시 부르지 못하면 옛 값을 그대로 씁니다. 소장 정보가 없어 빈 화면을 보여 주는
 * 것보다 언제 기준인지 밝히는 편이 낫기 때문입니다. 그래서 {@link Entry#fetchedAt()} 은
 * 화면까지 올라가야 합니다.
 */
public final class HoldingCache {

    /** 파일 첫 줄. 형식이 바뀌면 이 값을 올려 예전 파일을 조용히 무시하게 합니다. */
    private static final String HEADER = "# wimb-holding-cache v1";

    /** {@code region} 없이 전국을 받은 경우의 키. 시도 코드와 겹치지 않는 값이어야 합니다. */
    public static final String NATIONWIDE = "ALL";

    public record Key(String isbn13, String regionCode) {}

    /**
     * @param fetchedAt 이 답을 정보나루에서 받은 시각. <b>화면에 반드시 표시합니다.</b>
     *                  사용자가 "미소장"을 "확실히 없다"로 읽을지 "한 달 전 기준이다"로
     *                  읽을지가 이 표시 하나에 달려 있습니다.
     */
    public record Entry(List<String> libCodes, Instant fetchedAt) {}

    private final int maxEntries;
    private final Clock clock;

    /**
     * 부호 문자열을 한 벌만 두는 곳. 전국 1,619곳이라 유한합니다.
     *
     * <p>{@link String#intern()} 을 쓰지 않는 것은 그것이 JVM 문자열 풀에 남아 회수가
     * 까다롭기 때문입니다. 여기 두면 캐시와 함께 살고 함께 사라집니다.
     */
    private final Map<String, String> codePool = new HashMap<>();

    /** 접근 순서로 두어 가장 오래 쓰이지 않은 것부터 밀려나게 합니다. */
    private final LinkedHashMap<Key, Entry> entries;

    private boolean dirty;

    public HoldingCache(int maxEntries, Clock clock) {
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                return size() > HoldingCache.this.maxEntries;
            }
        };
    }

    public synchronized Optional<Entry> get(String isbn13, String regionCode) {
        return Optional.ofNullable(entries.get(new Key(isbn13, regionCode)));
    }

    public synchronized void put(String isbn13, String regionCode, List<String> libCodes) {
        entries.put(new Key(isbn13, regionCode), new Entry(share(libCodes), clock.instant()));
        dirty = true;
    }

    /**
     * 이 조합들 가운데 <b>가장 오래된</b> 수집 시각.
     *
     * <p>가장 최근이 아니라 가장 오래된 것을 돌려줍니다. 한 저작의 답은 판본과 시도 여러
     * 건을 합쳐 만드는데, 그중 하나라도 오래된 값이 섞여 있으면 그 답 전체가 그만큼 오래된
     * 것입니다. 가장 최근 시각을 말하면 실제보다 새것처럼 보입니다.
     *
     * @return 하나도 캐시에 없으면 비어 있습니다. 그때는 조회가 전부 실패한 경우입니다.
     */
    public synchronized Optional<Instant> oldestFetchedAt(Collection<String> isbn13List,
                                                          Collection<String> regionCodes) {
        Instant oldest = null;
        for (String isbn : isbn13List) {
            for (String region : regionCodes) {
                Entry entry = entries.get(new Key(isbn, region));
                if (entry == null) continue;
                if (oldest == null || entry.fetchedAt().isBefore(oldest)) oldest = entry.fetchedAt();
            }
        }
        return Optional.ofNullable(oldest);
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 마지막 저장 이후 바뀐 것이 있는지. 바뀐 것이 없으면 파일을 다시 쓰지 않습니다. */
    public synchronized boolean isDirty() {
        return dirty;
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

    /**
     * 지금 내용을 파일에 씁니다. gzip 으로 감싸는 것은 디스크를 아끼려는 것이기도 하지만,
     * 무료 등급 VM 의 표준 영구 디스크가 쓰기 45 IOPS 밖에 되지 않아 쓰는 양을 줄이는
     * 편이 낫기 때문입니다. 여섯 자리 숫자가 늘어선 자료라 잘 줄어듭니다.
     *
     * <p><b>임시 파일에 쓴 뒤 옮깁니다.</b> 쓰는 도중에 컨테이너가 죽으면 반쯤 쓰인 파일이
     * 남는데, 다음에 뜰 때 그것을 읽으면 캐시가 통째로 날아간 것과 같아집니다.
     */
    public void save(Path path) throws IOException {
        List<String> lines;
        synchronized (this) {
            lines = new ArrayList<>(entries.size());
            for (Map.Entry<Key, Entry> e : entries.entrySet()) {
                lines.add(e.getKey().isbn13() + '\t' + e.getKey().regionCode() + '\t'
                        + e.getValue().fetchedAt().getEpochSecond() + '\t'
                        + String.join(",", e.getValue().libCodes()));
            }
            dirty = false;
        }

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            out.write(HEADER);
            out.write('\n');
            for (String line : lines) {
                out.write(line);
                out.write('\n');
            }
        }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 파일에서 읽어 들입니다. 없거나 읽을 수 없으면 빈 채로 시작합니다.
     *
     * <p><b>읽지 못한 것을 실패로 다루지 않습니다.</b> 캐시는 있으면 좋은 것이지 없으면
     * 안 되는 것이 아닙니다. 여기서 예외를 밖으로 내보내면 파일 하나 때문에 서버가 뜨지
     * 않게 되는데, 그때 잃는 것이 훨씬 큽니다.
     *
     * @return 읽어 들인 항목 수
     */
    public int load(Path path) {
        if (!Files.isReadable(path)) return 0;
        int loaded = 0;
        try (var in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            String header = in.readLine();
            if (!HEADER.equals(header)) return 0;
            String line;
            while ((line = in.readLine()) != null) {
                // 마지막 칸은 비어 있을 수 있습니다. 「그 지역에 소장한 곳이 없다」도 답이라
                // 캐시에 남기므로, limit 을 -1 로 주어 끝의 빈 칸이 잘리지 않게 합니다.
                String[] parts = line.split("\t", -1);
                if (parts.length != 4) continue;
                List<String> codes = parts[3].isEmpty() ? List.of() : List.of(parts[3].split(","));
                synchronized (this) {
                    entries.put(new Key(parts[0], parts[1]),
                            new Entry(share(codes), Instant.ofEpochSecond(Long.parseLong(parts[2]))));
                }
                loaded++;
            }
        } catch (IOException | RuntimeException e) {
            // 반쯤 쓰인 파일이나 형식이 다른 파일입니다. 읽은 데까지만 쓰고 넘어갑니다.
            return loaded;
        }
        synchronized (this) {
            dirty = false;
        }
        return loaded;
    }
}
