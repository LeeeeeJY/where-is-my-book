package kr.wimb.api;

import kr.wimb.shelf.ShelfStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 서가 화면이 받아 가는 것. <b>정보나루를 한 번도 부르지 않습니다.</b>
 *
 * <p>수집기가 미리 적어 둔 파일을 그대로 흘려보내기만 합니다. 서가를 몇 명이 몇 번을
 * 열든 하루 호출 예산이 줄지 않고, 정보나루가 멈춰 있어도 서가는 평소대로 열립니다.
 *
 * <p>아직 수집하지 않은 도서관은 <b>404 가 아니라 빈 목록</b>으로 답합니다. 화면이
 * 「그 도서관은 서가를 아직 만들지 않았습니다」와 「서버가 고장 났습니다」를 갈라
 * 말해야 하기 때문입니다. 이 저장소가 「미소장」과 「확인 불가」를 갈라 두는 것과
 * 같은 이유입니다.
 */
@RestController
@RequestMapping("/api/shelf")
public class ShelfController {

    /**
     * 조각은 오래 기억해 둬도 됩니다. 화면이 주소에 기준일을 붙여 부르므로, 수집을
     * 다시 돌리면 주소가 달라져 새로 받습니다.
     */
    private static final CacheControl CHUNK_CACHE =
            CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    /** 차림표는 기준일이 들어 있는 쪽이라 짧게 기억합니다. */
    private static final CacheControl META_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic();

    private final ShelfStore store;

    public ShelfController(ShelfStore store) {
        this.store = store;
    }

    /**
     * 서가가 준비된 도서관. <b>고른 도서관 가운데 어느 곳을 열 수 있는지</b>를 화면이
     * 이것으로 압니다.
     */
    @GetMapping("/libraries")
    public Map<String, List<String>> libraries() {
        return Map.of("libCodes", store.libraries());
    }

    /** 자료실 목록과 초성 색인과 기준일. 서가를 열 때 맨 처음 받습니다. */
    @GetMapping("/{libCode}/meta")
    public ResponseEntity<byte[]> meta(@PathVariable String libCode) {
        return json(store.meta(libCode), META_CACHE,
                "그 도서관은 서가를 아직 만들지 않았습니다.");
    }

    /**
     * 서가의 한 조각. 화면이 스크롤하면서 필요한 것만 받아 갑니다.
     *
     * <p>화면은 주소 뒤에 기준일을 {@code ?v=2026-09-20} 으로 붙여 부릅니다.
     * <b>서버는 그 값을 쓰지 않습니다.</b> 수집을 다시 돌렸을 때 주소가 달라지게 해서,
     * 브라우저가 하루 동안 기억해 둔 예전 조각을 그대로 쓰지 않게 하는 것이 전부입니다.
     */
    @GetMapping("/{libCode}/{roomSlug}/{index}")
    public ResponseEntity<byte[]> chunk(@PathVariable String libCode,
                                        @PathVariable String roomSlug,
                                        @PathVariable int index) {
        return json(store.chunk(libCode, roomSlug, index), CHUNK_CACHE,
                "서가의 그 자리는 없습니다.");
    }

    private ResponseEntity<byte[]> json(Optional<byte[]> body, CacheControl cache, String missing) {
        byte[] found = body.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, missing));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(cache)
                .body(found);
    }
}
