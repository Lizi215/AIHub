import request from '@/utils/request'

/**
 * 分页列表。page 从 1 开始。
 * 未登录时只返回已发布资源；登录后会额外返回自己名下的草稿。
 */
export function listAssets(params) {
  return request.get('/assets', { params })
}

/**
 * 查「我自己的同名资源」：手动上传同名资源时用来决定追加新版本还是新建资源。
 * 返回 null 表示自己没有同名资源。
 */
export function lookupOwnAssetByName(name) {
  return request.get('/assets/own-by-name', { params: { name } })
}

/** 详情，含最新版本的 README */
export function getAssetDetail(id) {
  return request.get(`/assets/${id}`)
}

/** 上传阶段①：创建草稿，返回 assetId */
export function createAsset(data) {
  return request.post('/assets', data)
}

/**
 * 上传阶段②：上传文件并生成版本。
 *
 * <p>传 FormData（含 file / version / changelog / readme 字段），
 * 不要手工设置 Content-Type —— 交给浏览器带上 multipart boundary。
 */
export function uploadAssetVersion(id, formData) {
  return request.post(`/assets/${id}/versions`, formData)
}

/** 上传阶段③：发布 */
export function publishAsset(id) {
  return request.put(`/assets/${id}/publish`)
}

/** 修改资源基础信息（名称 / 简介 / 分类）。仅归属者本人可改 */
export function updateAsset(id, data) {
  return request.put(`/assets/${id}`, data)
}

/** 删除资源（软删除，后端同时下架并移除索引）。仅归属者本人可删 */
export function deleteAsset(id) {
  return request.delete(`/assets/${id}`)
}

/** 删除单个版本（软删，latest_version 由后端重算并返回）。仅归属者本人 */
export function deleteAssetVersion(id, version) {
  return request.delete(`/assets/${id}/versions/${encodeURIComponent(version)}`)
}

/** 批量删除版本（不存在的版本幂等跳过），返回重算后的 latest_version */
export function batchDeleteAssetVersions(id, versions) {
  return request.post(`/assets/${id}/versions/batch-delete`, { versions })
}

/** 下载最新版本，返回 Blob */
export function downloadAsset(id) {
  return request.get(`/assets/${id}/download`, { responseType: 'blob' })
}

/** 下载指定版本，返回 Blob */
export function downloadAssetVersion(id, version) {
  return request.get(`/assets/${id}/versions/${version}/download`, { responseType: 'blob' })
}

/**
 * 下载指定版本的 README（.md 文件），返回 Blob。
 * 每个版本对应自己那份 README，所以老版本的也能单独下载。
 */
export function downloadAssetVersionReadme(id, version) {
  return request.get(`/assets/${id}/versions/${version}/readme`, { responseType: 'blob' })
}

/** 版本列表（按创建时间倒序） */
export function listAssetVersions(id) {
  return request.get(`/assets/${id}/versions`)
}

/** 直接依赖列表 */
export function listAssetDependencies(id) {
  return request.get(`/assets/${id}/dependencies`)
}

/**
 * 依赖树（已展开传递依赖）。
 * 节点结构：{ assetId, name, cycle, children: [...] }，cycle 表示该节点指回了祖先节点。
 */
export function getDependencyTree(id) {
  return request.get(`/assets/${id}/dependency-tree`)
}


