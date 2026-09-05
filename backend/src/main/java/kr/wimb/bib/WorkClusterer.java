package kr.wimb.bib;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 서지를 저작 단위로 묶습니다.
 *
 * <p>후보 묶기로 비교 대상을 줄이고, {@link WorkMatcher}의 규칙으로 쌍마다 판정한 뒤,
 * <b>병합 판정만</b> union-find로 합칩니다. 검토 판정은 합치지 않고 큐에 쌓아 사람이 봅니다.
 *
 * <p>판정 근거를 간선으로 남기는 것이 중요합니다. 잘못된 묶음을 만났을 때 어느 규칙이
 * 그랬는지 알 수 없으면 고칠 수가 없습니다.
 */
public final class WorkClusterer {

    private WorkClusterer() {}

    /** 한 서지. {@code previousWorkId}는 직전 군집화에서 받았던 번호이고, 없으면 null입니다. */
    public record Input(String recordId, WorkMatcher.Candidate candidate, Integer previousWorkId) {
        public static Input of(String recordId, String title, String authors) {
            return new Input(recordId, WorkMatcher.Candidate.of(title, authors), null);
        }
    }

    public enum OverrideKind { FORCE_MERGE, FORCE_SPLIT }

    /** 사람이 손으로 고친 판정. 자동 판정을 항상 이깁니다. */
    public record Override(String recordA, String recordB, OverrideKind kind) {}

    public record Edge(String a, String b, String ruleCode, double confidence) {}

    /**
     * @param workIdAliases 흡수된 이전 번호에서 새 번호로. 301 전달에 씁니다.
     * @param violations    분리 지정을 지키지 못한 쌍. 사람이 봐야 합니다.
     */
    public record Result(
            Map<String, Integer> workIdByRecord,
            List<Edge> mergeEdges,
            List<Edge> reviewQueue,
            Map<Integer, Integer> workIdAliases,
            List<String> violations
    ) {}

    /**
     * 한 후보 묶음이 이보다 커지면 흔한 표제입니다(「국어」, 「일기」).
     * 이때는 가장 엄격한 규칙만 허용합니다.
     */
    private static final int MAX_BLOCK_SIZE = 500;

    /** 분리 지정을 지키려고 간선을 끊는 시도의 상한. */
    private static final int MAX_REPAIR_ROUNDS = 5;

    private static final Set<String> STRICT_RULES = Set.of("R0_ISBN_EXACT", "R3_TITLE_AUTHOR");

    /** 빈도표를 이 말뭉치에서 직접 만들어 씁니다. */
    public static Result cluster(List<Input> inputs, List<Override> overrides, IntSupplier idGenerator) {
        AuthorDocumentFrequency df = AuthorDocumentFrequency.build(
                inputs.stream().map(i -> i.candidate().authorTokens()).toList());
        return cluster(inputs, overrides, df, idGenerator);
    }

    public static Result cluster(List<Input> inputs, List<Override> overrides,
                                 AuthorTokens.DocumentFrequency df, IntSupplier idGenerator) {

        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) indexOf.put(inputs.get(i).recordId(), i);

        List<Edge> mergeEdges = new ArrayList<>();
        List<Edge> reviewQueue = new ArrayList<>();
        compareWithinBlocks(inputs, df, mergeEdges, reviewQueue);

        // 사람이 고친 판정이 자동 판정을 이깁니다.
        Set<String> forcedSplits = new LinkedHashSet<>();
        for (Override o : overrides) {
            if (o.kind() == OverrideKind.FORCE_SPLIT) {
                forcedSplits.add(pairKey(o.recordA(), o.recordB()));
            } else {
                mergeEdges.add(new Edge(o.recordA(), o.recordB(), "FORCE_MERGE", 1.0));
            }
        }
        mergeEdges.removeIf(e -> forcedSplits.contains(pairKey(e.a(), e.b())));

