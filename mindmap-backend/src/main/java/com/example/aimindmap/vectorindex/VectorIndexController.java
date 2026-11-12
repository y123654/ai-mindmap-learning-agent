package com.example.aimindmap.vectorindex;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户级向量重建接口（方案 §11.3，R-09）。
 * 只允许重建当前登录用户自己的数据；不提供任何全局/管理员范围接口。
 */
@RestController
@RequestMapping("/api/agent/vector-index")
public class VectorIndexController {

    private final VectorIndexTaskService taskService;

    public VectorIndexController(VectorIndexTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VectorIndexDtos.RebuildResponse rebuildAll(@AuthenticationPrincipal AuthUser user) {
        int enqueued = taskService.rebuildAll(user.userId());
        return new VectorIndexDtos.RebuildResponse(null, enqueued);
    }

    @PostMapping("/rebuild/{resourceType}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VectorIndexDtos.RebuildResponse rebuildByType(@AuthenticationPrincipal AuthUser user,
                                                          @PathVariable String resourceType) {
        VectorResourceType type = parseResourceType(resourceType);
        try {
            int enqueued = taskService.rebuildByType(user.userId(), type);
            return new VectorIndexDtos.RebuildResponse(type.wireName(), enqueued);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VECTOR_REBUILD_UNSUPPORTED", e.getMessage());
        }
    }

    private static VectorResourceType parseResourceType(String resourceType) {
        for (VectorResourceType type : VectorResourceType.values()) {
            if (type.wireName().equals(resourceType)) {
                return type;
            }
        }
        throw ApiException.badRequest("VECTOR_REBUILD_TYPE_INVALID",
                "不支持的资料类型：" + resourceType);
    }
}
