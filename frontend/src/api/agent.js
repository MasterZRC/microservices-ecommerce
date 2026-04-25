/**
 * Agent 相关 API：SSE 流式调用 + 普通 REST。
 *
 * 前端用 fetch + ReadableStream 解析 text/event-stream。
 * EventSource 不支持自定义 Header（无法带 Authorization），所以必须用 fetch。
 */

const AGENT_BASE = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

/**
 * 解析 SSE 帧。一个事件以 `\n\n` 分隔，单个事件内可能有多行 data。
 */
function parseEvents(buffer) {
  const events = []
  let idx
  while ((idx = buffer.indexOf('\n\n')) !== -1) {
    const raw = buffer.slice(0, idx)
    buffer = buffer.slice(idx + 2)
    let event = 'message'
    const dataLines = []
    raw.split('\n').forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    })
    if (dataLines.length === 0) continue
    let payload
    try {
      payload = JSON.parse(dataLines.join('\n'))
    } catch (e) {
      payload = { raw: dataLines.join('\n') }
    }
    events.push({ event, payload })
  }
  return { events, rest: buffer }
}

/**
 * 用户 Agent 流式对话。
 *
 * @param {Object} opts
 * @param {Array<{role,content}>} opts.messages 历史消息
 * @param {string} opts.userMessage          本次新消息
 * @param {Function} opts.onEvent           ({event, payload}) => void
 * @param {AbortSignal} [opts.signal]
 * @returns {Promise<void>}                 流结束（done/error）才 resolve
 */
export async function streamUserChat({ messages, userMessage, onEvent, signal } = {}) {
  const token = localStorage.getItem('token') || ''
  const resp = await fetch(`${AGENT_BASE}/agent/user/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      Accept: 'text/event-stream'
    },
    body: JSON.stringify({ messages: messages || [], user_message: userMessage || '' }),
    signal
  })

  if (!resp.ok) {
    let detail = ''
    try {
      const j = await resp.json()
      detail = j.detail || j.message || ''
    } catch (e) {
      detail = await resp.text().catch(() => '')
    }
    throw new Error(`Agent 服务返回 ${resp.status}: ${detail || '未知错误'}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const { events, rest } = parseEvents(buffer)
    buffer = rest
    for (const e of events) {
      onEvent(e)
      if (e.event === 'done' || e.event === 'error') {
        // 服务端已结束，不再继续读
        return
      }
    }
  }
}

export async function fetchGreeting() {
  const resp = await fetch(`${AGENT_BASE}/agent/greeting`)
  if (!resp.ok) return { user: '', admin: '' }
  return await resp.json()
}
