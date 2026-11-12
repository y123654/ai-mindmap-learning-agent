package com.example.aimindmap.vectorindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VectorPointIdsTest {

    @Test
    void sameLogicalPointAlwaysResolvesToSameId() {
        UUID first = VectorPointIds.pointId(VectorResourceType.DOCUMENT_CHUNK, "1001", 0);
        UUID second = VectorPointIds.pointId(VectorResourceType.DOCUMENT_CHUNK, "1001", 0);
        UUID rebuilt = VectorPointIds.pointId(VectorResourceType.DOCUMENT_CHUNK, "1001", 0);

        assertThat(first).isEqualTo(second).isEqualTo(rebuilt);
    }

    @Test
    void differentResourceChunkOrTypeResolvesToDifferentId() {
        UUID base = VectorPointIds.pointId(VectorResourceType.DOCUMENT_CHUNK, "1001", 0);

        assertThat(VectorPointIds.pointId(VectorResourceType.DOCUMENT_CHUNK, "1001", 1))
                .isNotEqualTo(base);
        assertThat(VectorPointIds.pointId(VectorResourceType.DOCUMENT_CHUNK, "1002", 0))
                .isNotEqualTo(base);
        assertThat(VectorPointIds.pointId(VectorResourceType.QUESTION, "1001", 0))
                .isNotEqualTo(base);
    }
}
