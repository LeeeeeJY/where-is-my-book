package kr.wimb.api;

import kr.wimb.shelf.Kdc;
import kr.wimb.shelf.ShelfService;
import kr.wimb.shelf.ShelfStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 서가 화면이 받아 가는 것.
 *
 * <h2>보는 것과 세우는 것을 갈라 둡니다</h2>
 *
 * <p>서가를 <b>보는</b> 요청은 미리 적어 둔 파일을 흘려보내기만 해서 정보나루를 한 번도
 * 부르지 않습니다. 서가를 <b>세우는</b> 요청은 수백 번을 부릅니다. 값이 이렇게 다른
 * 둘을 한 통로에 두면 주소별 한도를 어느 쪽에 맞춰도 틀립니다. 낮게 잡으면 아무나
 * 수백 회를 태울 수 있고, 높게 잡으면 진행률을 몇 번 물어보다 막힙니다.
 *
 * <p>그래서 세우는 것만 {@code POST} 로 갈라 두고 무게를 따로 매깁니다.
 *
 * <h2>없는 서가는 404 가 아닙니다</h2>
 *
 * <p>아직 아무도 열지 않은 서가는 <b>「없음」이라는 정상적인 상태</b>입니다. 화면이
 * 「고장」과 「아직 안 세움」을 갈라 말해야 하므로 상태로 답합니다. 이 저장소가
 * 「미소장」과 「확인 불가」를 갈라 두는 것과 같은 이유입니다.
 */
@RestController
@RequestMapping("/api/shelf")
public class ShelfController {

    /**
     * 조각은 오래 기억해 둬도 됩니다. 화면이 주소에 기준일을 붙여 부르므로, 다시
     * 세우면 주소가 달라져 새로 받습니다.
     */
    private static final CacheControl CHUNK_CACHE =
            CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    /** 차림표는 기준일이 들어 있는 쪽이라 짧게 기억합니다. */
    private static final CacheControl META_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic();

    private final ShelfStore store;
    private final ShelfService service;

    public ShelfController(ShelfStore store, ShelfService service) {
        this.store = store;
        this.service = service;
    }

    /**
     * 고를 수 있는 서가. <b>열 개는 고정이고 도서관마다 같습니다.</b>
     *
     * <p>이미 세워 둔 것이 무엇인지 함께 알려 줍니다. 화면이 그것으로 「바로 열림」과
     * 「세우는 데 시간이 걸림」을 미리 말해 줄 수 있습니다. 누르고 나서야 기다리라는
     * 말을 들으면 누른 것을 후회하게 됩니다.
     */
    @GetMapping("/{libCode}/subjects")
    public Map<String, Object> subjects(@PathVariable String libCode) {
        return Map.of(
                "subjects", Kdc.all().stream()
                        .map(one -> Map.of("code", one.code(), "label", one.label()))
                        .toList(),
                "built", store.builtSubjects(libCode));
    }

    /**
     * 그 서가가 지금 어떤 상태인지. <b>여기서는 세우지 않습니다.</b>
     *
     * <p>화면이 세우는 동안 이 주소를 되풀이해 물어 진행률을 받습니다. 그래서 값이
     * 싸야 합니다.
     */
    @GetMapping("/{libCode}/{kdc}")
    public Map<String, Object> status(@PathVariable String libCode, @PathVariable String kdc) {
        return asMap(service.status(libCode, subject(kdc)));
    }

    /**
     * 서가를 세우기 시작합니다. <b>이미 있거나 누가 세우는 중이면 아무 일도 하지
     * 않습니다.</b> 같은 서가를 둘이 열어도 한 번만 세웁니다.
     */
    @PostMapping("/{libCode}/{kdc}")
    public Map<String, Object> build(@PathVariable String libCode, @PathVariable String kdc) {
        return asMap(service.build(libCode, subject(kdc)));
    }

    /** 자료실 목록과 초성 색인과 기준일. 서가가 다 세워진 뒤에 받습니다. */
    @GetMapping("/{libCode}/{kdc}/meta")
    public ResponseEntity<byte[]> meta(@PathVariable String libCode, @PathVariable String kdc) {
        return json(store.meta(libCode, subject(kdc)), META_CACHE);
    }

    /**
     * 서가의 한 조각. 화면이 스크롤하면서 필요한 것만 받아 갑니다.
     *
     * <p>화면은 주소 뒤에 기준일을 {@code ?v=2026-09-20} 으로 붙여 부릅니다.
     * <b>서버는 그 값을 쓰지 않습니다.</b> 다시 세웠을 때 주소가 달라지게 해서,
     * 브라우저가 하루 동안 기억해 둔 예전 조각을 그대로 쓰지 않게 하는 것이 전부입니다.
     */
    @GetMapping("/{libCode}/{kdc}/{roomSlug}/{index}")
    public ResponseEntity<byte[]> chunk(@PathVariable String libCode,
                                        @PathVariable String kdc,
                                        @PathVariable String roomSlug,
                                        @PathVariable int index) {
        return json(store.chunk(libCode, subject(kdc), roomSlug, index), CHUNK_CACHE);
    }

    // ── 거들기 ────────────────────────────────────────────────────────────

    private static Map<String, Object> asMap(ShelfService.Status status) {
        return Map.of(
                "state", status.state().name().toLowerCase(java.util.Locale.ROOT),
                "stale", status.stale(),
                "percent", status.percent(),
                "books", status.books());
    }

    /**
     * 주소로 받은 대주제. <b>아는 것만 통과시킵니다.</b> 이 값이 그대로 파일 경로가
     * 되므로, 거르는 쪽이 아니라 허락한 것만 받는 쪽입니다.
     */
    private static Kdc subject(String code) {
        return Kdc.of(code).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "그런 서가는 없습니다."));
    }

    private ResponseEntity<byte[]> json(Optional<byte[]> body, CacheControl cache) {
        byte[] found = body.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "그 서가는 아직 세우지 않았습니다."));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(cache)
                .body(found);
    }
}
