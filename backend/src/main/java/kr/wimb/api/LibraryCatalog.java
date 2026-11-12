package kr.wimb.api;

import kr.wimb.data4library.LibraryInfo;
import kr.wimb.data4library.RegionCode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 도서관 마스터. 1,600곳 남짓이라 메모리에 통째로 들고 있고, <b>파일로도 남깁니다.</b>
 *
 * <h2>왜 파일로 남기나</h2>
 *
 * <p>예전에는 메모리에만 있었습니다. 그래서 컨테이너가 다시 뜨는 순간 정보나루의
 * {@code libSrch} 가 답하지 않으면 <b>그 배포 내내 목록이 비었습니다.</b> 2026-11-12 배포에서
 * 실제로 그랬습니다. 같은 시각 서지 검색과 소장 조회는 멀쩡했는데 {@code libSrch} 만 빈
 * 본문과 연결 실패로 답해서, 1,600곳 가운데 275곳만 들어왔습니다. 서울·경기를 포함한 열두
 * 시도의 도서관이 선택 칸에서 사라졌고, 그 도서관을 골라 둔 사람의 소장 조회는 「확인
 * 불가」로 나갔습니다. 소장 캐시와 서가는 볼륨에 남는데 목록만 그렇지 않았습니다.
 *
 * <h2>되살린 것은 예비입니다</h2>
 *
 * <p>뜰 때 파일에서 되살리되, <b>되살린 시도는 「새로 받았다」로 치지 않습니다.</b> 그래서
 * 미리 받기와 5분 간격의 재시도가 예전과 똑같이 돌고, 정보나루가 답하는 시도부터 새 것으로
 * 갈아 끼웁니다. 답하지 않는 시도만 되살린 것을 계속 씁니다. 되살린 것으로 「다 받았다」고
 * 쳐 버리면 그 목록은 영영 갱신되지 않습니다. 새로 생긴 도서관이 안 나오고 문 닫은 도서관이
 * 남는데, 화면에는 아무 이상이 없어 보입니다.
 *
 * <h2>소속을 함께 들고 다닙니다</h2>
 *
 * <p>각 도서관이 어느 시도에 속하는지는 <b>정보나루가 그 시도의 {@code libSrch} 목록에
 * 넣어 준 것</b>이 근거입니다. 소장 조회({@code libSrchByBook})의 {@code region} 이 여기서
 * 나오므로, 되살릴 때 이것을 잃으면 그 도서관은 물어보지도 않은 채 「없음」이 됩니다.
 * 그래서 파일의 줄마다 소속을 적습니다. 주소에서 다시 읽어 내지 않습니다.
 */
public final class LibraryCatalog {

    /** 파일 첫 줄. 형식이 바뀌면 이 값을 올려 예전 파일을 조용히 무시하게 합니다. */
    private static final String HEADER = "# wimb-library-catalog v1";

    /** 한 줄의 칸 수. 소속, 받은 시각, 그리고 {@link LibraryInfo} 의 열한 칸입니다. */
    private static final int COLUMNS = 13;

    /** 값이 없다는 표시. 빈 문자열과 갈라 둡니다. */
    private static final String NULL = "\\N";

    private final Map<String, LibraryInfo> libraries = new ConcurrentHashMap<>();
    private final Map<String, RegionCode> regionOf = new ConcurrentHashMap<>();

    /** 각 시도의 목록을 정보나루에서 받은 시각. 되살린 시도는 <b>그때</b> 받은 시각입니다. */
    private final Map<RegionCode, Instant> fetchedAt = new EnumMap<>(RegionCode.class);

    /**
     * 이 프로세스가 새로 받은 시도. <b>실패한 시도와 되살리기만 한 시도는 여기에 없으므로
     * 다음 기회에 다시 받습니다.</b>
     */
    private final Set<RegionCode> fresh = EnumSet.noneOf(RegionCode.class);

    /** 마지막 저장 이후 바뀐 것이 있는지. 없으면 쓰지 않습니다. */
    private volatile boolean dirty;

    public LibraryInfo get(String libCode) {
        return libraries.get(libCode);
    }

