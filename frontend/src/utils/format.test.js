import { describe, it, expect } from 'vitest'
import { formatDateTime, dateTimeFormatter } from './format'

describe('formatDateTime', () => {
  it('空值统一显示为 "-"', () => {
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime(undefined)).toBe('-')
    expect(formatDateTime('')).toBe('-')
    expect(formatDateTime(0)).toBe('-')
  })

  it('去掉 ISO 串中间的 T，保留到秒', () => {
    expect(formatDateTime('2026-09-27T17:45:22')).toBe('2026-09-27 17:45:22')
  })

  it('截掉毫秒', () => {
    expect(formatDateTime('2026-09-27T17:45:22.123')).toBe('2026-09-27 17:45:22')
  })

  it('截掉时区后缀（Z 与 +08:00）', () => {
    // 不做时区换算，直接展示后端给的本地时间字面值：
    // 用 Date 解析无时区串会按运行环境时区推断，导致整体偏移。
    expect(formatDateTime('2026-09-27T17:45:22Z')).toBe('2026-09-27 17:45:22')
    expect(formatDateTime('2026-09-27T17:45:22+08:00')).toBe('2026-09-27 17:45:22')
  })

  it('只有日期没有时间时原样返回', () => {
    expect(formatDateTime('2026-09-27')).toBe('2026-09-27')
  })

  it('el-table formatter 适配：取第三个参数 cellValue', () => {
    expect(dateTimeFormatter({}, {}, '2026-01-02T03:04:05')).toBe('2026-01-02 03:04:05')
    expect(dateTimeFormatter({}, {}, null)).toBe('-')
  })
})
