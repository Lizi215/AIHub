import request from '@/utils/request'

/**
 * Issue（资源问题/讨论）。
 *
 * 读取匿名可访问（社区内容公开），写入需要登录。
 */
export function listIssuesByAsset(assetId, params) {
  return request.get('/issues', { params: { assetId, ...params } })
}

/** 提 Issue：assetId 必填，version 可选（指向"哪个版本出的问题"） */
export function createIssue(data) {
  return request.post('/issues', data)
}

/** 变更状态。合法流转由后端的 IssueStatusMachine 判定 */
export function changeIssueStatus(id, status) {
  return request.put(`/issues/${id}/status`, null, { params: { status } })
}

/** 发表评论 */
export function createIssueComment(issueId, data) {
  return request.post(`/issues/${issueId}/comments`, data)
}

/** 评论列表（按时间升序） */
export function listIssueComments(issueId, params) {
  return request.get(`/issues/${issueId}/comments`, { params })
}
