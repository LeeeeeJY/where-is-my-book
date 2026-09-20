package kr.wimb.shelf;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 적어 둔 차림표를 <b>다시 읽습니다.</b>
 *
 * <h2>적을 때는 손으로 적고 읽을 때는 도구를 씁니다</h2>
 *
 * <p>적는 쪽({@link ShelfJson})이 손으로 적는 이유는 20만 권을 힙에 들지 않으려는
 * 것이었습니다. 읽는 쪽은 그런 사정이 없습니다. 파일 하나가 수십 KB 이고, 서가를 열
 * 때마다 한 번 읽습니다. <b>손으로 파싱하면 따옴표와 이스케이프를 또 다루게 되는데,
 * 그건 틀리기 쉬운 데다 틀려도 조용합니다.</b>
 *
 * <p>모르는 항목은 건너뜁니다. 서버는 새로 배포됐는데 볼륨에 예전 모양의 차림표가
 * 남아 있을 수 있고, 그때 <b>통째로 못 읽는 것보다 읽히는 만큼 읽는 편</b>이 낫습니다.
 * 못 읽으면 그 서가는 없는 것이 되어 사람이 다시 세우기를 기다리게 됩니다.
 */
final class ShelfMetaReader {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ShelfMetaReader() {}

    /** 못 읽으면 비어 있습니다. <b>예외를 올리지 않습니다.</b> */
    static Optional<ShelfMeta> parse(byte[] json) {
        try {
            JsonNode tree = JSON.readTree(json);
            if (tree == null || !tree.isObject()) return Optional.empty();
            return Optional.ofNullable(JSON.treeToValue(migrated((ObjectNode) tree), ShelfMeta.class))
                    .map(ShelfMetaReader::filled);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 예전 이름으로 적힌 값을 <b>지금 이름으로 옮겨 읽습니다.</b>
     *
     * <p>항목 이름을 바꾸면 이미 적어 둔 파일은 그대로 남습니다. 옮겨 읽지 않으면 그
     * 서가는 <b>실제로 가지고 있는 것을 없는 것으로</b> 답하게 되고, 화면은 그 단추를
     * 내리지 않습니다. 실제로 {@code findable} 을 {@link ShelfMeta#SHAPE_VERSION} 으로
     * 합치면서 이 길을 만들지 않아, <b>찾기 색인이 멀쩡히 있는 서가가 다시 세우기 전까지
     * 「책 찾기」를 잃었습니다.</b>
     *
     * <p>다시 세우면 어차피 낫는다는 것이 이유가 되지 않습니다. 세우는 데 몇십 초가
     * 걸리고 그동안 사람은 <b>기능이 사라진 화면</b>을 봅니다.
     */
    private static ObjectNode migrated(ObjectNode meta) {
        // `findable: true` 는 찾기 색인이 있다는 뜻이었습니다. 그것이 판 번호 1 입니다.
        if (!meta.hasNonNull("shapeVersion") && meta.path("findable").asBoolean(false)) {
            meta.put("shapeVersion", 1);
        }
        return meta;
    }

    /**
     * 없던 항목을 <b>빈 것으로 채웁니다. {@code null} 로 두지 않습니다.</b>
     *
     * <p>차림표는 예전 모양으로 적힌 것이 볼륨에 남아 있을 수 있습니다. 나중에 늘린
     * 항목은 그런 파일에 없고, 레코드로 읽으면 그 자리가 {@code null} 이 됩니다. 그대로
     * 들고 다니면 <b>읽을 때가 아니라 한참 뒤에 그 값을 만지는 자리에서 터집니다.</b>
     * 실제로 갈래 구간을 늘렸을 때 「확인한 날짜만 고쳐 쓰는」 길이 그렇게 막혔고,
     * 그 자리는 예외를 삼키고 있어서 <b>날짜가 조용히 안 바뀌는 것</b>으로만 드러났습니다.
     *
     * <p>모르는 항목을 건너뛰고 읽히는 만큼 읽는다는 이 파일의 규칙을 끝까지 지키려면,
     * 읽어 낸 값도 <b>빠진 데 없는 모양</b>이어야 합니다.
     */
    private static ShelfMeta filled(ShelfMeta meta) {
        List<ShelfMeta.Room> rooms = meta.rooms() == null ? List.of() : meta.rooms();
        return new ShelfMeta(meta.libCode(), meta.kdc(), meta.asOf(), meta.checkedAt(),
                meta.chunkSize(), meta.count(), meta.reported(), meta.keyVersion(),
                meta.shapeVersion(), rooms.stream().map(ShelfMetaReader::filled).toList());
    }

    private static ShelfMeta.Room filled(ShelfMeta.Room room) {
        return new ShelfMeta.Room(room.slug(), room.code(), room.name(), room.count(),
                room.chunks(), room.firstCall(), room.lastCall(),
                room.chosungAt() == null ? Map.of() : room.chosungAt(),
                room.sections() == null ? List.of() : room.sections());
    }
}
