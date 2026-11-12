/** 阶段 5：PDF 导入任务类型，字段与后端 §11.6 JobResponse 完全对齐。 */
export type PdfImportStatus =
  | 'PENDING'
  | 'EXTRACTING'
  | 'DRAFT_READY'
  | 'SCANNED'
  | 'FAILED'
  | 'ACCEPTED'

export interface PdfImportJob {
  id: string
  status: PdfImportStatus
  originalFileName: string
  pageCount: number | null
  extractedTextLength: number | null
  suspectedScanned: boolean
  markdownDraft: string | null
  documentId: string | null
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
}

export interface AcceptPdfDraftResponse {
  jobId: string
  documentId: string
  title: string
}
