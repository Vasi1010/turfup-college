import { s } from '../strings'
import { ApiError, NetworkError } from './api'

/** A clear, user-facing message for any error thrown by an API call. */
export function errorMessage(error: unknown): string {
  if (error instanceof NetworkError) return s.errors.offline
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_SLOT') {
      const reason = typeof error.body?.reason === 'string' ? error.body.reason : ''
      return s.errors[`INVALID_SLOT_${reason}`] ?? s.errors.INVALID_SLOT
    }
    if (error.code && s.errors[error.code]) return s.errors[error.code]
    if (error.status === 401) return s.errors.sessionExpired
    if (error.status === 403) return s.errors.forbidden
    if (error.status === 404) return s.errors.notFound
    // Domain errors without a translated code carry a readable message from the server.
    if (error.code && error.message) return error.message
    if (error.status === 400) return s.errors.invalid
  }
  return s.errors.generic
}

export function errorCode(error: unknown): string | undefined {
  return error instanceof ApiError ? error.code : undefined
}
