import { mmss } from '../lib/format'
import { s } from '../strings'

/** A floodlit ring that drains as the hold runs out; amber in the last minute, red once payment can no longer start. */
export function CountdownRing({ msLeft, totalMs, pastCutoff }: { msLeft: number; totalMs: number; pastCutoff: boolean }) {
  const r = 54
  const c = 2 * Math.PI * r
  const fraction = Math.max(0, Math.min(1, msLeft / totalMs))
  const color = pastCutoff ? 'var(--color-red)' : msLeft <= 60_000 ? 'var(--color-amber)' : 'var(--color-flood)'
  return (
    <div className="relative mx-auto size-48" role="timer" aria-live="off" aria-label={`${mmss(msLeft)} ${s.hold.timeLeft}`}>
      {/* Glow as a round shadow: an SVG filter here renders a visible square on some GPUs. */}
      <div className="absolute inset-3 rounded-full transition-shadow duration-500"
           style={{ boxShadow: `0 0 36px -6px ${color}, inset 0 0 28px -10px ${color}` }} />
      <svg viewBox="0 0 128 128" className="relative size-full -rotate-90">
        <circle cx="64" cy="64" r={r} fill="none" stroke="var(--color-line)" strokeWidth="6" />
        <circle
          cx="64"
          cy="64"
          r={r}
          fill="none"
          stroke={color}
          strokeWidth="6"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - fraction)}
          style={{ transition: 'stroke-dashoffset 250ms linear, stroke 400ms' }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="num text-6xl leading-none font-extrabold" style={{ color }}>{mmss(msLeft)}</span>
        <span className="mt-1 text-xs font-semibold uppercase tracking-[0.2em] text-chalk-dim">{s.hold.timeLeft}</span>
      </div>
    </div>
  )
}
