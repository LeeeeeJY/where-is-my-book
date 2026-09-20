package kr.wimb.api;

import kr.wimb.shelf.Kdc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서가에서 자리를 찾는 통로가 <b>주소째로 실제로 이어져 있는지</b> 봅니다.
 *
 * <h2>왜 굳이 컨텍스트를 올리나</h2>
 *
 * <p>찾기 주소({@code …/{roomSlug}/find})와 조각 주소({@code …/{roomSlug}/{index}})는
 * 칸 수가 같습니다. 어느 쪽으로 갈지는 Spring 이 정하는데, <b>그것이 뒤집히면 찾기가
 * 조각 읽기로 가면서 「find 는 숫자가 아닙니다」라는 400 이 납니다.</b> 단위 시험은
 * 클래스를 직접 만들어 쓰므로 이 뒤집힘을 잡지 못합니다.
 */
@SpringBootTest(properties = {
        "wimb.data4library.auth-key=test-key-not-used-for-calls",
        "wimb.shelf.data-dir=build/tmp/shelf-find-endpoint"})
@AutoConfigureMockMvc
class ShelfFindEndpointTest {

    private static final Path DATA = Path.of("build/tmp/shelf-find-endpoint");

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void writeShelf() throws Exception {
        Path room = DATA.resolve("141321").resolve(Kdc.LITERATURE.slug()).resolve("r0");
        Files.createDirectories(room);
        Files.writeString(room.resolve("find.txt"), """
                코스모스\t칼 세이건\t443.1 세69ㅋ
                토지 1\t박경리\t813.6 박14ㅌ1
                토지 2\t박경리\t813.6 박14ㅌ2
                """, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("표제로 그 서가의 자리 번호를 답한다")
    void answersWithAPositionInThatShelf() throws Exception {
        mvc.perform(get("/api/shelf/141321/8/r0/find").param("q", "토지"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.hits[0].at").value(1))
                .andExpect(jsonPath("$.hits[0].title").value("토지 1"))
                .andExpect(jsonPath("$.hits[0].call").value("813.6 박14ㅌ1"));
    }

    /**
     * <b>못 찾은 것은 빈 목록입니다.</b> 「이 서가에는 없다」는 정상적인 답이고, 그 책이
     * 다른 자료실이나 다른 대주제에 서 있을 수 있습니다. 오류로 답하면 화면이 그것을
     * 「지금은 찾을 수 없다」와 갈라 말할 수 없게 됩니다.
     */
    @Test
    @DisplayName("이 서가에 없는 것은 빈 목록이지 오류가 아니다")
    void nothingHereIsAnEmptyListNotAnError() throws Exception {
        mvc.perform(get("/api/shelf/141321/8/r0/find").param("q", "데미안"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.hits").isEmpty());
    }

    @Test
    @DisplayName("한 글자로는 찾지 않는다")
    void refusesQueriesTooShortToLeadAnywhere() throws Exception {
        mvc.perform(get("/api/shelf/141321/8/r0/find").param("q", "토"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 아직 색인이 없는 서가는 <b>「없음」이 아니라 「못 찾음」</b>입니다. 물어보지 못한
     * 것을 없다고 답하면 실제로 꽂혀 있는 책을 없다고 말하게 됩니다.
     */
    @Test
    @DisplayName("색인이 없는 서가는 없음이 아니라 못 찾음이다")
    void aShelfWithoutAnIndexIsNotAnEmptyShelf() throws Exception {
        mvc.perform(get("/api/shelf/141321/9/r0/find").param("q", "토지"))
                .andExpect(status().isNotFound());
    }
}
