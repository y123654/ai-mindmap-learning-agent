# 学习 Agent 综合升级方案（上下文工程优化 + 高并发异步架构）v1.0

> 适用项目：课程笔记整理与练习系统（AI 智能思维导图学习系统）
> 项目定位：毕业设计 + 求职简历项目
> 上游基准：《学习Agent改造方案-Qdrant+SpringAI-v1.0.md》阶段 0~4（已签核，209 个测试全绿）
> 文档用途：本文档由两份方案合并而成（①工具结果缓存与 Token 预算；②消息队列与高并发），是后续全部开发的**唯一需求基准**，供其他开发执行 agent 严格按阶段实施，不允许自行扩项。
> 创建日期：2026-10-14

---

## 0. 已确认事项（项目所有者 2026-10-14 拍板，执行中不得更改）

1. **MQ 选型：RabbitMQ**（曾评估 Kafka/RocketMQ，对比见附录 B；改型须先改文档）。
2. **部署目录**：RabbitMQ 独立部署于 `D:\develop\mq-docker\`（compose + 数据卷都在该目录）；不得改动 `D:\develop\rag-docker\` 与 Dify 的任何 compose 文件。
3. **Redis 复用现有容器，不新建**：机上 Redis 最可能为 Dify 自带的 `redis:6-alpine`（定义在 `D:\develop\dify-main\docker\docker-compose.yaml`，密码取 `REDIS_PASSWORD`，默认 `difyai123456`，数据在 `D:\develop\dify-main\docker\volumes\redis\data`）；实际以阶段三 `docker ps` 查到的容器为准。该容器默认未向宿主机发布 6379 端口，连通性按阶段三的分支处理。
   - **【2026-10-16 决策变更，项目所有者拍板】** 阶段三实测发现机上另有一个长期存在、已把 6379 发布到宿主机的独立 Redis 容器（容器名 `redis`，镜像 `redis:latest`，数据卷 `redis-data`，弱口令仅本机开发用），其 db0/db1/db5 被既有练习项目占用、db2~db4 全空；而 Dify Redis 的 db0 为缓存、db1 为 Celery 任务队列（`_kombu.*` 22 键）。经所有者选择：**本项目改用该独立 Redis 容器**（不再使用 Dify Redis、无需 socat 端口中转、不修改 Dify 任何文件），逻辑分区由 db1 **改为 db2**，key 前缀仍统一为 `aimindmap:`；不得读写/清空 db0、db1、db5。容器重启策略设为开机自启。原"二选一端口方案"对本决策作废。
4. **实施顺序**：先做第一部分（阶段一、二，小而独立），验收后再做第二部分（阶段三~八），禁止两条线并行改同一代码库。
5. **装包规则**：本方案全部新增 Maven 依赖（集中清单见 §3.4）在首次引入前必须向项目所有者逐项报备确认。

---

## 1. 背景、现状与目标

### 1.1 现状盘点（2026-10-14 代码核实，实施前须复读）

| 现状能力 | 实现方式 | 相关位置 |
|---|---|---|
| 工具调用型 Agent | Spring AI 1.0.0，6 个 @Tool 只读工具，单轮调用上限 3 次，结果截断 2500 字 | `agenttools/LearningResourceTools.java`、`agenttracing/*` |
| Agent 追踪 | `agent_runs` / `agent_tool_calls` 落库，虚拟线程 75 秒超时 | `agenttracing/*`、迁移 V8 |
| 上下文装配 | 系统提示词+长期记忆+摘要+最近 10/30 条消息；**按字符数 12000 裁剪**；输出固定 max_tokens=2000 | `assistant/KnowledgeAssistantService.java`（`persistUserMessage`、`buildGatewayContext`、`ChatStreamContext`） |
| 流式调用 | `.stream().content()` 只取文本，**丢弃平台 usage** | `assistant/KnowledgeAgentService.java` |
| 问答 SSE 编排 | run 生命周期、工具装配、`countPromptChars` | `assistant/KnowledgeAssistantStreamer.java` |
| 思维导图同步 | Spring 进程内事件 + `@Async` 线程池（core2/max4/queue20/CallerRunsPolicy） | `config/AsyncConfig.java`、`mindmap/SyncJobService.java`（发 `SyncJobRequested`）、`SyncJobWorker.java` |
| 出题 / 会话摘要 | 进程内事件 + worker；摘要 >20 条消息触发，保留最近 10 条 | `question/QuestionGenerationWorker.java`、`assistant/SessionSummaryWorker.java` |
| 向量索引管道 | 任务表 + `@Scheduled(5s)` + `FOR UPDATE SKIP LOCKED` + attempts 5 次重试 + 确定性 pointId | `vectorindex/*`、迁移 V7 |
| PDF 导入 | **同步**处理（上传请求内解析，20MB/100 页，扫描件识别） | `importpdf/*`、迁移 V6 |
| 同步进度 SSE | 内存注册表 + 15 秒心跳，代码自述"第一版本机单实例" | `mindmap/SyncJobEventPusher.java` |
| AI 配置 | `app.ai.*`（max-output-tokens=2000、max-prompt-chars=12000 等） | `ai/AiProperties.java`、`application.yml` |
| 迁移脚本 | 最新 V9；本方案依次占用 **V10、V11、V12…** | `src/main/resources/db/migration/` |
| 中间件环境 | Docker 29.4.1；rag-docker 在跑 Qdrant/Neo4j/Milvus；另有 Dify Redis 6 | 本机 |

### 1.2 目标（两部分，对应两类岗位的简历卖点）

**第一部分：上下文工程优化（对标 AI Agent / 大模型应用岗）**

- 消除单轮问答内的重复工具调用（重复调 Embedding/Qdrant 白花钱）。
- 上下文裁剪从"数字符"升级为"分词器真实 token 计量"，输出预算随模型窗口动态计算，回收平台真实 usage 做偏差校准。

**第二部分：高并发异步架构（对标 Java 后端岗）**

- RabbitMQ 承接全部后台任务：发布与执行解耦、重启不丢、失败死信、可多实例水平扩展。
- Outbox（本地发件箱）保证业务数据与消息一致；消费端全链路幂等。
- Redis 解决跨实例 SSE 推送、热点会话短期记忆缓存、接口限流。
- JMeter 压测 + Prometheus/Grafana 产出改造前后量化对比（毕设实验数据）。

### 1.3 非目标（防止扩项）

- 不做语义相似缓存、跨请求/落盘工具缓存、不缓存 AI 最终回答、不引入 Caffeine/Redis 做工具缓存。
- 不改思维导图同步的分批/截断重试业务逻辑（`SyncEngine`、`OpenAiCompatibleAiGateway` 等），第二部分只改其**触发方式**。
- 不做微服务 / Spring Cloud / Kafka 双 MQ / Elasticsearch / 分布式事务 XA / 多 Agent。
- PostgreSQL 始终是唯一事实源；Redis 只做缓存/广播/限流，丢失可重建。
- 不声称真实线上流量，所有性能结论以本机压测表述；不写未真实做到的部署（哨兵/集群/K8s）。
- SSE 协议只允许新增可选字段，不许改事件名或删改既有字段；前端除"PDF 异步进度/限流提示"外不改业务逻辑。

### 1.4 总路线图与最低可交付子集

| 阶段 | 内容 | 依赖 | 预估 | 迁移 |
---|---|---|---|---|
| 阶段一 | 单轮工具结果缓存 | 无 | 0.5~1 天 | V10 |
| 阶段二 | 真实 Token 计量与动态输出预算 | 阶段一（同文件区域，串行） | 0.5~1 天 | V11 |
| 阶段三 | 环境就绪：RabbitMQ 容器、Redis 连通验证、依赖与配置、冒烟 | 项目所有者确认环境 | 0.5 天 | — |
| 阶段四 | Outbox + MQ 接管向量索引链路（含死信/幂等/双实例） | 阶段三 | 1.5~2 天 | V12 |
| 阶段五 | PDF 导入异步化（上传即返回 + 前端轮询） | 阶段四 | 1 天 | 复用 V6 表 |
| 阶段六 | 同步 / 出题 / 摘要三条 AI 链路迁移 MQ | 阶段四 | 1~1.5 天 | 无新表 |
| 阶段七 | Redis 三件套：跨实例 SSE、短期记忆缓存、限流 | 阶段六 | 1.5 天 | 无新表 |
| 阶段八 | Actuator 指标 + Grafana + JMeter 压测 + 全量编排与报告 | 阶段七 | 1~2 天 | — |

**时间紧张时的最低可交付子集**：阶段一 + 阶段二 + 阶段三 + 阶段四 + 阶段八（仅压测场景 C 削峰）。即可同时讲清"上下文工程"与"MQ 不丢不重 + 削峰压测"两个完整故事。

每个阶段完成后：四段式汇报（任务清单 / 验收核对 / 未完成与注意点 / 代码状态）落盘 `docs/`，命名 `阶段XX完成汇报-YYYYMMDD[-N].md`；**项目所有者确认后才允许进入下一阶段**。

---

## 2. 现状关键代码索引（实施前逐一复读，以代码为准）

- 工具装饰器链：`agenttracing/AgentToolCallbackFactory.java`（`createForRun`：raw → `TracingToolCallback`）、`TracingToolCallback.java`（Guard 计数、截断、落库）、`AgentToolCallGuard.java`、`AgentRunTools.java`、`AgentToolContextKeys.java`
- 落库：`agenttracing/AgentTracingService.java`（startRun/succeedRun/failRun/startToolCall/succeedToolCall/failToolCall）、`AgentToolCallEntity.java`、`AgentRunEntity.java`、`AgentToolCallStatus.java`（RUNNING/SUCCEEDED/FAILED）、V8 脚本
- 问答：`assistant/KnowledgeAssistantService.java`、`KnowledgeAssistantStreamer.java`、`KnowledgeAgentService.java`、`KnowledgeAssistantPromptService.java`
- 异步：`config/AsyncConfig.java`、`mindmap/SyncJobWorker.java`、`question/QuestionGenerationWorker.java`、`assistant/SessionSummaryWorker.java`、`vectorindex/VectorIndexWorker.java`
- PDF：`importpdf/PdfImportService.java`、`PdfImportController.java`、`PdfImportJobEntity.java`
- SSE：`mindmap/SyncJobEventPusher.java`

---

## 3. 全阶段共用规范

### 3.1 数据库与 Flyway

- 只允许 Flyway 迁移，禁止手动 DDL；JPA `ddl-auto=validate` 启动必须通过。
- 版本固定：**V10 阶段一、V11 阶段二、V12 阶段四（outbox_events + idempotent_consumptions）**；若开工发现版本被占用，停止并询问项目所有者，不得自行跳号。

### 3.2 测试红线

- 基线 209 个测试，只增不减；旧测试改语义须在汇报逐条说明；`mvn -f mindmap-backend/pom.xml test` 全量绿；前端 `npm run build` 通过。
- 单测一律 Mockito，禁止真实 LLM / Embedding / Qdrant / MQ / Redis。
- 中间件集成测试允许 Testcontainers（仅专用 profile、本机有 Docker 时运行，无 Docker 跳过并说明），不得依赖"开发机正好开着"的容器。

### 3.3 日志与安全红线

- 新增 catch/失败/超限路径必须有日志；ERROR 带堆栈与业务上下文（taskId/runId/userId/sessionId）；禁止空 catch。
- 禁止打印密钥、JWT、提示词正文、工具入参/结果正文（只打摘要长度与计数）。
- MQ/Redis/AI 的地址、账号、密码、Key 全部走环境变量，默认值仅限 localhost 开发弱口令；外部配置文件不入 Git。
- **安全遗留提醒（不在开发范围，项目所有者自行处理）**：`application.yml` 中 `app.ai.api-key` 现有明文默认值，建议尽快到服务商轮换并改为 `${AI_API_KEY:}`；文档、日志、汇报中不得复述该 Key。

### 3.4 新增依赖清单（仅这些，引入前逐项报备；版本全部由 Spring Boot BOM 管理）

- 阶段一、二：**零新增依赖**——`com.knuddels:jtokkit:1.1.0` 已随 `spring-ai-client-chat:1.0.0` 传递引入（2026-10-14 已用 dependency:tree 证实）；阶段二开工先复跑 `mvn -f mindmap-backend/pom.xml dependency:tree` 确认，若缺失则停止报备，不得自行添加。
- 阶段三：`spring-boot-starter-amqp`、`spring-boot-starter-data-redis`。
- 阶段八：`spring-boot-starter-actuator`、`micrometer-registry-prometheus`。
- 测试域（可选）：`org.testcontainers:rabbitmq`、`org.testcontainers:junit-jupiter`（Testcontainers BOM）。

### 3.5 开关与降级（所有新能力默认可一键关闭）

- `app.mq.enabled`（默认 true）：关闭时 publisher 退化为 Noop，任务仍可由轮询兜底运行。
- `app.cache.redis-enabled`（默认 true）：关闭时 SSE 内存直推、限流走单机内存窗口。
- `app.agent.tool-cache.enabled`（默认 true）：关闭时工具装饰链退回现状。
- 任何外部中间件（MQ/Redis/Qdrant）宕机：核心问答与业务写入必须可用或有明确友好错误，不许级联雪崩。

---

# 第一部分：上下文工程优化

## 阶段一：单轮工具结果缓存（Flyway V10）

### A. 作用域与匹配语义

- 缓存对象：工具**截断后的最终返回字符串**（模型实际看到的版本），保证命中与首调完全一致。
- 作用域：单个 agent run，随 `AgentToolCallbackFactory.createForRun` 新建、run 结束即回收；绝不跨 run/用户/会话。
- Key：工具名 + 规范化参数 JSON。规范化：Jackson 将入参解析为树再有序重序列化（消除键序/空白差异）；解析失败退化为原文精确匹配。
- 失败不缓存：底层抛任何异常（含越权、超时）都不写入。
- 存储：`ConcurrentHashMap<String,String>`（工具可能跑在虚拟线程 / Reactor 调度线程）。

### B. 类与装配

- 新增 `agenttracing/PerRunToolResultCache.java`：`get(toolName, rawArguments)` / `put(...)` / `hits()`。
- 新增 `agenttracing/CachingToolCallback implements ToolCallback`，装饰顺序改为：

```text
CachingToolCallback( TracingToolCallback( rawCallback ) )
```

- 命中流程：算 key → 命中则调 `AgentTracingService.recordCacheHitToolCall(...)` 落一条 SUCCEEDED 记录（from_cache=true、duration_ms=0）→ hits+1 → 直接返回；**不经过 Guard、不占 3 次真实调用名额**；userId/runId 仍从 ToolContext 读取（沿用 `readLong`）。
- 未命中：委托 `TracingToolCallback`（既有计数/执行/截断/落库不变），仅成功返回时 put。
- 开关：`app.agent.tool-cache.enabled` / `AGENT_TOOL_CACHE_ENABLED`（默认 true），关闭则不包缓存层。

### C. V10 迁移

```sql
ALTER TABLE agent_tool_calls ADD COLUMN from_cache BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_runs ADD COLUMN cache_hits INTEGER NOT NULL DEFAULT 0;
```

- 两个实体加对应字段（`from_cache` 默认 false、`cache_hits` 默认 0）。
- `AgentTracingService` 新增 `recordCacheHitToolCall(...)`；`succeedRun/failRun` 统一改签名携带 cacheHits（调用方仅 Streamer，不留旧重载）。

### D. 测试（≥8 条，全 stub）

同参两次只执行 1 次且结果一致；键序/空白不同仍命中；不同参不命中；异常不缓存；命中不占 Guard 名额（maxToolCalls=1：1 次真实+2 次命中不拦，再来不同参才拦）；命中落库字段正确；两个 run 互不命中；开关关闭行为同现状。同步更新落库测试。

### E. 验收

重复工具调用不再触发 Embedding/Qdrant（stub 计数验证）；from_cache/cache_hits 正确；失败不缓存、严格单 run；全量测试绿；前端零改动。

## 阶段二：真实 Token 计量与动态输出预算（Flyway V11）

### A. 估算器

- 新增 `agentcontext/TokenEstimator.java`（组件，encoding 启动时初始化一次）：jtokkit `Encodings.newDefaultEncodingRegistry()` + `EncodingType.CL100K_BASE`。
- 消息估算 = ceil(countTokens(text) × 系数) + 4（ChatML 开销）；系统提示词只计文本。
- 系数 `app.ai.token-estimate-multiplier` / `AI_TOKEN_ESTIMATE_MULTIPLIER`，默认 1.10。
- 注释与汇报必须写明：cl100k 与 DeepSeek 分词器不同，这是**保守估算**，不是精确计费。

### B. 配置（AiProperties 增加字段）

| 键 | 环境变量 | 默认 | 含义 |
|---|---|---|---|
| `context-window-tokens` | `AI_CONTEXT_WINDOW_TOKENS` | 65536 | 对话模型窗口 |
| `max-input-tokens` | `AI_MAX_INPUT_TOKENS` | 24000 | 输入预算上限（常规裁剪线） |
| `output-reserve-tokens` | `AI_OUTPUT_RESERVE_TOKENS` | 2048 | 输出安全余量 |
| `min-output-tokens` | `AI_MIN_OUTPUT_TOKENS` | 512 | 低于此值判定上下文过长 |
| `token-estimate-multiplier` | `AI_TOKEN_ESTIMATE_MULTIPLIER` | 1.10 | 估算安全系数 |

- `max-output-tokens=2000` 语义改为"输出封顶值"；`max-prompt-chars=12000` 本版起停止读取，字段标 `@Deprecated`（下一版本再删）。
- 行为变化须在汇报明示：默认 24000 token 对中文对话略放宽；要维持旧成本水位设 `AI_MAX_INPUT_TOKENS=16000`。

### C. 预算服务 `agentcontext/TokenBudgetService.java`

输出 `ContextBudgetResult(retained, estimatedInputTokens, effectiveMaxOutputTokens, droppedMessages)`。候选消息仍取 top10（有摘要）/top30（无摘要）时间正序：

1. fixedTokens = estimate(systemPrompt)（含记忆块/摘要块，永不裁剪）；记忆块自身 1200 字符预算维持现状。
2. effectiveInputBudget = min(maxInputTokens, contextWindow − outputReserve − minOutput)。
3. 超限则从最旧消息移除，**必须保留最后一条用户消息**。
4. available = contextWindow − estimatedInputTokens − outputReserve；effectiveMaxOutputTokens = min(2000, available)。
5. available < minOutput（防御分支）抛现有错误码 `ASSISTANT_CONTEXT_TOO_LONG`。
6. 裁剪时 INFO 日志（sessionId、候选/保留条数、估算输入、动态输出）。

### D. 接线与真实 usage 回收

- `ChatStreamContext`：以动态 effectiveMaxOutputTokens 替代固定 2000；新增 estimatedInputTokens。
- Streamer 保留 prompt_chars 落库，同时把估算 token 传入 startRun。
- `KnowledgeAgentService` 从 `.stream().content()` 改为 `.stream().chatResponse()`；`OpenAiChatOptions.builder().streamUsage(true)`（API 已在 spring-ai-openai-1.0.0.jar 核实）；Streamer 逐块取 `getResult().getOutput().getText()`（空块跳过），从最后含 usage 的块读 `getMetadata().getUsage()` 的 promptTokens/completionTokens（spring-ai-model 的 `Usage` 接口已核实）。
- 成功后 INFO 记录估算/真实/偏差率；偏差绝对值 >25% 打 WARN（只告警，不自动改系数）。
- SSE `completed` 允许新增可选字段 `usage{estimatedInputTokens,promptTokens,completionTokens}`，旧字段不变，前端零改动（忽略未知字段）。
- **降级标准（满足任一则降级，不阻塞验收）**：实测 streamUsage 末帧 usage 为 null；或 chatResponse() 改造出现本任务内无法解决的兼容问题。降级后保留估算/裁剪/动态输出全部功能，usage 返回 null，汇报中写明；禁止为此换框架版本或加依赖。

### E. V11 迁移

```sql
ALTER TABLE agent_runs ADD COLUMN prompt_tokens INTEGER;
```

可空，仅记估算输入；真实 usage 只走日志/SSE，不入库（避免过度设计）。

### F. 测试

`TokenEstimatorTest`/`TokenBudgetServiceTest`：估算稳定与中英文/代码计数；裁剪从旧到新且保留末条用户消息；充足不裁剪；动态输出（短输入=2000、近窗口被压缩且≥minOutput、彻底不足报错）；两个预算约束取小；记忆/摘要块永不裁。改造 `KnowledgeAssistantServiceTest` 的字符预算用例为 token 口径。新增 Streamer 测试：末帧带 `DefaultUsage` 的 ChatResponse 流断言 completed.usage；usage=null 走降级正常完成。

### G. 验收

长会话按 token 裁剪（日志可验证）；实际 max_tokens 随输入变化、短对话仍 2000；usage 按平台支持情况返回或按降级标准执行；prompt_tokens 落库、validate 通过；全量绿、前端无回归。

---

# 第二部分：高并发异步架构

## 阶段三：环境就绪与冒烟（不碰业务）

> **【2026-10-16 执行口径，以本条为准】** 依据 §0 第 3 条决策变更：Redis 连通性验证对象改为独立容器 `redis`（已发布宿主机 6379），宿主机直连即可，**不再执行第 2 步的 socat/端口映射二选一**；逻辑分区用 **db 2**（不是 db 1），冒烟前后核对 db0/db1/db5 键数不变；密码只走环境变量/外部配置，不写进项目文档、日志、汇报。其余要求不变。

1. 在 `D:\develop\mq-docker\` 新建 `docker-compose.yml`：仅 `rabbitmq:3.13-management`（5672/15672，账号密码走 `.env`，禁止写死），数据卷 `./volumes/rabbitmq`；附启停脚本与 README。**不得新建 Redis**。
2. **Redis 连通性验证**：启动 Docker Desktop → `docker ps` 找到现有 Redis 容器真实名称：
   - `docker exec <容器> redis-cli -a <密码> ping` 返回 PONG（密码从 Dify 环境配置读取，禁止写进项目文档/日志）。
   - 宿主机 `Test-NetConnection 127.0.0.1 -Port 6379`：通则直连；不通（大概率）则**停止并报告项目所有者**，二选一：①（推荐）在 `D:\develop\mq-docker\` 加 `alpine/socat` 转发容器（`docker network ls` 找 Dify 网络 → connect → 宿主机 6379 转发到该网络 redis:6379）；②项目所有者自行给现有 Redis 加端口映射。未获选择不得操作。
3. **逻辑隔离**：统一使用 Redis **db 1**（现有业务默认 db 0），key 统一前缀 `aimindmap:`；只使用 Redis 6 支持的命令（GET/SET/EXPIRE/EVAL/PUBLISH/SUBSCRIBE 等）。
4. 报备后引入 §3.4 的两个 starter；配置 `spring.rabbitmq.*`、`spring.data.redis.*`（host/port/password/database=1）与三个降级开关。
5. 冒烟：测试队列收发；db 1 写/读带前缀测试 key（测完即删，db 0 不受影响）；管理台可打开。输出《环境启动说明》到 docs（不含明文密码）。

验收：RabbitMQ 随 compose 起停；Redis 连通结论与所选分支有记录；db 0/db 1 互不干扰；`app.mq.enabled=false` 时全部旧测试通过。

## 阶段四：Outbox + MQ 接管向量索引链路

### A. MQ 抽象层（新包 `mqsupport`，隔离中间件）

- `TaskMessage` record：taskType（枚举 PDF_IMPORT/VECTOR_UPSERT/VECTOR_DELETE/MINDMAP_SYNC/QUESTION_GENERATE/SESSION_SUMMARY）、taskId、entityId、op、occurredAt。
- 接口 `TaskMessagePublisher`；实现 `RabbitTaskMessagePublisher`（@ConditionalOnProperty）与 `NoopTaskMessagePublisher`（关闭时退化）。
- 拓扑（Bean 声明）：交换机/队列前缀 `aimindmap.`；每类任务一个队列；统一 DLX `aimindmap.dlx` + `aimindmap.dlq`；三档延时重试（TTL 5s/30s/2m + 死信转发，最多 3 档后进 DLQ）；AI 类队列用独立连接工厂、并发 1~2。
  > **【2026-10-16 决策留痕，项目所有者拍板】** 三档延时重试拓扑采用「方案 A：每类任务 3 个专属重试队列」：重试队列 `aimindmap.retry.<TaskType>.5s/.30s/.2m` 共 18 个，durable，x-message-ttl=5000/30000/120000ms，死信交换机指回 `aimindmap.tasks` 且 x-dead-letter-routing-key 固定为本任务 routing key；重试队列不绑交换机，消费者经默认交换机按队列名直投。总计 25 队列（6 工作+18 重试+1 DLQ）、2 交换机（`aimindmap.tasks` topic、`aimindmap.dlx` fanout）。落选方案：B 全局共享 3 重试队列+档位交换机（绕路多）；C 单队列消息级 TTL（队头阻塞导致延时失真）。4-3 消费者按消息头重试次数选档（0→5s、1→30s、2→2m），第 4 次失败 nack 经工作队列 DLX 进 DLQ。

### B. Outbox（V12）

```sql
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    task_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON outbox_events(status, id) WHERE status = 'PENDING';

CREATE TABLE idempotent_consumptions (
    message_id VARCHAR(128) PRIMARY KEY,   -- taskType:taskId
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

- 业务服务在**原事务内**写 outbox（替代直接 publishEvent 的位置）。
- `OutboxRelay`：每 1 秒 `SKIP LOCKED` 取 100 条 PENDING → 投递 → publisher confirm 成功置 SENT；失败 attempts+1，超 `app.mq.outbox-max-attempts=5` 置 DEAD + ERROR；SENT 保留 7 天定时清理。

### C. 消费者规范

- 手动 ACK；成功才 ack；可重试异常走三档重试队列；不可重试（非法参数/资源不存在）直接入死信。
- 幂等三件套：①任务表状态条件更新（`WHERE status='PENDING'`，更新 0 行直接 ack）；②向量确定性 pointId；③ `idempotent_consumptions` 唯一约束（插入冲突即跳过）。
- 监听容器 `concurrency=2 / max-concurrency=8`（可配）；死信必须落日志（taskType/taskId/原因/x-death）。
- 任务表/状态机全部保留，MQ 只负责唤醒；对账任务每 60 秒捞 PENDING 超时漏发任务重投（现有 VectorIndexWorker 的 @Scheduled 降级为该兜底）。

### D. 向量链路改造

`VectorIndexTaskService` 建任务同事务写 outbox；新增 `VectorIndexMessageConsumer` 复用 `VectorIndexTaskProcessor`（禁止重写业务）；删除/修改/重建三类操作回归。

验收：H1-1 正常流（PENDING→SENT→SUCCEEDED→可检）；H1-2 confirm 前 kill 应用重启不丢；H1-3 重复投递点数不变；H1-4 停 Qdrant 走重试→恢复成功/超限进 DLQ 可见；H1-5 8081/8082 双实例同任务只执行一次；H1-6 Testcontainers 集成测试覆盖以上。

## 阶段五：PDF 导入异步化

- `POST /api/imports/pdf` 只做校验、UUID 落盘、建 PENDING job、同事务写 outbox，立即返回 jobId（默认保持 HTTP 200 减少前端改动）。
- 消费者复用 `PdfImportService`（抽出 `process(jobId)`，禁止复制逻辑）；DRAFT_READY/SCANNED/FAILED 与错误码沿用现状；PDF 队列并发=1。
- 前端 `PdfImportDialog` 改为每 2 秒轮询 `GET /api/imports/pdf/{jobId}`（归属校验与外部 ID 混淆照旧），保留解析中文案；不引入第二条 SSE。

验收：上传 RT 降为毫秒级；四类结果（正常/扫描件/加密/损坏）与现状一致；连传 5 份大文件排队消费；消费者离线时任务不丢、恢复续跑。

## 阶段六：同步 / 出题 / 摘要迁移 MQ

- 思维导图同步：SyncJobRequested 发布点改写 outbox；消费者执行现有 SyncJobWorker 逻辑；进度在阶段七交付前先由消费者直接调本地 `SyncJobEventPusher`（标注 TODO-阶段七）。
- 出题：复用 question_generation_jobs 状态机同法迁移。
- 摘要：SessionSummaryRequested 改 MQ 低优先级队列；注意适配阶段二改造后的 `KnowledgeAssistantService`（新增消息触发点改为同事务写 outbox）；失败语义保持"只 warn 不影响聊天"。
- AI 类异常分级：429/5xx/超时可重试；配置类 4xx 直接死信；不发半成品。

验收：同步动画/出题/摘要端到端无回归；AI 不可用时按策略重试；双实例功能正确；全量测试绿。

## 阶段七：Redis 三件套

1. **跨实例 SSE**：抽象 `ProgressBroadcaster`，实现 `RedisPubSubBroadcaster`（频道 `aimindmap:job:{jobId}`）；任一实例消费者发状态 → 所有实例收 → 仅持有 emitter 的实例下发；`redis-enabled=false` 时内存直推降级。明确 Pub/Sub 不保证投递，前端/对账可兜底。
2. **短期记忆缓存（Cache-Aside）**：缓存"会话最近 N 条消息"查询（key `chat:{sessionId}:recent:{N}`，TTL 60s），写新消息/删会话主动失效；**不缓存摘要用的全量消息查询**；命中率打指标。
3. **限流（Redis + Lua 原子滑动窗口）**：AI 问答 20/min、PDF 上传 5/min、触发同步 10/min（均可配）；超限经全局异常体系返回 429 + `RATE_LIMITED` + 中文提示；关闭 Redis 时降级单机内存窗口。
4. 不做：向量检索结果缓存、缓存预热。

验收：双实例下连 B 实例也能收到 A 发起的进度；缓存命中有指标；阈值可配且有测试；Redis 宕机三项能力均优雅降级。

## 阶段八：压测、可观测与全量编排

1. 报备后引入 actuator + micrometer-registry-prometheus。业务指标：各任务入队/消费/失败/死信计数与耗时、PDF 上传 RT、同步 RT、AI/Embedding 耗时失败率、缓存命中率、限流拒绝数、SSE 连接数；中间件指标经 RabbitMQ management / Redis exporter 采集（exporter 以 external 方式连现有 Redis，不纳管其容器）。
2. Grafana 容器运行（放 `D:\develop\mq-docker\` 编排），预置面板（堆积/消费速率/失败率/P95/命中率/JVM），看板 JSON 存 `docs/grafana/`。
3. JMeter 脚本存 `docs/jmeter/`：A=100 并发问答（限流前后）；B=20 并发 PDF 上传（同步 vs 异步 RT/吞吐）；C=200 次瞬时同步（削峰曲线与追赶时间）。压测用打桩 profile（固定延迟假数据）防烧钱，另补一轮小流量真实调用。
4. 全量 compose 编排：app 双实例 + PostgreSQL + Qdrant（external）+ RabbitMQ + Redis（external）+ Prometheus + Grafana；输出《本地一键启动与压测手册》。
5. 产出 `docs/高并发改造-压测报告.md`：真实数据、前后对比、瓶颈分析，禁止编造数字。

验收：面板有真实曲线；三场景数据齐全可复跑；全量绿；新人照手册 30 分钟跑起全套。

---

## 4. 总验收标准

1. 六类后台任务全部经 outbox → MQ → 幂等消费者；任意时刻 kill -9 不丢任务。
2. 重复投递、消费失败、MQ/Redis/Qdrant 宕机均有证据与正确降级；双实例 SSE 正确、任务不重复。
3. 上下文按真实 token 裁剪、输出预算动态化、工具重复调用命中缓存，均有日志/测试证据。
4. 限流友好生效；缓存有命中率；压测报告、监控面板、一键编排齐备。
5. 每阶段汇报落盘并经项目所有者确认；全量测试只增不减。

## 5. 简历与面试口径（诚实红线）

- 可写："基于 Spring AI 实现工具调用型学习 Agent（RAG/记忆/摘要/追踪）；实施上下文工程优化——分词器 token 预算治理、动态输出上限、真实 usage 校准、单轮工具结果缓存；基于 RabbitMQ + Outbox 重构异步任务架构，消费端幂等与死信重试，多实例部署；Redis 跨实例 SSE/旁路缓存/滑动窗口限流；JMeter 压测验证削峰与响应改善（附数据）。"
- 禁写：未做到的数字、线上真实流量、Kafka、Redis 集群/哨兵、K8s、微服务。
- 必会知识点：消息丢失三段防护（confirm/持久化/手动 ack）、重复消费与幂等、积压排查、死信与重试档设计、Outbox vs 分布式事务、缓存穿透/击穿/雪崩与一致性、旁路失效、Pub/Sub 局限、cl100k 估算口径与偏差校准、为何 64K 窗口下仍做预算治理（换小模型安全/成本可视化）。

## 6. 风险与排期

1. 学习深水区多：宁可把阶段一/二/四/七做透，不要赶工走过场。
2. 总排期约 8~12 个有效工作日；紧张时按 §1.4 最低子集交付。
3. 本机内存：全套容器 + 双 app 实例约需 4~6GB，压测前关闭无关容器。
4. 阶段二与阶段六都会修改问答/摘要相关类——严格串行、阶段六开工前先复读阶段二产物。

## 附录 A：配置项总清单

- `app.agent.tool-cache.enabled`（AGENT_TOOL_CACHE_ENABLED，true）
- `app.ai.context-window-tokens=65536`、`max-input-tokens=24000`、`output-reserve-tokens=2048`、`min-output-tokens=512`、`token-estimate-multiplier=1.10`（均有 AI_* 环境变量）
- `app.mq.enabled=true`、`app.mq.outbox-fixed-delay-ms=1000`、`app.mq.outbox-max-attempts=5`、`app.mq.requeue-scan-delay-ms=60000`
- `spring.rabbitmq.*`（publisher-confirm-type=correlated；listener.simple.acknowledge-mode=manual、concurrency=2/max-concurrency=8）
- `spring.data.redis.*`（database=1）、`app.cache.redis-enabled=true`、`app.cache.key-prefix=aimindmap:`、`app.cache.chat-recent-ttl-seconds=60`
- `app.ratelimit.ai-per-min=20`、`pdf-per-min=5`、`sync-per-min=10`

## 附录 B：MQ 换型差异（日后改选才用，本版不实施）

- RocketMQ：rocketmq-spring 替代 spring-amqp；原生延时等级替代 TTL+DLX；NameServer/Broker 双容器；原生重试与 %DLQ% 消费组；Outbox/幂等/对账设计不变。
- Kafka：spring-kafka；topic 按 taskType、3~6 分区、任务 ID 做 key 保单实体有序；手动提交 offset；`DefaultErrorHandler` + 死信 topic（.DLT）+ 重试 topic 实现退避；acks=all + 幂等生产者；状态 CAS 幂等模型不变；强项是高吞吐，运维/概念成本更高。
