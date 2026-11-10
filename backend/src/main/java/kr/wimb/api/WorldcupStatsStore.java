package kr.wimb.api;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.wimb.worldcup.WorldcupRosters;
import kr.wimb.worldcup.WorldcupStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 월드컵 집계를 파일에 남겨 재배포를 넘겨 살립니다.
 *
 * <p>{@link HoldingCacheStore} 와 같은 방식이고 같은 볼륨을 씁니다. <b>종료 훅만 믿지 않고
 * 주기적으로도 저장합니다.</b> 컨테이너가 SIGKILL 로 바뀌면 종료 훅이 돌지 않습니다.
 * 소장 캐시는 잃어도 다시 물어보면 되지만 <b>이 집계는 다시 받을 곳이 없으므로</b> 저장
 * 간격을 더 짧게 둡니다. 바뀐 것이 없으면 쓰지 않습니다.
 *
 * <p><b>볼륨이 붙지 않은 채 뜨면 집계가 0 에서 다시 시작합니다.</b> 화면에는 「첫 번째
 * 우승」으로만 보여서 고장으로 읽히지 않습니다. 뜰 때 몇 줄을 되살렸는지 남기는 이유입니다.
 */
@Component
public class WorldcupStatsStore {

    private static final Logger log = LoggerFactory.getLogger(WorldcupStatsStore.class);

    private final WorldcupStats stats;
    private final Path path;

    /**
     * 서가를 수집하러 띄운 컨테이너인지. <b>그때는 되살리지도 저장하지도 않습니다.</b>
     * 같은 볼륨을 붙이고 뜨는데, 되살리지 않은 빈 집계를 저장하면 서비스 중인 서버의
     * 파일을 덮어씁니다.
     */
    private final boolean harvesting;

    public WorldcupStatsStore(@Value("${wimb.worldcup.stats-path:data/worldcup-stats.tsv}") String path,
                              @Value("${wimb.shelf.harvest:}") String harvest) {
        this.stats = new WorldcupStats(WorldcupRosters.fromClasspath());
        this.path = Path.of(path);
        this.harvesting = harvest != null && !harvest.isBlank();
    }

    public WorldcupStats stats() {
        return stats;
    }

    @PostConstruct
    void restore() {
        if (harvesting) return;
        int loaded = stats.load(path);
        log.info("월드컵 집계 {}줄을 {} 에서 되살렸습니다.", loaded, path.toAbsolutePath());
    }

    @Scheduled(initialDelayString = "${wimb.worldcup.save-interval-ms:15000}",
            fixedDelayString = "${wimb.worldcup.save-interval-ms:15000}")
    void persist() {
        flush();
    }

    @PreDestroy
    void persistOnShutdown() {
        flush();
    }

    private void flush() {
        if (harvesting || !stats.isDirty()) return;
        try {
            stats.save(path, LocalDate.now(RateLimit.ZONE));
        } catch (IOException | RuntimeException e) {
            // 저장에 실패해도 서비스는 계속됩니다. 다만 조용히 넘어가면 재배포 뒤에야
            // 집계가 사라진 것을 알게 되므로 남깁니다.
            log.warn("월드컵 집계를 {} 에 저장하지 못했습니다: {}", path.toAbsolutePath(), e.toString());
        }
    }
}