    /** 정보나루가 알려 준 소속. 모르는 도서관이면 비어 있습니다. */
    public Optional<RegionCode> regionOf(String libCode) {
        return Optional.ofNullable(regionOf.get(libCode));
    }

    public Collection<LibraryInfo> all() {
        return libraries.values();
    }

    public int size() {
        return libraries.size();
    }

    public boolean isEmpty() {
        return libraries.isEmpty();
    }

    /** 이 프로세스가 그 시도를 새로 받았는지. 되살린 것은 거짓입니다. */
    public synchronized boolean isFresh(RegionCode region) {
        return fresh.contains(region);
    }

    /** 모든 시도를 새로 받았는지. 하나라도 되살린 것이거나 못 받은 것이면 거짓입니다. */
    public synchronized boolean isComplete() {
        return fresh.size() == RegionCode.values().length;
    }

    /** 되살린 채로 아직 새로 받지 못한 시도 수. */
    public synchronized int restoredRegions() {
        int count = 0;
        for (RegionCode region : fetchedAt.keySet()) {
            if (!fresh.contains(region)) count++;
        }
        return count;
    }

    /** 새로 받은 시도 수. */
    public synchronized int freshRegions() {
        return fresh.size();
    }

    /** 지금 쓰고 있는 목록 가운데 가장 오래전에 받은 시각. 목록이 비었으면 비어 있습니다. */
    public synchronized Optional<Instant> oldestFetchedAt() {
        return fetchedAt.values().stream().min(Instant::compareTo);
    }

    /**
     * 정보나루에서 방금 받은 한 시도의 목록을 받아들입니다.
     *
     * <p>그 시도에 있던 도서관 가운데 새 목록에 없는 것은 뺍니다. 되살린 목록에 남아 있던
     * 문 닫은 도서관이 여기서 사라집니다. <b>새 것을 먼저 넣고 없어진 것을 나중에
     * 뺍니다.</b> 거꾸로 하면 그 사이에 목록을 읽은 요청이 그 시도가 빈 것을 봅니다.
     *
     * <p><b>빈 목록은 되살린 시도를 지우지 못합니다.</b> 도서관이 수십 곳 있던 시도가
     * 갑자기 0곳이 되었다면 그 답이 틀렸을 가능성이 훨씬 큽니다. 받아들이면 그 시도의
     * 도서관이 화면에서 통째로 사라지고 소장 조회는 「확인 불가」가 됩니다. 그때는 되살린
     * 것을 그대로 쓰고 「받지 못했다」로 쳐서 다음에 다시 받습니다.
     *
     * @return 받아들였으면 참. 거짓이면 그 시도는 아직 새로 받지 못한 것입니다
     */
    public synchronized boolean accept(RegionCode region, List<LibraryInfo> received, Instant at) {
        Map<String, LibraryInfo> incoming = new LinkedHashMap<>();
        for (LibraryInfo library : received) {
            if (library.libCode() != null) incoming.put(library.libCode(), library);
        }
        if (incoming.isEmpty() && !fresh.contains(region) && holdsAny(region)) return false;

        incoming.forEach((code, library) -> {
            libraries.put(code, library);
            regionOf.put(code, region);
        });
        for (String code : new ArrayList<>(regionOf.keySet())) {
            if (regionOf.get(code) == region && !incoming.containsKey(code)) {
                regionOf.remove(code);
                libraries.remove(code);
            }
        }
        fetchedAt.put(region, at);
        fresh.add(region);
        dirty = true;
        return true;
    }

    private boolean holdsAny(RegionCode region) {
        return regionOf.containsValue(region);
    }

    // ---------------------------------------------------------------
    // 파일 스냅샷
    // ---------------------------------------------------------------

    public boolean isDirty() {
        return dirty;
    }

