package kr.wimb.api;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 도서관 목록을 파일에 남겨 재배포를 넘겨 살립니다. {@link HoldingCacheStore} 와 같은
 * 방식이고 같은 볼륨을 씁니다. 왜 필요한지는 {@link LibraryCatalog} 에 적혀 있습니다.
 *
 * <p><b>종료 훅만으로는 안 됩니다.</b> 컨테이너를 바꿀 때 SIGKILL 이 오면 {@link PreDestroy}
 * 가 돌 기회가 없으므로 주기적으로도 저장합니다. 목록은 새 시도를 받았을 때만 바뀌므로
 * 실제로 쓰는 것은 배포 뒤 한두 번입니다.
 *
 * <p><b>파일은 컨테이너 밖에 있어야 합니다.</b> 이미지가
 * {@code WIMB_CATALOG_PATH=/data/library-catalog.tsv.gz} 로 덮고 배포 스크립트가 거기에
 * 볼륨을 붙입니다.
 */
@Component
public class LibraryCatalogStore {

    private static final Logger log = LoggerFactory.getLogger(LibraryCatalogStore.class);

    private final LibraryCatalog catalog;
    private final Path path;

    /**
     * 서가를 수집하러 띄운 컨테이너인지. 그때는 되살리지 않습니다. 수집 컨테이너는 도서관
     * 목록을 쓰지 않고, 되살리지 않으면 바뀐 것도 없어서 서버가 쓰는 파일을 덮어쓸 일이
     * 없습니다. {@link HoldingCacheStore} 가 같은 이유로 같은 일을 합니다.
     */
    private final boolean harvesting;

    public LibraryCatalogStore(LibraryCatalog catalog,
                               @Value("${wimb.catalog.path:data/library-catalog.tsv.gz}")
                               String path,
                               @Value("${wimb.shelf.harvest:}") String harvest) {
        this.catalog = catalog;
        this.path = Path.of(path);
        this.harvesting = harvest != null && !harvest.isBlank();
    }

    @PostConstruct
    void restore() {
        if (harvesting) {
            log.info("서가를 수집하는 중이라 도서관 목록을 되살리지 않습니다.");
            return;
        }
        int loaded = catalog.load(path);
        // 몇 곳을 되살렸는지 남깁니다. 배포 직후 목록이 비어 보일 때, 파일이 없었던 것인지
        // 읽지 못한 것인지를 여기서 가릅니다. 0이면 볼륨부터 보세요.
        log.info("도서관 목록 {}곳(시도 {}개)을 {} 에서 되살렸습니다. 정보나루가 답하는 시도부터 새로 받습니다.",
                loaded, catalog.restoredRegions(), path.toAbsolutePath());
    }

    @Scheduled(initialDelayString = "${wimb.catalog.save-interval-ms:60000}",
            fixedDelayString = "${wimb.catalog.save-interval-ms:60000}")
    void persist() {
        flush();
    }

    @PreDestroy
    void persistOnShutdown() {
        flush();
    }

    void flush() {
        if (!catalog.isDirty()) return;
        try {
            catalog.save(path);
            log.info("도서관 목록 {}곳을 저장했습니다(새로 받은 시도 {}개, 되살린 채인 시도 {}개).",
                    catalog.size(), catalog.freshRegions(), catalog.restoredRegions());
        } catch (IOException | RuntimeException e) {
            // 저장에 실패해도 서비스는 계속됩니다. 다만 조용히 넘어가면 다음 배포에서
            // 목록이 비고 나서야 알게 되므로 남깁니다.
            log.warn("도서관 목록을 {} 에 저장하지 못했습니다: {}", path.toAbsolutePath(), e.toString());
        }
    }
}
