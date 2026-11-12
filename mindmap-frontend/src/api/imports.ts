import { apiJson } from '../lib/http'
import type { AcceptPdfDraftResponse, PdfImportJob } from '../types/pdf'

/**
 * 阶段 5（v1.1 §11.6）：PDF 导入接口。
 * 上传走 multipart/form-data，复用 apiJson 自动带 JWT；
 * 注意不能手动设置 Content-Type，浏览器要自动生成 boundary。
 */
export async function uploadPdf(file: File): Promise<PdfImportJob> {
  const formData = new FormData()
  formData.append('file', file)
  return apiJson(
    '/api/imports/pdf',
    { method: 'POST', body: formData },
    'PDF 上传失败'
  )
}

export async function getImportJob(jobId: string): Promise<PdfImportJob> {
  return apiJson(`/api/imports/pdf/${jobId}`, {}, '查询导入任务失败')
}

/** 接受草稿，新建一篇笔记；title 留空时后端用原文件名去后缀。 */
export async function acceptPdfDraft(
  jobId: string,
  title: string
): Promise<AcceptPdfDraftResponse> {
  return apiJson(
    `/api/imports/${jobId}/accept-draft`,
    { method: 'POST', json: { title } },
    '接受草稿失败'
  )
}
