import type { BallSummary } from '../lib/tournamentTypes'

/** The balls of an over as chips: a wicket red, a boundary in floodlight, a wide or no-ball amber. */
export function BallChips({ balls, label }: { balls: BallSummary[]; label: string }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5" aria-label={label}>
      {balls.map((b) => (
        <span
          key={b.deliveryId}
          className={`grid min-w-8 place-items-center rounded-full px-2 py-1 text-xs font-bold tabular-nums ${
            b.wicket ? 'bg-red text-pitch' : b.boundary ? 'bg-flood text-flood-ink' : b.legal ? 'bg-turf-2' : 'bg-amber/30'
          }`}
        >
          {b.label}
        </span>
      ))}
    </div>
  )
}
