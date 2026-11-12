import { apiJson } from '../lib/http'
import type { MindMapResponse, NodeActionResponse } from '../types/mindmap'
export async function getMindMap(documentId: string): Promise<MindMapResponse> {
  return apiJson(`/api/documents/${documentId}/mindmap`, {}, '获取思维导图失败')
}
export async function addNode(
  mindmapId: string,
  body: { parentId: string; label: string; summary?: string; expectedGraphVersion: number }
): Promise<NodeActionResponse> {
  return apiJson(`/api/mindmaps/${mindmapId}/nodes`, {
    method: 'POST',
    json: body
  }, '新增节点失败')
}
export async function updateNode(
  mindmapId: string,
  nodeId: string,
  body: { label: string; summary?: string; expectedGraphVersion: number }
): Promise<NodeActionResponse> {
  return apiJson(`/api/mindmaps/${mindmapId}/nodes/${nodeId}`, {
    method: 'PUT',
    json: body
  }, '修改节点失败')
}
export async function deleteNode(
  mindmapId: string,
  nodeId: string,
  expectedGraphVersion: number
): Promise<NodeActionResponse> {
  return apiJson(
    `/api/mindmaps/${mindmapId}/nodes/${nodeId}?expectedGraphVersion=${expectedGraphVersion}`,
    { method: 'DELETE' },
    '删除节点失败'
  )
}
/** §11.4 接受（accept）或拒绝（keep）删除建议 */
export async function decideRemoval(
  mindmapId: string,
  nodeId: string,
  decision: 'accept' | 'keep',
  expectedGraphVersion: number
): Promise<NodeActionResponse> {
  return apiJson(`/api/mindmaps/${mindmapId}/nodes/${nodeId}/removal-decisions`, {
    method: 'POST',
    json: { decision, expectedGraphVersion }
  }, '删除建议决策失败')
}
/** 保存节点自由拖拽坐标（不递增语义图版本） */
export async function saveNodePosition(
  mindmapId: string,
  nodeId: string,
  body: { x: number; y: number; expectedGraphVersion: number }
): Promise<NodeActionResponse> {
  return apiJson(`/api/mindmaps/${mindmapId}/nodes/${nodeId}/position`, {
    method: 'PUT',
    json: body
  }, '保存节点位置失败')
}
/** 清空所有手动坐标，恢复 ELK 自动布局 */
export async function resetLayout(
  mindmapId: string,
  expectedGraphVersion: number
): Promise<NodeActionResponse> {
  return apiJson(`/api/mindmaps/${mindmapId}/reset-layout`, {
    method: 'POST',
    json: { expectedGraphVersion }
  }, '恢复自动布局失败')
}