import { getBaseUrl } from '../config'
import { getToken } from './api'

/**
 * 增量 UTF-8 解码：SSE 按 chunk 到达时可能把一个汉字/emoji 切成两半，
 * 且 App 端 JS 环境没有 TextDecoder，这里手写兜底。
 */
class Utf8StreamDecoder {
  constructor() {
    this.td = typeof TextDecoder !== 'undefined' ? new TextDecoder('utf-8') : null
    this.pending = new Uint8Array(0)
  }
  decode(buf) {
    const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf)
    if (this.td) return this.td.decode(u8, { stream: true })
    const all = this.pending.length ? concatBytes(this.pending, u8) : u8
    let out = ''
    let i = 0
    while (i < all.length) {
      const b = all[i]
      let len = 0
      let cp = 0
      if (b < 0x80) {
        len = 1
        cp = b
      } else if (b >= 0xc2 && b < 0xe0) {
        len = 2
        cp = b & 0x1f
      } else if (b >= 0xe0 && b < 0xf0) {
        len = 3
        cp = b & 0x0f
      } else if (b >= 0xf0 && b < 0xf5) {
        len = 4
        cp = b & 0x07
      } else {
        i++ // 非法字节，丢弃
        continue
      }
      if (i + len > all.length) break // 序列不完整，留到下一个 chunk
      let ok = true
      for (let j = 1; j < len; j++) {
        const c = all[i + j]
        if ((c & 0xc0) !== 0x80) {
          ok = false
          break
        }
        cp = (cp << 6) | (c & 0x3f)
      }
      if (!ok) {
        i++
        continue
      }
      if (cp > 0xffff) {
        // 4 字节（emoji 等）转 UTF-16 代理对
        cp -= 0x10000
        out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff))
      } else {
        out += String.fromCharCode(cp)
      }
      i += len
    }
    this.pending = i < all.length ? all.slice(i) : new Uint8Array(0)
    return out
  }
  flush() {
    if (this.td) return this.td.decode()
    this.pending = new Uint8Array(0)
    return ''
  }
}

function concatBytes(a, b) {
  const r = new Uint8Array(a.length + b.length)
  r.set(a)
  r.set(b, a.length)
  return r
}

/**
 * 流式 SSE（与 Web 端 api.js 的 sseStream 协议完全一致）：
 * POST + enableChunked 分块接收，事件块以 \n\n 分隔，data 行是 JSON；
 * {delta} 增量回调 onDelta，{sources/actions/saved/done} 结束回调 onDone。
 * App 端没有浏览器 fetch/EventSource，uni.request 分块是唯一通路。
 */
export function sseStream(path, body, onDelta, onDone) {
  return new Promise((resolve, reject) => {
    const decoder = new Utf8StreamDecoder()
    let buffer = ''
    let ended = false
    let gotDelta = false

    const finish = (err) => {
      if (ended) return
      ended = true
      err ? reject(err) : resolve()
    }

    const handleBlock = (block) => {
      const line = block.split('\n').find((l) => l.startsWith('data:'))
      if (!line) return
      let d
      try {
        d = JSON.parse(line.slice(5).trim())
      } catch (e) {
        return
      }
      if (d.delta !== undefined) {
        gotDelta = true
        onDelta(d.delta)
      } else if (d.sources !== undefined || d.saved !== undefined || d.actions !== undefined || d.done) {
        onDone(d)
      }
    }

    const consume = (text) => {
      buffer += text
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        handleBlock(block)
      }
    }

    const header = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
    const token = getToken()
    if (token) header.Authorization = 'Bearer ' + token

    let task
    try {
      task = uni.request({
        url: getBaseUrl() + path,
        method: 'POST',
        data: body,
        header,
        enableChunked: true,
        timeout: 600000, // 流式回答可能很长，放宽总超时
        success: (res) => {
          const status = res && res.statusCode
          if (status && status >= 400) {
            decoder.flush()
            let msg = '请求失败(' + status + ')'
            try {
              const j = JSON.parse((buffer || '').trim())
              if (j && j.message) msg = j.message
            } catch (e) { /* 非 JSON 错误体 */ }
            if (status === 401) {
              uni.removeStorageSync('la_token')
              uni.reLaunch({ url: '/pages/login/login' })
            }
            finish(new Error(msg))
            return
          }
          // 个别平台不支持分块回调时，整包落在 success.data 里，兜底解析一次
          if (typeof (res && res.data) === 'string' && res.data && !gotDelta) consume(res.data)
          finish()
        },
        fail: (err) => finish(new Error('连接失败：' + ((err && err.errMsg) || '网络错误')))
      })
    } catch (e) {
      finish(e)
      return
    }

    if (task && typeof task.onChunkReceived === 'function') {
      task.onChunkReceived((res) => {
        const chunk = res && res.chunk
        if (chunk == null) return
        const text = typeof chunk === 'string' ? chunk : decoder.decode(chunk)
        consume(text)
      })
    }
  })
}
