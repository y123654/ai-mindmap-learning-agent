package com.example.aimindmap.vectorindex;

/** Embedding/Qdrant 等外部服务的可预期失败。 */
public class VectorIndexExternalException extends RuntimeException {

    public VectorIndexExternalException(String message, Throwable cause) {
        super(message, cause);
    }
}
