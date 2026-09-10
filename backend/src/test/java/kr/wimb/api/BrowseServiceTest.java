package kr.wimb.api;

import kr.wimb.data4library.Data4LibraryClient;
import kr.wimb.ingest.InMemoryApiBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「오늘의 이야기」가 <b>날짜와 도서관만으로 정해지는지</b> 고정합니다.
 *
 * <p>이 성질이 깨지면 새로 고칠 때마다 책이 바뀝니다. 그러면 「오늘의」라는 말이 거짓이
 * 되고, 도서관마다 하루 한 번이던 호출이 방문자 수만큼 늘어납니다.
 */
class BrowseServiceTest {

    /** 문학 장서의 한 쪽. 소설(8x3)과 시집(811)을 섞어 둡니다. */
    private static final String PAGE_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response>
          <numFound>250</numFound>
          <docs>
            <doc><bookname><![CDATA[진달래꽃]]></bookname><authors><![CDATA[김소월]]></authors>
              <publisher><![CDATA[열린책들]]></publisher><publication_year>2020</publication_year>
              <isbn13>9788932917245</isbn13><class_no>811.6</class_no>
              <class_nm><![CDATA[한국시]]></class_nm>
              <callNumbers><callNumber><![CDATA[811.6 김56ㅈ]]></callNumber></callNumbers></doc>
            <doc><bookname><![CDATA[무진기행]]></bookname><authors><![CDATA[김승옥]]></authors>
              <publisher><![CDATA[민음사]]></publisher><publication_year>2007</publication_year>
              <isbn13>9788937462498</isbn13><class_no>813.6</class_no>
              <class_nm><![CDATA[한국소설]]></class_nm>
              <callNumbers><callNumber><![CDATA[813.6 김56ㅁ]]></callNumber></callNumbers></doc>
            <doc><bookname><![CDATA[살인자의 기억법]]></bookname><authors><![CDATA[김영하]]></authors>
              <publisher><![CDATA[문학동네]]></publisher><publication_year>2013</publication_year>
              <isbn13>9788954621892</isbn13><class_no>813.7</class_no>
              <class_nm><![CDATA[한국소설]]></class_nm>
              <callNumbers><callNumber><![CDATA[813.7 김64ㅅ]]></callNumber></callNumbers></doc>
            <doc><bookname><![CDATA[설국]]></bookname><authors><![CDATA[가와바타 야스나리]]></authors>
              <publisher><![CDATA[민음사]]></publisher><publication_year>2002</publication_year>
              <isbn13>9788937460517</isbn13><class_no>833.6</class_no>
              <class_nm><![CDATA[일본소설]]></class_nm>
              <callNumbers><callNumber><![CDATA[833.6 가66ㅅ]]></callNumber></callNumbers></doc>
          </docs>
        </response>""";

    private static final String COUNT_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response><numFound>250</numFound><resultNum>1</resultNum><docs></docs></response>""";

