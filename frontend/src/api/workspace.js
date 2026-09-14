import request from '@/utils/request'

/** 创建 Workspace（items 来自 Agent 草案或用户手工挑选） */
export function createWorkspace(data) {
  return request.post('/workspaces', data)
}

/** 我的 Workspace 列表 */
export function listWorkspaces(params) {
  return request.get('/workspaces', { params })
}

/** 详情（含条目与可用性） */
export function getWorkspace(id) {
  return request.get(`/workspaces/${id}`)
}

/** 删除 */
export function deleteWorkspace(id) {
  return request.delete(`/workspaces/${id}`)
}
