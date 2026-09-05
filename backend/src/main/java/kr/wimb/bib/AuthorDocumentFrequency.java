package kr.wimb.bib;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 저자 토큰의 말뭉치 출현 빈도표입니다.
 *
 * <p><b>이 표가 있어야 한국어 저자명 대조가 제대로 성립합니다.</b>
 * {@code 롤링}은 드물어서 그것 하나가 공유되면 동일인을 확정할 수 있지만,
 * {@code 김}은 흔해서 아무것도 확정하지 못합니다. 이 차이를 표 없이 알 수는 없습니다.
 *
 * <p>표는 군집화를 돌릴 때마다 대상 서지 전체에서 다시 만듭니다.
 * 말뭉치가 작으면 드문 토큰도 비율이 높게 나와 지름길이 작동하지 않는데,
 * 그 방향(병합을 덜 하는 쪽)이 안전하므로 그대로 둡니다.
 */
public final class AuthorDocumentFrequency implements AuthorTokens.DocumentFrequency {

    private final Map<String, Double> ratios;
    private final int documentCount;

    private AuthorDocumentFrequency(Map<String, Double> ratios, int documentCount) {
        this.ratios = ratios;
        this.documentCount = documentCount;
    }

    /** 저자 표기 목록에서 빈도표를 만듭니다. 한 서지가 문서 하나입니다. */
    public static AuthorDocumentFrequency build(List<AuthorTokens> corpus) {
        Map<String, Integer> counts = new HashMap<>();
        int documents = 0;
        for (AuthorTokens tokens : corpus) {
            if (tokens.isEmpty()) continue;   // 저자가 없는 자료는 분모에서 뺍니다
            documents++;
            // 한 서지 안에서 같은 토큰이 여러 번 나와도 한 번만 셉니다.
            Set<String> distinct = Set.copyOf(tokens.tokens());
            for (String token : distinct) counts.merge(token, 1, Integer::sum);
        }
        Map<String, Double> ratios = new HashMap<>();
        if (documents > 0) {
            for (var entry : counts.entrySet()) {
                ratios.put(entry.getKey(), (double) entry.getValue() / documents);
            }
        }
        return new AuthorDocumentFrequency(Map.copyOf(ratios), documents);
    }

    @Override
    public double ratioOf(String token) {
        // 말뭉치에 없는 토큰은 흔하다고 봅니다. 근거가 없을 때 병합을 밀어붙이지 않습니다.
        return ratios.getOrDefault(token, 1.0);
    }

    public int documentCount() {
        return documentCount;
    }

    /** 빈도가 낮은 순서로 몇 개만 들여다볼 때 씁니다. 표가 제대로 만들어졌는지 눈으로 확인하는 용도입니다. */
    public List<Map.Entry<String, Double>> rarest(int limit) {
        return ratios.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(limit)
                .toList();
    }
}
