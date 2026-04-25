/**
 * 管理端 Agent SSE 客户端。EventSource 不支持自定义 Header（无法带 Authorization），
 * 所以用 fetch + ReadableStream 解析 text/event-stream。
 */

const AGENT_BASE = '/api'

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

export async function streamAdminChat({ messages, userMessage, onEvent, signal } = {}) {
  const token = localStorage.getItem('admin_token') || ''
  const resp = await fetch(`${AGENT_BASE}/agent/admin/chat`, {
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
      if (e.event === 'done' || e.event === 'error') return
    }
  }
}

export async function fetchAgentGreeting() {
  try {
    const resp = await fetch(`${AGENT_BASE}/agent/greeting`)
    if (!resp.ok) return { user: '', admin: '' }
    return await resp.json()
  } catch (e) {
    return { user: '', admin: '' }
  }
}
