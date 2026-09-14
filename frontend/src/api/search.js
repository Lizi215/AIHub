import request from '@/utils/request'

/**
 * 资源搜索。
 *
 * @param params keyword / category / tag / sort(relevance|latest|hottest) /
 *               page / size / searchAfter（深分页游标）
 */
export function searchAssets(params) {
  return request.get('/search/assets', { params })
}
