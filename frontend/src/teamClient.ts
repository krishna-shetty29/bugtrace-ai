let csrf = ''
let installed = false
export function setTeamCsrf(value: string) { csrf = value }
export function teamRequest(input: RequestInfo | URL, init: RequestInit | undefined, origin: string, token: string) {
  const request = typeof input === 'object' && 'url' in input ? input as Request : null
  const url = new URL(request ? request.url : String(input), origin)
  if (url.origin !== new URL(origin).origin || !url.pathname.startsWith('/api/')) return init
  const method = (init?.method || request?.method || 'GET').toUpperCase()
  if (['GET', 'HEAD', 'OPTIONS'].includes(method) || !token) return init
  const headers = new Headers(init?.headers ?? request?.headers)
  headers.set('X-BugTrace-CSRF', token)
  return { ...init, headers }
}
export function installTeamFetch() {
  if (installed) return
  installed = true
  const original = window.fetch.bind(window)
  window.fetch = async (input, init) => {
    const result = await original(input, teamRequest(input, init, window.location.origin, csrf))
    if (result.status === 401) window.dispatchEvent(new Event('bugtrace-session-ended'))
    return result
  }
}
export async function accessApi<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api/access/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 800)}`)
  return JSON.parse(text) as T
}
