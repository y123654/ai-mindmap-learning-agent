package com.example.aimindmap.mindmap.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.1 方案 §7.1 思维导图节点。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MindMapNode {

    private String id;
    private String label;
    private String summary;
    /** markdown | manual | ai_enhanced */
    private String sourceType;
    private String sourceBlockId;
    private String sourceAnchor;
    private List<String> sourcePath = new ArrayList<>();
    private boolean manuallyEdited;
    private boolean manualLocked;
    /** active | removed_suggested */
    private String status = "active";
    /** 自由坐标；null 表示使用自动布局。 */
    private NodeLayout layout;
    private Instant createdAt;
    private Instant updatedAt;
    private List<MindMapNode> children = new ArrayList<>();

    public MindMapNode() {
    }

    public MindMapNode(String id, String label, String summary, String sourceType,
                       String sourceBlockId, String sourceAnchor, List<String> sourcePath,
                       Instant now) {
        this.id = id;
        this.label = label;
        this.summary = summary;
        this.sourceType = sourceType;
        this.sourceBlockId = sourceBlockId;
        this.sourceAnchor = sourceAnchor;
        this.sourcePath = sourcePath == null ? new ArrayList<>() : new ArrayList<>(sourcePath);
        this.manuallyEdited = false;
        this.manualLocked = false;
        this.status = "active";
        this.createdAt = now;
        this.updatedAt = now;
        this.children = new ArrayList<>();
    }

    public MindMapNode deepCopy() {
        MindMapNode copy = new MindMapNode();
        copy.id = this.id;
        copy.label = this.label;
        copy.summary = this.summary;
        copy.sourceType = this.sourceType;
        copy.sourceBlockId = this.sourceBlockId;
        copy.sourceAnchor = this.sourceAnchor;
        copy.sourcePath = new ArrayList<>(this.sourcePath);
        copy.manuallyEdited = this.manuallyEdited;
        copy.manualLocked = this.manualLocked;
        copy.status = this.status;
        copy.layout = this.layout == null ? null
                : new NodeLayout(this.layout.getX(), this.layout.getY(), this.layout.isManuallyPositioned());
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;
        for (MindMapNode child : this.children) {
            copy.children.add(child.deepCopy());
        }
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceBlockId() {
        return sourceBlockId;
    }

    public void setSourceBlockId(String sourceBlockId) {
        this.sourceBlockId = sourceBlockId;
    }

    public String getSourceAnchor() {
        return sourceAnchor;
    }

    public void setSourceAnchor(String sourceAnchor) {
        this.sourceAnchor = sourceAnchor;
    }

    public List<String> getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(List<String> sourcePath) {
        this.sourcePath = sourcePath;
    }

    public boolean isManuallyEdited() {
        return manuallyEdited;
    }

    public void setManuallyEdited(boolean manuallyEdited) {
        this.manuallyEdited = manuallyEdited;
    }

    public boolean isManualLocked() {
        return manualLocked;
    }

    public void setManualLocked(boolean manualLocked) {
        this.manualLocked = manualLocked;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public NodeLayout getLayout() {
        return layout;
    }

    public void setLayout(NodeLayout layout) {
        this.layout = layout;
    }
    public List<MindMapNode> getChildren() {
        return children;
    }

    public void setChildren(List<MindMapNode> children) {
        this.children = children;
    }
}