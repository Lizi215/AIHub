/**
 * 展示层格式化工具：把后端的 ISO-8601 串转成 `YYYY-MM-DD HH:mm:ss`。
 *
 * 用字符串截取而不是 `new Date()`：无时区后缀的 ISO 串交给 Date 解析会依赖运行环境的
 * 时区推断，容易把本地时间当成 UTC 处理而整体偏移几小时。
 */
export function formatDateTime(value) {
  if (!value) {
    return '-'
  }
  const text = String(value)
  const [date, time = ''] = text.split('T')
  if (!time) {
    return date
  }
  // 去掉毫秒与可能存在的时区后缀（Z / +08:00），只保留 HH:mm:ss
  return `${date} ${time.slice(0, 8)}`
}

/** el-table 的 formatter 适配：表格列传参是 (row, column, cellValue) */
export function dateTimeFormatter(row, column, cellValue) {
  return formatDateTime(cellValue)
}
