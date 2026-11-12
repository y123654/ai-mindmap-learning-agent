package com.example.aimindmap.mindmap;

/**
 * v1.1 方案 §9.3 支持的操作。
 */
public enum OperationType {
    ADD_NODE("add_node"),
    ADD_SUBTREE("add_subtree"),
    UPDATE_NODE("update_node"),
    PROPOSE_REMOVE_SOURCE_NODE("propose_remove_source_node"),
    ACCEPT_REMOVAL("accept_removal"),
    KEEP_NODE("keep_node"),
    NOOP("noop");

    private final String wire;

    OperationType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}