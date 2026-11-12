import { apiJson } from '../lib/http'
import type {
  DocumentResponse,
  DocumentSummaryResponse,
  SaveContentResponse
} from '../types/document'

export async function listDocuments(): Promise<DocumentSummaryResponse[]> {
  return apiJson('/api/documents', {}, '获取笔记列表失败')
}

export async function createDocument(title = '未命名文档'): Promise<DocumentResponse> {
  return apiJson('/api/documents', {
    method: 'POST',
    json: { title }
  }, '新建文档失败')
}

export async function getDocument(id: string): Promise<DocumentResponse> {
  return apiJson(`/api/documents/${id}`, {}, '获取文档失败')
}

export async function saveDocumentContent(
  id: string,
  content: string,
  expectedVersion: number
): Promise<SaveContentResponse> {
  return apiJson(`/api/documents/${id}/content`, {
    method: 'PUT',
    json: { content, expectedVersion }
  }, '保存失败')
}

/** 修订第 23 条：重命名笔记。 */
export async function renameDocument(id: string, title: string): Promise<DocumentResponse> {
  return apiJson(`/api/documents/${id}`, {
    method: 'PATCH',
    json: { title }
  }, '重命名失败')
}

/** 修订第 23 条：软删除笔记（204 无响应体）。 */
export async function deleteDocument(id: string): Promise<void> {
  await apiJson<null>(`/api/documents/${id}`, {
    method: 'DELETE'
  }, '删除笔记失败')
}