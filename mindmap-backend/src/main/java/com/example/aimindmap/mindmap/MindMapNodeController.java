package com.example.aimindmap.mindmap;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.OwnershipGuard;
import com.example.aimindmap.mindmap.NodeEditDtos.AddNodeRequest;
import com.example.aimindmap.mindmap.NodeEditDtos.NodeActionResponse;
import com.example.aimindmap.mindmap.NodeEditDtos.ResetLayoutRequest;
import com.example.aimindmap.mindmap.NodeEditDtos.UpdateNodePositionRequest;
import com.example.aimindmap.mindmap.NodeEditDtos.UpdateNodeRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * v1.1 §11.4 思维导图节点手动编辑接口。
 * 修订第 44 条：路径中的 mindmapId 为外部混淆 ID，进 Controller 先解码。
 */
@RestController
@RequestMapping("/api/mindmaps/{mindmapId}")
public class MindMapNodeController {

    private final MindMapNodeService nodeService;
    private final OwnershipGuard ownershipGuard;
    private final ExternalIdCodec idCodec;

    public MindMapNodeController(MindMapNodeService nodeService,
                                 OwnershipGuard ownershipGuard,
                                 ExternalIdCodec idCodec) {
        this.nodeService = nodeService;
        this.ownershipGuard = ownershipGuard;
        this.idCodec = idCodec;
    }

    @PostMapping("/nodes")
    public NodeActionResponse addNode(@AuthenticationPrincipal AuthUser user,
                                      @PathVariable String mindmapId,
                                      @Valid @RequestBody AddNodeRequest request) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return nodeService.addNode(internalMindmapId, request);
    }

    @PutMapping("/nodes/{nodeId}")
    public NodeActionResponse updateNode(@AuthenticationPrincipal AuthUser user,
                                         @PathVariable String mindmapId,
                                         @PathVariable String nodeId,
                                         @Valid @RequestBody UpdateNodeRequest request) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return nodeService.updateNode(internalMindmapId, nodeId, request);
    }

    @PutMapping("/nodes/{nodeId}/position")
    public NodeActionResponse updatePosition(@AuthenticationPrincipal AuthUser user,
                                             @PathVariable String mindmapId,
                                             @PathVariable String nodeId,
                                             @Valid @RequestBody UpdateNodePositionRequest request) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return nodeService.updatePosition(internalMindmapId, nodeId, request);
    }

    @PostMapping("/reset-layout")
    public NodeActionResponse resetLayout(@AuthenticationPrincipal AuthUser user,
                                          @PathVariable String mindmapId,
                                          @Valid @RequestBody ResetLayoutRequest request) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return nodeService.resetLayout(internalMindmapId, request);
    }

    @PostMapping("/nodes/{nodeId}/removal-decisions")
    public NodeActionResponse removalDecision(@AuthenticationPrincipal AuthUser user,
                                              @PathVariable String mindmapId,
                                              @PathVariable String nodeId,
                                              @Valid @RequestBody NodeEditDtos.RemovalDecisionRequest request) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return nodeService.decideRemoval(internalMindmapId, nodeId, request);
    }

    @DeleteMapping("/nodes/{nodeId}")
    public NodeActionResponse deleteNode(@AuthenticationPrincipal AuthUser user,
                                         @PathVariable String mindmapId,
                                         @PathVariable String nodeId,
                                         @RequestParam("expectedGraphVersion") Integer expectedGraphVersion) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return nodeService.deleteNode(internalMindmapId, nodeId, expectedGraphVersion);
    }
}