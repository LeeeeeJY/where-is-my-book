package kr.wimb.shelf;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.ApiBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 서가를 <b>언제 세울지</b> 정합니다.
 *
 * <h2>미리 세워 두지 않습니다</h2>
 *
 * <p>전국 1,619곳을 미리 받으면 48만 회에 스무 날, 디스크 65GB 입니다. 무료 등급 VM
 * 에 들어가지 않습니다. 그래서 <b>사람이 여는 서가만</b> 세웁니다. 아무도 열지 않는
 * 도서관에는 호출도 디스크도 한 건 쓰지 않습니다.
 *
 * <pre>
 *   사람이 서가를 엶
 *     ├─ 세워 둔 것이 있다 ──────────────→ 바로 보여 줌 (호출 0회)
 *     │    └─ 기한이 지났으면 뒤에서 다시 세움 (보여 주는 것은 안 멈춤)
 *     └─ 없다 ─→ 지금 세움 (40~200회, 15초~1분) → 다음 사람부터는 0회
 * </pre>
 *
 * <h2>낡았다고 지우지 않습니다</h2>
 *
 * <p>이 저장소가 소장 캐시에서 쓰는 규칙과 같습니다. 기한이 지난 서가도 <b>기준일과
 * 함께 그대로 내보내고 갱신은 뒤에서</b> 합니다. 지우고 다시 세우면 그동안 서가가
 * 비어 사람이 기다리는데, 20일 전 서가라도 빈 화면보다 낫습니다. 기준일이 화면에
 * 적혀 있으니 거짓말도 아닙니다.
 *
 * <h2>같은 서가를 두 사람이 열어도 한 번만 세웁니다</h2>
 *
 * <p>세우는 데 몇십 초가 걸리므로 그 사이에 다른 사람이 같은 서가를 열 수 있습니다.
 * 그때마다 새로 시작하면 <b>같은 일을 겹쳐 하면서 호출만 몇 배로 씁니다.</b> 이미
 * 돌고 있으면 그 진행 상황을 함께 봅니다.
 */
public class ShelfService {

