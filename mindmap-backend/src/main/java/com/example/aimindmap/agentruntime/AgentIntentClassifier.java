package com.example.aimindmap.agentruntime;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 意图分类器（方案 §5 P1-1）：把一条用户消息判成 {@link AgentIntent}。
 *
 * <p><b>规则优先</b>，判定顺序（命中即返回，后面的规则不再看）：
 * <ol>
 *   <li>问候 / 自我介绍 / 致谢 / 告别 → {@link AgentIntent#CHITCHAT}；</li>
 *   <li>出题、生成导图、导出等动作请求 → {@link AgentIntent#TASK}（规格外的最小补充，见类尾说明）；</li>
 *   <li>含对比 / 区别 / 为什么 / 步骤 / “先…再…” / “根据…和…” → {@link AgentIntent#MULTI_HOP}；</li>
 *   <li>含学习关键词（课程/笔记/概念/原理/算法/题/复习/解释/导图…）→ {@link AgentIntent#RETRIEVE}
 *       （宁查勿编的兜底）；</li>
 *   <li>长度 &lt; {@value #DIRECT_MAX_LENGTH} 且无学习关键词 → {@link AgentIntent#DIRECT}；</li>
 *   <li>其余（长度中等且无任何关键词）判不了 → 返回 {@link Optional#empty()}，
 *       由 {@link #classify(String)} 决定“问小模型”还是“兜底 RETRIEVE”。</li>
 * </ol>
 *
 * <p><b>可选小模型</b>：只有 {@code app.agent.runtime.router.llm-enabled=true}
 * <b>并且</b> {@link AiGateway#configured()} 为真时，才会对“规则判不了”的消息调一次
 * {@link AiGateway#completeJson}（{@code maxTokens <= 50}，单独超时）。默认关，
 * 关着时<strong>零网络调用</strong>。解析失败 / 返回非法意图 / 超时 / 抛异常一律
 * 降级 {@link AgentIntent#RETRIEVE}，<b>绝不把异常抛给调用方</b>——分类失败不应该让一次问答失败。
 *
 * <p><b>纯函数可测</b>：规则部分（{@link #ruleIntent(String)}）不碰网络、不读库；
 * {@link AiGateway} 由构造器注入，单测用 mock 即可，不会真调模型。
 *
 * <p><b>P1-A 边界</b>：本类只“判意图”，不写 {@code agent_runs.intent}、不改
 * {@code KnowledgeAssistantStreamer}、不做路由；接线在 P1-B。
 */
public final class AgentIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(AgentIntentClassifier.class);

    /** 方案原文“长度 < 10 且无学习关键词 → DIRECT”的阈值。 */
    public static final int DIRECT_MAX_LENGTH = 10;

    /** 送进小模型的消息最长保留字符数：分类只用到开头语义，长文没必要烧 token。 */
    static final int LLM_INPUT_MAX_CHARS = 200;

    /** 小模型分类用的系统提示词（只输出一个 JSON 字段，便于解析与降级判断）。 */
    private static final String LLM_SYSTEM_PROMPT = """
            你是意图分类器。把用户消息分到下面五个意图之一，只输出 JSON，不要解释：
            {"intent":"CHITCHAT|DIRECT|RETRIEVE|MULTI_HOP|TASK"}
            定义：
            - CHITCHAT：问候、自我介绍、致谢、告别等闲聊，不需要查资料；
            - DIRECT：不查用户资料也能答的短问题（常识、翻译、改写）；
            - RETRIEVE：需要查用户自己的笔记/导图/题目/记忆才能答的知识问题；
            - MULTI_HOP：需要对比两篇以上资料，或需要多步推理、多步操作；
            - TASK：要求产出产物的动作请求（出题、生成思维导图、导出文件）。
            拿不准就选 RETRIEVE。
            """;

    /** 问候词（英文用紧凑形式，见 {@link #compact(String)} 会把空格去掉）。 */
    private static final String GREETING = "(你好|您好|嗨|哈喽|哈啰|hi|hello|hey)";
    /** 语气词后缀，允许“你好呀”“谢谢啦”这类口语。 */
    private static final String PARTICLE = "(呀|啊|哈|哦|呢|吧|啦)?";

    /**
     * 闲聊规则（整串匹配，避免“你好，帮我对比 A 和 B”被误判成闲聊）。
     *
     * <p>覆盖方案要求的问候 / 自我介绍 / 致谢，外加同为礼貌用语的告别。
     */
    private static final List<Pattern> CHITCHAT_PATTERNS = List.of(
            Pattern.compile("^" + GREETING + PARTICLE + "$"),
            Pattern.compile("^" + GREETING + "(请问)?(你是谁|你叫什么名字|你叫什么|你是什么|你是什么模型"
                    + "|你是什么ai|你能做什么|你会什么|你可以做什么|你有什么功能|介绍一下你自己|自我介绍"
                    + "|谢谢|谢谢你)" + PARTICLE + "$"),
            Pattern.compile("^(你是谁|你叫什么名字|你叫什么|你是什么|你是什么模型|你是什么ai|你能做什么"
                    + "|你会什么|你可以做什么|你有什么功能)" + PARTICLE + "$"),
            Pattern.compile("^(请|麻烦)?(简单|简短)?(介绍|介绍一下|介绍下)(你自己|你)?" + PARTICLE + "$"),
            Pattern.compile("^自我介绍(一下)?" + PARTICLE + "$"),
            Pattern.compile("^(非常|很|太)?(谢谢|多谢|感谢|谢啦)(你|您)?" + PARTICLE + "$"),
            Pattern.compile("^(thanks|thankyou|thankyouverymuch|manythanks|thx|tks)$"),
            Pattern.compile("^(再见|拜拜|bye|byebye|goodbye|seeyou|下次见)" + PARTICLE + "$"),
            Pattern.compile("^(在吗|在么|在不在|你在吗|你在么)" + PARTICLE + "$")
    );

    /**
     * 任务类规则：要求“产出产物”的动作。
     *
     * <p>这是规格外的最小补充：方案 §P1-1 的规则条文只写了闲聊/多跳/短句三条，
     * 但 {@link AgentIntent} 里有 {@code TASK}，且验收要求“5 类意图都有规则用例”。
     * 因此补了 4 条<b>很窄</b>的动作规则（出题必须带量词“道”、导图必须带生成动词），
     * 保证它只吃“明显是让我做东西”的消息，不会把知识提问抢走。P1-B 若不使用 TASK，可直接删掉这组。
     */
    private static final List<Pattern> TASK_PATTERNS = List.of(
            Pattern.compile("(出|生成|编写|来|做)(一|几|两|三|四|五|六|七|八|九|十|\\d+)?道?.{0,12}题"),
            Pattern.compile("(生成|创建|画|整理成|导出).{0,15}(思维导图|导图)"),
            Pattern.compile("(导出|保存为|另存为)(成|为)?(pdf|markdown|md|图片|png|word)"),
            Pattern.compile("(生成|制定|做)(一份|一个|个)?(复习|学习)计划")
    );

    /**
     * 多跳规则（方案原文：对比 / 分别 / 区别 / 异同 / 为什么 / 步骤 / 计划 / 根据…和… / 先…再…）。
     *
     * <p>在“紧凑文本”（去掉空白与标点）上搜，中文长句里的“先…再…”用有限窗口匹配，
     * 避免整段瞎连。
     */
    private static final Pattern MULTI_HOP_PATTERN = Pattern.compile(
            "对比|比较|区别|异同|差异|差别|不同|各自|分别|versus|compare|comparison|difference|diff"
                    + "|为什么|为何|why|reason"
                    + "|步骤|流程|计划|第一步|第二步|step|steps|howto"
                    + "|先.{0,15}(再|然后|接着)"
                    + "|(根据|结合|从|按照|按).{0,25}(和|与|以及)"
                    // 方案原文只列了“根据…和…”，这里补同义的“结合…从…/再…/然后…”多源写法
                    + "|(结合|根据).{0,20}(从|再|然后)");

    /** 学习关键词（方案原文列举的课程/笔记/概念/原理/算法/题/复习/解释/导图等 + 常见学习对象）。 */
    private static final List<String> LEARNING_KEYWORDS = List.of(
            "课程", "笔记", "概念", "原理", "算法", "题", "复习", "解释", "导图", "知识",
            "定义", "公式", "定理", "考试", "章节", "文档", "资料", "总结", "分析", "举例",
            "代码", "函数", "进程", "线程", "内存", "调度", "网络", "数据库", "数据结构", "操作系统",
            "实验", "信号量", "临界区", "分页", "死锁", "虚拟内存", "作业", "习题", "考点", "错题",
            "复杂度", "推导", "证明", "什么是", "是什么", "讲解");

    private final AiGateway aiGateway;
    private final AgentRuntimeProperties properties;

    /**
     * @param aiGateway  小模型网关；允许为 {@code null}（纯规则模式 / 单测只测规则）
     * @param properties 配置；允许为 {@code null}（等价于默认全关）
     */
    public AgentIntentClassifier(AiGateway aiGateway, AgentRuntimeProperties properties) {
        this.aiGateway = aiGateway;
        this.properties = properties;
    }

    /**
     * 判定意图：规则优先，规则判不了时才（可选地）问小模型，最后兜底 RETRIEVE。
     *
     * @param userMessage 用户原始消息，可为 {@code null}/空白（按 DIRECT 处理）
     * @return 永远返回一个意图，不会抛异常
     */
    public AgentIntent classify(String userMessage) {
        Optional<AgentIntent> ruled = ruleIntent(userMessage);
        if (ruled.isPresent()) {
            return ruled.get();
        }
        if (!canUseLlm()) {
            log.debug("意图分类：规则不确定且小模型分类未启用，兜底 RETRIEVE");
            return AgentIntent.RETRIEVE;
        }
        return classifyByLlm(userMessage);
    }

    /**
     * 纯规则部分：不依赖网络、不读任何共享状态，可直接单测。
     *
     * @return 命中的意图；返回 {@link Optional#empty()} 表示“规则判不了”（规则不确定）
     */
    public Optional<AgentIntent> ruleIntent(String userMessage) {
        String normalized = normalize(userMessage);
        String compact = compact(normalized);
        if (compact.isEmpty()) {
            // 空消息没什么可检索的，按“直接回答”处理，避免凭空触发一次资料查询
            return Optional.of(AgentIntent.DIRECT);
        }
        if (matchesAny(CHITCHAT_PATTERNS, compact)) {
            return Optional.of(AgentIntent.CHITCHAT);
        }
        if (isAllChitchatSegments(normalized)) {
            // “你叫什么名字？介绍一下你自己吧。”这种多句问候/自我介绍，每句都是闲聊才算闲聊
            return Optional.of(AgentIntent.CHITCHAT);
        }
        if (containsAny(TASK_PATTERNS, compact)) {
            return Optional.of(AgentIntent.TASK);
        }
        if (MULTI_HOP_PATTERN.matcher(compact).find()) {
            return Optional.of(AgentIntent.MULTI_HOP);
        }
        if (hasLearningKeyword(compact)) {
            return Optional.of(AgentIntent.RETRIEVE);
        }
        if (compact.length() < DIRECT_MAX_LENGTH) {
            return Optional.of(AgentIntent.DIRECT);
        }
        // 无任何关键词、长度又不算短：规则判不了，交给可选小模型（关闭时由 classify 兜底 RETRIEVE）
        return Optional.empty();
    }

    /** 小模型分类是否可用：开关打开 + 网关存在 + 网关已配置（未配置时不浪费一次异常）。 */
    private boolean canUseLlm() {
        if (aiGateway == null || properties == null || properties.getRouter() == null) {
            return false;
        }
        if (!properties.getRouter().isLlmEnabled()) {
            return false;
        }
        return aiGateway.configured();
    }

    /** 调小模型判意图；任何失败都降级 RETRIEVE，不抛给调用方。 */
    private AgentIntent classifyByLlm(String userMessage) {
        AgentRuntimeProperties.Router router = properties.getRouter();
        int timeoutMs = router.getLlmTimeoutMs();
        String snippet = truncate(normalize(userMessage), LLM_INPUT_MAX_CHARS);
        AiJsonRequest request = new AiJsonRequest(
                LLM_SYSTEM_PROMPT,
                "用户消息：" + snippet,
                true,
                router.getLlmMaxTokens(),
                0.0d);

        AgentIntentLlmResult result;
        try {
            result = callWithTimeout(request, timeoutMs);
        } catch (TimeoutException e) {
            log.warn("意图分类：小模型分类超时（{}ms），降级 RETRIEVE", timeoutMs, e);
            return AgentIntent.RETRIEVE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("意图分类：小模型分类被中断，降级 RETRIEVE", e);
            return AgentIntent.RETRIEVE;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("意图分类：小模型分类调用失败（cause={}），降级 RETRIEVE",
                    cause.getClass().getSimpleName(), cause);
            return AgentIntent.RETRIEVE;
        } catch (RuntimeException e) {
            // 走到这里说明是本类没预料到的问题（网关异常已由 ExecutionException 覆盖）
            log.error("意图分类：小模型分类出现未预期异常，降级 RETRIEVE", e);
            return AgentIntent.RETRIEVE;
        }

        Optional<AgentIntent> parsed = parseIntent(result);
        if (parsed.isEmpty()) {
            log.warn("意图分类：小模型返回的 intent 无法识别（raw={}），降级 RETRIEVE",
                    result == null ? null : result.intent());
            return AgentIntent.RETRIEVE;
        }
        log.debug("意图分类：小模型判定 intent={}", parsed.get());
        return parsed.get();
    }

    /**
     * 带超时的网关调用：起一个虚拟线程跑阻塞调用，超时就中断并抛 {@link TimeoutException}。
     *
     * <p>不用 {@code CompletableFuture.orTimeout} 是为了不占公共线程池；
     * 虚拟线程按次创建成本极低，且默认关（{@code llm-enabled=false}）时根本不会走到这里。
     */
    private AgentIntentLlmResult callWithTimeout(AiJsonRequest request, int timeoutMs)
            throws TimeoutException, InterruptedException, ExecutionException {
        FutureTask<AgentIntentLlmResult> task =
                new FutureTask<>(() -> aiGateway.completeJson(request, AgentIntentLlmResult.class));
        Thread worker = Thread.ofVirtual().name("agent-intent-llm").start(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 尽力让底层 HTTP 调用尽快失败退出；已经超时就不再等它
            worker.interrupt();
            task.cancel(true);
            throw e;
        }
    }

    /** 把返回的 intent 字符串转枚举（大小写不敏感）；无法识别返回空。 */
    private static Optional<AgentIntent> parseIntent(AgentIntentLlmResult result) {
        if (result == null || result.intent() == null) {
            return Optional.empty();
        }
        String raw = result.intent().strip();
        for (AgentIntent intent : AgentIntent.values()) {
            if (intent.name().equalsIgnoreCase(raw)) {
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }

    /** 整串匹配（闲聊必须整句都是问候，否则“你好，帮我对比…”会被误判）。 */
    private static boolean matchesAny(List<Pattern> patterns, String compact) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(compact).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 子串匹配（任务类规则要允许“帮我出 5 道…”“把笔记整理成导图”这种前后有修饰的写法）。 */
    private static boolean containsAny(List<Pattern> patterns, String compact) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(compact).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 多句闲聊判定：先按标点/空白切句，每一句都命中闲聊模式才算闲聊。
     *
     * <p>这样“你叫什么名字？介绍一下你自己吧。”能判成 CHITCHAT，
     * 而“你好，帮我对比 FCFS 和 SJF”不会（后半句不是闲聊）。
     */
    private static boolean isAllChitchatSegments(String normalized) {
        int segments = 0;
        for (String rawSegment : normalized.split("[\\p{P}\\p{S}\\s]+")) {
            String segment = compact(rawSegment);
            if (segment.isEmpty()) {
                continue;
            }
            segments++;
            if (!matchesAny(CHITCHAT_PATTERNS, segment)) {
                return false;
            }
        }
        return segments >= 2;
    }

    private static boolean hasLearningKeyword(String compact) {
        for (String keyword : LEARNING_KEYWORDS) {
            if (compact.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 紧凑化：把空白、标点、符号全部去掉。
     *
     * <p>这样“你好，在吗？”“Hello!”“先 … 再 …”都能用同一套简单正则匹配，
     * 不会因为全角/半角标点和空格写法不同而漏判。
     */
    static String compact(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Z}\\s\\p{P}\\p{S}]+", "");
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
