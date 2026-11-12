package com.example.aimindmap.assistant;

import com.example.aimindmap.auth.ExternalIdCodec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class KnowledgeAssistantDtos {

    private KnowledgeAssistantDtos() {
    }

    public record CreateSessionRequest(
            @Size(max = 100, message = "会话标题不能超过 100 字")
            String title
    ) {
    }

    public record StreamChatRequest(
            @NotBlank(message = "请输入要提问的内容")
            @Size(max = 4000, message = "单次提问不能超过 4000 字")
            String content
    ) {
    }

    /** 修订第 44 条：id 对外为混淆字符串。 */
    public record SessionSummaryResponse(
            String id,
            String title,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static SessionSummaryResponse from(AiChatSessionEntity entity, ExternalIdCodec idCodec) {
            return new SessionSummaryResponse(
                    idCodec.encode(entity.getId()), entity.getTitle(),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 修订第 44 条：id 对外为混淆字符串。 */
    public record MessageResponse(
            String id,
            String role,
            String content,
            String provider,
            String model,
            Instant createdAt
    ) {
        public static MessageResponse from(AiChatMessageEntity entity, ExternalIdCodec idCodec) {
            return new MessageResponse(
                    idCodec.encode(entity.getId()),
                    entity.getRole(),
                    entity.getContent(),
                    entity.getAiProvider(),
                    entity.getAiModel(),
                    entity.getCreatedAt()
            );
        }
    }

    /** 修订第 44 条：id 对外为混淆字符串。 */
    public record SessionDetailResponse(
            String id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            List<MessageResponse> messages
    ) {
    }

    public record StreamDelta(String content) {
    }

    /**
     * 阶段二 D：completed 事件仅新增可选 usage 字段，旧字段不动；
     * 平台不支持流式 usage 时 usage 为 null，前端忽略未知字段即可（前端零改动）。
     *
     * <p>P0-E1：在最后追加可选字段 {@code runId}（对外混淆字符串，指向 {@code GET /api/agent/runs/{runId}}）。
     * 旧字段、旧顺序、旧事件全部不变；不关心它的旧客户端照旧忽略即可。
     * 另保留 3 参构造，兼容 P0-E1 之前的调用方/单测（此时 runId 为 null）。</p>
     */
    public record StreamCompleted(MessageResponse message, SessionSummaryResponse session,
                                  StreamUsage usage, String runId) {

        public StreamCompleted(MessageResponse message, SessionSummaryResponse session,
                               StreamUsage usage) {
            this(message, session, usage, null);
        }
    }

    /** 阶段二 D：本轮估算输入与平台真实 token 用量；平台未回传时整个对象为 null。 */
    public record StreamUsage(Integer estimatedInputTokens,
                              Integer promptTokens,
                              Integer completionTokens) {
    }

    public record StreamError(String code, String message) {
    }

    /**
     * P1-D2：流结束后追加「补充说明」时同步下发的结构化提示事件（{@code event: notice}）。
     *
     * <p><b>纯新增</b>：既有 delta / completed / error / tool_call 事件的字段与顺序一律不变；
     * 前端本轮不改，未识别的事件直接忽略，所以同一段文字也照旧通过 delta 下发（立即可见）。
     *
     * @param code    稳定错误/提示码，本轮只有 {@code CITATION_UNSUPPORTED}
     * @param message 与 delta 里追加文字一致的人话（不含 {@code >} 引用块前缀）
     * @param points  未找到依据的「关键结论句」序号（在关键结论句里从 1 开始，最多 3 个）
     */
    public record StreamNotice(String code, String message, List<Integer> points) {
    }
}