    private static final Logger log = LoggerFactory.getLogger(ShelfService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ShelfStore store;
    private final ShelfHarvester harvester;
    private final ApiBudget budget;
    private final Clock clock;

    /** 다시 세울 때가 되었는지 보는 기한(일). */
    private final int refreshDays;

    /**
     * 서가를 세우기 전에 남겨 두어야 하는 하루 몫.
     *
     * <p>서가 하나가 수백 회입니다. 남은 것을 여기에 다 쓰면 <b>그날 검색이 통째로
     * 막힙니다.</b> 서가는 내일 세워도 되지만 검색은 그 사람이 지금 기다리고 있습니다.
     */
    private final int reserve;

    /**
     * 한꺼번에 세울 수 있는 서가 수.
     *
     * <p><b>막아 두지 않으면 사람 다섯이 서로 다른 서가를 열 때 호출 천 건이 한꺼번에
     * 줄을 섭니다.</b> 정보나루에 동시에 나가는 수는 전송 계층이 따로 묶지만, 줄이
     * 길어지면 그 뒤에 선 <b>검색이 몇 분을 기다립니다.</b> 사람이 기다리는 검색을
     * 서가 세우기보다 뒤에 둘 수는 없습니다.
     */
    private final Semaphore slots;

    /** 지금 세우고 있는 서가. 같은 것을 두 번 시작하지 않으려고 둡니다. */
    private final Map<String, ShelfHarvester.Progress> building = new ConcurrentHashMap<>();

    /**
     * 세우는 일을 맡는 곳. <b>요청을 처리하는 스레드에서 세우지 않습니다.</b> 몇십 초가
     * 걸리는 일이라 그대로 두면 요청이 그만큼 잡혀 있고, 사람은 화면이 멈춘 것으로
     * 읽습니다. 시작만 하고 바로 답한 뒤 진행률을 따로 물어보게 합니다.
     */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ShelfService(ShelfStore store, ShelfHarvester harvester, ApiBudget budget,
                        int refreshDays, int maxConcurrentBuilds, int reserve, Clock clock) {
        this.store = store;
        this.harvester = harvester;
        this.budget = budget;
        this.refreshDays = refreshDays;
        this.reserve = reserve;
        this.slots = new Semaphore(Math.max(1, maxConcurrentBuilds));
        this.clock = clock;
    }

    /** 서가가 지금 어떤 상태인지. */
    public enum State {
        /** 세워져 있어 바로 볼 수 있습니다. */
        READY,
        /** 지금 세우는 중입니다. 진행률이 함께 옵니다. */
        BUILDING,
        /** 아직 아무도 열지 않았습니다. <b>고장이 아닙니다.</b> */
        ABSENT
    }

    /**
     * @param stale   세워져 있지만 기한이 지난 것. <b>보여 주기는 그대로 보여 줍니다.</b>
     *                갱신은 뒤에서 돕니다
     * @param percent 세우는 중일 때의 진행률. 아니면 0 입니다
     */
    public record Status(State state, boolean stale, int percent, int books) {}

    /**
     * 그 서가의 상태를 봅니다. <b>여기서는 세우지 않습니다.</b>
     *
     * <p>세우는 일은 호출을 수백 번 쓰므로 그냥 들여다보는 것과 값이 완전히 다릅니다.
     * 둘을 한 통로에 두면 주소 한도(무게)를 어느 쪽에 맞춰도 틀립니다. 낮게 잡으면
     * 아무나 수백 회를 태울 수 있고, 높게 잡으면 진행률을 몇 번 물어보다 막힙니다.
     */
    public Status status(String libCode, Kdc kdc) {
        ShelfHarvester.Progress running = building.get(keyOf(libCode, kdc));
        if (running != null) {
            return new Status(State.BUILDING, false, running.percent(), running.books());
        }

        Optional<ShelfMeta> meta = readMeta(libCode, kdc);
        if (meta.isEmpty()) return new Status(State.ABSENT, false, 0, 0);

        boolean stale = isStale(meta.get());
        if (stale) refreshInBackground(libCode, kdc);
        return new Status(State.READY, stale, 100, meta.get().count());
    }

    /**
     * 서가를 세우기 시작합니다. <b>이미 있거나 돌고 있으면 아무 일도 하지 않습니다.</b>
     *
     * @return 시작한 뒤의 상태
     */
    public Status build(String libCode, Kdc kdc) {
        Status now = status(libCode, kdc);
        if (now.state() != State.ABSENT) return now;

        // **하루 몫이 얼마 안 남았으면 시작하지 않습니다.** 서가 하나가 수백 회라,
        // 남은 것을 여기에 다 쓰면 그날 검색이 통째로 막힙니다. 서가는 내일 세워도
        // 되지만 검색은 그 사람이 지금 기다리고 있습니다.
        if (!hasRoomToBuild()) {
            log.info("오늘 몫이 얼마 남지 않아 서가를 세우지 않습니다(남은 {}건): 도서관 {}, {}",
                    budget.remaining(Data4LibraryClient.SOURCE_CODE), libCode, kdc.label());
            return new Status(State.ABSENT, false, 0, 0);
        }

        startBuild(libCode, kdc, false);
        return status(libCode, kdc);
    }

    // ── 실제로 세우는 곳 ─────────────────────────────────────────────────

    /**
     * 기한이 지난 서가를 뒤에서 다시 세웁니다. <b>보여 주는 것을 멈추지 않습니다.</b>
     * 다 세우면 그때 갈아 끼우므로, 그 사이에 여는 사람은 예전 서가를 봅니다.
     */
    private void refreshInBackground(String libCode, Kdc kdc) {
        if (!hasRoomToBuild()) return;
        startBuild(libCode, kdc, true);
    }

    private void startBuild(String libCode, Kdc kdc, boolean refresh) {
        String key = keyOf(libCode, kdc);
        ShelfHarvester.Progress progress = new ShelfHarvester.Progress();
        // 이미 누가 시작했으면 그대로 둡니다. 같은 서가를 두 번 세우면 호출만 두 배입니다.
        if (building.putIfAbsent(key, progress) != null) return;

        workers.submit(() -> {
            // **자리를 얻지 못하면 기다립니다.** 여기서 포기하면 화면은 「세우는 중」인데
            // 아무도 세우지 않는 상태가 됩니다. 기다리는 동안 진행률은 0 이고, 화면은
            // 그것을 「아직 시작 전」으로 그립니다.
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                building.remove(key);
                return;
            }

            try {
                if (refresh && canSkipRebuild(libCode, kdc)) {
                    // 권수가 그대로면 다시 세우지 않고 확인한 날짜만 올립니다. 호출
                    // 한 번으로 수십~수백 번을 아낍니다. **기준일은 건드리지 않습니다.**
                    // 받아 온 적 없는 날짜를 받아 온 것처럼 말하게 됩니다.
                    touchChecked(libCode, kdc);
                    log.info("서가가 그대로라 다시 세우지 않았습니다: 도서관 {}, {}",
                            libCode, kdc.label());
                    return;
                }
                harvester.harvest(libCode, kdc, progress);
            } catch (Exception e) {
                // **실패해도 예전 서가는 그대로입니다.** 수집기가 다 만든 뒤에 갈아
                // 끼우므로, 여기서 죽어도 사람이 보던 것이 사라지지 않습니다.
                log.warn("서가를 세우지 못했습니다: 도서관 {}, {}: {}", libCode, kdc.label(), e.toString());
            } finally {
                slots.release();
                building.remove(key);
            }
        });
    }

