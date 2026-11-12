package com.example.aimindmap.vectorindex;

/** 向量资料类型（方案 §7.2）。 */
public enum VectorResourceType {
    DOCUMENT_CHUNK("document_chunk"),
    MINDMAP_NODE("mindmap_node"),
    QUESTION("question"),
    /** 阶段 4 才使用，本阶段仅预留枚举值，不写任何记忆逻辑。 */
    MEMORY("memory");

    private final String wireName;

    VectorResourceType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static VectorResourceType fromWire(String wire) {
        for (VectorResourceType type : values()) {
            if (type.wireName.equals(wire)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知向量资料类型：" + wire);
    }
}
