package kr.wimb.api;

import kr.wimb.worldcup.WorldcupStats;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「인생 책 월드컵」의 집계를 받고 내주는 통로.
 *
 * <p><b>정보나루를 한 번도 부르지 않습니다.</b> 후보 책은 화면이 들고 있는 고정 목록이고,
 * 여기서 하는 일은 「몇 번 골라졌는가」를 세는 것뿐입니다. 그래서 정보나루가 답하지 않는
 * 날에도 월드컵은 그대로 돕니다.
 *
 * <p><b>집계가 없어도 월드컵은 끝까지 진행됩니다.</b> 화면은 이 통로가 실패하면 통계
 * 자리만 비웁니다. 여기서 오류를 내는 것이 진행을 막지 않습니다.
 */
@RestController
@RequestMapping("/api/worldcup")
public class WorldcupController {

    /**
     * 집계는 잠깐 기억해 둬도 됩니다. 결과 화면을 여는 사람마다 물어보는데, 몇십 초 전
     * 숫자라고 달라질 것이 없습니다. 방금 완주한 사람은 완주 응답으로 새 숫자를 받습니다.
     */
    private static final CacheControl STATS_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    public record FinishRequest(String winner, List<List<String>> matches) {}

    public record EventRequest(String name, String utm) {}

    private final WorldcupStats stats;

    public WorldcupController(WorldcupStatsStore store) {
        this.stats = store.stats();
    }

    @GetMapping("/{themeId}/stats")
    public ResponseEntity<WorldcupStats.ThemeView> stats(@PathVariable String themeId) {
        return ResponseEntity.ok().cacheControl(STATS_CACHE).body(known(themeId));
    }

    /**
     * 완주 한 번을 세고 <b>센 뒤의 집계를 돌려줍니다.</b> 화면이 「이 책의 n번째 우승」을
     * 말하려면 자기 몫이 더해진 숫자가 필요한데, 따로 다시 물어보면 그 사이에 다른
     * 완주가 끼어들 수 있습니다.
     */
    @PostMapping("/{themeId}/finish")
    public WorldcupStats.ThemeView finish(@PathVariable String themeId,
                                          @RequestBody FinishRequest request) {
        known(themeId);
        List<WorldcupStats.Match> matches = new ArrayList<>();
        if (request.matches() != null) {
            for (List<String> pair : request.matches()) {
                if (pair == null || pair.size() != 2) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "대결 기록을 읽지 못했습니다.");
                }
                matches.add(new WorldcupStats.Match(pair.get(0), pair.get(1)));
            }
        }
        try {
            return stats.recordFinish(themeId, request.winner(), matches);
        } catch (WorldcupStats.InvalidResultException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 진행 단계 하나를 셉니다. <b>무엇을 눌렀는지와 어느 광고로 왔는지만 받습니다.</b>
     * 받지 않는 이름이어도 오류로 답하지 않습니다. 세는 것이 실패했다고 화면이 할 일은
     * 없습니다.
     */
    @PostMapping("/{themeId}/event")
    public Map<String, Boolean> event(@PathVariable String themeId,
                                      @RequestBody EventRequest request) {
        // 본문을 비우지 않습니다. 화면의 통로가 모든 답을 JSON 으로 읽으므로, 빈 답을 주면
        // 멀쩡히 센 것이 화면에서는 「서버에 닿지 못했다」로 읽힙니다.
        return Map.of("counted", stats.recordEvent(
                LocalDate.now(RateLimit.ZONE), themeId, request.name(), request.utm()));
    }

    /** 날짜·단계·유입 표시별 횟수. 운영자가 광고 성과를 볼 때 엽니다. */
    @GetMapping("/{themeId}/events")
    public Map<String, Object> events(@PathVariable String themeId) {
        known(themeId);
        return Map.of("themeId", themeId, "rows", stats.events(themeId));
    }

    private WorldcupStats.ThemeView known(String themeId) {
        WorldcupStats.ThemeView view = stats.view(themeId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 월드컵 주제입니다.");
        }
        return view;
    }
}
