package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.ai.AiChatRequest;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * P1-C1「迷你 Plan」的 Planner 单测（不真调 AI：全部走 {@link FakeAiGateway}）。
 *
 * <p>覆盖方案 §P1-2 的 Planner 契约：正常 1~3 子问题、超 3 截断、query/resources 校验、
 * 非法 JSON / 非对象、网关异常、超时、预算不足，并核对<b>任何失败都降级且稳定错误码</b>，
 * 从不向外抛异常。
 */
class AgentPlannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("测试数据不是合法 JSON：" + raw, e);
        }
    }

    // ===== 正常计划 =====

    @Test
    void twoSubQuestionsAreParsedAndResourceAliasesAreCanonicalized() {
        FakeAiGateway gateway = FakeAiGateway.returning(json("""
                {"complexity":"MULTI_HOP",
                 "subQuestions":[
                   {"id":"q1","question":"进程和线程有什么区别","resources":["document_chunk"],"query":"进程 线程 区别"},
                   {"id":"q2","question":"有哪些常见调度算法","resources":["mindmap_node","question"],"query":"进程调度算法"}],
                 "needsMemory":true,
                 "answerShape":"comparison"}
                """), new AiUsage(30, 15, 45));
        AgentPlanner planner = new AgentPlanner(gateway);

        AgentPlanner.Outcome outcome = planner.plan("对比进程和线程", 5_000L);

        AgentPlan plan = outcome.plan();
        assertThat(plan.degraded()).isFalse();
        assertThat(plan.degradeReason()).isNull();
        assertThat(plan.complexity()).isEqualTo(AgentPlan.COMPLEXITY_MULTI_HOP);
        assertThat(plan.subQuestions()).hasSize(2);
        assertThat(plan.subQuestions().get(0).id()).isEqualTo("q1");
        assertThat(plan.subQuestions().get(0).query()).isEqualTo("进程 线程 区别");
        // document_chunk 是真实 wire 名，规范化成短名 document
        assertThat(plan.subQuestions().get(0).resources()).containsExactly(AgentPlan.RESOURCE_DOCUMENT);
        assertThat(plan.subQuestions().get(0).vectorResourceTypes())
                .containsExactly(VectorResourceType.DOCUMENT_CHUNK);
        assertThat(plan.subQuestions().get(1).resources())
                .containsExactly(AgentPlan.RESOURCE_MINDMAP, AgentPlan.RESOURCE_QUESTION);
        assertThat(plan.subQuestions().get(1).vectorResourceTypes())
                .containsExactly(VectorResourceType.MINDMAP_NODE, VectorResourceType.QUESTION);
        assertThat(plan.needsMemory()).isTrue();
        assertThat(plan.answerShape()).isEqualTo("comparison");
        // Planner 真实 usage 原样返回（拿不到时才是 0，这里不估算）
        assertThat(outcome.usage()).isEqualTo(new AiUsage(30, 15, 45));
        assertThat(outcome.elapsedMs()).isGreaterThanOrEqualTo(0L);

        assertThat(gateway.calls()).isEqualTo(1);
        AiJsonRequest request = gateway.requests().get(0);
        assertThat(request.requireJson()).isTrue();
        assertThat(request.temperature()).isEqualTo(0.0d);
        assertThat(request.maxOutputTokens()).isEqualTo(AgentPlanner.PLANNER_MAX_OUTPUT_TOKENS);
        assertThat(request.userPrompt()).contains("对比进程和线程");
        assertThat(request.systemPrompt()).contains("subQuestions");
    }

    @Test
    void fourSubQuestionsAreTruncatedToThreeAndMissingResourcesDefaultToDocument() {
        FakeAiGateway gateway = FakeAiGateway.returning(json("""
                {"subQuestions":[
                   {"id":"q1","question":"一","query":"查询一"},
                   {"id":"q2","question":"二","resources":["document"],"query":"查询二"},
                   {"id":"q3","question":"三","resources":["mindmap"],"query":"查询三"},
                   {"id":"q4","question":"四","resources":["question"],"query":"查询四"}],
                 "needsMemory":false}
                """), AiUsage.empty());
        AgentPlanner planner = new AgentPlanner(gateway);

        AgentPlan plan = planner.plan("四个子问题", 5_000L).plan();

        // 超过上限属于“过度拆分”：截断成前 3 个仍然可用，不整份降级
        assertThat(plan.degraded()).isFalse();
        assertThat(plan.subQuestions()).extracting(AgentPlan.SubQuestion::id)
                .containsExactly("q1", "q2", "q3");
        assertThat(plan.subQuestions().get(0).resources()).containsExactly(AgentPlan.RESOURCE_DOCUMENT);
        // answerShape 缺省补 "answer"
        assertThat(plan.answerShape()).isEqualTo("answer");
    }

    @Test
    void missingIdAndQuestionAreFilledWithStableDefaults() {
        FakeAiGateway gateway = FakeAiGateway.returning(json("""
                {"subQuestions":[{"resources":["document"],"query":"进程调度算法"}]}
                """), AiUsage.empty());
        AgentPlanner planner = new AgentPlanner(gateway);

        AgentPlan plan = planner.plan("进程调度", 5_000L).plan();

        assertThat(plan.degraded()).isFalse();
        assertThat(plan.subQuestions()).singleElement().satisfies(sub -> {
            assertThat(sub.id()).isEqualTo("q1");
            assertThat(sub.question()).isEqualTo("进程调度算法");
            assertThat(sub.query()).isEqualTo("进程调度算法");
        });
    }

    // ===== 降级路径（都返回 degraded=true + 稳定错误码，永不抛异常） =====

    @Test
    void missingQueryDegradesWithStableErrorCode() {
        FakeAiGateway gateway = FakeAiGateway.returning(json("""
                {"subQuestions":[
                   {"id":"q1","question":"一","resources":["document"],"query":"查询一"},
                   {"id":"q2","question":"二","resources":["document"]}]}
                """), new AiUsage(30, 15, 45));
        AgentPlanner planner = new AgentPlanner(gateway);

        AgentPlan plan = planner.plan("原问题", 5_000L).plan();

        assertThat(plan.degraded()).isTrue();
        assertThat(plan.degradeReason()).isEqualTo(AgentPlanner.DEGRADE_INVALID_SUB_QUESTION);
        assertThat(plan.complexity()).isEqualTo(AgentPlan.COMPLEXITY_SINGLE_QUERY);
        assertThat(plan.subQuestions()).singleElement().satisfies(sub -> {
            assertThat(sub.query()).isEqualTo("原问题");
            assertThat(sub.resources()).containsExactly(AgentPlan.RESOURCE_DOCUMENT);
        });
    }

    @Test
    void illegalResourceDegrades() {
        FakeAiGateway gateway = FakeAiGateway.returning(json("""
                {"subQuestions":[{"id":"q1","question":"一","resources":["memory"],"query":"查询一"}]}
                """), AiUsage.empty());
        AgentPlanner planner = new AgentPlanner(gateway);

        AgentPlan plan = planner.plan("原问题", 5_000L).plan();

        assertThat(plan.degraded()).isTrue();
        assertThat(plan.degradeReason()).isEqualTo(AgentPlanner.DEGRADE_INVALID_RESOURCE);
        assertThat(plan.subQuestions()).singleElement()
                .satisfies(sub -> assertThat(sub.query()).isEqualTo("原问题"));
    }

    @Test
    void nonObjectPayloadDegradesToInvalidJson() {
        AgentPlanner planner = new AgentPlanner(FakeAiGateway.returning(json("[1,2,3]"),
                new AiUsage(10, 5, 15)));

        AgentPlanner.Outcome outcome = planner.plan("原问题", 5_000L);

        assertThat(outcome.plan().degraded()).isTrue();
        assertThat(outcome.plan().degradeReason()).isEqualTo(AgentPlanner.DEGRADE_INVALID_JSON);
        assertThat(outcome.plan().subQuestions()).hasSize(1);
        // 已经产生了真实调用，usage 如实保留（不估成 0）
        assertThat(outcome.usage()).isEqualTo(new AiUsage(10, 5, 15));
    }

    @Test
    void nullPayloadDegradesToInvalidJson() {
        AgentPlanner planner = new AgentPlanner(FakeAiGateway.returning(null, AiUsage.empty()));

        AgentPlanner.Outcome outcome = planner.plan("原问题", 5_000L);

        assertThat(outcome.plan().degraded()).isTrue();
        assertThat(outcome.plan().degradeReason()).isEqualTo(AgentPlanner.DEGRADE_INVALID_JSON);
        assertThat(outcome.plan().subQuestions()).singleElement()
                .satisfies(sub -> assertThat(sub.query()).isEqualTo("原问题"));
    }

    @Test
    void emptySubQuestionsDegrades() {
        AgentPlanner planner = new AgentPlanner(FakeAiGateway.returning(
                json("{\"subQuestions\":[]}"), AiUsage.empty()));

        AgentPlan plan = planner.plan("原问题", 5_000L).plan();

        assertThat(plan.degraded()).isTrue();
        assertThat(plan.degradeReason()).isEqualTo(AgentPlanner.DEGRADE_EMPTY_SUB_QUESTIONS);
        assertThat(plan.subQuestions()).singleElement()
                .satisfies(sub -> assertThat(sub.query()).isEqualTo("原问题"));
    }

    @Test
    void gatewayFailureDegradesWithEmptyUsage() {
        AgentPlanner planner = new AgentPlanner(
                FakeAiGateway.failing(new IllegalStateException("模拟网关故障")));

        AgentPlanner.Outcome outcome = planner.plan("原问题", 5_000L);

        assertThat(outcome.plan().degraded()).isTrue();
        assertThat(outcome.plan().degradeReason()).isEqualTo(AgentPlanner.DEGRADE_CALL_FAILED);
        assertThat(outcome.plan().subQuestions()).singleElement()
                .satisfies(sub -> assertThat(sub.query()).isEqualTo("原问题"));
        // 调用失败拿不到 usage，记 0（不估算）
        assertThat(outcome.usage()).isEqualTo(AiUsage.empty());
    }

    @Test
    void slowGatewayTimesOutAndDegradesWithoutWaitingForIt() {
        FakeAiGateway gateway = FakeAiGateway.slow(5_000L, json("""
                {"subQuestions":[{"query":"不会用到"}]}
                """));
        AgentPlanner planner = new AgentPlanner(gateway);

        long startedNanos = System.nanoTime();
        AgentPlanner.Outcome outcome = planner.plan("原问题", 1_000L);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

        assertThat(outcome.plan().degraded()).isTrue();
        assertThat(outcome.plan().degradeReason()).isEqualTo(AgentPlanner.DEGRADE_TIMEOUT);
        // 实际生效超时 = min(20s, 剩余预算 1s)：必须 1s 左右返回，不能等满 5s
        assertThat(elapsedMs).isLessThan(3_000L);
        assertThat(gateway.calls()).isEqualTo(1);
    }

    @Test
    void insufficientBudgetSkipsGatewayEntirely() {
        // 网关被调用就会抛异常：预算不足的路径必须一次都不碰网关
        FakeAiGateway gateway = FakeAiGateway.failing(
                new IllegalStateException("预算不足时不应该调用网关"));
        AgentPlanner planner = new AgentPlanner(gateway);

        AgentPlanner.Outcome outcome = planner.plan("原问题", AgentPlanner.MIN_PLANNER_BUDGET_MS - 1);

        assertThat(outcome.plan().degraded()).isTrue();
        assertThat(outcome.plan().degradeReason()).isEqualTo(AgentPlanner.DEGRADE_BUDGET_EXHAUSTED);
        assertThat(outcome.plan().subQuestions()).singleElement()
                .satisfies(sub -> assertThat(sub.query()).isEqualTo("原问题"));
        assertThat(gateway.calls()).isZero();
    }

    @Test
    void nullGatewayDegradesInsteadOfThrowing() {
        AgentPlanner planner = new AgentPlanner(null);

        AgentPlan plan = planner.plan("原问题", 5_000L).plan();

        assertThat(plan.degraded()).isTrue();
        assertThat(plan.degradeReason()).isEqualTo(AgentPlanner.DEGRADE_CALL_FAILED);
    }

    // ===== Fake 网关 =====

    /** 只实现 {@code completeJsonForResult} 的确定性 fake：可返回固定 JSON / 延迟 / 抛异常。 */
    private static final class FakeAiGateway implements AiGateway {

        private final JsonNode payload;
        private final RuntimeException failure;
        private final long sleepMillis;
        private final AiUsage usage;
        private final AtomicInteger callCount = new AtomicInteger();
        private final List<AiJsonRequest> requests = new ArrayList<>();

        private FakeAiGateway(JsonNode payload, RuntimeException failure, long sleepMillis,
                              AiUsage usage) {
            this.payload = payload;
            this.failure = failure;
            this.sleepMillis = sleepMillis;
            this.usage = usage == null ? AiUsage.empty() : usage;
        }

        private static FakeAiGateway returning(JsonNode payload, AiUsage usage) {
            return new FakeAiGateway(payload, null, 0L, usage);
        }

        private static FakeAiGateway failing(RuntimeException failure) {
            return new FakeAiGateway(null, failure, 0L, AiUsage.empty());
        }

        private static FakeAiGateway slow(long sleepMillis, JsonNode payload) {
            return new FakeAiGateway(payload, null, sleepMillis, AiUsage.empty());
        }

        private int calls() {
            return callCount.get();
        }

        private List<AiJsonRequest> requests() {
            return requests;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> AiJsonResult<T> completeJsonForResult(AiJsonRequest request,
                                                        Class<T> responseType) {
            callCount.incrementAndGet();
            requests.add(request);
            if (sleepMillis > 0L) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fake 网关等待被打断", e);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return new AiJsonResult<>((T) payload, usage, 1L);
        }

        @Override
        public <T> T completeJson(AiJsonRequest request, Class<T> responseType) {
            throw new UnsupportedOperationException("测试 fake 只实现 completeJsonForResult");
        }

        @Override
        public String completeChat(AiChatRequest request) {
            throw new UnsupportedOperationException("测试 fake 不支持 completeChat");
        }

        @Override
        public void streamChat(AiChatRequest request, Consumer<String> onDelta) {
            throw new UnsupportedOperationException("测试 fake 不支持 streamChat");
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-model";
        }
    }
}
