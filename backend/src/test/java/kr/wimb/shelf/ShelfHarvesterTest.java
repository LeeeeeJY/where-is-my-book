package kr.wimb.shelf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 수집기가 적어 둔 서가가 <b>실제로 서가 순서인지</b>, 화면이 읽을 수 있는 모양인지 봅니다. */
class ShelfHarvesterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 2026-09-20 한국 시각 낮. 기준일이 그날로 찍혀야 합니다. */
    private static final Clock NOON = Clock.fixed(Instant.parse("2026-09-20T03:00:00Z"),
            ZoneId.of("UTC"));

    @Test
    @DisplayName("받은 차례가 아니라 서가 차례로 적는다")
    void writesInShelfOrderNotArrivalOrder(@TempDir Path dir) throws IOException {
        // 정보나루는 아무 순서로나 줍니다. 정렬 항목이 아예 없습니다.
        var books = List.of(
                book("아몬드", "9788936434267", "813.7", "손66ㅇ", "종합자료실"),
                book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실"),
                book("코스모스", "9788983711892", "443.1", "세69ㅋ", "종합자료실"));

        harvest(dir, books);

        assertEquals(List.of("코스모스", "토지", "아몬드"), titlesIn(dir, "141321", "r0", 0));
    }

    @Test
    @DisplayName("자료실이 다르면 서가를 나눈다")
    void splitsShelvesByRoom(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(
                book("아몬드", "9788936434267", "813.7", "손66ㅇ", "어린이자료실"),
                book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실")));

        JsonNode meta = metaOf(dir, "141321");
        assertEquals(2, meta.get("rooms").size(), "층이 다르면 아예 다른 서가입니다");

        // 자료실 이름을 폴더 이름으로 쓰지 않습니다. 대괄호와 괄호가 든 값이 실제로 옵니다.
        for (JsonNode room : meta.get("rooms")) {
            assertTrue(room.get("slug").asText().matches("r[0-9]+"), room.get("slug").asText());
        }
    }

    /**
     * 한 책에 복본이 둘이면 <b>서가에 두 자리를 차지합니다.</b> 한 줄로 합치면 둘 중
     * 하나를 찾으러 간 사람이 그 자리에서 책을 찾지 못합니다.
     */
    @Test
    @DisplayName("복본은 저마다 한 자리를 차지한다")
    void eachCopyGetsItsOwnSlot(@TempDir Path dir) throws IOException {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response><numFound>1</numFound><docs>
              <doc><bookname><![CDATA[토지]]></bookname><isbn13>9788937437267</isbn13>
                <class_no>813.6</class_no>
                <callNumbers>
                  <callNumber><book_code>박14ㅌ</book_code><shelf_loc_code>A</shelf_loc_code>
                    <shelf_loc_name>종합자료실</shelf_loc_name></callNumber>
                  <callNumber><book_code>박14ㅌ</book_code><shelf_loc_code>A</shelf_loc_code>
                    <shelf_loc_name>종합자료실</shelf_loc_name><copy_code>c.2</copy_code></callNumber>
                </callNumbers></doc>
            </docs></response>""";

        ShelfMeta meta = harvestXml(dir, xml);

        assertEquals(2, meta.count(), "복본 둘이면 서가에 두 자리입니다");
        assertEquals(1, meta.reported(), "정보나루가 말한 장서 건수는 책 수입니다");
    }

    /**
     * <b>청구기호가 없는 자료는 서가에 세우지 않습니다.</b> 어디에 꽂혔는지 모르는 책을
     * 순서 어딘가에 끼워 넣으면 그 자리를 보고 찾아간 사람이 헛걸음합니다.
     */
    @Test
    @DisplayName("청구기호가 없는 자료는 서가에 세우지 않는다")
    void skipsItemsWithoutACallNumber(@TempDir Path dir) throws IOException {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response><numFound>2</numFound><docs>
              <doc><bookname><![CDATA[자리를 모르는 책]]></bookname><isbn13>9788936434267</isbn13>
                <class_no>813.7</class_no></doc>
              <doc><bookname><![CDATA[토지]]></bookname><isbn13>9788937437267</isbn13>
                <class_no>813.6</class_no>
                <callNumbers><callNumber><book_code>박14ㅌ</book_code>
                  <shelf_loc_name>종합자료실</shelf_loc_name></callNumber></callNumbers></doc>
            </docs></response>""";

        ShelfMeta meta = harvestXml(dir, xml);

        assertEquals(1, meta.count());
        // 두 숫자를 함께 두는 이유가 이것입니다. 차이가 벌어진 것을 알아챌 수 있습니다.
        assertEquals(2, meta.reported());
    }

    @Test
    @DisplayName("차림표에 기준일과 초성 색인이 들어간다")
    void metaCarriesAsOfAndChosungIndex(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(
                book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실"),
                book("아몬드", "9788936434267", "813.7", "손66ㅇ", "종합자료실"),
                book("데미안", "9788937437268", "833.6", "헤53ㄷ", "종합자료실")));

        JsonNode meta = metaOf(dir, "141321");
        assertEquals("2026-09-20", meta.get("asOf").asText(), "수집을 끝낸 한국 날짜입니다");

        JsonNode at = meta.get("rooms").get(0).get("chosungAt");
        assertEquals(0, at.get("ㅂ").asInt(), "박14ㅌ 이 첫 자리입니다");
        assertEquals(1, at.get("ㅅ").asInt());
        assertEquals(2, at.get("ㅎ").asInt());
        // **없는 초성은 담기지 않습니다.** 화면이 그 줄을 흐리게 그립니다.
        assertFalse(at.has("ㄱ"));
    }

    @Test
    @DisplayName("선반 라벨로 쓸 청구기호 범위를 적는다")
    void metaCarriesCallNumberRange(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(
                book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실"),
                book("데미안", "9788937437268", "833.6", "헤53ㄷ", "종합자료실")));

        JsonNode room = metaOf(dir, "141321").get("rooms").get(0);
        assertEquals("813.6 박14ㅌ", room.get("firstCall").asText());
        assertEquals("833.6 헤53ㄷ", room.get("lastCall").asText());
    }

    /**
     * 표제와 저자명은 도서관이 적어 넣은 값이라 무엇이 들어 있을지 모릅니다. 한 권이
     * 깨지면 <b>그 조각 200권이 통째로</b> 화면에 나오지 못합니다.
     */
    @Test
    @DisplayName("따옴표가 든 표제가 있어도 조각이 깨지지 않는다")
    void quotesInTitlesDoNotBreakTheChunk(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(
                book("그가 말한 \"사랑\"\\에 대하여", "9788937437267", "813.6", "박14ㅌ", "종합자료실")));

        assertEquals(List.of("그가 말한 \"사랑\"\\에 대하여"), titlesIn(dir, "141321", "r0", 0));
    }

    /**
     * 수집은 십 분 넘게 걸립니다. 그동안 <b>화면에 답하고 있는 서버가 반쯤 쓰인 서가를
     * 읽으면</b> 책이 몇 권 없는 서가가 나가고, 그것은 고장으로 읽힙니다.
     */
    @Test
    @DisplayName("다시 수집해도 예전 서가가 중간에 사라지지 않는다")
    void republishesAtomically(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실")));
        harvest(dir, List.of(
                book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실"),
                book("아몬드", "9788936434267", "813.7", "손66ㅇ", "종합자료실")));

        assertEquals(List.of("토지", "아몬드"), titlesIn(dir, "141321", "r0", 0));
        // 임시 자리가 남아 있으면 다음 수집이 예전 찌꺼기 위에 적습니다.
        assertFalse(Files.exists(dir.resolve("141321").resolve(Kdc.LITERATURE.slug() + ".new")));
        assertFalse(Files.exists(dir.resolve("141321").resolve(Kdc.LITERATURE.slug() + ".old")));
    }

    @Test
    @DisplayName("조각 크기마다 파일을 나눈다")
    void splitsIntoChunks(@TempDir Path dir) throws IOException {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < ShelfHarvester.CHUNK + 5; i++) {
            many.add(book("책 " + i, "978893743%04d".formatted(i),
                    "813.6", "박%03dㅌ".formatted(i), "종합자료실"));
        }
        harvest(dir, many);

        JsonNode room = metaOf(dir, "141321").get("rooms").get(0);
        assertEquals(ShelfHarvester.CHUNK + 5, room.get("count").asInt());
        assertEquals(2, room.get("chunks").asInt());
        assertEquals(ShelfHarvester.CHUNK, titlesIn(dir, "141321", "r0", 0).size());
        assertEquals(5, titlesIn(dir, "141321", "r0", 1).size());
    }

    /** 적어 둔 서가를 화면 쪽 통로로 다시 읽어 봅니다. 주소로 받은 값을 거르는지도 봅니다. */
    @Test
    @DisplayName("서가 파일은 ShelfStore 를 거쳐서만 나간다")
    void storeReadsBackAndRefusesOddPaths(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실")));
        var store = new ShelfStore(dir);

        assertEquals(List.of("8"), store.builtSubjects("141321"));
        assertTrue(store.meta("141321", Kdc.LITERATURE).isPresent());
        assertTrue(store.chunk("141321", Kdc.LITERATURE, "r0", 0).isPresent());

        // 도서관부호와 자리 이름이 그대로 파일 경로가 되므로 모양이 맞는 것만 받습니다.
        assertTrue(store.meta("../../etc", Kdc.LITERATURE).isEmpty());
        assertTrue(store.chunk("141321", Kdc.LITERATURE, "../..", 0).isEmpty());
        assertTrue(store.chunk("141321", Kdc.LITERATURE, "r0", -1).isEmpty());
        // 아직 세우지 않은 서가는 비어 있습니다. 고장이 아닙니다.
        assertTrue(store.meta("111001", Kdc.LITERATURE).isEmpty());
        assertTrue(store.meta("141321", Kdc.HISTORY).isEmpty(), "다른 대주제는 따로입니다");
    }

    /**
     * <b>찾기 색인은 서가와 줄 수가 같고 차례도 같아야 합니다.</b> 줄 번호가 곧 자리
     * 번호라, 한 줄만 어긋나도 화면은 아무 이상 없이 <b>옆 책</b>을 보여 줍니다.
     */
    @Test
    @DisplayName("찾기 색인을 서가와 같은 차례로 함께 적는다")
    void writesAFindIndexInTheSameOrderAsTheShelf(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(
                book("아몬드", "9788936434267", "813.7", "손66ㅇ", "종합자료실"),
                book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실"),
                book("코스모스", "9788983711892", "443.1", "세69ㅋ", "종합자료실")));

        List<String> lines = Files.readAllLines(
                shelfDir(dir, "141321").resolve("r0").resolve("find.txt"), StandardCharsets.UTF_8);

        assertEquals(3, lines.size(), "서가에 선 권수와 같아야 합니다");
        assertEquals(List.of("코스모스", "토지", "아몬드"),
                lines.stream().map(line -> line.split("\t", -1)[0]).toList());
        assertEquals("443.1 세69ㅋ", lines.get(0).split("\t", -1)[2]);
    }

    /**
     * 찾기는 <b>세워 둔 파일만 읽습니다.</b> 화면 쪽 통로로 실제로 찾아 봅니다.
     */
    @Test
    @DisplayName("세워 둔 서가에서 자리 번호를 찾는다")
    void findsAPositionInTheShelfItJustBuilt(@TempDir Path dir) throws IOException {
        harvest(dir, List.of(
                book("아몬드", "9788936434267", "813.7", "손66ㅇ", "종합자료실"),
                book("토지", "9788937437267", "813.6", "박14ㅌ", "종합자료실"),
                book("코스모스", "9788983711892", "443.1", "세69ㅋ", "종합자료실")));
        var store = new ShelfStore(dir);

        var found = store.find("141321", Kdc.LITERATURE, "r0", "토지", ShelfFind.LIMIT);

        assertTrue(found.isPresent());
        assertEquals(1, found.get().total());
        assertEquals(1, found.get().hits().get(0).at(), "서가에서 둘째 자리입니다");

        // 차림표가 「찾을 수 있다」고 말해야 화면이 단추를 냅니다.
        assertTrue(metaOf(dir, "141321").get("findable").asBoolean());

        // 주소로 받은 값이 그대로 경로가 되는 것은 여기서도 같습니다.
        assertTrue(store.find("141321", Kdc.LITERATURE, "../..", "토지", 20).isEmpty());
        // 아직 세우지 않은 서가는 **「없음」이 아니라 「못 찾음」**입니다. 물어보지 못한
        // 것을 없다고 답하면 실제로 꽂혀 있는 책을 없다고 말하게 됩니다.
        assertTrue(store.find("141321", Kdc.HISTORY, "r0", "토지", 20).isEmpty());
    }

    // ── 거들기 ────────────────────────────────────────────────────────────

    private static String book(String title, String isbn, String classNo,
                               String bookCode, String room) {
        return """
            <doc><bookname><![CDATA[%s]]></bookname><isbn13>%s</isbn13>
              <class_no>%s</class_no><class_nm><![CDATA[문학 > 한국문학 > 소설]]></class_nm>
              <callNumbers><callNumber><book_code>%s</book_code>
                <shelf_loc_code>%s</shelf_loc_code>
                <shelf_loc_name>%s</shelf_loc_name></callNumber></callNumbers></doc>
            """.formatted(escape(title), isbn, classNo, bookCode, room, room);
    }

    /** CDATA 안이라 {@code ]]>} 만 아니면 그대로 실립니다. */
    private static String escape(String title) {
        return title.replace("]]>", "]]&gt;");
    }

    private static ShelfMeta harvest(Path dir, List<String> docs) throws IOException {
        return harvestXml(dir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <response><numFound>%d</numFound><docs>%s</docs></response>"""
                .formatted(docs.size(), String.join("", docs)));
    }

    private static ShelfMeta harvestXml(Path dir, String xml) throws IOException {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 10_000), NOON);
        var client = new Data4LibraryClient(new OnePageTransport(xml), "테스트키", budget);
        return new ShelfHarvester(client, dir, NOON).harvest("141321", Kdc.LITERATURE);
    }

    private static JsonNode metaOf(Path dir, String libCode) throws IOException {
        return JSON.readTree(Files.readString(
                shelfDir(dir, libCode).resolve("meta.json"), StandardCharsets.UTF_8));
    }

    /** 서가는 (도서관 × 대주제)마다 하나입니다. */
    private static Path shelfDir(Path dir, String libCode) {
        return dir.resolve(libCode).resolve(Kdc.LITERATURE.slug());
    }

    private static List<String> titlesIn(Path dir, String libCode, String slug, int chunk)
            throws IOException {
        JsonNode rows = JSON.readTree(Files.readString(
                shelfDir(dir, libCode).resolve(slug).resolve(chunk + ".json"),
                StandardCharsets.UTF_8));
        List<String> titles = new ArrayList<>();
        for (JsonNode row : rows) titles.add(row.get("title").asText());
        return titles;
    }

    /**
     * 첫 쪽만 책을 주고 그다음 쪽은 비워 둡니다. {@code numFound} 는 그대로 두므로,
     * <b>수집기가 쪽 크기를 실제로 재는지</b>도 함께 확인됩니다. 500권을 달라고 했는데
     * 몇 권만 오면 그 수를 한 쪽 분량으로 삼아야 합니다.
     */
    private record OnePageTransport(String firstPage) implements Data4LibraryClient.Transport {
        private static final Pattern PAGE = Pattern.compile("[?&]pageNo=([0-9]+)");

        @Override public String get(URI uri) {
            Matcher page = PAGE.matcher(uri.toString());
            if (page.find() && !page.group(1).equals("1")) {
                return "<?xml version=\"1.0\"?><response><numFound>0</numFound><docs/></response>";
            }
            return firstPage;
        }
    }
}
