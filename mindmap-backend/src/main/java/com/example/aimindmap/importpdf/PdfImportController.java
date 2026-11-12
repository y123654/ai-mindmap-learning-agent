package com.example.aimindmap.importpdf;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.ratelimit.RateLimitScope;
import com.example.aimindmap.ratelimit.RateLimited;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 阶段 5：PDF 导入接口（v1.1 §11.6，修订第 28 条，方案 A：强制用户归属）。 */
@RestController
@RequestMapping("/api/imports")
public class PdfImportController {

    private final PdfImportService pdfImportService;
    private final ExternalIdCodec idCodec;

    public PdfImportController(PdfImportService pdfImportService, ExternalIdCodec idCodec) {
        this.pdfImportService = pdfImportService;
        this.idCodec = idCodec;
    }

    /**
     * 阶段五-A：上传 PDF 只做校验、落盘、建 PENDING 任务与 outbox，立即返回 jobId。
     * 保持 HTTP 200（方案明确要求默认 200，减少前端改动）；后台异步解析，前端轮询 GET。
     *
     * <p>阶段七-3：PDF 上传限流 {@code app.ratelimit.pdf-per-min}（默认 5/min）。
     */
    @RateLimited(RateLimitScope.PDF_UPLOAD)
    @PostMapping("/pdf")
    @ResponseStatus(HttpStatus.OK)
    public PdfImportDtos.JobResponse uploadPdf(@AuthenticationPrincipal AuthUser user,
                                               @RequestParam("file") MultipartFile file) {
        return pdfImportService.uploadPdf(user.userId(), file);
    }

    /**
     * 阶段五-B（方案 A）：轮询查询路径迁移到 GET /api/imports/pdf/{jobId}，与 POST /pdf 成组；
     * 旧路径 GET /api/imports/{jobId} 已下线。非本人任务由服务层返回 404。
     */
    @GetMapping("/pdf/{jobId}")
    public PdfImportDtos.JobResponse getJob(@AuthenticationPrincipal AuthUser user,
                                            @PathVariable String jobId) {
        return pdfImportService.getJob(user.userId(), idCodec.decode(jobId));
    }

    /** 接受草稿并新建一篇笔记（不覆盖任何已有文档）。 */
    @PostMapping("/{jobId}/accept-draft")
    @ResponseStatus(HttpStatus.CREATED)
    public PdfImportDtos.AcceptDraftResponse acceptDraft(@AuthenticationPrincipal AuthUser user,
                                                         @PathVariable String jobId,
                                                         @Valid @RequestBody(required = false)
                                                         PdfImportDtos.AcceptDraftRequest request) {
        String title = request == null ? null : request.title();
        return pdfImportService.acceptDraft(user.userId(), idCodec.decode(jobId), title);
    }
}
