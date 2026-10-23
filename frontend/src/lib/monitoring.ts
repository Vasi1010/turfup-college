import * as Sentry from '@sentry/react'

/**
 * Error reporting. Off unless the build sets VITE_SENTRY_DSN (see frontend/Dockerfile), so local dev,
 * tests and the college build send nothing anywhere.
 */
export function initMonitoring() {
  const dsn = import.meta.env.VITE_SENTRY_DSN
  if (!dsn) return
  Sentry.init({
    dsn,
    environment: import.meta.env.VITE_SENTRY_ENVIRONMENT || 'production',
    // Errors only: no performance tracing, no session replay, no personal data.
    sendDefaultPii: false,
  })
}
