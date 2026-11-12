package com.example.aimindmap.mindmap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class NodeEditDtos {

    private NodeEditDtos() {
    }

    /** §11.4 新增节点请求。 */
    public record AddNodeRequest(
            @NotBlank(message = "parentId 不能为空")
            String parentId,
            @NotBlank(message = "节点标题不能为空")
            String label,
            String summary,
            @NotNull(message = "expectedGraphVersion 不能为空")
            Integer expectedGraphVersion
    ) {
    }

    /** §11.4 修改节点标题或摘要请求。 */
    public record UpdateNodeRequest(
            @NotBlank(message = "节点标题不能为空")
            String label,
            String summary,
            @NotNull(message = "expectedGraphVersion 不能为空")
            Integer expectedGraphVersion
    ) {
    }

    /** §11.4 删除建议决策：accept=接受删除，keep=保留节点。 */
    public record RemovalDecisionRequest(
            @NotBlank(message = "decision 不能为空（accept/keep）")
            String decision,
            @NotNull(message = "expectedGraphVersion 不能为空")
            Integer expectedGraphVersion
    ) {
    }
    /** 节点拖拽后的坐标保存请求。 */
    public record UpdateNodePositionRequest(
            @NotNull(message = "expectedGraphVersion 不能为空")
            Integer expectedGraphVersion,
            Double x,
            Double y
    ) {
    }

    /** 恢复自动布局请求。 */
    public record ResetLayoutRequest(
            @NotNull(message = "expectedGraphVersion 不能为空")
            Integer expectedGraphVersion
    ) {
    }

    /** 节点操作后的统一返回：新版本号、最新图，以及手动节点回写后的文档。 */
    public record NodeActionResponse(
            Integer graphVersion,
            Object graph,
            String documentContent,
            Integer documentVersion
    ) {
        public static NodeActionResponse graphOnly(Integer graphVersion, Object graph) {
            return new NodeActionResponse(graphVersion, graph, null, null);
        }
    }
}