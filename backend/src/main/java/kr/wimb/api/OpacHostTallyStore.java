package kr.wimb.api;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.wimb.opac.DetailResolver;
import kr.wimb.opac.OpacHostTally;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 호스트별 상세 집계({@link OpacHostTally})를 파일에 남겨, 서버가 다시 떠도 이어서 세게 합니다.
 *
 * <p>이 집계가 답하려는 것은 「그 호스트가 운영에서 얼마나 자주 검색 결과로 내려가는가」이고, 누르는
 * 사람이 많지 않아 며칠을 쌓아야 보입니다. 그런데 병합할 때마다 새 이미지가 뜨므로 메모리에만 두면
 * 규칙을 넣는 날마다 0 으로 돌아갑니다.
 *
 * <p>{@link CallLedgerStore} 와 같은 방식이고 같은 이유입니다. <b>종료 훅만으로는 안 됩니다.</b>
 * {@code docker rm -f} 는 SIGKILL 을 바로 보내므로 주기적으로도 저장합니다. <b>파일은 컨테이너
 * 밖(볼륨)에 있어야 합니다.</b> 이미지가 {@code WIMB_OPAC_HOST_TALLY_PATH=/data/opac-host-tally.tsv}
 * 로 덮습니다. 저장과 저장 사이에 죽으면 그 사이에 센 것은 잃습니다. 바뀐 것이 없으면 쓰지 않습니다.
 *
 * <p><b>처음부터 다시 세려면 서버를 멈춘 뒤에 그 파일을 지우고 다시 띄웁니다.</b> 규칙을 고친 호스트의
 * 예전 숫자가 새 숫자에 섞여 읽기 어려울 때 그렇게 합니다. 돌고 있는 서버는 멈출 때 세어 둔 것을 다시
 * 적으므로, 멈추기 전에 지우면 파일이 되살아납니다. 지우는 통로를 API 로 두지 않았습니다. 인증이 없어
 * 아무나 지울 수 있게 됩니다.
 */
@Component
public class OpacHostTallyStore {

    private static final Logger log = LoggerFactory.getLogger(OpacHostTallyStore.class);

    private final OpacHostTally tally;
    private final Path path;

    /**
     * 서가를 수집하러 띄운 컨테이너인지. 그때는 이 파일을 읽지도 쓰지도 않습니다. 같은 볼륨을 붙이고
     * 따로 도는 컨테이너가 같은 파일을 쓰면 나중에 적은 쪽이 앞의 값을 덮어씁니다
     * ({@link CallLedgerStore} 와 같은 까닭입니다).
     */
    private final boolean harvesting;

    public OpacHostTallyStore(DetailResolver detailResolver,
                              @Value("${wimb.opac.host-tally-path:data/opac-host-tally.tsv}") String path,
                              @Value("${wimb.shelf.harvest:}") String harvest) {
        this.tally = detailResolver.hostTally();
        this.path = Path.of(path);
        this.harvesting = harvest != null && !harvest.isBlank();
    }

    @PostConstruct
    void restore() {
        if (harvesting) {
            log.info("서가를 수집하는 중이라 호스트별 상세 집계를 이어받지 않습니다.");
            return;
        }
        int hosts = tally.load(path);
        // 몇 개를 이어받았는지 남깁니다. 배포 직후 진단이 빈 목록을 줄 때, 아직 아무도 누르지 않은
        // 것인지 파일을 잃은 것인지를 이 줄로 가릅니다.
        log.info("호스트별 상세 집계 {}개 호스트를 {} 에서 이어받았습니다.", hosts, path.toAbsolutePath());
    }

    @Scheduled(initialDelayString = "${wimb.opac.host-tally-save-interval-ms:60000}",
            fixedDelayString = "${wimb.opac.host-tally-save-interval-ms:60000}")
    void persist() {
        flush();
    }

    @PreDestroy
    void persistOnShutdown() {
        flush();
    }

    private void flush() {
        if (harvesting || !tally.isDirty()) return;
        try {
            tally.save(path);
        } catch (IOException | RuntimeException e) {
            // 저장에 실패해도 서비스는 계속됩니다. 다만 조용히 넘어가면 다시 뜬 뒤에야 집계가 비어
            // 있는 것을 보게 되므로 남깁니다.
            log.warn("호스트별 상세 집계를 {} 에 저장하지 못했습니다: {}", path.toAbsolutePath(), e.toString());
        }
    }
}
