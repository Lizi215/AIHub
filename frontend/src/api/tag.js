import request from '@/utils/request'

/** 某个资源的标签（公开读）。 */
export function listAssetTags(id) {
  return request.get(`/assets/${id}/tags`)
}

/**
 * 整组设置资源标签（owner only）。
 *
 * 后端清洗后整组替换，字典里没有的名字会自动注册；返回清洗后的最终名单。
 */
export function setAssetTags(id, tags) {
  return request.put(`/assets/${id}/tags`, { tags })
}
