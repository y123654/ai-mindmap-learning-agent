package com.example.aimindmap.agentmemory;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆管理接口（方案 §11.2 / §6.5；任务二）。
 * 普通 REST 管理接口，不是 AI 工具（R-06 不冲突）；全部只操作本人数据。
 */
@RestController
@RequestMapping("/api/agent/memories")
public class AgentMemoryController {

    private final AgentMemoryService service;
    private final ExternalIdCodec idCodec;

    public AgentMemoryController(AgentMemoryService service, ExternalIdCodec idCodec) {
        this.service = service;
        this.idCodec = idCodec;
    }

    @GetMapping
    public List<AgentMemoryDtos.MemoryResponse> list(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam(name = "status", required = false) String status) {
        return service.listMemories(user.userId(), status).stream()
                .map(memory -> AgentMemoryDtos.MemoryResponse.from(memory, idCodec))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentMemoryDtos.MemoryResponse create(
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody AgentMemoryDtos.CreateMemoryRequest request) {
        AgentMemoryEntity saved = service.createCandidate(user.userId(), request);
        return AgentMemoryDtos.MemoryResponse.from(saved, idCodec);
    }

    @PutMapping("/{memoryId}")
    public AgentMemoryDtos.MemoryResponse update(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable String memoryId,
            @Valid @RequestBody AgentMemoryDtos.UpdateMemoryRequest request) {
        AgentMemoryEntity saved =
                service.updateMemory(user.userId(), idCodec.decode(memoryId), request);
        return AgentMemoryDtos.MemoryResponse.from(saved, idCodec);
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user,
                       @PathVariable String memoryId) {
        service.deleteMemory(user.userId(), idCodec.decode(memoryId));
    }

    @PostMapping("/{memoryId}/confirm")
    public AgentMemoryDtos.MemoryResponse confirm(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable String memoryId) {
        AgentMemoryEntity saved = service.confirmMemory(user.userId(), idCodec.decode(memoryId));
        return AgentMemoryDtos.MemoryResponse.from(saved, idCodec);
    }

    @PostMapping("/{memoryId}/archive")
    public AgentMemoryDtos.MemoryResponse archive(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable String memoryId) {
        AgentMemoryEntity saved = service.archiveMemory(user.userId(), idCodec.decode(memoryId));
        return AgentMemoryDtos.MemoryResponse.from(saved, idCodec);
    }

    /** 清空本人全部记忆。字面路径 /clear 优先于 /{memoryId} 匹配，不会冲突。 */
    @PostMapping("/clear")
    public ClearResponse clear(@AuthenticationPrincipal AuthUser user) {
        int deleted = service.clearMemories(user.userId());
        return new ClearResponse(deleted);
    }

    public record ClearResponse(int deletedCount) {
    }
}
