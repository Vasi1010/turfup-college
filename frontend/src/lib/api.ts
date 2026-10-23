import { API_BASE, setBackendWaking, watchSlowRequest } from './backend'
import { clearSession, getSession } from './session'

/** A failed API call. `code` is the server's stable error code (e.g. SLOT_UNAVAILABLE) when it sent one. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly body: Record<string, unknown> | undefined

  constructor(status: number, code: string | undefined, message: string, body?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.body = body
  }
}

/** Thrown when the request never reached the server. */
export class NetworkError extends Error {}

// Server clock offset, from the Date header of every response. Countdowns use serverNow(), so a phone with a
// wrong clock still shows the real time left on a hold.
let skewMs = 0

export function serverNow(): number {
  return Date.now() + skewMs
}

/** Exposed for tests and for screens that receive the server time in a payload. */
export function syncClock(serverTimeMs: number) {
  skewMs = serverTimeMs - Date.now()
}

interface Options {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  query?: Record<string, string | number | undefined>
}

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const { method = 'GET', body, query } = options
  let url = API_BASE + path
  if (query) {
    const params = new URLSearchParams()
    for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== '') params.set(k, String(v))
    const qs = params.toString()
    if (qs) url += `?${qs}`
  }

  const session = getSession()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (session) headers.Authorization = `Bearer ${session.token}`

  let res: Response
  const stopWatching = watchSlowRequest()
  try {
    res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    setBackendWaking(true)
    throw new NetworkError('offline')
  } finally {
    stopWatching()
  }
  setBackendWaking([502, 503, 504].includes(res.status))

  const date = res.headers.get('Date')
  if (date) {
    const t = Date.parse(date)
    if (!Number.isNaN(t)) syncClock(t)
  }

  const text = await res.text()
  const json = text ? safeJson(text) : undefined

  if (!res.ok) {
    if (res.status === 401 && session) clearSession()
    const problem = (json ?? {}) as Record<string, unknown>
    const code = typeof problem.code === 'string' ? problem.code : undefined
    const detail = typeof problem.detail === 'string' ? problem.detail : res.statusText
    throw new ApiError(res.status, code, detail, problem)
  }
  return json as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}
