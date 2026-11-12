package com.example.aimindmap.agentruntime;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.VectorIndexExternalException;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorSearchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多查询检索 + RRF 融合（方案 §P1-5 第 1~4 条）。
 *
 * <p>一次检索 = <b>原 query + 至多 2 个改写</b>（<b>默认只用规则改写</b>，即 0~1 个清洗变体；
 * 只有把 {@code app.agent.retrieval.rewrite-llm-enabled} 显式打开时才是 2 个 LLM 改写），
 * 每个 query 各取 top
 * {@code app.qdrant.search-top-k}（默认 8）个片段，然后用
 * {@code score = Σ 1/(60 + rank)} 融合（rank 从 1 开始），再按
 * <b>(resourceType, resourceId, chunkIndex)</b> 去重、<b>每篇文档最多保留
 * {@code app.agent.retrieval.max-chunks-per-document} 个 chunk</b>（默认 2），
 * 最后按融合分降序，并收敛到 {@code app.agent.retrieval.max-results}（默认 5）条。
 *
 * <p><b>降级是一等公民</b>（方案原文“失败则用规则改写”）：
 * <ul>
 *   <li><b>LLM 改写默认关</b>（{@code rewrite-llm-enabled=false}，所有者 2026-10-28 决定，
 *       依据 E2/E2b 两次测量：+780~863ms、召回 +0.0pp）：此时<b>完全不碰 {@link AiGateway}</b>
 *       （连 {@code configured()} 探测都不做），只做规则改写，列表长度始终 ≥ 1；</li>
 *   <li>打开后：两个改写<b>并发</b>发起，每个独立超时 {@value #REWRITE_TIMEOUT_MS}ms、两个合计不超过
 *       {@value #REWRITE_TOTAL_BUDGET_MS}ms；超时 / 抛异常 / 返回空或非法 JSON 都只是丢掉这一路改写；</li>
 *   <li>一路都没拿到时改用<b>规则改写</b>（只清洗：去疑问词/停用词/标点压平，<b>不引入任何原 query
 *       里没有的概念词</b>，因此至多 1 个变体），列表长度始终 ≥ 1（至少原 query）；</li>
 *   <li>3 次向量检索也并发，合计预算 {@value #SEARCH_TOTAL_BUDGET_MS}ms。改写 4s + 检索 4s
 *       = 最坏约 8s，<b>落在 {@code app.agent.tool-timeout-seconds=10} 之内</b>
 *       （LLM 改写关着时不消耗改写预算，最坏只剩 4s）；</li>
 *   <li>改写列表的检索失败只降级（打 warn、该列表按空处理）；
 *       <b>原 query 的检索失败仍按旧语义抛出</b>（工具层转成 AGENT_RETRIEVAL_FAILED），
 *       保证“基础设施坏了”不会被多查询悄悄吞掉。</li>
 * </ul>
 *
 * <p><b>开关与上限</b>：{@code app.agent.retrieval.multi-query-enabled}（默认 true）。关掉或者用
 * {@link #legacy(VectorSearchService)} 构造时，{@link #search} 直接委托
 * {@link VectorSearchService#search}——单 query、每篇 1 chunk，与 P1-5 之前逐字节一致。
 * 两个上限（{@code max-results} / {@code max-chunks-per-document}）<b>只作用于多查询路径</b>，
 * 开关关闭时仍走旧路径自带的收敛逻辑。LLM 改写开关（{@code rewrite-llm-enabled}，默认 false）
 * <b>只影响改写方式</b>：关着时仍然走 RRF 融合与每篇上限，不会退回旧路径。
 *
 * <p>本类不调用真实网络：{@link AiGateway} 与 {@link VectorSearchService} 都由构造器注入，
 * 单测用 fake/mock 即可，不连 DeepSeek、不连 Qdrant。
 */
public final class MultiQueryRetriever {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryRetriever.class);

    /** RRF 常数（方案原文：{@code score = Σ 1/(60 + rank)}）。 */
    public static final int RRF_K = 60;

    /** 每个改写请求的独立超时（毫秒）；只有 {@code rewrite-llm-enabled=true} 时才用得上。 */
    public static final int REWRITE_TIMEOUT_MS = 2000;

    /** 两个改写合计的时间预算（毫秒），方案原文“总量 ≤ 4s”。 */
    public static final long REWRITE_TOTAL_BUDGET_MS = 4000L;

    /** 3 次向量检索合计的时间预算（毫秒）；与改写预算相加仍小于 10s 工具超时。 */
    public static final long SEARCH_TOTAL_BUDGET_MS = 4000L;

    /** 送进改写模型的问题最长字符数（改写只需要语义，长文没必要烧 token）。 */
    static final int REWRITE_INPUT_MAX_CHARS = 200;

    /** 采纳的改写结果最长字符数，超长按截断（防止模型返回整段解释）。 */
    static final int REWRITE_OUTPUT_MAX_CHARS = 100;

    /** 改写模型输出上限（一条检索串，不需要长输出）。 */
    static final int REWRITE_MAX_OUTPUT_TOKENS = 120;

    /**
     * 改写 A：把问题改写成另一个自然语言问句（换说法，帮召回的语义面更宽）。
     * 只要求一个 JSON 字段，便于解析与失败判断。
     */
    private static final String PARAPHRASE_SYSTEM_PROMPT = """
            你是检索查询改写器。把用户的问题改写成另一个意思相同、但措辞不同的中文检索问句，
            便于在课程笔记里做语义检索。只输出 JSON，不要解释：
            {"query":"改写后的问句"}
            要求：不回答原问题，不引入原问题之外的信息，不超过 60 字。
            """;

    /** 改写 B：抽出关键词式的检索串（帮关键词/术语型召回）。 */
    private static final String KEYWORD_SYSTEM_PROMPT = """
            你是检索关键词提取器。把用户的问题压缩成一串中文检索关键词（用空格分隔的术语，
            可以补上最直接的同义术语），便于在课程笔记里做语义检索。只输出 JSON，不要解释：
            {"query":"关键词串"}
            要求：不回答问题，不加入原问题之外的新信息，不超过 40 字。
            """;

    /** 疑问词 / 口语填充词（规则改写第一步去掉，剩下的才是检索实体）。 */
    private static final Pattern FILLER_PATTERN = Pattern.compile(
            "是什么意思|是什么|什么是|有什么|有哪些|为什么|为何|怎么样|怎么|如何|是否|能否"
                    + "|请问|请帮我|帮我|麻烦|我想知道|想知道|我想问|想问|解释一下|介绍一下"
                    + "|讲解一下|讲一下|说一下|说说|一下|谢谢");

    /** 停用词：去掉后不改变检索对象的虚词（“进程和线程” → “进程线程”）。 */
    private static final Pattern STOPWORD_PATTERN = Pattern.compile(
            "(的|了|吗|呢|吧|啊|呀|是|在|和|与|及|或者|或|以及|对于|关于|这个|那个|这些|那些)");

    /** 标点 / 符号 / 空白（规则改写最后统一压成单个空格）。 */
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[\\p{Z}\\s\\p{P}\\p{S}]+");

    private final VectorSearchService vectorSearchService;
    private final AiGateway aiGateway;
    private final QdrantProperties qdrantProperties;
    private final RetrievalProperties properties;

    /** “LLM 改写默认关”的说明是否已打过（只打一次 debug，不刷屏）。 */
    private final AtomicBoolean rewriteLlmDisabledLogged = new AtomicBoolean();

    /**
     * @param vectorSearchService 向量检索（必填）
     * @param aiGateway           改写用网关；只有 {@code rewrite-llm-enabled=true} 才会用到，
     *                            {@code null} 或 {@code configured()=false} 时只用规则改写
     * @param qdrantProperties    用于取每个 query 的 top-k 与 min-score；{@code null} 视为“多查询不可用”
     * @param properties          开关与两个上限（条数、每篇 chunk 数）；{@code null} 视为关闭
     */
    public MultiQueryRetriever(VectorSearchService vectorSearchService,
                               AiGateway aiGateway,
                               QdrantProperties qdrantProperties,
                               RetrievalProperties properties) {
        this.vectorSearchService = vectorSearchService;
        this.aiGateway = aiGateway;
        this.qdrantProperties = qdrantProperties;
        this.properties = properties;
    }

    /**
     * 旧路径检索器：恒定单 query、每篇 1 chunk。
     *
     * <p>给“开关关闭”之外的第二条回滚通道——旧装配（例如只注入 {@link VectorSearchService} 的
     * 单测/老代码）可以用它拿到与 P1-5 之前完全一致的行为，不需要构造 AI 网关或 Qdrant 配置。
     */
    public static MultiQueryRetriever legacy(VectorSearchService vectorSearchService) {
        return new MultiQueryRetriever(vectorSearchService, null, null, null);
    }

    /** 多查询是否生效（开关打开且依赖齐全）。 */
    public boolean isEnabled() {
        return properties != null && properties.isMultiQueryEnabled() && qdrantProperties != null;
    }

    /**
     * 检索入口，签名与 {@link VectorSearchService#search} 保持一致，便于工具层零改动切换。
     *
     * @return 融合后的命中列表（{@link VectorSearchService.VectorHit#score()} 已是<b>融合分</b>）
     * @throws IllegalArgumentException      query 为空
     * @throws VectorIndexExternalException  原 query 的检索失败（保持旧语义：致命）
     */
    public VectorSearchService.SearchOutcome search(Long userId, String query,
                                                    List<VectorResourceType> resourceTypes) {
        if (!isEnabled()) {
            // 开关关闭：完全走旧路径（单 query、每篇 1 chunk），一行逻辑都不多加
            return vectorSearchService.search(userId, query, resourceTypes);
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索内容不能为空");
        }
        List<String> queries = buildQuerySet(userId, query);
        int topK = Math.max(1, qdrantProperties.getSearchTopK());
        List<List<VectorSearchService.VectorHit>> perQueryHits = retrieveAll(
                userId, queries, resourceTypes, topK,
                System.nanoTime() + SEARCH_TOTAL_BUDGET_MS * 1_000_000L);
        List<VectorSearchService.VectorHit> hits = fuse(perQueryHits);
        if (hits.isEmpty()) {
            log.info("多查询检索未命中 userId={} queryLength={} queryCount={} minScore={}",
                    userId, query.length(), queries.size(), qdrantProperties.getMinScore());
        } else {
            log.debug("多查询检索完成 userId={} queryLength={} queryCount={} fusedHits={}",
                    userId, query.length(), queries.size(), hits.size());
        }
        return new VectorSearchService.SearchOutcome(List.copyOf(hits), !hits.isEmpty());
    }

    // ------------------------------------------------------------------
    // 1) 查询集：原 query + 至多 2 个改写（默认规则改写；LLM 改写需显式打开）
    // ------------------------------------------------------------------

    /**
     * 组装查询集：第 0 个永远是原 query，后面是至多 2 个去重后的改写。
     *
     * <p>列表长度恒 ≥ 1：即使 LLM 不可用、规则也抠不出任何变体，也至少检索原 query 一次。
     *
     * <p><b>默认不碰 AiGateway</b>：{@code rewrite-llm-enabled=false}（默认）时走规则分支，
     * 且用 {@code &&} 短路保证连 {@code configured()} 都不会被调用——开关关着就是零交互，
     * 不是“调一次再判断”。只有把开关显式打开，才会恢复“LLM 优先（2 路并发）、
     * 失败/超时降级规则”的旧行为。
     */
    List<String> buildQuerySet(Long userId, String query) {
        Set<String> queries = new LinkedHashSet<>();
        queries.add(query.strip());
        List<String> variants;
        long startedNanos = System.nanoTime();
        if (llmRewriteEnabled() && llmRewriteAvailable()) {
            variants = llmRewrites(userId, query);
            if (!variants.isEmpty()) {
                log.debug("多查询：LLM 改写成功 userId={} 改写数={} 耗时Ms={}",
                        userId, variants.size(), elapsedMs(startedNanos));
            } else {
                variants = ruleRewrites(query);
                log.info("多查询：LLM 改写全部失败，降级规则改写 userId={} queryLength={} 规则变体数={}",
                        userId, query.length(), variants.size());
            }
        } else {
            variants = ruleRewrites(query);
            if (llmRewriteEnabled()) {
                // 开关已打开但网关不可用（未配 Key）：每个请求都值得知道一次，仍是 debug
                log.debug("多查询：AI 网关不可用，使用规则改写 userId={} 规则变体数={}",
                        userId, variants.size());
            } else {
                // 默认路径：debug 只打一次，避免每轮检索刷日志（所有者要求不新增 info 级每请求日志）
                logRewriteLlmDisabledOnce();
            }
        }
        for (String variant : variants) {
            if (variant != null && !variant.isBlank()) {
                queries.add(variant.strip());
            }
        }
        return List.copyOf(queries);
    }

    /**
     * LLM 改写开关是否打开（{@code app.agent.retrieval.rewrite-llm-enabled}，<b>默认 false</b>）。
     *
     * <p>所有者 2026-10-28 决定默认关：依据 E2/E2b 两次真实测量，LLM 改写只买到
     * P50 +780~863ms 的延迟，召回 +0.0pp（干净夹具与干扰夹具都测不出收益）。
     * 规则改写与 RRF 机制保留，成本≈0、延迟与旧基线同量级。
     */
    private boolean llmRewriteEnabled() {
        return properties != null && properties.isRewriteLlmEnabled();
    }

    /** “LLM 改写默认关”的启动/首用说明：只打一次 debug，不产生每请求日志噪音。 */
    private void logRewriteLlmDisabledOnce() {
        if (rewriteLlmDisabledLogged.compareAndSet(false, true)) {
            log.debug("多查询：LLM 改写默认关闭（所有者 2026-10-28 决定；依据 E2/E2b：+780ms、召回 +0.0pp），"
                    + "本次及后续检索只用规则改写；如需恢复置 app.agent.retrieval.rewrite-llm-enabled=true");
        }
    }

    /** 改写是否可用：网关存在且已配置（未配置时直接走规则，避免浪费一次必然失败的调用）。 */
    private boolean llmRewriteAvailable() {
        return aiGateway != null && aiGateway.configured();
    }

    /**
     * 两个改写<b>并发</b>发起：A 换说法、B 提关键词。
     *
     * <p>每个改写独立 2s 超时，两个合计不超过 4s；任何一路失败都只丢掉这一路，绝不抛异常。
     */
    private List<String> llmRewrites(Long userId, String query) {
        String snippet = truncate(query.strip(), REWRITE_INPUT_MAX_CHARS);
        long deadlineNanos = System.nanoTime() + REWRITE_TOTAL_BUDGET_MS * 1_000_000L;
        FutureTask<AiJsonResult<QueryRewriteResult>> paraphrase =
                startRewrite("agent-mq-rewrite-paraphrase", PARAPHRASE_SYSTEM_PROMPT, snippet);
        FutureTask<AiJsonResult<QueryRewriteResult>> keywords =
                startRewrite("agent-mq-rewrite-keywords", KEYWORD_SYSTEM_PROMPT, snippet);
        List<String> variants = new ArrayList<>(2);
        String first = awaitRewrite(paraphrase, deadlineNanos, "paraphrase", userId);
        if (first != null) {
            variants.add(first);
        }
        String second = awaitRewrite(keywords, deadlineNanos, "keywords", userId);
        if (second != null) {
            variants.add(second);
        }
        return variants;
    }

    private FutureTask<AiJsonResult<QueryRewriteResult>> startRewrite(String threadName,
                                                                      String systemPrompt,
                                                                      String snippet) {
        AiJsonRequest request = new AiJsonRequest(systemPrompt, "用户问题：" + snippet,
                true, REWRITE_MAX_OUTPUT_TOKENS, 0.3d);
        FutureTask<AiJsonResult<QueryRewriteResult>> task = new FutureTask<>(
                () -> aiGateway.completeJsonForResult(request, QueryRewriteResult.class));
        Thread.ofVirtual().name(threadName).start(task);
        return task;
    }

    /**
     * 等一路改写：超时 / 异常 / 空结果 / 非法结果都返回 {@code null}（= 丢掉这一路）。
     *
     * <p>用 {@code min(总预算, 本路 2s)} 作为等待上限，因此两路加起来不会超过 4s。
     */
    private String awaitRewrite(FutureTask<AiJsonResult<QueryRewriteResult>> task, long deadlineNanos,
                                String kind, Long userId) {
        long waitMillis = Math.min(remainingMillis(deadlineNanos),
                remainingMillis(System.nanoTime() + REWRITE_TIMEOUT_MS * 1_000_000L));
        try {
            AiJsonResult<QueryRewriteResult> result = task.get(waitMillis, TimeUnit.MILLISECONDS);
            QueryRewriteResult value = result == null ? null : result.value();
            String rewritten = value == null ? null : value.query();
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("多查询：改写结果为空，忽略这一路 userId={} kind={} waitMs={}",
                        userId, kind, waitMillis);
                return null;
            }
            // token 用量只打 debug：用于评估“多一次改写”的成本，不落库、不进 DTO
            log.debug("多查询：改写成功 userId={} kind={} promptTokens={} completionTokens={}",
                    userId, kind,
                    result.usage() == null ? null : result.usage().promptTokens(),
                    result.usage() == null ? null : result.usage().completionTokens());
            return truncate(rewritten.strip(), REWRITE_OUTPUT_MAX_CHARS);
        } catch (TimeoutException e) {
            task.cancel(true);
            log.warn("多查询：改写超时（上限 {}ms），忽略这一路 userId={} kind={}",
                    waitMillis, userId, kind);
            return null;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("多查询：改写等待被中断，忽略这一路 userId={} kind={}", userId, kind, e);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("多查询：改写调用失败，忽略这一路 userId={} kind={} cause={}",
                    userId, kind, cause.getClass().getSimpleName(), cause);
            return null;
        } catch (RuntimeException e) {
            log.error("多查询：改写出现未预期异常，忽略这一路 userId={} kind={}", userId, kind, e);
            return null;
        }
    }

    /**
     * 规则改写兜底：<b>只做清洗，不引入原 query 里没有的任何概念词</b>。
     *
     * <p>规则兜底是“LLM 改写失败时的保守退路”，保守的含义是<b>不引入新词</b>：
     * 变体最多 1 个 = 去疑问词/停用词/标点压平后的实体串；清洗后与原文相同就直接丢掉
     * （此时查询集里只剩原 query，即“列表只有 1 路”）。
     *
     * <p><b>为什么删掉了同义词追加</b>（所有者 2026-10-27 裁定）：原先那张
     * “进程→线程、索引→B+树、缓存→Redis、排序→时间复杂度、分页→页面置换”的表
     * <b>不是同义词，是相关概念</b>；猜错的词会把噪声拉进候选，再被 RRF 累加放大，
     * 反而挤掉真正相关的命中。所以整张表与追加逻辑都已删除，<b>也不留成“默认关”的配置项</b>
     * （避免以后有人打开）。方案原文里的“加同义词”因此收窄为“只清洗”，这是评估组长的显式裁定。
     */
    List<String> ruleRewrites(String query) {
        String stripped = stripFillers(query);
        if (stripped.isBlank() || sameQuery(stripped, query)) {
            return List.of();
        }
        return List.of(stripped);
    }

    /** 去疑问词 → 去停用词 → 标点压成单空格。抠不出任何实体时返回空串（由调用方保底用原 query）。 */
    static String stripFillers(String query) {
        if (query == null) {
            return "";
        }
        String text = FILLER_PATTERN.matcher(query).replaceAll(" ");
        text = STOPWORD_PATTERN.matcher(text).replaceAll(" ");
        text = PUNCTUATION_PATTERN.matcher(text).replaceAll(" ").strip();
        return text;
    }

    // ------------------------------------------------------------------
    // 2) 3 次向量检索（并发，各自降级）+ 原 query 失败保持致命语义
    // ------------------------------------------------------------------

    /**
     * 并发跑 3 次检索，返回与 {@code queries} 顺序一致的结果列表。
     *
     * <p>第 0 个（原 query）的结果是<b>必须拿到</b>的：它的失败（含超时）按旧语义抛出，
     * 由工具层转成 AGENT_RETRIEVAL_FAILED。改写列表的失败只降级为空列表。
     */
    private List<List<VectorSearchService.VectorHit>> retrieveAll(
            Long userId, List<String> queries, List<VectorResourceType> resourceTypes,
            int topK, long deadlineNanos) {
        List<FutureTask<List<VectorSearchService.VectorHit>>> tasks = new ArrayList<>(queries.size());
        for (int index = 0; index < queries.size(); index++) {
            String perQuery = queries.get(index);
            FutureTask<List<VectorSearchService.VectorHit>> task = new FutureTask<>(
                    () -> vectorSearchService.searchTopChunks(userId, perQuery, resourceTypes, topK));
            Thread.ofVirtual().name("agent-mq-search-" + index).start(task);
            tasks.add(task);
        }
        List<List<VectorSearchService.VectorHit>> perQueryHits = new ArrayList<>(tasks.size());
        for (int index = 0; index < tasks.size(); index++) {
            boolean primary = index == 0;
            List<VectorSearchService.VectorHit> hits = awaitSearch(
                    tasks.get(index), deadlineNanos, userId, index, primary);
            perQueryHits.add(hits == null ? List.of() : hits);
        }
        return perQueryHits;
    }

    /**
     * 等一次检索结果。
     *
     * @param primary true = 原 query：超时/失败/中断都会抛出，保持“检索坏了要报错”的旧语义；
     *                false = 改写列表：任何问题都降级为 {@code null}（调用方按空列表处理）
     */
    private List<VectorSearchService.VectorHit> awaitSearch(
            FutureTask<List<VectorSearchService.VectorHit>> task, long deadlineNanos,
            Long userId, int index, boolean primary) {
        long waitMillis = remainingMillis(deadlineNanos);
        try {
            return task.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            String message = "检索超时（合计上限 " + SEARCH_TOTAL_BUDGET_MS + "ms）";
            if (primary) {
                log.error("多查询：原 query 检索超时，按检索失败处理 userId={} waitMs={}",
                        userId, waitMillis, e);
                throw new VectorIndexExternalException(message + "：原查询", e);
            }
            log.warn("多查询：改写列表检索超时，忽略这一路 userId={} queryIndex={} waitMs={}",
                    userId, index, waitMillis);
            return null;
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            if (primary) {
                log.warn("多查询：原 query 检索被中断 userId={}", userId, e);
                throw new VectorIndexExternalException("检索被中断", e);
            }
            log.warn("多查询：改写列表检索被中断，忽略这一路 userId={} queryIndex={}",
                    userId, index, e);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (primary) {
                // 原 query 的失败保持旧类型（VectorIndexExternalException → 工具层转 AGENT_RETRIEVAL_FAILED）
                if (cause instanceof VectorIndexExternalException external) {
                    throw external;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new VectorIndexExternalException("原查询检索失败", cause);
            }
            log.warn("多查询：改写列表检索失败，忽略这一路 userId={} queryIndex={} cause={}",
                    userId, index, cause.getClass().getSimpleName(), cause);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 3) RRF 融合 + 去重 + 每篇最多 2 个 chunk
    // ------------------------------------------------------------------

    /**
     * RRF 融合。
     *
     * <p>规则（严格按方案原文）：
     * <ol>
     *   <li>每个列表内部按 rank 从 1 开始计 {@code 1/(60+rank)}；同一列表里重复出现的同一 chunk
     *       只按第一次（最高）计；</li>
     *   <li>同一 {@code (resourceType, resourceId, chunkIndex)} 在多个列表出现 → <b>分数累加、
     *       只输出一条</b>；</li>
     *   <li>按融合分降序（同分保留“首次出现”的顺序），同一篇文档最多保留
     *       {@code max-chunks-per-document} 个 chunk，最后按 {@code max-results} 收敛条数。</li>
     * </ol>
     */
    private List<VectorSearchService.VectorHit> fuse(
            List<List<VectorSearchService.VectorHit>> perQueryHits) {
        Map<HitId, FusedHit> fusedById = new LinkedHashMap<>();
        for (List<VectorSearchService.VectorHit> hits : perQueryHits) {
            Set<HitId> seenInList = new HashSet<>();
            for (int rank = 1; rank <= hits.size(); rank++) {
                VectorSearchService.VectorHit hit = hits.get(rank - 1);
                HitId id = new HitId(hit.sourceType(), hit.sourceId(), hit.chunkIndex());
                if (!seenInList.add(id)) {
                    continue;
                }
                fusedById.computeIfAbsent(id, key -> new FusedHit(key, hit))
                        .add(1.0d / (RRF_K + rank));
            }
        }
        List<FusedHit> ordered = new ArrayList<>(fusedById.values());
        // 稳定排序：同分时保留首次出现顺序（原 query 命中的优先），结果可复现
        ordered.sort(Comparator.comparingDouble(FusedHit::score).reversed());

        int maxResults = properties.getMaxResults();
        int maxChunksPerDocument = properties.getMaxChunksPerDocument();
        Map<String, Integer> chunksPerDocument = new HashMap<>();
        List<VectorSearchService.VectorHit> result = new ArrayList<>(Math.min(maxResults, ordered.size()));
        for (FusedHit fused : ordered) {
            String documentKey = fused.id().documentKey();
            int kept = chunksPerDocument.getOrDefault(documentKey, 0);
            if (kept >= maxChunksPerDocument) {
                continue;
            }
            chunksPerDocument.put(documentKey, kept + 1);
            result.add(fused.toHit());
            if (result.size() >= maxResults) {
                break;
            }
        }
        return result;
    }

    /** 去重键：资料类型 + 资料 ID + 片段序号（与 {@code EvidenceLedger} 的口径一致）。 */
    record HitId(String resourceType, String resourceId, int chunkIndex) {

        /**
         * “每篇文档”的归并键。
         *
         * <p>本轮按 {@code resourceType + resourceId} 归并（规格写的是 resourceId；带上类型是为了
         * 不同资料类型恰好同 ID 时不被误并，实际每个检索工具只查一种类型，两者等价）。
         * 按 {@code sourcePath} 归并是另一个可选口径，见汇报“待注意点”。
         */
        String documentKey() {
            return resourceType + ":" + resourceId;
        }
    }

    /** 融合中的一条：保留代表 hit（标题/正文/路径）与累加的融合分。 */
    private static final class FusedHit {

        private final HitId id;
        private final VectorSearchService.VectorHit representative;
        private double score;

        private FusedHit(HitId id, VectorSearchService.VectorHit representative) {
            this.id = id;
            this.representative = representative;
        }

        private void add(double weight) {
            this.score += weight;
        }

        private HitId id() {
            return id;
        }

        private double score() {
            return score;
        }

        /** 输出仍用 {@link VectorSearchService.VectorHit}，只有 {@code score} 换成融合分（规格要求）。 */
        private VectorSearchService.VectorHit toHit() {
            return new VectorSearchService.VectorHit(
                    id.resourceType(), id.resourceId(), id.chunkIndex(),
                    representative.title(), representative.content(), representative.sourcePath(),
                    score, representative.pointId());
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 距离 deadline 还剩多少毫秒（至少 1ms，避免 get(0) 直接抛超时的歧义）。 */
    private static long remainingMillis(long deadlineNanos) {
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(1L, remaining);
    }

    private static boolean sameQuery(String left, String right) {
        return normalizeQuery(left).equals(normalizeQuery(right));
    }

    private static String normalizeQuery(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String truncate(String text, int maxChars) {
        return text == null || text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
