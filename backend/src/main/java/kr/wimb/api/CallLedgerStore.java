package kr.wimb.api;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.wimb.ingest.DailyCallLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 그날 정보나루를 부른 건수를 파일에 남겨, 서버가 다시 떠도 이어서 세게 합니다.
 *
 * <p>메모리에만 두던 때에는 컨테이너가 새로 뜰 때마다 건수가 0 으로 돌아갔습니다. 배포가
 * 잦은 날에는 {@code /api/status} 의 {@code callsUsedToday} 가 실제보다 훨씬 작았고, 서가를
 * 세울지 정하는 쪽이 남은 몫을 실제보다 넉넉하게 봤습니다.
 *
 * <p>{@link HoldingCacheStore} 와 같은 방식이고 같은 이유입니다. <b>종료 훅만으로는 안
 * 됩니다.</b> {@code docker rm -f} 는 SIGKILL 을 바로 보내므로 주기적으로도 저장합니다.
 * <b>파일은 컨테이너 밖(볼륨)에 있어야 합니다.</b> 이미지가
 * {@code WIMB_DATA4LIBRARY_LEDGER_PATH=/data/call-ledger.tsv} 로 덮습니다.
 *
 * <p>저장과 저장 사이에 죽으면 그 사이의 건수는 잃습니다. 그만큼 실제보다 적게 셉니다.
 * 바뀐 것이 없으면 쓰지 않습니다.
 */
@Component
public class CallLedgerStore {

    private static final Logger log = LoggerFactory.getLogger(CallLedgerStore.class);

    private final DailyCallLedger ledger;
    private final Path path;

    /**
     * 서가를 수집하러 띄운 컨테이너인지. <b>그때는 이 파일을 읽지도 쓰지도 않습니다.</b>
     *
     * <p>수집 컨테이너는 서비스하는 서버와 같은 볼륨을 붙이고 따로 돕니다. 둘이 같은 파일에
     * 저마다 센 값을 적으면 나중에 적은 쪽이 앞의 값을 덮어씁니다. 수집이 끝나며 적은 값이
     * 남으면 서버가 그날 센 건수가 사라지고, 서버가 다음 주기에 다시 덮으면 수집의 건수가
     * 사라집니다. 어느 쪽도 맞는 합이 아닙니다. 그래서 파일은 서버만 씁니다.
     * <b>수집 컨테이너가 부른 건수는 서버의 원장에 들지 않습니다.</b> 이것은 예전과 같습니다.
     */
    private final boolean harvesting;

    public CallLedgerStore(DailyCallLedger ledger,
                           @Value("${wimb.data4library.ledger-path:data/call-ledger.tsv}")
                           String path,
                           @Value("${wimb.shelf.harvest:}") String harvest) {
        this.ledger = ledger;
        this.path = Path.of(path);
        this.harvesting = harvest != null && !harvest.isBlank();
    }

    @PostConstruct
    void restore() {
        if (harvesting) {
            log.info("서가를 수집하는 중이라 호출 원장을 이어받지 않습니다.");
            return;
        }
        int carried = ledger.load(path);
        // 몇 건을 이어받았는지 남깁니다. 배포 직후 callsUsedToday 가 0 일 때, 오늘 처음 뜬
        // 것인지 파일을 잃은 것인지를 이 줄로 가릅니다.
        log.info("오늘 정보나루를 부른 건수 {}건을 {} 에서 이어받았습니다.",
                carried, path.toAbsolutePath());
    }

    @Scheduled(initialDelayString = "${wimb.data4library.ledger-save-interval-ms:60000}",
            fixedDelayString = "${wimb.data4library.ledger-save-interval-ms:60000}")
    void persist() {
        flush();
    }

    @PreDestroy
    void persistOnShutdown() {
        flush();
    }

    private void flush() {
        if (harvesting || !ledger.isDirty()) return;
        try {
            ledger.save(path);
        } catch (IOException | RuntimeException e) {
            // 저장에 실패해도 서비스는 계속됩니다. 다만 조용히 넘어가면 다시 뜬 뒤에야
            // 건수가 0 인 것을 보게 되므로 남깁니다.
            log.warn("호출 원장을 {} 에 저장하지 못했습니다: {}", path.toAbsolutePath(), e.toString());
        }
    }
}
