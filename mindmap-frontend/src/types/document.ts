export interface DocumentResponse {
  id: string
  title: string
  content: string
  contentVersion: number
  sourceType: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface SaveContentResponse {
  documentId: string
  contentVersion: number
  savedAt: string
}

export interface DocumentSummaryResponse {
  id: string
  title: string
  contentPreview: string
  contentVersion: number
  sourceType: string
  status: string
  createdAt: string
  updatedAt: string
}
