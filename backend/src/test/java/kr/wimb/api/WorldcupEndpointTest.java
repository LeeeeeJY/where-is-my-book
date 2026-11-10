package kr.wimb.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.wimb.worldcup.WorldcupRosters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 월드컵 집계 통로가 <b>실려 나가는 명단으로 끝까지 이어져 있는지</b> 봅니다.
 *
 * <p>집계 자체는 {@code WorldcupStatsTest} 가 봅니다. 여기서 보는 것은 주소와 본문의
 * 모양입니다. 화면과 서버가 따로 배포되므로, 본문의 칸 이름이 한쪽에서만 바뀌면 모든
 * 완주가 400 으로 거절되는데 <b>화면은 통계 자리만 비우므로 아무도 눈치채지 못합니다.</b>
 */
@SpringBootTest(properties = {
        "wimb.data4library.auth-key=test-key-not-used-for-calls",
        "wimb.worldcup.stats-path=build/tmp/worldcup-endpoint/stats.tsv",
        "wimb.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class WorldcupEndpointTest {

    @Autowired
    MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    /** 명단 순서대로 앞쪽이 이기는 대진을 만듭니다. 맨 앞 책이 우승합니다. */
    private static List<List<String>> bracket(List<String> books) {
        List<List<String>> matches = new ArrayList<>();
        List<String> alive = new ArrayList<>(books);
        while (alive.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < alive.size(); i += 2) {
                matches.add(List.of(alive.get(i), alive.get(i + 1)));
                next.add(alive.get(i));
            }
            alive = next;
        }
        return matches;
    }

    @Test
    @DisplayName("완주를 보내면 센 뒤의 집계가 돌아오고, 다시 물어도 같은 숫자다")
    void finishIsCountedAndReadBack() throws Exception {
        WorldcupRosters rosters = WorldcupRosters.fromClasspath();
        String theme = rosters.themes().iterator().next();
        List<String> books = new ArrayList<>(rosters.books(theme));
        String winner = books.get(0);

        String before = mvc.perform(get("/api/worldcup/" + theme + "/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long completions = json.readTree(before).get("completions").asLong();

        mvc.perform(post("/api/worldcup/" + theme + "/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("winner", winner, "matches", bracket(books)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeId").value(theme))
                .andExpect(jsonPath("$.completions").value(completions + 1))
                .andExpect(jsonPath("$.wins['" + winner + "']").isNumber());

        mvc.perform(get("/api/worldcup/" + theme + "/stats"))
                .andExpect(jsonPath("$.completions").value(completions + 1));
    }

    @Test
    @DisplayName("성립하지 않는 결과는 400 이고 이유를 함께 준다")
    void invalidResultIsABadRequestWithAReason() throws Exception {
        String theme = WorldcupRosters.fromClasspath().themes().iterator().next();
        mvc.perform(post("/api/worldcup/" + theme + "/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winner\":\"x\",\"matches\":[[\"x\",\"y\"]]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").isNotEmpty());
    }

    /** 없는 주제는 404 입니다. 화면은 그것을 「통계 없음」으로 그립니다. */
    @Test
    @DisplayName("없는 주제는 404 다")
    void unknownThemeIsNotFound() throws Exception {
        mvc.perform(get("/api/worldcup/no-such-theme/stats")).andExpect(status().isNotFound());
    }

    /**
     * <b>앞뒤의 차이로 봅니다.</b> 집계 파일이 시험 사이에 남아 있어서, 「1 이어야 한다」로
     * 적으면 두 번째로 돌릴 때부터 실패합니다. 실제로 그렇게 실패했습니다. 완주 시험이
     * 앞뒤를 견주는 것과 같은 이유입니다.
     */
    @Test
    @DisplayName("단계를 세면 날짜별 횟수로 읽힌다")
    void eventsAreCounted() throws Exception {
        String theme = WorldcupRosters.fromClasspath().themes().iterator().next();
        long before = counted(theme, "start", "instagram/paid/endpoint-test");

        mvc.perform(post("/api/worldcup/" + theme + "/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"start\",\"utm\":\"instagram/paid/endpoint-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counted").value(true));
        // 받지 않는 이름이어도 오류가 아닙니다. 세는 것이 실패했다고 화면이 할 일은 없습니다.
        mvc.perform(post("/api/worldcup/" + theme + "/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"whatever\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counted").value(false));

        assertEquals(before + 1, counted(theme, "start", "instagram/paid/endpoint-test"));
        assertEquals(0, counted(theme, "whatever", "-"));
    }

    private long counted(String theme, String event, String utm) throws Exception {
        String body = mvc.perform(get("/api/worldcup/" + theme + "/events"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long sum = 0;
        for (JsonNode row : json.readTree(body).get("rows")) {
            if (row.get("event").asText().equals(event) && row.get("utm").asText().equals(utm)) {
                sum += row.get("count").asLong();
            }
        }
        return sum;
    }
}
