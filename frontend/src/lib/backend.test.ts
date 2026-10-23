import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from './api'
import { isWakeError, queryRetry, queryRetryDelay } from './backend'

const offline = new NetworkError('offline')
const badGateway = new ApiError(502, undefined, 'Bad Gateway')
const serverBug = new ApiError(500, undefined, 'Internal Server Error')
const notFound = new ApiError(404, undefined, 'Not Found')

describe('waking a sleeping backend', () => {
  it('treats no answer and gateway errors as a backend that is starting', () => {
    expect(isWakeError(offline)).toBe(true)
    expect(isWakeError(badGateway)).toBe(true)
    expect(isWakeError(new ApiError(503, undefined, ''))).toBe(true)
    expect(isWakeError(serverBug)).toBe(false)
    expect(isWakeError(notFound)).toBe(false)
  })

  it('keeps retrying for minutes when the backend sleeps', () => {
    expect(queryRetry(10, offline, true)).toBe(true)
    expect(queryRetry(47, badGateway, true)).toBe(true)
    expect(queryRetry(48, badGateway, true)).toBe(false)
    expect(queryRetryDelay(3, offline, true)).toBe(5_000)
  })

  it('retries twice otherwise, and never on client errors', () => {
    expect(queryRetry(1, offline, false)).toBe(true)
    expect(queryRetry(2, offline, false)).toBe(false)
    expect(queryRetry(1, serverBug, true)).toBe(true)
    expect(queryRetry(2, serverBug, true)).toBe(false)
    expect(queryRetry(0, notFound, true)).toBe(false)
    expect(queryRetryDelay(1, offline, false)).toBe(2_000)
  })
})
