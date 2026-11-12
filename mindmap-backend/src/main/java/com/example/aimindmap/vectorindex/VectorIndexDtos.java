package com.example.aimindmap.vectorindex;

public final class VectorIndexDtos {

    private VectorIndexDtos() {
    }

    /** 用户级重建结果：实际新投递的任务数（去重跳过不计）。 */
    public record RebuildResponse(String resourceType, int enqueuedTaskCount) {
    }
}
