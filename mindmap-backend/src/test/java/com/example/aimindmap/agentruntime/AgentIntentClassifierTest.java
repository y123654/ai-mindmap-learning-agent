package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.AiJsonRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/**
 * P1-A：{@link AgentIntentClassifier} 纯单测。
 *
 * <p>不连网、不调真实模型：小模型网关一律 Mockito mock。
 * 规则用例用 {@code @CsvSource} 表驱动，覆盖 5 类意图各自的正例。
 */
class AgentIntentClassifierTest {

    /** 规则判不了的“长度中等且无关键词”消息，用来验证小模型分支与兜底。 */
    private static final String UNCERTAIN_MESSAGE = "帮我看看这段内容写得顺不顺";

    private final AiGateway aiGateway = mock(AiGateway.class);
    private AgentRuntimeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentRuntimeProperties();
    }

    private AgentIntentClassifier classifier() {
        return new AgentIntentClassifier(aiGateway, properties);
    }

    private void enableLlm(int timeoutMs) {
        properties.getRouter().setLlmEnabled(true);
        properties.getRouter().setLlmTimeoutMs(timeoutMs);
        when(aiGateway.configured()).thenReturn(true);
    }

    private void enableLlm() {
        enableLlm(properties.getRouter().getLlmTimeoutMs());
    }

    private void stubLlmReturn(AgentIntentLlmResult result) {
        when(aiGateway.completeJson(any(AiJsonRequest.class), eq(AgentIntentLlmResult.class)))
                .thenReturn(result);
    }

    // ------------------------------------------------------------------ 规则

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', value = {
            // ===== CHITCHAT：问候 / 自我介绍 / 致谢 / 告别（中英） =====
            "你好|CHITCHAT",
            "您好呀|CHITCHAT",
            "Hello!|CHITCHAT",
            "hi|CHITCHAT",
            "在吗？|CHITCHAT",
            "你是谁|CHITCHAT",
            "介绍一下你自己|CHITCHAT",
            "谢谢|CHITCHAT",
            "Thanks|CHITCHAT",
            "再见|CHITCHAT",
            // 多句闲聊（每句都是问候/自我介绍才算）
            "你叫什么名字？介绍一下你自己吧。|CHITCHAT",
            // ===== DIRECT：长度 < 10 且无学习关键词 =====
            "帮我翻译这句话|DIRECT",
            "今天天气怎么样|DIRECT",
            "嗯嗯|DIRECT",
            "随便聊聊|DIRECT",
            "把这段英文改成中文|DIRECT",
            // ===== RETRIEVE：学习关键词兜底（宁查勿编） =====
            "什么是进程|RETRIEVE",
            "解释一下时间片轮转调度|RETRIEVE",
            "复习一下进程同步的重点|RETRIEVE",
            "我的笔记里有哪些调度算法|RETRIEVE",
            "导图里进程同步下面有哪些节点|RETRIEVE",
            "信号量的 P 操作是什么|RETRIEVE",
            "这道题怎么解|RETRIEVE",
            // ===== MULTI_HOP：对比 / 区别 / 为什么 / 步骤 / 先…再… / 根据…和… =====
            "对比 FCFS 和 SJF 的优缺点|MULTI_HOP",
            "进程和线程有什么区别|MULTI_HOP",
            "为什么长作业可能饥饿|MULTI_HOP",
            "解释一下信号量的使用步骤|MULTI_HOP",
            "先把笔记里的信号量讲清楚，再找一道相关的练习题|MULTI_HOP",
            "根据我的笔记和导图，说说进程同步包含哪些知识点|MULTI_HOP",
            "先解释定义再举例说明|MULTI_HOP",
            // “结合…从…”是“根据…和…”的多源同义写法
            "我的薄弱点是什么？结合这个薄弱点，从我自己的题目里找一道相关的练习题给我|MULTI_HOP",
            // ===== TASK：会产出产物的动作请求（窄规则，规格外最小补充） =====
            "帮我出 5 道信号量的选择题|TASK",
            "生成一张关于进程调度的思维导图|TASK",
            "把这篇笔记整理成导图|TASK",
            "导出成 PDF|TASK"
    })
    void ruleTableCoversAllFiveIntents(String message, AgentIntent expected) {
        assertThat(classifier().ruleIntent(message)).contains(expected);
        // 规则命中时 classify 也走同一条路，不依赖网关
        assertThat(classifier().classify(message)).isEqualTo(expected);
    }

    @Test
    void enumNamesAreTheStableContractForAgentRunsIntent() {
        // agent_runs.intent 存的就是枚举名，改名等于改契约；这里锁死顺序与拼写
        assertThat(AgentIntent.values()).containsExactly(
                AgentIntent.CHITCHAT,
                AgentIntent.DIRECT,
                AgentIntent.RETRIEVE,
                AgentIntent.MULTI_HOP,
                AgentIntent.TASK);
    }

    @Test
    void emptyBlankAndPunctuationOnlyMessagesCountAsDirect() {
        AgentIntentClassifier classifier = classifier();

        assertThat(classifier.classify(null)).isEqualTo(AgentIntent.DIRECT);
        assertThat(classifier.classify("")).isEqualTo(AgentIntent.DIRECT);
        assertThat(classifier.classify("    ")).isEqualTo(AgentIntent.DIRECT);
        assertThat(classifier.classify("？。！")).isEqualTo(AgentIntent.DIRECT);
        assertThat(classifier.ruleIntent("")).contains(AgentIntent.DIRECT);
    }

    @Test
    void greetingWithFollowingQuestionIsNotChitchat() {
        // 整串匹配的意义：问候后面跟真问题，不能因为“你好”就跳过检索
        assertThat(classifier().ruleIntent("你好，帮我对比 FCFS 和 SJF 的优缺点"))
                .contains(AgentIntent.MULTI_HOP);
        assertThat(classifier().ruleIntent("你好，请解释一下时间片轮转"))
                .contains(AgentIntent.RETRIEVE);
    }

    @Test
    void uncertainMessageLeavesRulesEmptyButStillFallsBackToRetrieve() {
        AgentIntentClassifier classifier = classifier();

        assertThat(classifier.ruleIntent(UNCERTAIN_MESSAGE)).isEmpty();
        // 小模型默认关：兜底 RETRIEVE（宁查勿编）
        assertThat(classifier.classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);
    }

    // ------------------------------------------------------- 小模型关闭：零调用

    @Test
    void llmDisabledMakesNoGatewayCallAtAll() {
        AgentIntentClassifier classifier = classifier();

        assertThat(classifier.classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);

        // 开关默认关，连 configured() 都不应该去问
        verifyNoInteractions(aiGateway);
    }

    @Test
    void llmEnabledButGatewayNotConfiguredFallsBackWithoutCallingModel() {
        properties.getRouter().setLlmEnabled(true);
        when(aiGateway.configured()).thenReturn(false);

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);

        verify(aiGateway, never()).completeJson(any(AiJsonRequest.class), eq(AgentIntentLlmResult.class));
    }

    @Test
    void ruleHitDoesNotTouchTheModelEvenWhenLlmEnabled() {
        enableLlm();

        AgentIntentClassifier classifier = classifier();
        assertThat(classifier.classify("你好")).isEqualTo(AgentIntent.CHITCHAT);
        assertThat(classifier.classify("对比 FCFS 和 SJF 的优缺点")).isEqualTo(AgentIntent.MULTI_HOP);

        verify(aiGateway, never()).completeJson(any(AiJsonRequest.class), eq(AgentIntentLlmResult.class));
    }

    // ------------------------------------------------------- 小模型开启：正常

    @ParameterizedTest
    @EnumSource(AgentIntent.class)
    void llmReturnedIntentIsUsedAsIs(AgentIntent expected) {
        enableLlm();
        stubLlmReturn(new AgentIntentLlmResult(expected.name()));

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(expected);
    }

    @Test
    void llmIntentNameIsCaseInsensitiveAndTrimmed() {
        enableLlm();
        stubLlmReturn(new AgentIntentLlmResult("  direct "));

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.DIRECT);
    }

    @Test
    void llmRequestUsesSmallTokenBudgetAndJsonMode() {
        enableLlm();
        stubLlmReturn(new AgentIntentLlmResult("RETRIEVE"));

        classifier().classify(UNCERTAIN_MESSAGE);

        ArgumentCaptor<AiJsonRequest> captor = ArgumentCaptor.forClass(AiJsonRequest.class);
        verify(aiGateway).completeJson(captor.capture(), eq(AgentIntentLlmResult.class));
        AiJsonRequest request = captor.getValue();
        assertThat(request.maxOutputTokens()).isLessThanOrEqualTo(50);
        assertThat(request.requireJson()).isTrue();
        assertThat(request.systemPrompt()).contains("intent");
        assertThat(request.userPrompt()).contains(UNCERTAIN_MESSAGE);
    }

    @Test
    void longUncertainMessageIsTruncatedBeforeBeingSentToModel() {
        enableLlm();
        stubLlmReturn(new AgentIntentLlmResult("DIRECT"));
        String longMessage = "帮我看看这段话" + "a".repeat(400);

        assertThat(classifier().classify(longMessage)).isEqualTo(AgentIntent.DIRECT);

        ArgumentCaptor<AiJsonRequest> captor = ArgumentCaptor.forClass(AiJsonRequest.class);
        verify(aiGateway).completeJson(captor.capture(), eq(AgentIntentLlmResult.class));
        assertThat(captor.getValue().userPrompt().length())
                .isLessThanOrEqualTo("用户消息：".length() + AgentIntentClassifier.LLM_INPUT_MAX_CHARS);
    }

    // ---------------------------------------------- 小模型开启：失败一律降级

    @Test
    void unknownIntentFromModelFallsBackToRetrieve() {
        enableLlm();
        stubLlmReturn(new AgentIntentLlmResult("SOMETHING_ELSE"));

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);
    }

    @Test
    void nullIntentFromModelFallsBackToRetrieve() {
        enableLlm();
        stubLlmReturn(new AgentIntentLlmResult(null));

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);
    }

    @Test
    void nullModelResponseFallsBackToRetrieve() {
        enableLlm();
        stubLlmReturn(null);

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);
    }

    @Test
    void invalidJsonFromModelFallsBackToRetrieveWithoutThrowing() {
        enableLlm();
        // 真网关解析不了 JSON 时抛的就是 AiGatewayException（AI_JSON_PARSE_FAILED）
        when(aiGateway.completeJson(any(AiJsonRequest.class), eq(AgentIntentLlmResult.class)))
                .thenThrow(new AiGatewayException("AI_JSON_PARSE_FAILED", "AI 返回的 JSON 无法解析"));

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);
    }

    @Test
    void unexpectedRuntimeExceptionFallsBackToRetrieveWithoutThrowing() {
        enableLlm();
        when(aiGateway.completeJson(any(AiJsonRequest.class), eq(AgentIntentLlmResult.class)))
                .thenThrow(new IllegalStateException("gateway exploded"));

        assertThat(classifier().classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);
    }

    @Test
    void slowModelCallTimesOutAndFallsBackToRetrieve() {
        enableLlm(50);
        when(aiGateway.completeJson(any(AiJsonRequest.class), eq(AgentIntentLlmResult.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(600L);
                    return new AgentIntentLlmResult("DIRECT");
                });

        long startedNanos = System.nanoTime();
        AgentIntent intent = classifier().classify(UNCERTAIN_MESSAGE);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

        assertThat(intent).isEqualTo(AgentIntent.RETRIEVE);
        // 证明是“到点就走”，不是等模型自己 600ms 跑完
        assertThat(elapsedMs).isLessThan(600L);
    }

    // ------------------------------------------------------------ 默认配置

    @Test
    void runtimeSwitchDefaultsToOff() {
        AgentRuntimeProperties defaults = new AgentRuntimeProperties();

        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getRouter().isLlmEnabled()).isFalse();
        assertThat(defaults.getRouter().getLlmMaxTokens()).isEqualTo(50);
        assertThat(defaults.getRouter().getLlmTimeoutMs()).isEqualTo(2000);
    }

    @Test
    void nullDependenciesDegradeToPureRules() {
        // 允许用 null 网关/配置构造：纯规则模式照样能判
        AgentIntentClassifier bare = new AgentIntentClassifier(null, null);

        assertThat(bare.classify("你好")).isEqualTo(AgentIntent.CHITCHAT);
        assertThat(bare.classify(UNCERTAIN_MESSAGE)).isEqualTo(AgentIntent.RETRIEVE);
    }
}
