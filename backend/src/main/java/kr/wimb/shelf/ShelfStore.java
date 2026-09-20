package kr.wimb.shelf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 적어 둔 서가를 읽어 화면에 넘깁니다.
 *
 * <h2>힙에 담아 두지 않습니다</h2>
 *
 * <p>서가 하나가 20만 권이고 파일로는 50MB 안팎입니다. 기계가 메모리 1GB 라 그것을
 * 들고 있을 수 없고, 들고 있을 이유도 없습니다. <b>화면이 한 번에 보는 것은 조각
 * 하나(200권, 45KB)뿐</b>이라 그때 파일에서 읽으면 됩니다. 자주 읽는 조각은 운영체제가
 * 알아서 기억합니다.
 *
 * <p>그래서 <b>요청마다 디스크를 봅니다.</b> 차림표를 메모리에 담아 두면 수집이
 * 끝났는데도 예전 값을 답하게 되는데, 수집은 다른 컨테이너에서 도는 일이라 이쪽이
 * 그것을 알 방법이 없습니다. 작은 파일 하나 읽는 값보다 「고쳤는데 안 바뀐다」를
 * 쫓는 값이 훨씬 비쌉니다.
 *
 * <h2>주소로 받은 값을 경로에 그대로 쓰지 않습니다</h2>
 *
 * <p>도서관부호와 자료실 자리와 조각 번호가 전부 파일 경로가 됩니다. 받은 값을
 * 그대로 이으면 {@code ../../etc/passwd} 같은 것이 들어와 서버 안의 아무 파일이나
 * 읽힙니다. 그래서 <b>모양이 맞는 것만 통과시킵니다.</b> 걸러 내는 것이 아니라
 * 허락한 모양만 받는 쪽입니다.
 */
public class ShelfStore {

    /** 도서관부호. 정보나루가 주는 것은 숫자뿐입니다. */
    private static final Pattern LIB_CODE = Pattern.compile("[0-9]{1,10}");

    /** 자료실 자리. 수집기가 {@code r0}, {@code r1} 처럼 붙입니다. */
    private static final Pattern ROOM_SLUG = Pattern.compile("r[0-9]{1,4}");

    private final Path dataDir;

    public ShelfStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 그 도서관에 이미 세워 둔 대주제. <b>없는 것은 아직 아무도 열지 않은 것입니다.</b>
     * 고장이 아니므로 화면이 그렇게 말합니다.
     */
    public List<String> builtSubjects(String libCode) {
        if (!LIB_CODE.matcher(libCode).matches()) return List.of();
        Path dir = dataDir.resolve(libCode);
        if (!Files.isDirectory(dir)) return List.of();

        List<String> out = new ArrayList<>();
        for (Kdc kdc : Kdc.all()) {
            if (Files.isReadable(dir.resolve(kdc.slug()).resolve("meta.json"))) out.add(kdc.code());
        }
        return out;
    }

    /** 차림표. 그 서가를 아직 세우지 않았으면 비어 있습니다. */
    public Optional<byte[]> meta(String libCode, Kdc kdc) {
        if (!LIB_CODE.matcher(libCode).matches()) return Optional.empty();
        return read(dataDir.resolve(libCode).resolve(kdc.slug()).resolve("meta.json"));
    }

    /** 조각 하나. 화면이 스크롤하면서 받아 가는 것입니다. */
    public Optional<byte[]> chunk(String libCode, Kdc kdc, String roomSlug, int index) {
        if (!LIB_CODE.matcher(libCode).matches()) return Optional.empty();
        if (!ROOM_SLUG.matcher(roomSlug).matches()) return Optional.empty();
        if (index < 0) return Optional.empty();
        return read(dataDir.resolve(libCode).resolve(kdc.slug())
                .resolve(roomSlug).resolve(index + ".json"));
    }

    /**
     * 그 자료실 안에서 표제나 저자로 <b>자리 번호</b>를 찾습니다.
     *
     * <p>세워 둘 때 함께 적어 둔 색인 파일만 읽습니다. <b>정보나루를 부르지 않고
     * 조각도 열지 않습니다.</b> 자세한 규칙은 {@link ShelfFind} 에 있습니다.
     *
     * <p><b>색인이 없으면 비어 있습니다.</b> 색인이 생기기 전에 세운 서가가 그렇고,
     * 그 서가는 {@code findable} 이 내려가 있어 서버가 뒤에서 다시 세웁니다.
     * 읽다가 실패한 것도 같은 답입니다. 어느 쪽이든 <b>「이 서가에는 없다」로 답하면
     * 안 됩니다.</b> 물어보지 못한 것을 없다고 말하는 일이라, 이 저장소가 소장 조회에서
     * 「미소장」과 「확인 불가」를 갈라 두는 것과 같은 자리입니다.
     */
    public Optional<ShelfFind.Result> find(String libCode, Kdc kdc, String roomSlug,
                                           String query, int limit) {
        if (!LIB_CODE.matcher(libCode).matches()) return Optional.empty();
        if (!ROOM_SLUG.matcher(roomSlug).matches()) return Optional.empty();

        Path path = inside(dataDir.resolve(libCode).resolve(kdc.slug())
                .resolve(roomSlug).resolve(ShelfFind.FILE));
        if (path == null) return Optional.empty();

        // 통째로 읽지 않고 흘려보냅니다. 한 서가가 10만 권이면 색인도 10MB 입니다.
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return Optional.of(ShelfFind.scan(lines, query, limit));
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    /** {@code /api/status} 가 내보냅니다. 배포 뒤에 서가를 잃지 않았는지 봅니다. */
    public int size() {
        if (!Files.isDirectory(dataDir)) return 0;
        try (Stream<Path> dirs = Files.list(dataDir)) {
            int total = 0;
            for (Path dir : dirs.toList()) {
                total += builtSubjects(dir.getFileName().toString()).size();
            }
            return total;
        } catch (IOException e) {
            return 0;
        }
    }

    private Optional<byte[]> read(Path path) {
        Path resolved = inside(path);
        if (resolved == null) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(resolved));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 읽어도 되는 자리인지 마지막으로 한 번 더 봅니다. 아니면 {@code null} 입니다.
     *
     * <p>모양을 이미 걸렀지만 규칙이 늘어나면서 구멍이 생기는 자리라, <b>실제로 읽기
     * 직전에</b> 데이터 자리 안인지 확인하는 편이 안전합니다.
     */
    private Path inside(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        if (!resolved.startsWith(dataDir.toAbsolutePath().normalize())) return null;
        return Files.isReadable(resolved) ? resolved : null;
    }
}