        // 분리 지정한 쌍이 제3의 레코드를 거쳐 다시 이어지면, 그 경로에서 가장 약한 간선을 끊습니다.
        List<String> violations = new ArrayList<>();
        UnionFind uf = union(inputs.size(), mergeEdges, indexOf);
        for (int round = 0; round < MAX_REPAIR_ROUNDS; round++) {
            List<String> stillJoined = new ArrayList<>();
            for (Override o : overrides) {
                if (o.kind() != OverrideKind.FORCE_SPLIT) continue;
                Integer a = indexOf.get(o.recordA());
                Integer b = indexOf.get(o.recordB());
                if (a == null || b == null) continue;
                if (uf.find(a) == uf.find(b)) stillJoined.add(pairKey(o.recordA(), o.recordB()));
            }
            if (stillJoined.isEmpty()) break;

            boolean cut = false;
            for (String pair : stillJoined) {
                String[] ends = pair.split(" ", 2);
                Edge weakest = weakestEdgeOnPath(mergeEdges, ends[0], ends[1]);
                if (weakest != null) {
                    mergeEdges.remove(weakest);
                    cut = true;
                }
            }
            uf = union(inputs.size(), mergeEdges, indexOf);
            if (!cut) {
                // 끊을 간선이 없는데 여전히 이어져 있다면 더 해 볼 것이 없습니다.
                violations.addAll(stillJoined);
                break;
            }
            if (round == MAX_REPAIR_ROUNDS - 1) violations.addAll(stillJoined);
        }

