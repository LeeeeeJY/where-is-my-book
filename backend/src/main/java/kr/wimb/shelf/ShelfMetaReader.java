package kr.wimb.shelf;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
            return Optional.ofNullable(JSON.readValue(json, ShelfMeta.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
