import { describe, it, expect, vi, beforeEach } from 'vitest'

// 锁定「前端 api 封装 → 网关路径」的契约：路径或动词写错，页面会 404/405，
// 这类错误在浏览器里很难定位，用单测守住最省事。
vi.mock('@/utils/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

import request from '@/utils/request'
import { listAssets, lookupOwnAssetByName, getAssetDetail, createAsset, uploadAssetVersion, publishAsset, updateAsset, deleteAsset, deleteAssetVersion, batchDeleteAssetVersions, downloadAsset, downloadAssetVersion, downloadAssetVersionReadme, listAssetVersions, listAssetDependencies, getDependencyTree } from './asset'
import { register, login, logout, refreshToken, getCurrentUser } from './auth'
import { searchAssets } from './search'
import { listTags, listAssetTags, setAssetTags } from './tag'
import { createAgentTask, getAgentTask, confirmAgentTask, cancelAgentTask } from './agent'
import { createWorkspace, listWorkspaces, getWorkspace, deleteWorkspace } from './workspace'
import { listIssuesByAsset, createIssue, changeIssueStatus, createIssueComment, listIssueComments } from './issue'

describe('api 契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('资源接口路径与动词', () => {
    listAssets({ page: 1, size: 10 })
    expect(request.get).toHaveBeenCalledWith('/assets', { params: { page: 1, size: 10 } })

    lookupOwnAssetByName('a11y-rules')
    expect(request.get).toHaveBeenCalledWith('/assets/own-by-name', { params: { name: 'a11y-rules' } })

    getAssetDetail('123')
    expect(request.get).toHaveBeenCalledWith('/assets/123')

    createAsset({ name: 'x' })
    expect(request.post).toHaveBeenCalledWith('/assets', { name: 'x' })

    const fd = new FormData()
    uploadAssetVersion('123', fd)
    expect(request.post).toHaveBeenCalledWith('/assets/123/versions', fd)

    publishAsset('123')
    expect(request.put).toHaveBeenCalledWith('/assets/123/publish')

    updateAsset('123', { name: 'n' })
    expect(request.put).toHaveBeenCalledWith('/assets/123', { name: 'n' })

    deleteAsset('123')
    expect(request.delete).toHaveBeenCalledWith('/assets/123')

    // 版本号要编码，防止版本里出现斜杠等字符破坏路径
    deleteAssetVersion('123', '1.0.0/rc')
    expect(request.delete).toHaveBeenCalledWith('/assets/123/versions/1.0.0%2Frc')

    batchDeleteAssetVersions('123', ['1.0.0'])
    expect(request.post).toHaveBeenCalledWith('/assets/123/versions/batch-delete', { versions: ['1.0.0'] })

    listAssetVersions('123')
    expect(request.get).toHaveBeenCalledWith('/assets/123/versions')

    listAssetDependencies('123')
    expect(request.get).toHaveBeenCalledWith('/assets/123/dependencies')

    getDependencyTree('123')
    expect(request.get).toHaveBeenCalledWith('/assets/123/dependency-tree')
  })

  it('下载接口必须带 responseType=blob', () => {
    downloadAsset('123')
    expect(request.get).toHaveBeenCalledWith('/assets/123/download', { responseType: 'blob' })

    downloadAssetVersion('123', '1.0.0')
    expect(request.get).toHaveBeenCalledWith('/assets/123/versions/1.0.0/download', { responseType: 'blob' })

    downloadAssetVersionReadme('123', '1.0.0')
    expect(request.get).toHaveBeenCalledWith('/assets/123/versions/1.0.0/readme', { responseType: 'blob' })
  })

  it('认证接口路径', () => {
    register({ username: 'u', password: 'p' })
    expect(request.post).toHaveBeenCalledWith('/auth/register', { username: 'u', password: 'p' })

    login({ username: 'u', password: 'p' })
    expect(request.post).toHaveBeenCalledWith('/auth/login', { username: 'u', password: 'p' })

    logout({ refreshToken: 'r' })
    expect(request.post).toHaveBeenCalledWith('/auth/logout', { refreshToken: 'r' })

    refreshToken({ refreshToken: 'r' })
    expect(request.post).toHaveBeenCalledWith('/auth/refresh', { refreshToken: 'r' })

    getCurrentUser()
    expect(request.get).toHaveBeenCalledWith('/users/me')
  })

  it('搜索与标签接口路径', () => {
    searchAssets({ keyword: 'k' })
    expect(request.get).toHaveBeenCalledWith('/search/assets', { params: { keyword: 'k' } })

    listTags()
    expect(request.get).toHaveBeenCalledWith('/tags')

    listAssetTags('123')
    expect(request.get).toHaveBeenCalledWith('/assets/123/tags')

    // 整组替换语义：body 形如 { tags: [...] }
    setAssetTags('123', ['ai', '工具'])
    expect(request.put).toHaveBeenCalledWith('/assets/123/tags', { tags: ['ai', '工具'] })
  })

  it('Agent 接口路径与动词', () => {
    createAgentTask({ scene: 'SEARCH', request: 'r' })
    expect(request.post).toHaveBeenCalledWith('/agent/tasks', { scene: 'SEARCH', request: 'r' })

    getAgentTask('t1')
    expect(request.get).toHaveBeenCalledWith('/agent/tasks/t1')

    confirmAgentTask('t1')
    expect(request.post).toHaveBeenCalledWith('/agent/tasks/t1/confirm')

    cancelAgentTask('t1')
    expect(request.delete).toHaveBeenCalledWith('/agent/tasks/t1')
  })

  it('Workspace 接口路径', () => {
    createWorkspace({ name: 'w', items: [] })
    expect(request.post).toHaveBeenCalledWith('/workspaces', { name: 'w', items: [] })

    listWorkspaces({ page: 1 })
    expect(request.get).toHaveBeenCalledWith('/workspaces', { params: { page: 1 } })

    getWorkspace('w1')
    expect(request.get).toHaveBeenCalledWith('/workspaces/w1')

    deleteWorkspace('w1')
    expect(request.delete).toHaveBeenCalledWith('/workspaces/w1')
  })

  it('Issue 接口路径：assetId 合并进 params，状态用 query 传递', () => {
    listIssuesByAsset('123', { page: 1 })
    expect(request.get).toHaveBeenCalledWith('/issues', { params: { assetId: '123', page: 1 } })

    createIssue({ assetId: '123', title: 't' })
    expect(request.post).toHaveBeenCalledWith('/issues', { assetId: '123', title: 't' })

    changeIssueStatus('i1', 'CLOSED')
    expect(request.put).toHaveBeenCalledWith('/issues/i1/status', null, { params: { status: 'CLOSED' } })

    createIssueComment('i1', { content: 'c' })
    expect(request.post).toHaveBeenCalledWith('/issues/i1/comments', { content: 'c' })

    listIssueComments('i1', { page: 1 })
    expect(request.get).toHaveBeenCalledWith('/issues/i1/comments', { params: { page: 1 } })
  })
})
