package com.example.aimindmap.vectorindex;

import com.example.aimindmap.vectorindex.QdrantVectorGateway.RetrievedPoint;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 阶段 2 真实环境冒烟（仅在 STAGE2_SMOKE=1 时运行；普通 mvn test 自动跳过）。
 * 依赖真实 Qdrant 与 config/application.yml 中的真实 Embedding key。
 * 直接调服务验证：语义检索（top8 -> 合并 -> 3~5 条）与低分阈值未命中。
 */
@EnabledIfEnvironmentVariable(named = "STAGE2_SMOKE", matches = "1")
// 4-3 起 worker 调度改读 app.mq.requeue-scan-delay-ms（MQ 开启时仅做对账补投）；
// 手工冒烟期间把间隔拉到 1 小时，避免后台调度干扰。
@SpringBootTest(properties = "app.mq.requeue-scan-delay-ms=3600000")
class VectorIndexSmokeManualTest {

    @Autowired private VectorSearchService searchService;
    @Autowired private QdrantVectorGateway gateway;
    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void semanticSearchAndLowScoreThreshold() {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "stage2smoke");

        List<String> queries = List.of(
                "什么是进程？进程和线程有什么区别",
                "进程调度有哪些常见的算法",
                "内存管理的主要功能是什么，什么是虚拟内存",
                "进程管理有哪些主要功能");

        for (String query : queries) {
            float[] queryVector = embeddingModel.embed(query);
            List<RetrievedPoint> raw = gateway.search(
                    "learning_resources_text_embedding_v3_1024",
                    VectorSearchService.buildFilter(userId, null), queryVector, 8);
            System.out.println("==== QUERY: " + query);
            System.out.println("-- raw top8 count=" + raw.size());
            for (RetrievedPoint point : raw) {
                System.out.println("   raw score=" + String.format("%.4f", point.score())
                        + " " + point.payload().get("resource_type")
                        + " source=" + point.payload().get("source_id")
                        + " chunk=" + point.payload().get("chunk_index"));
            }
            VectorSearchService.SearchOutcome outcome = searchService.search(userId, query, null);
            System.out.println("-- service matched=" + outcome.matched()
                    + " returned=" + outcome.hits().size());
            for (VectorSearchService.VectorHit hit : outcome.hits()) {
                System.out.println("   hit " + String.format("%.4f", hit.score())
                        + " " + hit.sourceType() + " source=" + hit.sourceId()
                        + " title=" + hit.title()
                        + " path=" + String.join(" / ", hit.sourcePath()));
            }
        }

        String offTopic = "如何制作法式焦糖布丁？需要哪些食材和详细步骤";
        float[] offVector = embeddingModel.embed(offTopic);
        List<RetrievedPoint> offRaw = gateway.search(
                "learning_resources_text_embedding_v3_1024",
                VectorSearchService.buildFilter(userId, null), offVector, 8);
        System.out.println("==== OFFTOPIC: " + offTopic);
        double bestRaw = offRaw.isEmpty() ? Double.NaN : offRaw.get(0).score();
        System.out.println("-- raw best score=" + bestRaw);
        VectorSearchService.SearchOutcome offOutcome = searchService.search(userId, offTopic, null);
        System.out.println("-- service matched=" + offOutcome.matched()
                + " hits=" + offOutcome.hits().size());
    }
    @Test
    void crossAccountIsolation() {
        Long userOne = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "stage2smoke");
        Long userTwo = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "stage2smoke2");

        List<String> sharedTopicQueries = List.of(
                "进程调度有哪些常见算法",
                "什么是虚拟内存，分页和分段的作用",
                "进程和线程有什么区别");

        for (String query : sharedTopicQueries) {
            float[] queryVector = embeddingModel.embed(query);
            List<RetrievedPoint> oneRaw = gateway.search(
                    "learning_resources_text_embedding_v3_1024",
                    VectorSearchService.buildFilter(userOne, null), queryVector, 100);
            List<RetrievedPoint> twoRaw = gateway.search(
                    "learning_resources_text_embedding_v3_1024",
                    VectorSearchService.buildFilter(userTwo, null), queryVector, 100);
            System.out.println("==== CROSS QUERY: " + query);
            System.out.println("-- user1 raw=" + oneRaw.size()
                    + " allOwned=" + oneRaw.stream().allMatch(p ->
                            Long.valueOf(((Number) p.payload().get("user_id")).longValue()).equals(userOne)));
            System.out.println("-- user2 raw=" + twoRaw.size()
                    + " allOwned=" + twoRaw.stream().allMatch(p ->
                            Long.valueOf(((Number) p.payload().get("user_id")).longValue()).equals(userTwo)));

            VectorSearchService.SearchOutcome oneOutcome =
                    searchService.search(userOne, query, null);
            VectorSearchService.SearchOutcome twoOutcome =
                    searchService.search(userTwo, query, null);
            System.out.println("-- user1 service matched=" + oneOutcome.matched()
                    + " sources=" + oneOutcome.hits().stream()
                        .map(h -> h.sourceType() + ":" + h.sourceId()).toList());
            System.out.println("-- user2 service matched=" + twoOutcome.matched()
                    + " sources=" + twoOutcome.hits().stream()
                        .map(h -> h.sourceType() + ":" + h.sourceId()).toList());
        }
    }
}
