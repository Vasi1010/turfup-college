import { useSyncExternalStore } from 'react'
import { ApiError, NetworkError } from './api'

/**
 * Where the API lives. Empty (local dev, the Docker production build): `/api` on this origin. The hosted demo sets
 * VITE_API_URL to its Render backend, which the app calls directly: Vercel's proxy fails at once while a free Render
 * service is asleep and never wakes it, while a direct request does.
 */
export const API_BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '')

/** The backend may be asleep (free hosting) and take a few minutes to answer its first request. */
export const BACKEND_SLEEPS = import.meta.env.VITE_BACKEND_SLEEPS === 'true'

/** Keep retrying a sleeping backend this long (its cold start is about 2½ minutes). */
const WAKE_DELAY_MS = 5_000
const WAKE_ATTEMPTS = 48

/** What a sleeping or starting backend looks like: no answer, or a gateway error. */
export function isWakeError(error: unknown): boolean {
  return error instanceof NetworkError || (error instanceof ApiError && [502, 503, 504].includes(error.status))
}

/** React Query retry rule: client errors never, a waking backend for minutes, anything else twice. */
export function queryRetry(failureCount: number, error: unknown, sleeps = BACKEND_SLEEPS): boolean {
  if (error instanceof ApiError && error.status < 500) return false
  if (sleeps && isWakeError(error)) return failureCount < WAKE_ATTEMPTS
  return failureCount < 2
}

export function queryRetryDelay(failureCount: number, error: unknown, sleeps = BACKEND_SLEEPS): number {
  if (sleeps && isWakeError(error)) return WAKE_DELAY_MS
  return Math.min(1000 * 2 ** failureCount, 30_000)
}

/** A request still unanswered after this long is probably waiting for the backend to start. */
const SLOW_MS = 4_000

/**
 * Shows the waking banner if a request is still waiting after SLOW_MS: Render holds the first request open while a
 * sleeping service starts, so it does not fail, it just takes minutes. Returns the function that stops the timer.
 */
export function watchSlowRequest(): () => void {
  if (!BACKEND_SLEEPS) return () => {}
  const timer = setTimeout(() => setBackendWaking(true), SLOW_MS)
  return () => clearTimeout(timer)
}

// Whether the backend seems to be starting (a request failed or is slow); the shell shows a banner until one succeeds.
let waking = false
const listeners = new Set<() => void>()

export function setBackendWaking(next: boolean) {
  if (!BACKEND_SLEEPS || waking === next) return
  waking = next
  listeners.forEach((l) => l())
}

export function useBackendWaking(): boolean {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => waking,
  )
}
