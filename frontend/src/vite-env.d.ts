/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Sentry DSN for browser errors; unset = monitoring off. */
  readonly VITE_SENTRY_DSN?: string
  readonly VITE_SENTRY_ENVIRONMENT?: string
  /** Backend origin when it is not this site's own /api (the hosted demo's Render URL). */
  readonly VITE_API_URL?: string
  /** 'true' when the backend sleeps when idle (free hosting): retry for minutes and say it is waking up. */
  readonly VITE_BACKEND_SLEEPS?: string
}
