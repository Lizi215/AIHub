import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { saveBlob } from './download'

describe('saveBlob', () => {
  beforeEach(() => {
    // jsdom 不实现 createObjectURL / revokeObjectURL，必须打桩
    global.URL.createObjectURL = vi.fn(() => 'blob:mock-url')
    global.URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('生成 a[download]、触发点击、随后移除并释放 URL', () => {
    let captured = null
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
      captured = this
    })

    const blob = new Blob(['payload'])
    saveBlob(blob, 'package.zip')

    expect(global.URL.createObjectURL).toHaveBeenCalledWith(blob)
    expect(click).toHaveBeenCalledTimes(1)
    expect(captured).not.toBeNull()
    expect(captured.download).toBe('package.zip')
    expect(captured.href).toBe('blob:mock-url')
    // 及时释放，避免对象 URL 泄漏
    expect(global.URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
    // 临时节点不残留在 DOM 里
    expect(document.querySelector('a')).toBeNull()
  })
})
