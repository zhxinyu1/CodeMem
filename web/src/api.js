const JSON_HEADERS = { 'Content-Type': 'application/json; charset=utf-8' }

async function request(url, options = {}) {
  const response = await fetch(url, options)
  if (!response.ok) {
    let message = `HTTP ${response.status}`
    try {
      const body = await response.json()
      if (body && body.message) message = body.message
    } catch (ignored) {
      /* 保留状态码信息即可 */
    }
    throw new Error(message)
  }
  if (response.status === 204) return null
  return response.json()
}

export function getJson(url) {
  return request(url)
}

export function postJson(url, body) {
  return request(url, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(body)
  })
}

export function patchJson(url, body) {
  return request(url, {
    method: 'PATCH',
    headers: JSON_HEADERS,
    body: JSON.stringify(body ?? {})
  })
}

export function deleteJson(url) {
  return request(url, { method: 'DELETE' })
}

/**
 * 消费 POST SSE：meta / citation / token / agent / done / error。
 * 未知事件一律忽略，保证后端追加事件时不破坏前端。
 */
export async function streamChat({ learnerId, projectId, conversationId, message, onEvent }) {
  const response = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: { ...JSON_HEADERS, Accept: 'text/event-stream' },
    body: JSON.stringify({ learnerId, projectId, conversationId, message })
  })
  if (!response.ok || !response.body) {
    throw new Error(`SSE 连接失败：HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let separator = buffer.indexOf('\n\n')
    while (separator >= 0) {
      const raw = buffer.slice(0, separator)
      buffer = buffer.slice(separator + 2)
      const event = parseEvent(raw)
      if (event) onEvent(event.name, event.data)
      separator = buffer.indexOf('\n\n')
    }
  }
}

function parseEvent(raw) {
  let name = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return { name, data: null }
  const text = dataLines.join('\n')
  try {
    return { name, data: JSON.parse(text) }
  } catch (ignored) {
    return { name, data: text }
  }
}