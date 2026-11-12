package com.example.aimindmap.agenteval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * 读取并校验 {@code src/test/resources/agent-eval/cases.yaml}（方案 §4 P0-4）。
 *
 * <p>校验原则：宁可启动就报错，也不要带着脏数据跑评测（脏数据的指标没有意义）。
 * 必填：version / dataset / cases 非空 / 每条 id、intent、question、expect；
 * id 去重；intent 必须落在方案 §5 P1-1 定义的 5 个枚举内。
 */
public final class AgentEvalCases {

    private static final Logger log = LoggerFactory.getLogger(AgentEvalCases.class);

    /** classpath 路径：surefire 会把 src/test/resources 复制到 target/test-classes。 */
    static final String CLASSPATH_RESOURCE = "/agent-eval/cases.yaml";

    /** 兜底文件路径（surefire 工作目录是 mindmap-backend）。 */
    static final Path FILE_FALLBACK = Path.of("src/test/resources/agent-eval/cases.yaml");

    /** 方案 §5 P1-1 的意图枚举；本阶段不实现路由，只要求用例意图落在这 5 个值内。 */
    static final Set<String> ALLOWED_INTENTS =
            Set.of("CHITCHAT", "DIRECT", "RETRIEVE", "MULTI_HOP", "TASK");

    private AgentEvalCases() {
    }

    public static AgentEvalDataset load() {
        Map<String, Object> root = readYaml();
        String version = requireText(root.get("version"), "version");
        String dataset = requireText(root.get("dataset"), "dataset");
        String note = root.get("dataset_note") == null ? "" : String.valueOf(root.get("dataset_note"));
        List<Object> rawCases = asList(root.get("cases"), "cases");
        if (rawCases.isEmpty()) {
            throw new IllegalStateException("agent-eval/cases.yaml 的 cases 不能为空");
        }

        List<AgentEvalCase> cases = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < rawCases.size(); index++) {
            AgentEvalCase parsed = parseCase(asMap(rawCases.get(index), "cases[" + index + "]"), index);
            if (!ids.add(parsed.id())) {
                throw new IllegalStateException("agent-eval/cases.yaml 用例 id 重复：" + parsed.id());
            }
            cases.add(parsed);
        }
        AgentEvalDataset result = new AgentEvalDataset(version, dataset, note, List.copyOf(cases));
        log.info("评测用例加载完成 dataset={} version={} count={} ids={}",
                dataset, version, cases.size(), ids);
        return result;
    }

    private static Map<String, Object> readYaml() {
        try (InputStream classpath = AgentEvalCases.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (classpath != null) {
                return parse(classpath);
            }
            if (Files.isRegularFile(FILE_FALLBACK)) {
                log.warn("classpath 上找不到 {}，回退读取文件 {}", CLASSPATH_RESOURCE, FILE_FALLBACK.toAbsolutePath());
                try (InputStream file = Files.newInputStream(FILE_FALLBACK)) {
                    return parse(file);
                }
            }
            throw new IllegalStateException("找不到评测用例文件：" + CLASSPATH_RESOURCE
                    + "（兜底路径 " + FILE_FALLBACK.toAbsolutePath() + " 也不存在）");
        } catch (IOException e) {
            throw new IllegalStateException("读取评测用例文件失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(InputStream inputStream) {
        Object loaded = new Yaml().load(inputStream);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalStateException("agent-eval/cases.yaml 根节点必须是 map");
        }
        return (Map<String, Object>) map;
    }

    private static AgentEvalCase parseCase(Map<String, Object> raw, int index) {
        String id = requireText(raw.get("id"), "cases[" + index + "].id");
        String intent = requireText(raw.get("intent"), "cases[" + index + "].intent");
        if (!ALLOWED_INTENTS.contains(intent)) {
            throw new IllegalStateException("用例 " + id + " 的 intent 不合法：" + intent
                    + "，允许值=" + ALLOWED_INTENTS);
        }
        String question = requireText(raw.get("question"), "cases[" + index + "].question");
        Map<String, Object> expectMap = asMap(raw.get("expect"), "cases[" + index + "].expect");
        AgentEvalCase.Expect expect = new AgentEvalCase.Expect(
                asStringList(expectMap.get("tools_any"), "cases[" + index + "].expect.tools_any"),
                asBoolean(expectMap.get("must_cite"), "cases[" + index + "].expect.must_cite"),
                asBoolean(expectMap.get("refusal"), "cases[" + index + "].expect.refusal"),
                asStringList(expectMap.get("key_points"), "cases[" + index + "].expect.key_points"),
                asStringList(expectMap.get("refusal_keywords"),
                        "cases[" + index + "].expect.refusal_keywords"),
                asStringList(expectMap.get("forbidden_contains"),
                        "cases[" + index + "].expect.forbidden_contains"));
        return new AgentEvalCase(
                id,
                intent,
                question,
                expect,
                asStringList(raw.get("gold_chunks"), "cases[" + index + "].gold_chunks"),
                asStringList(raw.get("tags"), "cases[" + index + "].tags"),
                raw.get("fixture_note") == null ? "" : String.valueOf(raw.get("fixture_note")));
    }

    private static String requireText(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("agent-eval/cases.yaml 缺少必填字段：" + field);
        }
        return String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("agent-eval/cases.yaml 字段 " + field + " 必须是 map");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("agent-eval/cases.yaml 字段 " + field + " 必须是 list");
        }
        return (List<Object>) list;
    }

    private static List<String> asStringList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        List<Object> raw = asList(value, field);
        List<String> result = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element == null) {
                continue;
            }
            result.add(String.valueOf(element).trim());
        }
        return List.copyOf(result);
    }

    private static boolean asBoolean(Object value, String field) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalStateException("agent-eval/cases.yaml 字段 " + field + " 必须是布尔值，实际=" + text);
    }

    /** 一份用例集：version + 数据集名 + 用例列表。 */
    public record AgentEvalDataset(
            String version,
            String dataset,
            String datasetNote,
            List<AgentEvalCase> cases) {

        public AgentEvalCase requireById(String caseId) {
            return cases.stream()
                    .filter(item -> item.id().equals(caseId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("用例不存在：" + caseId));
        }
    }
}
