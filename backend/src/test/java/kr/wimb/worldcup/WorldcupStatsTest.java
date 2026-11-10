package kr.wimb.worldcup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldcupStatsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    /** 책 여덟 권짜리 주제. 대진의 모양은 서른두 권과 같습니다. */
    private static WorldcupStats eight() {
        Set<String> books = new LinkedHashSet<>();
        for (int i = 1; i <= 8; i++) books.add("b" + i);
        return new WorldcupStats(new WorldcupRosters(Map.of("small", books)));
    }

    /** b1 이 끝까지 이기는 대진. 8강 넷, 4강 둘, 결승 하나입니다. */
    private static List<WorldcupStats.Match> b1Wins() {
        List<WorldcupStats.Match> out = new ArrayList<>();
        out.add(new WorldcupStats.Match("b1", "b2"));
        out.add(new WorldcupStats.Match("b3", "b4"));
        out.add(new WorldcupStats.Match("b5", "b6"));
        out.add(new WorldcupStats.Match("b7", "b8"));
        out.add(new WorldcupStats.Match("b1", "b3"));
        out.add(new WorldcupStats.Match("b5", "b7"));
        out.add(new WorldcupStats.Match("b1", "b5"));
        return out;
    }

    @Test
    @DisplayName("완주를 세면 우승 횟수와 대결의 승패가 함께 남는다")
    void finishCountsTheWinnerAndEveryMatch() {
        WorldcupStats stats = eight();
        WorldcupStats.ThemeView view = stats.recordFinish("small", "b1", b1Wins());

        assertEquals(1, view.completions());
        assertEquals(Map.of("b1", 1L), view.wins());
        assertEquals(1, stats.pairCount("small", "b1", "b5"));
        assertEquals(1, stats.pairCount("small", "b7", "b8"));
        assertEquals(0, stats.pairCount("small", "b5", "b1"));

        stats.recordFinish("small", "b1", b1Wins());
        assertEquals(2, stats.view("small").completions());
        assertEquals(2, stats.pairCount("small", "b1", "b5"));
    }

    @Test
    @DisplayName("아무도 완주하지 않은 주제는 0 이고, 없는 주제는 없다고 답한다")
    void emptyThemeIsZeroAndUnknownThemeIsAbsent() {
        WorldcupStats stats = eight();
        assertEquals(0, stats.view("small").completions());
        assertNull(stats.view("nope"));
    }

    /**
     * <b>인증이 없는 통로라 아무 글자나 들어옵니다.</b> 받아 적으면 남이 지어낸 이름이
     * 디스크에 쌓이고, 「우승만 적은 결과」가 순위표를 움직입니다.
     */
    @Test
    @DisplayName("성립하지 않는 대진은 세지 않는다")
    void rejectsResultsThatAreNotATournament() {
        WorldcupStats stats = eight();

        // 명단에 없는 책
        List<WorldcupStats.Match> stranger = b1Wins();
        stranger.set(0, new WorldcupStats.Match("b1", "someone-else"));
        assertThrows(WorldcupStats.InvalidResultException.class,
                () -> stats.recordFinish("small", "b1", stranger));

        // 대결 수가 모자람
        assertThrows(WorldcupStats.InvalidResultException.class,
                () -> stats.recordFinish("small", "b1", b1Wins().subList(0, 6)));

        // 이미 진 책이 다시 이김
        List<WorldcupStats.Match> revived = b1Wins();
        revived.set(4, new WorldcupStats.Match("b2", "b3"));
        assertThrows(WorldcupStats.InvalidResultException.class,
                () -> stats.recordFinish("small", "b2", revived));

        // 우승이 대진과 다름
        assertThrows(WorldcupStats.InvalidResultException.class,
                () -> stats.recordFinish("small", "b5", b1Wins()));

        // 없는 주제
        assertThrows(WorldcupStats.InvalidResultException.class,
                () -> stats.recordFinish("nope", "b1", b1Wins()));

        assertEquals(0, stats.view("small").completions());
        assertFalse(stats.isDirty());
    }

    @Test
    @DisplayName("단계는 정해 둔 이름만, 유입 표시는 받아 적을 수 있는 글자만 센다")
    void eventsAreCountedByDayNameAndSource() {
        WorldcupStats stats = eight();
        assertTrue(stats.recordEvent(DAY, "small", "start", "instagram/paid/launch"));
        assertTrue(stats.recordEvent(DAY, "small", "start", "instagram/paid/launch"));
        assertTrue(stats.recordEvent(DAY, "small", "complete", null));
        assertFalse(stats.recordEvent(DAY, "small", "anything", "x"));
        assertFalse(stats.recordEvent(DAY, "nope", "start", "x"));

        List<WorldcupStats.EventRow> rows = stats.events("small");
        assertEquals(2, rows.size());
        assertEquals(new WorldcupStats.EventRow("2026-11-10", "complete", "-", 1), rows.get(0));
        assertEquals(new WorldcupStats.EventRow("2026-11-10", "start", "instagram/paid/launch", 2), rows.get(1));
    }

    /** 탭이나 줄바꿈이 섞이면 파일의 칸이 밀립니다. 길이를 두지 않으면 아무 글이나 적힙니다. */
    @Test
    @DisplayName("유입 표시에서 칸을 깨는 글자를 떼고 길이를 자른다")
    void utmIsReducedToPlainCharacters() {
        assertEquals("ab/c_d-e.f", WorldcupStats.normalizeUtm("a\tb/c_d-e.f\n한글<>"));
        assertEquals("-", WorldcupStats.normalizeUtm("   "));
        assertEquals(WorldcupStats.MAX_UTM_LENGTH,
                WorldcupStats.normalizeUtm("x".repeat(500)).length());
    }

    @Test
    @DisplayName("유입 표시의 가짓수가 넘치면 한 칸에 모은다")
    void tooManySourcesFoldIntoOne() {
        WorldcupStats stats = eight();
        for (int i = 0; i < WorldcupStats.MAX_UTM_VALUES + 50; i++) {
            stats.recordEvent(DAY, "small", "start", "source" + i);
        }
        long folded = stats.events("small").stream()
                .filter(row -> row.utm().equals("other")).mapToLong(WorldcupStats.EventRow::count).sum();
        assertEquals(50, folded);
    }

    @Test
    @DisplayName("저장한 것을 되살리면 같은 집계가 나온다")
    void savedStatsComeBackTheSame(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deep/worldcup-stats.tsv");
        WorldcupStats stats = eight();
        stats.recordFinish("small", "b1", b1Wins());
        stats.recordEvent(DAY, "small", "start", "instagram/paid");
        assertTrue(stats.isDirty());
        stats.save(file, DAY);
        assertFalse(stats.isDirty());

        WorldcupStats back = eight();
        assertTrue(back.load(file) > 0);
        assertEquals(stats.view("small"), back.view("small"));
        assertEquals(1, back.pairCount("small", "b1", "b3"));
        assertEquals(stats.events("small"), back.events("small"));
        // 되살린 것만으로는 다시 쓸 것이 없습니다.
        assertFalse(back.isDirty());
    }

    /** 한 줄이 깨졌다고 전부 버리면 그동안 모은 집계가 통째로 사라집니다. */
    @Test
    @DisplayName("깨진 줄은 건너뛰고 나머지를 살린다")
    void brokenLinesAreSkipped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("worldcup-stats.tsv");
        Files.writeString(file, """
                #wimb-worldcup-stats v1
                F\tsmall\t3
                W\tsmall\tb1\tnot-a-number
                W\tsmall\tb2\t3
                E\tyesterday\tsmall\tstart\t-\t4
                garbage
                """);
        WorldcupStats stats = eight();
        assertEquals(2, stats.load(file));
        assertEquals(3, stats.view("small").completions());
        assertEquals(Map.of("b2", 3L), stats.view("small").wins());
    }

    @Test
    @DisplayName("오래된 날의 단계 횟수는 저장할 때 덜어 낸다")
    void oldEventDaysAreDroppedOnSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("worldcup-stats.tsv");
        WorldcupStats stats = eight();
        stats.recordEvent(DAY.minusDays(WorldcupStats.KEEP_EVENT_DAYS + 1), "small", "start", null);
        stats.recordEvent(DAY, "small", "start", null);
        stats.save(file, DAY);

        WorldcupStats back = eight();
        back.load(file);
        assertEquals(1, back.events("small").size());
    }

    @Test
    @DisplayName("명단 파일의 모양이 틀리면 뜨지 않는다")
    void malformedRosterFailsLoudly() throws Exception {
        WorldcupRosters ok = WorldcupRosters.parse(new BufferedReader(new StringReader(
                "# 주석\nclassics\tdemian\nclassics\thamlet\n")));
        assertEquals(Set.of("demian", "hamlet"), ok.books("classics"));
        assertNull(ok.books("nope"));

        assertThrows(IllegalStateException.class, () -> WorldcupRosters.parse(
                new BufferedReader(new StringReader("classics\tDemian\n"))));
        assertThrows(IllegalStateException.class, () -> WorldcupRosters.parse(
                new BufferedReader(new StringReader("classics\tdemian\nclassics\tdemian\n"))));
    }

    /**
     * <b>실제로 실려 나가는 명단을 봅니다.</b> 파일이 비거나 이미지에 실리지 않으면 모든
     * 완주가 「없는 주제」로 거절되는데, 화면은 통계 자리만 비우므로 눈에 띄지 않습니다.
     */
    @Test
    @DisplayName("실려 나가는 명단에 주제가 있고 책 수가 2의 거듭제곱이다")
    void shippedRosterIsUsable() {
        WorldcupRosters shipped = WorldcupRosters.fromClasspath();
        assertFalse(shipped.themes().isEmpty());
        for (String theme : shipped.themes()) {
            int size = shipped.books(theme).size();
            assertTrue(size >= 4 && Integer.bitCount(size) == 1, theme + " 의 책이 " + size + "권입니다");
        }
    }
}
