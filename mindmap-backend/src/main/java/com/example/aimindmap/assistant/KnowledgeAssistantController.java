package com.example.aimindmap.assistant;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.ratelimit.RateLimitScope;
import com.example.aimindmap.ratelimit.RateLimited;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/ai-assistant")
public class KnowledgeAssistantController {

    private final KnowledgeAssistantService service;
    private final KnowledgeAssistantStreamer streamer;
    private final ExternalIdCodec idCodec;

    public KnowledgeAssistantController(KnowledgeAssistantService service,
                                        KnowledgeAssistantStreamer streamer,
                                        ExternalIdCodec idCodec) {
        this.service = service;
        this.streamer = streamer;
        this.idCodec = idCodec;
    }

    @GetMapping("/sessions")
    public List<KnowledgeAssistantDtos.SessionSummaryResponse> listSessions(
            @AuthenticationPrincipal AuthUser user) {
        return service.listSessions(user.userId());
    }

    @PostMapping("/sessions")
    public KnowledgeAssistantDtos.SessionSummaryResponse createSession(
            @AuthenticationPrincipal AuthUser user,
            @RequestBody(required = false) KnowledgeAssistantDtos.CreateSessionRequest request) {
        return service.createSession(user.userId(), request);
    }

    @GetMapping("/sessions/{sessionId}")
    public KnowledgeAssistantDtos.SessionDetailResponse getSession(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable String sessionId) {
        return service.getSession(user.userId(), idCodec.decode(sessionId));
    }

    /** 修订第 23 条：删除会话及其消息。 */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@AuthenticationPrincipal AuthUser user,
                                              @PathVariable String sessionId) {
        service.deleteSession(user.userId(), idCodec.decode(sessionId));
        return ResponseEntity.noContent().build();
    }

    /** 阶段七-3：AI 问答限流 {@code app.ratelimit.ai-per-min}（默认 20/min）。 */
    @RateLimited(RateLimitScope.AI_CHAT)
    @PostMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(@AuthenticationPrincipal AuthUser user,
                                         @PathVariable String sessionId,
                                         @Valid @RequestBody KnowledgeAssistantDtos.StreamChatRequest request,
                                         HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        KnowledgeAssistantService.ChatStreamContext context =
                service.prepareUserMessage(user.userId(), idCodec.decode(sessionId), request.content());
        return outputStream -> streamer.stream(context, outputStream);
    }
}