    private static final String DETAIL_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <response><numFound>1</numFound><docs><doc>
          <bookname><![CDATA[무진기행]]></bookname><isbn13>9788937462498</isbn13>
          <bookDtlUrl><![CDATA[https://data4library.kr/bookV?seq=99]]></bookDtlUrl>
        </doc></docs></response>""";

    /** 요청 주소를 보고 답을 고릅니다. 어디를 몇 번 불렀는지도 셉니다. */
    private static final class FakeTransport implements Data4LibraryClient.Transport {
        final List<String> requests = new ArrayList<>();
        boolean broken = false;

        @Override
        public String get(URI uri) {
            requests.add(uri.toString());
            if (broken) throw new IllegalStateException("정보나루가 답하지 않습니다");
            String url = uri.toString();
            if (url.contains("srchBooks")) return DETAIL_XML;
            if (url.contains("pageSize=1&")|| url.endsWith("pageSize=1")) return COUNT_XML;
            return PAGE_XML;
        }
    }

    private static BrowseService serviceAt(FakeTransport transport, AtomicReference<Instant> now) {
        var budget = new InMemoryApiBudget(Map.of(Data4LibraryClient.SOURCE_CODE, 100_000),
                Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneId.of("UTC")));
        var client = new Data4LibraryClient(transport, "테스트키", budget);
        Clock moving = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        return new BrowseService(client, moving);
    }

    @Test
    @DisplayName("같은 날 같은 도서관이면 몇 번을 물어도 같은 책이고 호출이 늘지 않는다")
    void sameDaySameLibraryGivesSameBook() {
        var transport = new FakeTransport();
        var now = new AtomicReference<>(Instant.parse("2026-09-10T03:00:00Z"));
        var service = serviceAt(transport, now);

        var first = service.storyOf("111001").orElseThrow();
        int afterFirst = transport.requests.size();
        var second = service.storyOf("111001").orElseThrow();
        var third = service.storyOf("111001").orElseThrow();

        assertEquals(first.isbn13(), second.isbn13());
        assertEquals(first.isbn13(), third.isbn13());
        assertEquals(afterFirst, transport.requests.size(),
                "두 번째부터는 정보나루를 부르지 않아야 합니다");
    }

    @Test
    @DisplayName("날이 바뀌면 다시 고른다")
    void newDayPicksAgain() {
        var transport = new FakeTransport();
        var now = new AtomicReference<>(Instant.parse("2026-09-10T03:00:00Z"));
        var service = serviceAt(transport, now);

        service.storyOf("111001").orElseThrow();
        int afterFirst = transport.requests.size();

        now.set(Instant.parse("2026-09-11T03:00:00Z"));
        service.storyOf("111001").orElseThrow();

        assertTrue(transport.requests.size() > afterFirst, "날이 바뀌면 다시 불러야 합니다");
    }

    @Test
    @DisplayName("시집은 뽑지 않는다")
    void poetryIsNotAStory() {
        var transport = new FakeTransport();
        var now = new AtomicReference<>(Instant.parse("2026-09-10T03:00:00Z"));

        // 씨앗이 어떤 값이든 시집이 나오면 안 됩니다. 도서관을 바꿔 가며 확인합니다.
        for (int i = 0; i < 40; i++) {
            var story = serviceAt(transport, now).storyOf("11100" + i).orElseThrow();
            assertNotEquals("진달래꽃", story.title(),
                    "class_no 811 은 문학이지만 소설이 아닙니다");
        }
    }

    @Test
    @DisplayName("한국 시각으로 날짜 경계를 잡는다")
    void dayBoundaryIsSeoul() {
        var transport = new FakeTransport();
        // UTC 로는 9월 10일 15:30 이지만 한국 시각으로는 이미 9월 11일 00:30 입니다.
        var now = new AtomicReference<>(Instant.parse("2026-09-10T15:30:00Z"));
        var service = serviceAt(transport, now);

        assertEquals("2026-09-11", service.storyOf("111001").orElseThrow().date());
    }

    @Test
    @DisplayName("정보나루가 답하지 않아도 예외 대신 빈 값을 준다")
    void upstreamFailureIsNotAnError() {
        var transport = new FakeTransport();
        transport.broken = true;
        var now = new AtomicReference<>(Instant.parse("2026-09-10T03:00:00Z"));
        var service = serviceAt(transport, now);

        // 둘러보기는 곁들이 화면입니다. 이것 때문에 검색까지 막히면 안 됩니다.
        assertTrue(service.storyOf("111001").isEmpty());
        assertTrue(service.popularOf("111001").isEmpty());
    }

    @Test
    @DisplayName("실패는 기억하지 않는다")
    void failureIsNotCached() {
        var transport = new FakeTransport();
        transport.broken = true;
        var now = new AtomicReference<>(Instant.parse("2026-09-10T03:00:00Z"));
        var service = serviceAt(transport, now);

        assertTrue(service.storyOf("111001").isEmpty());
        transport.broken = false;

        // 정보나루가 잠깐 흔들린 뒤에도 하루 내내 빈 화면이 이어지면 안 됩니다.
        assertTrue(service.storyOf("111001").isPresent());
    }

    @Test
    @DisplayName("청구기호와 정보나루 주소를 함께 싣는다")
    void storyCarriesCallNumberAndDetailUrl() {
        var transport = new FakeTransport();
        var now = new AtomicReference<>(Instant.parse("2026-09-10T03:00:00Z"));

        var story = serviceAt(transport, now).storyOf("111001").orElseThrow();

        assertNotNull(story.callNumber(), "서가에서 찾을 때 쓰는 값입니다");
        assertTrue(story.callNumber().startsWith("8"));
        // itemSrch 는 bookDtlUrl 을 주지 않으므로 ISBN 으로 한 번 더 물어봅니다.
        assertEquals("https://data4library.kr/bookV?seq=99", story.detailUrl());
        assertEquals(250, story.poolSize());
    }

    @Test
    @DisplayName("씨앗은 날짜와 도서관이 같으면 같고 다르면 다르다")
    void seedIsStable() {
        var day = java.time.LocalDate.parse("2026-09-10");
        assertEquals(BrowseService.seedOf(day, "111001"), BrowseService.seedOf(day, "111001"));
        assertNotEquals(BrowseService.seedOf(day, "111001"), BrowseService.seedOf(day, "111002"));
        assertNotEquals(BrowseService.seedOf(day, "111001"),
                BrowseService.seedOf(day.plusDays(1), "111001"));
    }
}
