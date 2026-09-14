// 把下载接口返回的 Blob 变成一个浏览器下载动作。
// 资源列表与搜索页都要用，抽到一处避免两份实现漂移。

export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
