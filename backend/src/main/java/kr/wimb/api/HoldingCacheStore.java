package kr.wimb.api;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.wimb.holdings.HoldingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 소장 캐시를 파일에 남겨 재배포를 넘겨 살립니다.
 *
 * <p><b>종료 훅만으로는 안 됩니다.</b> 배포 스크립트가 컨테이너를 바꿀 때 쓰는
 * {@code docker rm -f} 는 SIGKILL 을 바로 보내므로 {@link PreDestroy} 가 돌 기회가 없습니다.
 * 배포마다 그날 쌓은 캐시가 통째로 사라지면 캐시를 둔 보람이 없어지므로 <b>주기적으로도</b>
 * 저장합니다. 종료 훅은 {@code docker stop} 처럼 유예를 주는 경우를 위해 함께 둡니다.
 *
 * <p><b>그리고 파일은 반드시 컨테이너 밖에 두어야 합니다.</b> 컨테이너 파일 시스템은
 * 컨테이너와 함께 사라지므로, 볼륨을 붙이지 않으면 주기적으로 저장해도 재배포 때 같이
 * 없어집니다. {@code scripts/vm/deploy.sh} 가 {@code /data} 를 붙입니다.
 *
 * <p>바뀐 것이 없으면 쓰지 않습니다. 무료 등급 VM 의 표준 영구 디스크가 쓰기 45 IOPS
 * 밖에 되지 않아, 같은 내용을 되풀이해 쓰면 사람이 기다리는 요청의 몫을 갉아먹습니다.
 */
@Component
public class HoldingCacheStore {

    private static final Logger log = LoggerFactory.getLogger(HoldingCacheStore.class);

    private final HoldingCache cache;
    private final Path path;

    public HoldingCacheStore(HoldingCache cache,
                             @Value("${wimb.holdings.cache-path:data/holding-cache.tsv.gz}")
                             String path) {
        this.cache = cache;
        this.path = Path.of(path);
    }

    @PostConstruct
    void restore() {
        int loaded = cache.load(path);
        // 몇 건을 되살렸는지 남깁니다. 이것이 없으면 캐시가 살아 돌아온 것인지 매번 처음부터
        // 쌓는 것인지 구별할 수 없어, 호출이 줄지 않는 이유를 추측하게 됩니다.
        log.info("소장 캐시 {}건을 {} 에서 되살렸습니다.", loaded, path.toAbsolutePath());
    }

    @Scheduled(initialDelayString = "${wimb.holdings.save-interval-ms:60000}",
            fixedDelayString = "${wimb.holdings.save-interval-ms:60000}")
    void persist() {
        flush();
    }

    @PreDestroy
    void persistOnShutdown() {
        flush();
    }

    private void flush() {
        if (!cache.isDirty()) return;
        try {
            cache.save(path);
            log.debug("소장 캐시 {}건을 저장했습니다.", cache.size());
        } catch (IOException | RuntimeException e) {
            // 저장에 실패해도 서비스는 계속됩니다. 캐시는 있으면 좋은 것이지 없으면 안 되는
            // 것이 아닙니다. 다만 조용히 넘어가면 재배포 뒤에야 알게 되므로 남깁니다.
            log.warn("소장 캐시를 {} 에 저장하지 못했습니다: {}", path.toAbsolutePath(), e.toString());
        }
    }
}
