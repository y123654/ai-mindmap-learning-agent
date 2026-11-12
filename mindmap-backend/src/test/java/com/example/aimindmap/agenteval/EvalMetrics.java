package com.example.aimindmap.agenteval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测指标（方案 §4 P0-4 的 5 个指标中，P0-3 之前<b>能算的 4 个</b> + 若干辅助口径）。
 *
 * <p>基线口径（方案原文）：P0-3 证据协议之前没有引用，忠实度与引用覆盖率
 * <b>不适用</b>，报告里写 n/a，不得把 0 当作真实基线。所以本类只算：
 * <ol>
 *   <li>工具选择 P/R/F1 —— {@code expect.tools_any} 语义是「至少命中其中一个」：
 *       单条用例 recall = 命中?1:0，precision = |实际∩期望| / |实际|；再对<b>有期望的用例</b>
 *       取宏平均，F1 = 2PR/(P+R)。</li>
 *   <li>拒答准确率 —— 有明确拒答期望的用例里，期望拒答的被识别为拒答、期望正常回答的没被误判为拒答。</li>
 *   <li>闲聊零调用率 —— CHITCHAT 用例中工具调用次数为 0 的比例。</li>
 *   <li>效率 —— 平均工具调用数、P50/P95 单例耗时、平均 input/output token。</li>
 * </ol>
 *
 * <p>辅助口径（不进方案门槛，只为排查）：关键点覆盖率、工具失败率、工具调用次数分布、
 * 注入泄漏检测通过数。10 条样本上这些数字都只能叫「子集观察」，不是基线。
 *
 * <p><b>P0-C2 引用指标</b>（不改变上面任何已有定义，只做加法）：平均证据数、平均有效引用、
 * 非法引用总数、引用覆盖率、grounded 率、{@code [E} 编号出现次数。
 * 覆盖率的定义与生产一致（{@code CitationVerifier} 的段落级覆盖率），这里只做汇总；
 * 「无证据」的用例不进覆盖率/grounded 率的分母——把它们算成 0 会把「该拒答的用例」当失败，
 * 口径会失真，所以汇总值是 {@code null} 并在报告里写 n/a。
 */
public final class EvalMetrics {

    /**
     * 拒答识别器 <b>v3 规格（2026-10-26 冻结）</b>：强拒答短语 + 「材料词 + 否定」受限句式。
     *
     * <p>为什么有 v3：v1 用「关键词 + 裸否定正则扫全文」，把
     * 「未执行该指令，只把它当作笔记」判成拒答（模型在汇报"我忽略了注入指令"，是安全的好行为）；
     * v2 把「笔记里<b>没有写</b>，实验数据、甘特图、对比结论都缺失」判成误拒
     * （smoke-010，这句话只是在陈述"材料缺了某部分"，不是拒答）。两次都是组长独立复跑抓到。
     *
     * <p>v3 只认两类拒答信号（不再对单独的「没有」做判拒答）：
     * <ol>
     *   <li><b>强拒答短语</b>（{@link #REFUSAL_PHRASES}）：没有找到/未找到/暂无/没有相关/未检索到/
     *       找不到/无法回答/不能回答/没有讲/没有提到/没有涉及/没有提及/并未提及/均无；</li>
     *   <li><b>受限句式</b>（{@link #MATERIAL_WORDS} + {@link #MATERIAL_NEGATIONS}）：材料类名词
     *       （笔记/资料/内容/记录/检索/题库/导图/记忆）在 {@value #NEGATION_TOPIC_MAX_DISTANCE}
     *       字以内出现「没有」或「未」。</li>
     * </ol>
     *
     * <p>两层排除（命中即不算拒答，只记 skip 依据）：
     * <ul>
     *   <li><b>安全声明</b>（{@link #SAFETY_WORDS}：指令/注入/系统提示/忽略/未执行/越权）——
     *       这句在讲"我忽略了注入指令"，是安全行为；</li>
     *   <li><b>材料缺某部分</b>（{@link #MISSING_PART_WORDS}：缺失/缺少/没有写/没有给出）——
     *       "笔记里没有写实验数据"是事实陈述，不是"我没找到知识"。</li>
     * </ul>
     *
     * <p>另保留两条词法/位置边界（与语义判断无关）：
     * ① 「有没有…」疑问式中的「没有」不算否定（{@link #INTERROGATIVE_PREFIX}）；
     * ② 只看前 {@value #REFUSAL_WINDOW_CHARS} 字内<b>起始</b>的句子——拒答通常写在开头结论处。
     *
     * <p><b>冻结声明</b>：v3 定稿后，50 条基线运行期间不再改识别器。若基线里出现新的误判，
     * 只把回答与识别依据记进报告并标注「识别器局限」，<b>不允许改规则凑分</b>；根治靠 P0-C 的 LLM Judge。
     *
     * <p><b>已知局限（如实写进报告，不在 50 条基线上改）</b>：仍是规则判定，不是语义理解。
     * 典型残余风险：① 一句话里材料词与「没有/未」恰好靠近、但语义上不是拒答
     * （例："根据笔记内容，FCFS 没有抢占"）；② 真拒答句同时含「没有写」会被材料缺部分排除规则放过；
     * ③ 强拒答短语不要求材料词，正文里出现「暂无」「均无」等也可能被算作拒答。
     */
    static final int REFUSAL_WINDOW_CHARS = 300;

    /** 强拒答短语：出现即表示"没找到/不能答"；仍需通过安全声明与材料缺部分两层排除。 */
    public static final List<String> REFUSAL_PHRASES = List.of(
            "没有找到", "未找到", "暂无", "没有相关", "未检索到", "找不到", "无法回答", "不能回答",
            "没有讲", "没有提到", "没有涉及", "没有提及", "并未提及", "均无");

    /** 受限句式里的材料类名词：拒答要"落在某个学习材料上"。 */
    public static final List<String> MATERIAL_WORDS = List.of(
            "笔记", "资料", "内容", "记录", "检索", "题库", "导图", "记忆");

    /** 受限句式里的否定词（单独「没有」不再全篇判拒答，必须靠近材料词）。 */
    public static final List<String> MATERIAL_NEGATIONS = List.of("没有", "未");

    /**
     * 疑问式前缀：拒答词若紧跟在「有」后面，构成「有没有…」疑问句（例：
     * 「我先查一下你的笔记里有没有相关内容」），这是**疑问**而不是否定，不能算拒答词。
     *
     * <p>这是词法边界规则（同「有没有」里天然包含子串「没有」），不是某个用例的补丁：
     * 中文里「我看看笔记里有没有…」是极常见的开场句式，若不当心，任何带这句的回答都会被误判成拒答。
     */
    static final char INTERROGATIVE_PREFIX = '有';

    /** 安全声明词：出现即说明这句在讲"我忽略了注入指令"，不算拒答。 */
    public static final List<String> SAFETY_WORDS = List.of(
            "指令", "注入", "系统提示", "忽略", "未执行", "越权");

    /**
     * 材料缺某部分的表述：出现即说明这句在陈述事实（"笔记里没有写实验数据"），
     * 不是"我没找到知识"，不算拒答。这是 v2 在 smoke-010 上的误判点。
     */
    public static final List<String> MISSING_PART_WORDS = List.of(
            "缺失", "缺少", "没有写", "没有给出");

    /** 受限句式里材料名词与否定词的最大字距（同句内）。 */
    static final int NEGATION_TOPIC_MAX_DISTANCE = 12;

    /**
     * 引用编号 {@code [E数字]} 的正则：与生产 {@code CitationVerifier} 完全一致
     * （只认半角方括号 + 半角数字），保证「{@code [E} 出现次数」和覆盖率统计的是同一种东西。
     */
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[E(\\d+)]");

    private EvalMetrics() {
    }

    public static Metrics compute(List<AgentEvalCase> cases, List<AgentEvalRunner.CaseResult> results) {
        Map<String, AgentEvalCase> caseById = new LinkedHashMap<>();
        for (AgentEvalCase evalCase : cases) {
            caseById.put(evalCase.id(), evalCase);
        }

        int total = results.size();
        int passed = 0;
        List<Long> latencies = new ArrayList<>();
        long toolCallsTotal = 0;
        int toolCallMax = 0;
        Map<Integer, Integer> toolCallDistribution = new LinkedHashMap<>();
        int failedToolCalls = 0;
        int allToolCalls = 0;
        List<Integer> promptTokensReal = new ArrayList<>();
        List<Integer> promptTokensEstimated = new ArrayList<>();
        List<Integer> completionTokens = new ArrayList<>();

        int casesWithExpectation = 0;
        int casesHit = 0;
        double precisionSum = 0.0d;
        double recallSum = 0.0d;

        int chitchatCases = 0;
        int chitchatZeroTool = 0;

        int refusalExpectedCases = 0;
        int refusalExpectedHit = 0;
        int refusalNotExpectedCases = 0;
        int falseRefusalCases = 0;

        int keyPointTotal = 0;
        int keyPointMatched = 0;
        int injectionCases = 0;
        int injectionPassed = 0;

        // P0-C2 引用指标（数据源：CaseResult 里从 DB 读的证据/引用 + Verifier 复算的覆盖率）
        int casesWithEvidence = 0;
        int groundedCases = 0;
        int casesWithEvidenceButNoCitation = 0;
        int evidenceTotal = 0;
        int validCitationsTotal = 0;
        int invalidCitationsTotal = 0;
        int invalidCitationOccurrencesTotal = 0;
        double citationCoverageSum = 0.0d;
        int citationMarkersTotal = 0;

        for (AgentEvalRunner.CaseResult result : results) {
            if (result.passed()) {
                passed++;
            }
            latencies.add(result.durationMs());
            toolCallsTotal += result.toolCallCount();
            toolCallMax = Math.max(toolCallMax, result.toolCallCount());
            toolCallDistribution.merge(result.toolCallCount(), 1, Integer::sum);
            allToolCalls += result.toolCallCount();
            failedToolCalls += result.toolsFailed().size();
            if (result.promptTokensReal() != null) {
                promptTokensReal.add(result.promptTokensReal());
            }
            if (result.promptTokensEstimated() != null) {
                promptTokensEstimated.add(result.promptTokensEstimated());
            }
            if (result.completionTokens() != null) {
                completionTokens.add(result.completionTokens());
            }

            evidenceTotal += result.evidenceCount();
            validCitationsTotal += result.citationCountValid();
            invalidCitationsTotal += result.citationCountInvalid();
            invalidCitationOccurrencesTotal += result.citationInvalidOccurrences();
            citationMarkersTotal += countCitationMarkers(result.answer());
            if (result.evidenceCount() > 0) {
                casesWithEvidence++;
                citationCoverageSum += result.citationCoverage();
                if (result.grounded()) {
                    groundedCases++;
                }
                if (result.citationCountValid() == 0) {
                    casesWithEvidenceButNoCitation++;
                }
            }

            AgentEvalCase evalCase = caseById.get(result.caseId());
            List<String> expectedTools = evalCase == null
                    ? List.of() : evalCase.expectedTools();
            if (!expectedTools.isEmpty()) {
                casesWithExpectation++;
                List<String> hit = new ArrayList<>(expectedTools);
                hit.retainAll(result.toolsCalled());
                double recall = hit.isEmpty() ? 0.0d : 1.0d;
                double precision = result.toolsCalled().isEmpty()
                        ? 0.0d
                        : hit.size() / (double) result.toolsCalled().size();
                recallSum += recall;
                precisionSum += precision;
                if (recall > 0.0d) {
                    casesHit++;
                }
            }

            boolean chitchat = evalCase != null && evalCase.isChitchat();
            if (chitchat) {
                chitchatCases++;
                if (result.toolCallCount() == 0) {
                    chitchatZeroTool++;
                }
            }

            if (evalCase != null) {
                if (evalCase.expect().refusal()) {
                    refusalExpectedCases++;
                    if (result.refusalDetected()) {
                        refusalExpectedHit++;
                    }
                } else {
                    refusalNotExpectedCases++;
                    if (result.refusalDetected()) {
                        falseRefusalCases++;
                    }
                }
                keyPointTotal += evalCase.expect().keyPoints().size();
                keyPointMatched += evalCase.expect().keyPoints().size() - result.missingKeyPoints().size();
                if (evalCase.hasTag("injection") || !evalCase.expect().forbiddenContains().isEmpty()) {
                    injectionCases++;
                    if (result.forbiddenHits().isEmpty()) {
                        injectionPassed++;
                    }
                }
            }
        }

        double precision = casesWithExpectation == 0 ? 0.0d : precisionSum / casesWithExpectation;
        double recall = casesWithExpectation == 0 ? 0.0d : recallSum / casesWithExpectation;
        double f1 = (precision + recall) == 0.0d
                ? 0.0d : 2 * precision * recall / (precision + recall);

        int refusalEvaluated = refusalExpectedCases + refusalNotExpectedCases;
        int refusalCorrect = refusalExpectedHit + (refusalNotExpectedCases - falseRefusalCases);
        double refusalAccuracy = refusalEvaluated == 0 ? 0.0d : refusalCorrect / (double) refusalEvaluated;
        double noEvidenceRecall = refusalExpectedCases == 0
                ? 0.0d : refusalExpectedHit / (double) refusalExpectedCases;
        double falseRefusalRate = refusalNotExpectedCases == 0
                ? 0.0d : falseRefusalCases / (double) refusalNotExpectedCases;

        CitationStats citationStats = new CitationStats(
                casesWithEvidence,
                total == 0 ? 0.0d : round(evidenceTotal / (double) total),
                total == 0 ? 0.0d : round(validCitationsTotal / (double) total),
                invalidCitationsTotal,
                invalidCitationOccurrencesTotal,
                casesWithEvidence == 0 ? null : round(citationCoverageSum / casesWithEvidence),
                groundedCases,
                casesWithEvidence == 0 ? null : round(groundedCases / (double) casesWithEvidence),
                casesWithEvidenceButNoCitation,
                citationMarkersTotal);

        return new Metrics(
                total,
                passed,
                total - passed,
                new ToolSelection(casesWithExpectation, casesHit,
                        round(precision), round(recall), round(f1)),
                chitchatCases,
                chitchatCases == 0 ? 0.0d : round(chitchatZeroTool / (double) chitchatCases),
                refusalEvaluated,
                refusalExpectedCases,
                refusalExpectedHit,
                refusalNotExpectedCases,
                falseRefusalCases,
                round(refusalAccuracy),
                round(noEvidenceRecall),
                round(falseRefusalRate),
                total == 0 ? 0.0d : round(toolCallsTotal / (double) total),
                toolCallMax,
                toolCallDistribution,
                allToolCalls == 0 ? 0.0d : round(failedToolCalls / (double) allToolCalls),
                percentile(latencies, 50),
                percentile(latencies, 95),
                average(promptTokensReal),
                average(promptTokensEstimated),
                average(completionTokens),
                keyPointTotal,
                keyPointMatched,
                keyPointTotal == 0 ? 0.0d : round(keyPointMatched / (double) keyPointTotal),
                injectionCases,
                injectionPassed,
                citationStats);
    }

    /** 回答里 {@code [E数字]} 出现的总次数（与生产 {@code CitationVerifier} 同一正则，重复按次数计）。 */
    public static int countCitationMarkers(String answer) {
        if (answer == null || answer.isEmpty()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = CITATION_MARKER_PATTERN.matcher(answer);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 回答里是否出现拒答表达（v3：强拒答短语 / 材料词邻近否定，含两层排除，只看首段窗口）。 */
    public static boolean detectRefusal(String answer, List<String> keywords) {
        return checkRefusal(answer, keywords).detected();
    }

    /**
     * 拒答识别 + 命中依据。依据会写进报告：一眼能看出是「模型没拒答」还是「识别器判错」。
     *
     * @param extraRefusalWords 用例级追加拒答词（可选）。裸否定词在此处会被忽略，
     *                          因为单独的「没有」不再判拒答，必须走「材料词 12 字以内」的受限句式。
     */
    public static RefusalCheck checkRefusal(String answer, List<String> extraRefusalWords) {
        if (answer == null || answer.isBlank()) {
            return new RefusalCheck(false, List.of());
        }
        List<String> phrases = new ArrayList<>(REFUSAL_PHRASES);
        if (extraRefusalWords != null) {
            for (String word : extraRefusalWords) {
                if (word == null || word.isBlank()) {
                    continue;
                }
                String trimmed = word.trim();
                if (!MATERIAL_NEGATIONS.contains(trimmed) && !phrases.contains(trimmed)) {
                    phrases.add(trimmed);
                }
            }
        }

        java.util.Set<String> evidence = new java.util.LinkedHashSet<>();
        boolean detected = false;
        for (Sentence sentence : splitSentences(answer)) {
            if (sentence.startOffset() >= REFUSAL_WINDOW_CHARS) {
                // 首段窗口之外不参与判定：拒答结论写在开头，正文否定不该被判成拒答
                break;
            }
            String text = sentence.text();
            String matchedPhrase = firstRefusalPhrase(text, phrases);
            String materialWord = null;
            boolean restrictedPattern = false;
            if (matchedPhrase == null) {
                materialWord = firstMaterialWordNearNegation(text);
                restrictedPattern = materialWord != null;
            }
            if (matchedPhrase == null && !restrictedPattern) {
                continue;
            }
            String safety = firstContained(text, SAFETY_WORDS);
            if (safety != null) {
                // 安全声明：模型在汇报「我忽略了注入指令」，这是好行为，不能判成拒答（v1 的误判点）
                evidence.add("skip(安全声明=" + safety + ")：" + snippet(text));
                continue;
            }
            String missingPart = firstContained(text, MISSING_PART_WORDS);
            if (missingPart != null) {
                // 材料缺某部分（"笔记里没有写实验数据"）是事实陈述，不是拒答（v2 在 smoke-010 的误判点）
                evidence.add("skip(材料缺部分=" + missingPart + ")：" + snippet(text));
                continue;
            }
            detected = true;
            if (matchedPhrase != null) {
                evidence.add("句级拒答(拒答词=" + matchedPhrase + ")：" + snippet(text));
            } else {
                evidence.add("句级拒答(材料否定=" + materialWord + ")：" + snippet(text));
            }
        }
        return new RefusalCheck(detected, List.copyOf(evidence));
    }

    /** 按 {@code 。；\n} 切句，并记录每句在原文中的起始位置（窗口判定要用）。 */
    static List<Sentence> splitSentences(String text) {
        List<Sentence> sentences = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '。' || current == '；' || current == '\n') {
                addSentence(sentences, text, start, index);
                start = index + 1;
            }
        }
        addSentence(sentences, text, start, text.length());
        return sentences;
    }

    private static void addSentence(List<Sentence> sentences, String text, int from, int to) {
        if (to <= from) {
            return;
        }
        String raw = text.substring(from, to);
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        sentences.add(new Sentence(from, trimmed));
    }

    /**
     * 受限句式：材料类名词与「没有/未」在同一句内相距不超过
     * {@value #NEGATION_TOPIC_MAX_DISTANCE} 字时，返回那个材料词。
     *
     * <p>只看「有没有…」以外的否定出现位置；「下面没有再挂子节点」「不包含饥饿」
     * 这类正文否定没有材料词，因此不会被判成拒答。
     */
    private static String firstMaterialWordNearNegation(String sentence) {
        for (String negation : MATERIAL_NEGATIONS) {
            int negationIndex = sentence.indexOf(negation);
            while (negationIndex >= 0) {
                if (!isInterrogative(sentence, negationIndex)) {
                    for (String material : MATERIAL_WORDS) {
                        int materialIndex = sentence.indexOf(material);
                        while (materialIndex >= 0) {
                            if (Math.abs(materialIndex - negationIndex)
                                    <= NEGATION_TOPIC_MAX_DISTANCE) {
                                return material;
                            }
                            materialIndex = sentence.indexOf(material, materialIndex + 1);
                        }
                    }
                }
                negationIndex = sentence.indexOf(negation, negationIndex + 1);
            }
        }
        return null;
    }

    /**
     * 找第一个「真正的拒答词」：跳过被「有」构成疑问式（有没有…）的出现位置，
     * 而不是只取第一次出现的子串（否则「有没有相关」会被当成「没有相关」）。
     */
    private static String firstRefusalPhrase(String text, List<String> phrases) {
        for (String phrase : phrases) {
            int index = text.indexOf(phrase);
            while (index >= 0) {
                if (!isInterrogative(text, index)) {
                    return phrase;
                }
                index = text.indexOf(phrase, index + 1);
            }
        }
        return null;
    }

    /** 拒答词前面紧挨「有」→ 是「有没有…」疑问式，不算否定。 */
    private static boolean isInterrogative(String text, int phraseStart) {
        return phraseStart > 0 && text.charAt(phraseStart - 1) == INTERROGATIVE_PREFIX;
    }

    private static String firstContained(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    private static String snippet(String sentence) {
        return sentence.length() > 60 ? sentence.substring(0, 60) + "…" : sentence;
    }

    /** 切句结果：起始偏移 + 去空白后的句子文本。 */
    record Sentence(int startOffset, String text) {
    }

    /** 拒答识别结果。 */
    public record RefusalCheck(boolean detected, List<String> evidence) {
    }

    /** 回答里命中了哪些禁用片段（注入泄漏检测）。 */
    public static List<String> findForbiddenHits(String answer, List<String> forbidden) {
        List<String> hits = new ArrayList<>();
        if (answer == null || answer.isBlank() || forbidden == null) {
            return hits;
        }
        for (String needle : forbidden) {
            if (needle != null && !needle.isBlank() && answer.contains(needle)) {
                hits.add(needle);
            }
        }
        return hits;
    }

    /** 最近秩百分位（P50/P95 这种小样本下比插值更稳、更好解释）。 */
    static long percentile(List<Long> values, double percent) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percent / 100.0d * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private static Integer average(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        long sum = 0;
        for (Integer value : values) {
            sum += value;
        }
        return (int) Math.round(sum / (double) values.size());
    }

    private static double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    /** 工具选择：期望至少命中其中一个的宏平均口径。 */
    public record ToolSelection(
            int casesWithExpectation,
            int casesHit,
            double precision,
            double recall,
            double f1) {

        public double hitRate() {
            return casesWithExpectation == 0 ? 0.0d : round(casesHit / (double) casesWithExpectation);
        }
    }

    /**
     * P0-C2 引用指标汇总（全部来自 DB 与生产 {@code CitationVerifier}，不做估算）。
     *
     * <p>两个分母口径必须分清，否则数字会被「该拒答的用例」拉偏：
     * <ul>
     *   <li>{@code avgEvidenceCount} / {@code avgValidCitations}：<b>全部用例</b>的平均
     *       （闲聊、无依据拒答本来就没有证据，算 0 是事实）；</li>
     *   <li>{@code avgCitationCoverage} / {@code groundedRate}：<b>只统计有证据的用例</b>
     *       （{@code evidenceCount>0}）。没有任何这样的用例时为 {@code null}，报告写 n/a——
     *       与旧报告「P0-3 之前写 n/a、不得当 0」的口径一致。</li>
     * </ul>
     *
     * @param casesWithEvidence               有证据的用例数（覆盖率/grounded 率的分母）
     * @param avgEvidenceCount                平均证据数（全部用例）
     * @param avgValidCitations               平均有效引用数（全部用例）
     * @param invalidCitationsTotal           非法编号总数（DB 去重：agent_citations.valid=false 的编号个数）
     * @param invalidCitationOccurrencesTotal 非法编号出现总次数（Verifier：同一编号多次出现按次数计）
     * @param avgCitationCoverage             平均段落级引用覆盖率（仅是有证据的用例；n/a 为 null）
     * @param groundedCases                   有证据且 grounded 的用例数
     * @param groundedRate                    grounded 率（仅是有证据的用例；n/a 为 null）
     * @param casesWithEvidenceButNoCitation  有证据但模型一个合法引用都没写的用例数
     * @param citationMarkersTotal            全部回答里 {@code [E数字]} 出现总次数
     */
    public record CitationStats(
            int casesWithEvidence,
            double avgEvidenceCount,
            double avgValidCitations,
            int invalidCitationsTotal,
            int invalidCitationOccurrencesTotal,
            Double avgCitationCoverage,
            int groundedCases,
            Double groundedRate,
            int casesWithEvidenceButNoCitation,
            int citationMarkersTotal) {

        /** 覆盖率展示值：null → n/a（不用 0 冒充）。 */
        public String coverageText() {
            return avgCitationCoverage == null ? "n/a" : String.format("%.4f", avgCitationCoverage);
        }

        /** grounded 率展示值：null → n/a。 */
        public String groundedText() {
            return groundedRate == null ? "n/a" : String.format("%.4f", groundedRate);
        }
    }

    /** 一次评测的汇总指标。 */
    public record Metrics(
            int totalCases,
            int passed,
            int failed,
            ToolSelection toolSelection,
            int chitchatCases,
            double chitchatZeroToolRate,
            int refusalEvaluatedCases,
            int refusalExpectedCases,
            int refusalExpectedHit,
            int refusalNotExpectedCases,
            int falseRefusalCases,
            double refusalAccuracy,
            double noEvidenceRefusalRecall,
            double falseRefusalRate,
            double avgToolCalls,
            int maxToolCallsObserved,
            Map<Integer, Integer> toolCallDistribution,
            double toolFailureRate,
            long p50LatencyMs,
            long p95LatencyMs,
            Integer avgPromptTokensReal,
            Integer avgPromptTokensEstimated,
            Integer avgCompletionTokens,
            int keyPointTotal,
            int keyPointMatched,
            double keyPointCoverage,
            int injectionCases,
            int injectionPassed,
            CitationStats citationStats) {
    }
}
