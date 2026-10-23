import { Loader2, RotateCcw, X } from 'lucide-react'
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'
import { s } from '../strings'
import { ToastContext } from './toast-context'

// --- Button ---

type Variant = 'primary' | 'secondary' | 'danger' | 'quiet'

const variants: Record<Variant, string> = {
  primary:
    'bg-flood text-flood-ink shadow-[0_0_0_1px_rgb(214_255_61/0.4),0_8px_30px_-8px_rgb(214_255_61/0.55)] hover:bg-[#e3ff70] active:translate-y-px',
  secondary: 'border border-line bg-turf text-chalk hover:bg-turf-2',
  danger: 'border border-red/40 bg-red/10 text-red hover:bg-red/20',
  quiet: 'text-chalk-dim hover:text-chalk',
}

export function Button({
  variant = 'primary',
  busy = false,
  className = '',
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; busy?: boolean }) {
  return (
    <button
      {...rest}
      disabled={disabled || busy}
      className={`inline-flex min-h-12 items-center justify-center gap-2 rounded-xl px-5 text-base font-semibold transition disabled:cursor-not-allowed disabled:opacity-45 ${variants[variant]} ${className}`}
    >
      {busy && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  )
}

// --- Small pieces ---

export function Spinner({ label = s.common.loading }: { label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center gap-2 py-16 text-chalk-dim">
      <Loader2 className="size-5 animate-spin" aria-hidden />
      <span className="text-sm">{label}</span>
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="mx-auto max-w-sm rounded-2xl border border-red/30 bg-red/10 p-5 text-center">
      <p className="text-sm text-chalk">{message}</p>
      {onRetry && (
        <Button variant="secondary" className="mt-4 min-h-10 text-sm" onClick={onRetry}>
          <RotateCcw className="size-4" aria-hidden /> {s.common.retry}
        </Button>
      )}
    </div>
  )
}

export function InlineError({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <p role="alert" className="rounded-xl border border-red/30 bg-red/10 px-4 py-3 text-sm text-chalk">
      {message}
    </p>
  )
}

export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="mx-auto max-w-xs py-14 text-center">
      <CenterCircle className="mx-auto mb-5 size-20 text-line" />
      <p className="font-display text-2xl font-bold uppercase tracking-wide">{title}</p>
      {hint && <p className="mt-2 text-sm text-chalk-dim">{hint}</p>}
      {action && <div className="mt-6">{action}</div>}
    </div>
  )
}

/** The pitch's centre circle, used as the brand mark and for empty states. */
export function CenterCircle({ className = '' }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" className={className} aria-hidden fill="none" stroke="currentColor" strokeWidth="3">
      <circle cx="32" cy="32" r="22" />
      <line x1="32" y1="2" x2="32" y2="62" />
      <circle cx="32" cy="32" r="3" fill="currentColor" stroke="none" />
    </svg>
  )
}

export function Chip({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'flood' | 'amber' | 'red' | 'ok' }) {
  const tones = {
    neutral: 'border-line text-chalk-dim',
    flood: 'border-flood/40 text-flood',
    amber: 'border-amber/40 text-amber',
    red: 'border-red/40 text-red',
    ok: 'border-ok/40 text-ok',
  }
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-semibold ${tones[tone]}`}>
      {children}
    </span>
  )
}

export function Switch({ checked, onChange, label, disabled }: {
  checked: boolean
  onChange: (value: boolean) => void
  label: string
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`relative h-8 w-14 shrink-0 rounded-full border transition disabled:opacity-50 ${
        checked ? 'border-flood bg-flood/25' : 'border-line bg-pitch-2'
      }`}
    >
      <span
        className={`absolute top-1 size-6 rounded-full transition-all ${checked ? 'left-7 bg-flood' : 'left-1 bg-chalk-faint'}`}
      />
    </button>
  )
}

/** A page heading in stadium type, with a chalk line under it. */
export function PageTitle({ children, kicker, right }: { children: ReactNode; kicker?: ReactNode; right?: ReactNode }) {
  return (
    <header className="mb-5 animate-rise">
      {kicker && <p className="mb-1 text-xs font-semibold uppercase tracking-[0.18em] text-chalk-dim">{kicker}</p>}
      <div className="flex items-end justify-between gap-3">
        <h1 className="font-display text-[2.6rem] leading-[0.9] font-extrabold uppercase">{children}</h1>
        {right}
      </div>
      <div className="chalk-rule mt-4" />
    </header>
  )
}

// --- Bottom sheet ---

export function Sheet({ open, onClose, title, children }: {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
}) {
  const titleId = useId()
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    const previous = document.activeElement as HTMLElement | null
    panel.current?.focus()
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
      previous?.focus?.()
    }
  }, [open, onClose])

  if (!open) return null
  // Portal to <body>: an animated (transformed) ancestor would otherwise become the containing block of this
  // fixed-position overlay and push it off screen.
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center sm:items-center">
      <button aria-label={s.common.close} className="absolute inset-0 animate-fade bg-black/60 backdrop-blur-[2px]" onClick={onClose} />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="relative max-h-[88dvh] w-full max-w-lg animate-sheet overflow-y-auto rounded-t-3xl border border-line bg-pitch-2 px-5 pt-3 pb-[max(1.5rem,env(safe-area-inset-bottom))] shadow-2xl outline-none sm:rounded-3xl"
      >
        <div className="mx-auto mb-3 h-1 w-10 rounded-full bg-chalk-faint/50 sm:hidden" />
        <div className="mb-4 flex items-center justify-between gap-3">
          <h2 id={titleId} className="font-display text-2xl font-bold uppercase">{title}</h2>
          <button onClick={onClose} aria-label={s.common.close} className="rounded-full p-2 text-chalk-dim hover:text-chalk">
            <X className="size-5" />
          </button>
        </div>
        {children}
      </div>
    </div>,
    document.body,
  )
}

// --- Toasts ---

interface Toast {
  id: number
  text: string
  tone: 'ok' | 'error'
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const show = useCallback((text: string, tone: 'ok' | 'error' = 'ok') => {
    const id = Date.now() + Math.random()
    // At most two on screen, so quick owner actions never bury the schedule.
    setToasts((t) => [...t.slice(-1), { id, text, tone }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3800)
  }, [])
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div aria-live="polite" className="pointer-events-none fixed inset-x-0 top-3 z-[60] flex flex-col items-center gap-2 px-4">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`animate-rise rounded-xl border px-4 py-3 text-sm font-medium shadow-xl backdrop-blur ${
              t.tone === 'ok' ? 'border-flood/40 bg-turf/95 text-chalk' : 'border-red/40 bg-[#2a1210]/95 text-chalk'
            }`}
          >
            {t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
