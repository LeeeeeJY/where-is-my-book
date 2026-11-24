package kr.wimb.api;

import kr.wimb.opac.DetailResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 호스트별 상세 집계 파일을 <b>누가 읽고 쓰는지</b>를 봅니다. 세는 것 자체는 {@code OpacHostTallyTest}
 * 와 {@code DetailResolverTest} 가 봅니다.
 */
class OpacHostTallyStoreTest {

    private static final Pattern BOOK_LINK = Pattern.compile("href=[\"'](/book[^\"']*)[\"']");
    private static final Clock NOON =
            Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneId.of("UTC"));

    /** 어느 주소든 상세 링크 하나가 든 검색 결과를 주는 가짜 OPAC. */
    private static DetailResolver resolver() {
        return new DetailResolver(url -> url.getPath().equals("/robots.txt")
                ? new DetailResolver.Page(404, "", url)
                : new DetailResolver.Page(200, "<a href='/book/1'>코스모스</a>", URI.create(url.toString())), NOON);
    }

    private static long attempts(DetailResolver resolver, String host) {
        return resolver.hostTally().snapshot().stream()
                .filter(row -> host.equals(row.get("host")))
                .mapToLong(row -> (Long) row.get("attempts")).sum();
    }

    @Test
    @DisplayName("주기 저장으로 적은 집계를 다시 뜬 서버가 이어받는다")
    void whatOneServerPersistsTheNextRestores(@TempDir Path dir) {
        String file = dir.resolve("opac-host-tally.tsv").toString();

        var first = resolver();
        var firstStore = new OpacHostTallyStore(first, file, "");
        firstStore.restore();
        for (int i = 0; i < 3; i++) first.resolve("https://lib.example.kr/search?q=" + i, BOOK_LINK);
        firstStore.persist();
        // 종료 훅 없이 죽은 경우입니다(docker rm -f 는 SIGKILL 입니다). 주기 저장이 남긴 것만으로
        // 이어져야 합니다.

        var second = resolver();
        new OpacHostTallyStore(second, file, "").restore();
        assertEquals(3, attempts(second, "lib.example.kr"));
    }

    @Test
    @DisplayName("바뀐 것이 없으면 파일을 쓰지 않는다")
    void doesNotWriteWhenNothingChanged(@TempDir Path dir) {
        Path file = dir.resolve("opac-host-tally.tsv");
        var store = new OpacHostTallyStore(resolver(), file.toString(), "");
        store.restore();
        store.persist();
        store.persistOnShutdown();

        assertFalse(Files.exists(file), "아무도 누르지 않았는데 파일을 썼습니다");
    }

    @Test
    @DisplayName("서가를 수집하러 뜬 컨테이너는 집계 파일을 읽지도 쓰지도 않는다")
    void harvestContainerLeavesTheFileAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("opac-host-tally.tsv");
        var server = resolver();
        for (int i = 0; i < 5; i++) server.resolve("https://lib.example.kr/search?q=" + i, BOOK_LINK);
        server.hostTally().save(file);
        String written = Files.readString(file);

        var harvester = resolver();
        var store = new OpacHostTallyStore(harvester, file.toString(), "141321:8");
        store.restore();
        assertEquals(0, attempts(harvester, "lib.example.kr"), "수집 컨테이너는 서버의 집계를 이어받지 않습니다");

        harvester.resolve("https://lib.example.kr/search?q=9", BOOK_LINK);
        store.persist();
        store.persistOnShutdown();
        assertEquals(written, Files.readString(file), "서버가 적은 집계를 덮어썼습니다");
    }
}