        return assignWorkIds(inputs, uf, mergeEdges, reviewQueue, violations, idGenerator);
    }

    // 후보 묶기와 쌍 판정
    // ---------------------------------------------------------------

    private static void compareWithinBlocks(List<Input> inputs, AuthorTokens.DocumentFrequency df,
                                            List<Edge> mergeEdges, List<Edge> reviewQueue) {
        Map<String, List<Integer>> blocks = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            TitleParts title = inputs.get(i).candidate().title();
            addToBlock(blocks, title.titleKeyCore(), i);
            for (String alias : title.aliasKeys()) addToBlock(blocks, alias, i);
            // 부표제가 표제로 흘러 들어온 자료를 잡기 위한 앞 8글자 묶음입니다.
            String core = title.titleKeyCore();
            if (core.length() > 8) addToBlock(blocks, "P8:" + core.substring(0, 8), i);
        }

        Set<String> compared = new HashSet<>();
        for (List<Integer> block : blocks.values()) {
            if (block.size() < 2) continue;
            boolean strict = block.size() > MAX_BLOCK_SIZE;
            for (int x = 0; x < block.size(); x++) {
                for (int y = x + 1; y < block.size(); y++) {
                    Input a = inputs.get(block.get(x));
                    Input b = inputs.get(block.get(y));
                    if (!compared.add(pairKey(a.recordId(), b.recordId()))) continue;

                    var decision = WorkMatcher.compare(a.candidate(), b.candidate(), df, false);
                    if (decision.isMerge()) {
                        // 흔한 표제 묶음에서는 가장 엄격한 규칙만 받아들입니다.
                        if (strict && !STRICT_RULES.contains(decision.ruleCode())) continue;
                        mergeEdges.add(new Edge(a.recordId(), b.recordId(),
                                decision.ruleCode(), decision.confidence()));
                    } else if (decision.verdict() == WorkMatcher.Verdict.REVIEW) {
                        reviewQueue.add(new Edge(a.recordId(), b.recordId(),
                                decision.ruleCode(), decision.confidence()));
                    }
                }
            }
        }
    }

    private static void addToBlock(Map<String, List<Integer>> blocks, String key, int index) {
        if (key == null || key.isBlank()) return;
        blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
    }

    // 안정적인 work_id 승계
    // ---------------------------------------------------------------

    /**
     * 군집마다 구성원들이 이전에 갖고 있던 번호의 최빈값을 물려받습니다.
     *
     * <p><b>재군집화 때 번호가 새로 매겨지면 공유된 URL이 전부 조용히 깨집니다.</b>
     * 흡수된 이전 번호는 별칭으로 남겨 301로 넘길 수 있게 합니다.
     */
    private static Result assignWorkIds(List<Input> inputs, UnionFind uf, List<Edge> mergeEdges,
                                        List<Edge> reviewQueue, List<String> violations,
                                        IntSupplier idGenerator) {
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            components.computeIfAbsent(uf.find(i), k -> new ArrayList<>()).add(i);
        }
        // 결과가 실행마다 같도록 구성원의 레코드 ID 순으로 처리합니다.
        List<List<Integer>> ordered = components.values().stream()
                .sorted(Comparator.comparing(c -> inputs.get(c.get(0)).recordId()))
                .toList();

        Map<String, Integer> workIdByRecord = new LinkedHashMap<>();
        Map<Integer, Integer> aliases = new LinkedHashMap<>();
        Set<Integer> claimed = new HashSet<>();

        for (List<Integer> component : ordered) {
            Map<Integer, Integer> votes = new LinkedHashMap<>();
            for (int i : component) {
                Integer prev = inputs.get(i).previousWorkId();
                if (prev != null) votes.merge(prev, 1, Integer::sum);
            }
            Integer inherited = votes.entrySet().stream()
                    // 표가 같으면 작은 번호를 씁니다. 결과가 실행마다 달라지지 않게 하려는 것입니다.
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey)
                    .filter(id -> !claimed.contains(id))
                    .findFirst().orElse(null);

            int assigned = inherited != null ? inherited : mint(idGenerator, claimed);
            claimed.add(assigned);

            for (int i : component) workIdByRecord.put(inputs.get(i).recordId(), assigned);
            for (Integer prev : votes.keySet()) {
                if (!prev.equals(assigned)) aliases.put(prev, assigned);
            }
        }
        return new Result(workIdByRecord, List.copyOf(mergeEdges), List.copyOf(reviewQueue),
                aliases, List.copyOf(violations));
    }

    private static int mint(IntSupplier idGenerator, Set<Integer> claimed) {
        int candidate;
        do {
            candidate = idGenerator.getAsInt();
        } while (claimed.contains(candidate));
        return candidate;
    }

    // 그래프 보조
    // ---------------------------------------------------------------

    private static UnionFind union(int size, List<Edge> edges, Map<String, Integer> indexOf) {
        UnionFind uf = new UnionFind(size);
        for (Edge e : edges) {
            Integer a = indexOf.get(e.a());
            Integer b = indexOf.get(e.b());
            if (a != null && b != null) uf.union(a, b);
        }
        return uf;
    }

    /** 두 레코드를 잇는 최단 경로에서 신뢰도가 가장 낮은 간선을 찾습니다. */
    private static Edge weakestEdgeOnPath(List<Edge> edges, String from, String to) {
        Map<String, List<Edge>> adjacency = new HashMap<>();
        for (Edge e : edges) {
            adjacency.computeIfAbsent(e.a(), k -> new ArrayList<>()).add(e);
            adjacency.computeIfAbsent(e.b(), k -> new ArrayList<>()).add(e);
        }
        Map<String, Edge> cameBy = new HashMap<>();
        Set<String> visited = new HashSet<>();
        visited.add(from);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);

        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (node.equals(to)) break;
            for (Edge e : adjacency.getOrDefault(node, List.of())) {
                String next = e.a().equals(node) ? e.b() : e.a();
                if (visited.add(next)) {
                    cameBy.put(next, e);
                    queue.add(next);
                }
            }
        }
        if (!visited.contains(to)) return null;

        Edge weakest = null;
        String node = to;
        while (cameBy.containsKey(node)) {
            Edge e = cameBy.get(node);
            if (weakest == null || e.confidence() < weakest.confidence()) weakest = e;
            node = e.a().equals(node) ? e.b() : e.a();
        }
        return weakest;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + " " + b : b + " " + a;
    }

    private static final class UnionFind {
        private final int[] parent;

        UnionFind(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
