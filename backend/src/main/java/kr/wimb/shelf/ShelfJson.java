package kr.wimb.shelf;

import java.util.Map;

/**
 * 서가 파일에 적는 JSON. <b>손으로 적습니다.</b>
 *
 * <h2>왜 손으로 적나</h2>
 *
 * <p>수집기가 20만 권을 힙에 들고 정렬해야 하는데, 기계는 메모리 1GB 이고 힙은 그
 * 절반입니다. 객체로 들고 있으면 모자라므로 <b>한 권을 문자열 하나로 눌러 담는데</b>,
 * 그 문자열의 마지막 조각이 여기서 만든 JSON 입니다. 이미 글자인 것을 다시 객체로
 * 되돌렸다가 또 글자로 만들 이유가 없습니다.
 *
 * <p>그리고 이 JSON 은 <b>화면이 그대로 받는 모양</b>이라 필드 이름이 곧 약속입니다.
 * 자동으로 만들면 자바 쪽 필드 이름을 바꿀 때 화면이 조용히 깨집니다.
 *
 * <h2>빈 값은 아예 적지 않습니다</h2>
 *
 * <p>표지 없는 책, 출판사를 모르는 책이 적지 않습니다. {@code null} 을 적으면 한 권에
 * 수십 바이트씩 늘고, 20만 권이면 그것만 몇 MB 입니다. 화면은 없는 항목을 없는 것으로
 * 다루므로 {@code null} 과 다를 것이 없습니다.
 */
final class ShelfJson {

    private ShelfJson() {}

    /**
     * 서가에 세운 한 권. <b>화면이 쓰는 것만 담습니다.</b>
     *
     * <p>자료실 이름처럼 그 조각 안에서 늘 같은 값은 담지 않습니다. 차림표에 한 번만
     * 적어 두면 되는 것을 200번 되풀이할 이유가 없습니다.
     */
    static String item(ShelfItem item) {
        StringBuilder out = new StringBuilder(220).append('{');
        next(out, "isbn", item.isbn13());
        next(out, "title", item.title());
        next(out, "author", item.author());
        next(out, "publisher", item.publisher());
        next(out, "year", item.pubYear());
        next(out, "cover", item.coverUrl());
        next(out, "call", item.callText());
        next(out, "chosung", item.chosung());
        /*
          **분류명을 한 권마다 싣습니다.** 같은 조각 안에서 거의 같은 값이 되풀이되지만,
          서가의 큰 제목이 「지금 어느 갈래 앞에 서 있는가」를 말하는 자리라 스크롤하는
          동안 바뀌어야 합니다. 되풀이되는 글자는 gzip 이 거의 그대로 지웁니다.

          **우리가 분류번호로 갈래 이름을 지어내지 않습니다.** 정보나루가 class_nm 으로
          주는 값이고, KDC 표를 우리가 들고 있으면 그 표가 틀리는 날 서가가 엉뚱한
          이름을 답합니다.
        */
        next(out, "classNm", item.classNm());
        return out.append('}').toString();
    }

    /** 차림표. 화면이 맨 처음 받는 것입니다. */
    static String meta(ShelfMeta meta) {
        StringBuilder out = new StringBuilder(1024).append('{');
        next(out, "libCode", meta.libCode());
        next(out, "kdc", meta.kdc());
        next(out, "asOf", meta.asOf());
        next(out, "checkedAt", meta.checkedAt());
        num(out, "chunkSize", meta.chunkSize());
        num(out, "count", meta.count());
        num(out, "reported", meta.reported());
        comma(out);
        quote(out, "rooms").append(":[");

        for (int i = 0; i < meta.rooms().size(); i++) {
            if (i > 0) out.append(',');
            room(out, meta.rooms().get(i));
        }
        return out.append("]}").toString();
    }

    private static void room(StringBuilder out, ShelfMeta.Room room) {
        out.append('{');
        next(out, "slug", room.slug());
        next(out, "code", room.code());
        next(out, "name", room.name());
        num(out, "count", room.count());
        num(out, "chunks", room.chunks());
        next(out, "firstCall", room.firstCall());
        next(out, "lastCall", room.lastCall());

        comma(out);
        quote(out, "chosungAt").append(":{");
        boolean started = false;
        for (Map.Entry<String, Integer> entry : room.chosungAt().entrySet()) {
            if (started) out.append(',');
            quote(out, entry.getKey()).append(':').append(entry.getValue());
            started = true;
        }
        out.append("}}");
    }

    // ── 글자로 옮기기 ────────────────────────────────────────────────────

    /**
     * 항목 하나. 빈 값이면 아무것도 적지 않습니다.
     *
     * <p><b>쉼표는 자리 번호가 아니라 지금까지 적힌 마지막 글자를 보고 붙입니다.</b>
     * 빈 값을 건너뛰는 탓에 몇 번째 항목인지로는 「앞에 무엇이 있었는가」를 알 수
     * 없습니다. 자리 번호로 세면 첫 항목이 비었을 때 쉼표가 하나 남아 JSON 이 통째로
     * 깨지고, 그 조각 200권이 화면에 나오지 못합니다.
     */
    private static void next(StringBuilder out, String key, String value) {
        if (isBlank(value)) return;
        comma(out);
        quote(out, key).append(':');
        quote(out, value);
    }

    /** 숫자 항목. 0 도 뜻이 있으므로 건너뛰지 않습니다. */
    private static void num(StringBuilder out, String key, int value) {
        comma(out);
        quote(out, key).append(':').append(value);
    }

    /** 여는 중괄호 바로 뒤가 아니면 쉼표가 필요합니다. */
    private static void comma(StringBuilder out) {
        if (out.charAt(out.length() - 1) != '{') out.append(',');
    }

    /**
     * 큰따옴표로 감싸며 <b>JSON 이 깨질 수 있는 글자를 전부 막습니다.</b>
     *
     * <p>표제와 저자명은 도서관이 적어 넣은 값이라 무엇이 들어 있을지 모릅니다. 실제로
     * 큰따옴표가 든 표제가 있고, 제어 문자가 섞여 들어오기도 합니다. 한 권이 깨지면
     * <b>그 조각 200권이 통째로</b> 화면에 나오지 못합니다.
     */
    private static StringBuilder quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // 제어 문자는 그대로 두면 JSON 규격에 어긋납니다.
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
