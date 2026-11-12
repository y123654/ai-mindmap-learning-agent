package com.example.aimindmap.common.error;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", exception.getCode());
        body.put("message", exception.getMessage());
        body.put("time", Instant.now().toString());
        body.putAll(exception.getDetails());
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    /**
     * 阶段七-3：接口限流超限（AI 问答 / PDF 上传 / 触发同步）。
     *
     * <p>响应体沿用既有 {@code {code, message, time}} 三字段格式，状态码 429、code=RATE_LIMITED。
     * <b>必须显式 contentType(APPLICATION_JSON)：</b>AI 问答 {@code POST .../stream} 的请求
     * Accept 是 {@code text/event-stream}，而该 handler 映射又在 {@code produces} 上登记了
     * 可生产媒体类型；走默认内容协商时 Jackson 无法按 text/event-stream 写 JSON，
     * 异常响应会被 {@code HttpMediaTypeNotAcceptableException} 顶成 406。显式声明 Content-Type
     * 会让 Spring 跳过协商直接用 JSON 转换器，Accept 头如何都能稳定返回 429 JSON。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "code", "RATE_LIMITED",
                        "message", exception.getMessage(),
                        "time", Instant.now().toString()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数不正确");

        return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", message,
                "time", Instant.now().toString()
        ));
    }

    /** 阶段 5：上传文件超过 spring.servlet.multipart 限制（容器在进入 Controller 前拒绝）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return pdfTooLarge();
    }

    /** 阶段 5：Tomcat 等容器把超限异常包在 MultipartException 里，需要识别原因链。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException exception) {
        if (isSizeLimit(exception)) {
            return pdfTooLarge();
        }
        return ResponseEntity.badRequest().body(Map.of(
                "code", "PDF_UPLOAD_FAILED",
                "message", "文件上传失败，请确认文件没有损坏后重试",
                "time", Instant.now().toString()
        ));
    }

    private ResponseEntity<Map<String, Object>> pdfTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "code", "PDF_FILE_TOO_LARGE",
                "message", "PDF 文件过大，当前版本最大支持 20MB",
                "time", Instant.now().toString()
        ));
    }

    private boolean isSizeLimit(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String name = current.getClass().getName();
            if (current instanceof MaxUploadSizeExceededException
                    || name.contains("SizeLimitExceededException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "服务器内部错误",
                "time", Instant.now().toString()
        ));
    }
}
