package com.example.aimindmap.agenteval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * P1-E2 检索质量测量的<b>纯函数</b>部分：gold 解析、gold → payload 映射、命中判定、指标聚合。
 *
 * <p>本类不连 Spring、不连网络、不读文件，方便单独核对口径；真正的检索与落盘在
 * {@link RetrievalQualityIT}。
 *
 * <p><b>三条口径（报告里必须原样打印，避免数字被误读）</b>
 * <ol>
 *   <li><b>gold 短名 → 向量 payload 类型</b>：{@code document → document_chunk}、
 *       {@code mindmap → mindmap_node}、{@code question → question}；{@code memory} 不参与本轮
 *       （检索路径不同，见 {@link GoldType#searchable()}）。</li>
 *   <li><b>命中</b>：payload 的 {@code (resource_type, source_id, chunk_index)} 与 gold 的
 *       {@code (type, resourceId, chunkIndex)} 对齐。导图是唯一的例外：Seeder 写进 Qdrant 的
 *       {@code source_id} 形如 {@code "888911:n-root"}（导图 id + 节点 id），而 gold 只写到
 *       {@code "888911"}，因此导图按「同一张导图下任意节点命中即算命中」对齐（前缀
 *       {@code "888911:"}）。这个放宽会抬高导图类分数，所以同时算一份
 *       <b>严格口径</b>（resourceId 必须完全相等）作为敏感性分析，两个数都进报告。</li>
 *   <li><b>recall@5</b> = |gold ∩ top5| / |gold|；<b>MRR</b> 用整条有序 hit 列表里
 *       <b>第一个</b> gold 命中的 rank（没命中 = 0），另给 {@code MRR@5}（只看前 5）。</li>
 * </ol>
 *
 * <p>统计口径：<b>按用例取宏平均</b>（每条用例权重相同），不是按 gold 条目微平均。
 */
final class RetrievalQualityMetrics {

    /** 本轮 recall 的截断位置（方案 §P1-5 第 580 行写的就是 recall@5）。 */
    static final int RECALL_AT = 5;

    private RetrievalQualityMetrics() {
    }

    // ------------------------------------------------------------------
    // 1) gold 短名 → payload 类型映射
    // ------------------------------------------------------------------

    /** gold 里出现的四类资源；短名与向量 payload 的 {@code resource_type} 不是同一个字符串。 */
    enum GoldType {
        DOCUMENT("document", "document_chunk", true),
        MINDMAP("mindmap", "mindmap_node", true),
        QUESTION("question", "question", true),
        /** 记忆走 {@code searchTopMemory} 独立路径，不在本轮检索路径内（整条用例排除）。 */
        MEMORY("memory", "memory", false);

        private final String shortName;
        private final String wireName;
        private final boolean searchable;

        GoldType(String shortName, String wireName, boolean searchable) {
            this.shortName = shortName;
            this.wireName = wireName;
            this.searchable = searchable;
        }

        String shortName() {
            return shortName;
        }

        String wireName() {
            return wireName;
        }

        /** true = 本轮检索层能查（document / mindmap / question）；false = memory（排除）。 */
        boolean searchable() {
            return searchable;
        }

        static GoldType fromShort(String value) {
            for (GoldType type : values()) {
                if (type.shortName.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("gold_chunks 的资源类型未知：" + value
                    + "（允许 document / mindmap / question / memory）");
        }
    }

    /** 一条 gold：{@code type:resourceId:chunkIndex}，例如 {@code document:888901:1}。 */
    record GoldRef(GoldType type, String resourceId, int chunkIndex, String raw) {

        /** 同一条 gold 只算一次（防止用例里重复写同一片段）。 */
        String key() {
            return type.shortName() + ":" + resourceId + ":" + chunkIndex;
        }
    }

    /** 检索层返回的一条 hit（已从 {@code VectorHit} 摘出指标需要的字段）。 */
    record HitRef(String sourceType, String sourceId, int chunkIndex, double score) {

        String label() {
            return sourceType + ":" + sourceId + ":" + chunkIndex;
        }
    }

    /** 严格解析 gold；格式不对直接抛错（宁可启动就失败，也不带着脏数据算指标）。 */
    static GoldRef parseGold(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("gold_chunks 不能为空串");
        }
        String[] parts = raw.trim().split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("gold_chunks 格式必须是 type:resourceId:chunkIndex，实际="
                    + raw + "（期望例如 document:888901:1）");
        }
        GoldType type = GoldType.fromShort(parts[0]);
        String resourceId = parts[1];
        if (resourceId.isBlank()) {
            throw new IllegalArgumentException("gold_chunks 的 resourceId 不能为空：" + raw);
        }
        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("gold_chunks 的 chunkIndex 必须是整数，实际=" + raw, e);
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("gold_chunks 的 chunkIndex 不能为负：" + raw);
        }
        return new GoldRef(type, resourceId, chunkIndex, raw);
    }

    // ------------------------------------------------------------------
    // 2) 命中判定
    // ------------------------------------------------------------------

    /**
     * 正式口径的命中判定。
     *
     * <p>document / question：三个字段全等。mindmap：{@code chunk_index} 相等，且 payload 的
     * {@code source_id} 等于 gold 的 resourceId（导图 id）或以 {@code "导图id:"} 开头
     * （= 该导图下的某个节点）。
     */
    static boolean matches(GoldRef gold, HitRef hit) {
        if (gold == null || hit == null) {
            return false;
        }
        if (!gold.type().wireName().equals(hit.sourceType())) {
            return false;
        }
        if (gold.chunkIndex() != hit.chunkIndex()) {
            return false;
        }
        if (gold.type() == GoldType.MINDMAP) {
            return hit.sourceId().equals(gold.resourceId())
                    || hit.sourceId().startsWith(gold.resourceId() + ":");
        }
        return hit.sourceId().equals(gold.resourceId());
    }

    /** 严格口径：resourceId 必须完全相等（导图不放宽）；只用于敏感性分析。 */
    static boolean strictMatches(GoldRef gold, HitRef hit) {
        return gold != null && hit != null
                && gold.type().wireName().equals(hit.sourceType())
                && gold.chunkIndex() == hit.chunkIndex()
                && hit.sourceId().equals(gold.resourceId());
    }

    // ------------------------------------------------------------------
    // 3) 单条用例评分
    // ------------------------------------------------------------------

    /**
     * 一条用例的得分。
     *
     * @param firstGoldRank    第一个被命中的 gold 在整条有序列表里的 rank（1 起；0 = 没命中）
     * @param goldHitsTop1     第 1 名里命中的 gold 条目数（用来看“天花板效应”有多严重）
     * @param goldHitsTop5     前 5 条里命中的 gold 条目数（正式口径）
     * @param goldHitsTop5Strict 前 5 条里命中的 gold 条目数（严格口径，仅敏感性分析）
     * @param goldCount        gold 条目总数（分母）
     * @param recallAt1        goldHitsTop1 / goldCount
     * @param recallAt5        goldHitsTop5 / goldCount
     * @param recallAt5Strict  goldHitsTop5Strict / goldCount
     * @param mrr              1 / firstGoldRank（整条列表；没命中 0）
     * @param mrrAt5           1 / firstGoldRank（仅当 rank ≤ 5；否则 0）
     * @param matchedGoldKeys  前 5 条里命中的 gold（正式口径），用于明细表
     */
    record CaseScore(
            int firstGoldRank,
            int goldHitsTop1,
            int goldHitsTop5,
            int goldHitsTop5Strict,
            int goldCount,
            double recallAt1,
            double recallAt5,
            double recallAt5Strict,
            double mrr,
            double mrrAt5,
            List<String> matchedGoldKeys) {

        public CaseScore {
            matchedGoldKeys = matchedGoldKeys == null ? List.of() : List.copyOf(matchedGoldKeys);
        }
    }

    /** 对一条用例算分：recall@1 / recall@{@value #RECALL_AT} + MRR（整条列表） + MRR@5。 */
    static CaseScore score(List<GoldRef> golds, List<HitRef> hits) {
        List<GoldRef> uniqueGolds = dedupeGolds(golds);
        if (uniqueGolds.isEmpty()) {
            throw new IllegalArgumentException("没有 gold 的用例不应进入检索质量测量（分母会是 0）");
        }
        int firstGoldRank = 0;
        int hitsAtTop1 = 0;
        int hitsAtTop = 0;
        int hitsAtTopStrict = 0;
        List<String> matched = new ArrayList<>();
        for (GoldRef gold : uniqueGolds) {
            int primaryRank = firstMatchRank(gold, hits, false);
            int strictRank = firstMatchRank(gold, hits, true);
            if (primaryRank > 0) {
                if (firstGoldRank == 0 || primaryRank < firstGoldRank) {
                    firstGoldRank = primaryRank;
                }
                if (primaryRank == 1) {
                    hitsAtTop1++;
                }
                if (primaryRank <= RECALL_AT) {
                    hitsAtTop++;
                    matched.add(gold.key());
                }
            }
            if (strictRank > 0 && strictRank <= RECALL_AT) {
                hitsAtTopStrict++;
            }
        }
        int goldCount = uniqueGolds.size();
        double mrr = firstGoldRank == 0 ? 0.0d : 1.0d / firstGoldRank;
        double mrrAt5 = firstGoldRank == 0 || firstGoldRank > RECALL_AT ? 0.0d : 1.0d / firstGoldRank;
        return new CaseScore(
                firstGoldRank,
                hitsAtTop1,
                hitsAtTop,
                hitsAtTopStrict,
                goldCount,
                hitsAtTop1 / (double) goldCount,
                hitsAtTop / (double) goldCount,
                hitsAtTopStrict / (double) goldCount,
                mrr,
                mrrAt5,
                matched);
    }

    private static int firstMatchRank(GoldRef gold, List<HitRef> hits, boolean strict) {
        for (int index = 0; index < hits.size(); index++) {
            HitRef hit = hits.get(index);
            boolean hitMatched = strict ? strictMatches(gold, hit) : matches(gold, hit);
            if (hitMatched) {
                return index + 1;
            }
        }
        return 0;
    }

    static List<GoldRef> dedupeGolds(List<GoldRef> golds) {
        Map<String, GoldRef> unique = new LinkedHashMap<>();
        if (golds != null) {
            for (GoldRef gold : golds) {
                unique.putIfAbsent(gold.key(), gold);
            }
        }
        return List.copyOf(unique.values());
    }

    // ------------------------------------------------------------------
    // 4) 聚合
    // ------------------------------------------------------------------

    /** 一条用例在某个配置下的一次测量结果（含失败原因，不把失败悄悄当成 0 分）。 */
    record CaseMeasurement(
            String caseId,
            String category,
            String intent,
            String goldTypes,
            List<GoldRef> golds,
            List<HitRef> hits,
            CaseScore score,
            long latencyMs,
            String error) {

        public CaseMeasurement {
            golds = golds == null ? List.of() : List.copyOf(golds);
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        boolean failed() {
            return error != null && !error.isBlank();
        }
    }

    /** 一组用例的汇总（宏平均 + 耗时百分位）。 */
    record Aggregate(
            int cases,
            int failedCases,
            double meanRecallAt1,
            double meanRecall5,
            double meanRecall5Strict,
            double meanMrr,
            double meanMrr5,
            double avgHits,
            long p50Ms,
            long p95Ms,
            long totalMs) {

        static Aggregate empty() {
            return new Aggregate(0, 0, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0L, 0L, 0L);
        }
    }

    /** 按用例宏平均；耗时用最近秩百分位（与 {@link EvalMetrics#percentile} 同一实现）。 */
    static Aggregate aggregate(List<CaseMeasurement> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            return Aggregate.empty();
        }
        int failed = 0;
        double recall1Sum = 0.0d;
        double recallSum = 0.0d;
        double recallStrictSum = 0.0d;
        double mrrSum = 0.0d;
        double mrr5Sum = 0.0d;
        long hitSum = 0L;
        long totalMs = 0L;
        List<Long> latencies = new ArrayList<>(measurements.size());
        for (CaseMeasurement measurement : measurements) {
            if (measurement.failed()) {
                failed++;
            }
            recall1Sum += measurement.score().recallAt1();
            recallSum += measurement.score().recallAt5();
            recallStrictSum += measurement.score().recallAt5Strict();
            mrrSum += measurement.score().mrr();
            mrr5Sum += measurement.score().mrrAt5();
            hitSum += measurement.hits().size();
            totalMs += measurement.latencyMs();
            latencies.add(measurement.latencyMs());
        }
        int count = measurements.size();
        return new Aggregate(
                count,
                failed,
                round(recall1Sum / count),
                round(recallSum / count),
                round(recallStrictSum / count),
                round(mrrSum / count),
                round(mrr5Sum / count),
                round(hitSum / (double) count),
                EvalMetrics.percentile(latencies, 50),
                EvalMetrics.percentile(latencies, 95),
                totalMs);
    }

    /** 按某个维度分组汇总；分组顺序按首次出现（保持用例集原有分类顺序）。 */
    static Map<String, Aggregate> groupBy(List<CaseMeasurement> measurements,
                                          Function<CaseMeasurement, String> keyFunction) {
        Map<String, List<CaseMeasurement>> grouped = new LinkedHashMap<>();
        for (CaseMeasurement measurement : measurements) {
            String key = keyFunction.apply(measurement);
            if (key == null || key.isBlank()) {
                key = "unknown";
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(measurement);
        }
        Map<String, Aggregate> result = new LinkedHashMap<>();
        grouped.forEach((key, value) -> result.put(key, aggregate(value)));
        return result;
    }

    /** 平均证据条数 = 平均返回的 hit 条数（不是去重后的文档数）。 */
    static double averageHits(List<CaseMeasurement> measurements) {
        return aggregate(measurements).avgHits();
    }

    private static double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