    /**
     * 다시 세우지 않고 <b>확인한 날짜만 올려도 되는지.</b>
     *
     * <p>둘을 함께 봅니다. <b>책이 그대로인가</b>와 <b>순서를 적은 규칙이 그대로인가</b>
     * 입니다. 권수는 한 번 물어보면 알 수 있어서 수십~수백 번을 아낍니다. 신착과 폐기가
     * 같은 수만큼 일어나면 못 잡지만, 대부분의 날에는 아무것도 안 바뀌고 못 잡은 것도
     * 기한이 다시 차면 어차피 세우게 됩니다.
     *
     * <p><b>뒤엣것이 없으면 정렬 규칙을 고쳐도 서가가 영영 예전 순서로 남습니다.</b>
     * 권수는 그대로일 테니 여기서 늘 건너뛰기 때문입니다. 화면에는 아무 이상이 없어
     * 보여서, 규칙을 고친 사람은 고쳐졌다고 믿습니다. 찾기 색인이 빠진 서가도
     * 마찬가지라 {@link #outOfDate} 가 둘을 함께 봅니다.
     */
    private boolean canSkipRebuild(String libCode, Kdc kdc) {
        Optional<ShelfMeta> meta = readMeta(libCode, kdc);
        if (meta.isEmpty() || outOfDate(meta.get())) return false;
        try {
            return harvester.countOf(libCode, kdc) == meta.get().reported();
        } catch (RuntimeException e) {
            // 물어보지 못했으면 그대로인지 알 수 없습니다. 모를 때는 세우는 쪽입니다.
            return false;
        }
    }

    /**
     * 오늘 몫이 서가를 세울 만큼 남았는지. <b>예산을 깎지 않고 보기만 합니다.</b>
     * 실제로 깎는 것은 호출마다 전송 계층이 합니다.
     */
    private boolean hasRoomToBuild() {
        return budget.remaining(Data4LibraryClient.SOURCE_CODE) > reserve;
    }

    // ── 거들기 ───────────────────────────────────────────────────────────

    private boolean isStale(ShelfMeta meta) {
        if (outOfDate(meta)) return true;
        try {
            return meta.checkedOn().plusDays(refreshDays).isBefore(today());
        } catch (RuntimeException e) {
            // 날짜를 못 읽으면 낡은 것으로 봅니다. 모를 때는 다시 세우는 쪽입니다.
            return true;
        }
    }

    /**
     * 날짜와 상관없이 <b>다시 세워야 하는</b> 서가인지.
     *
     * <p>둘 다 수집할 때 한 번 적히고 그 뒤로는 파일에 그대로 남는 것이라, 코드만
     * 고쳐서는 이미 세워 둔 서가가 <b>영영 예전 모양으로 남습니다.</b> 게다가 권수가
     * 그대로면 갱신도 건너뛰므로 아무도 손대지 않으면 영영입니다.
     *
     * <ul>
     *   <li><b>순서 규칙의 판 번호</b>({@link ShelfSortKey#VERSION})가 다르면 서가가
     *       예전 순서로 서 있습니다. 화면에는 아무 이상이 없어 보입니다</li>
     *   <li><b>적어 둔 모양의 판 번호</b>({@link ShelfMeta#SHAPE_VERSION})가 다르면
     *       나중에 늘린 것이 그 서가에는 없습니다. 찾기 색인과 갈래 구간이 그렇습니다.
     *       화면은 그 단추를 내지 않아 사람이 막다른 길을 만나지는 않지만, 다시
     *       세우기 전에는 그 상태가 이어집니다</li>
     * </ul>
     *
     * <p><b>기능마다 불리언을 하나씩 늘리지 마세요.</b> 낡았는지 보는 자리가 흩어지고,
     * 그중 하나를 빠뜨리면 그 서가는 영영 예전 모양으로 남습니다.
     */
    private boolean outOfDate(ShelfMeta meta) {
        return meta.keyVersion() != ShelfSortKey.VERSION
                || meta.shapeVersion() != ShelfMeta.SHAPE_VERSION;
    }

    private void touchChecked(String libCode, Kdc kdc) {
        readMeta(libCode, kdc).ifPresent(meta -> {
            try {
                harvester.rewriteCheckedAt(libCode, kdc, meta, today().toString());
            } catch (Exception e) {
                // 날짜만 못 고친 것이라 서가는 멀쩡합니다. 다음 기회에 다시 봅니다.
                log.debug("확인 날짜를 고치지 못했습니다: {}", e.toString());
            }
        });
    }

    private Optional<ShelfMeta> readMeta(String libCode, Kdc kdc) {
        return store.meta(libCode, kdc).flatMap(ShelfMetaReader::parse);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }

    private static String keyOf(String libCode, Kdc kdc) {
        return libCode + "/" + kdc.code();
    }
}