    /**
     * 지금 들고 있는 목록을 파일에 씁니다. 새로 받은 시도와 되살린 시도를 함께 씁니다.
     * 되살린 시도를 빼면, 정보나루가 답하지 않는 채로 배포가 두 번 이어질 때 둘째 배포에서
     * 그 시도를 잃습니다.
     *
     * <p><b>임시 파일에 쓴 뒤 옮깁니다.</b> 쓰는 도중에 컨테이너가 죽으면 반쯤 쓰인 파일이
     * 남는데, 다음에 뜰 때 그것을 읽으면 뒤쪽 시도가 통째로 빠집니다.
     */
    public synchronized void save(Path path) throws IOException {
        dirty = false;
        List<String> lines = new ArrayList<>(libraries.size());
        for (LibraryInfo library : libraries.values()) {
            RegionCode region = regionOf.get(library.libCode());
            Instant at = region == null ? null : fetchedAt.get(region);
            if (at == null) continue;
            lines.add(String.join("\t",
                    region.code(), String.valueOf(at.getEpochSecond()),
                    escape(library.libCode()), escape(library.libName()),
                    escape(library.address()), escape(library.tel()), escape(library.fax()),
                    escape(library.latitude()), escape(library.longitude()),
                    escape(library.homepage()), escape(library.closed()),
                    escape(library.operatingTime()), escape(library.bookCount())));
        }
        // 파일이 바뀌지 않았는데 줄 순서만 달라지는 일이 없게 합니다. 사람이 두 파일을
        // 견줘 볼 때 차이가 곧 달라진 도서관이어야 합니다.
        lines.sort(null);

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
     * <p><b>읽지 못한 것을 실패로 다루지 않습니다.</b> 여기서 예외를 밖으로 내보내면 파일
     * 하나 때문에 서버가 뜨지 않습니다. 되살리지 못하면 예전처럼 정보나루에서 받으면 됩니다.
     *
     * <p>읽어 들인 시도는 <b>새로 받은 것으로 치지 않습니다.</b> 이미 새로 받은 시도는
     * 파일의 것으로 덮지 않습니다.
     *
     * @return 되살린 도서관 수
     */
    public synchronized int load(Path path) {
        if (!Files.isReadable(path)) return 0;
        int loaded = 0;
        Set<RegionCode> skipped = new HashSet<>(fresh);
        try (var in = new BufferedReader(new InputStreamReader(
                new java.util.zip.GZIPInputStream(Files.newInputStream(path)),
                StandardCharsets.UTF_8))) {
            if (!HEADER.equals(in.readLine())) return 0;
            String line;
            while ((line = in.readLine()) != null) {
                // 뒤쪽 칸이 비어 있을 수 있으므로 끝의 빈 칸이 잘리지 않게 합니다.
                String[] parts = line.split("\t", -1);
                if (parts.length != COLUMNS) continue;
                Optional<RegionCode> region = RegionCode.ofCode(parts[0]);
                String libCode = unescape(parts[2]);
                // 모르는 지역 코드나 부호 없는 줄은 버립니다. 소속을 지어내지 않습니다.
                if (region.isEmpty() || libCode == null || skipped.contains(region.get())) continue;
                Instant at;
                try {
                    at = Instant.ofEpochSecond(Long.parseLong(parts[1]));
                } catch (NumberFormatException e) {
                    continue;
                }
                libraries.put(libCode, new LibraryInfo(libCode,
                        unescape(parts[3]), unescape(parts[4]), unescape(parts[5]),
                        unescape(parts[6]), asDouble(unescape(parts[7])),
                        asDouble(unescape(parts[8])), unescape(parts[9]), unescape(parts[10]),
                        unescape(parts[11]), asInteger(unescape(parts[12]))));
                regionOf.put(libCode, region.get());
                fetchedAt.merge(region.get(), at, (a, b) -> a.isBefore(b) ? a : b);
                loaded++;
            }
        } catch (IOException | RuntimeException e) {
            // 반쯤 쓰인 파일이거나 형식이 다른 파일입니다. 읽은 데까지만 쓰고 넘어갑니다.
            // 빠진 시도는 정보나루에서 받습니다.
            return loaded;
        }
        return loaded;
    }

    /**
     * 탭과 줄바꿈이 값 안에 있어도 한 줄 한 칸이 깨지지 않게 합니다. 휴관일과 운영 시간은
     * 도서관이 자유롭게 적는 칸이라 무엇이 들어올지 모릅니다.
     */
    private static String escape(Object value) {
        if (value == null) return NULL;
        String text = value.toString();
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescape(String text) {
        if (NULL.equals(text)) return null;
        if (text.indexOf('\\') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 == text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static Double asDouble(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInteger(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
