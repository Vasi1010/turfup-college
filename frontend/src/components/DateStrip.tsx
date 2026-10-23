import { useEffect, useRef } from 'react'
import { s } from '../strings'
import { addDays, dateParts } from '../lib/format'

/** A horizontal strip of dates, scoreboard style. The selected date scrolls into view. */
export function DateStrip({ from, days, value, onChange }: {
  from: string
  days: number
  value: string
  onChange: (date: string) => void
}) {
  const selected = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    selected.current?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' })
  }, [value])

  const dates = Array.from({ length: days }, (_, i) => addDays(from, i))
  return (
    <div className="-mx-4 overflow-x-auto px-4 pb-1 [scrollbar-width:none]">
      <div className="flex gap-2" role="tablist" aria-label={s.venue.slotsFor}>
        {dates.map((d, i) => {
          const p = dateParts(d)
          const active = d === value
          const top = i === 0 ? s.common.today : i === 1 ? s.common.tomorrow : p.weekday
          return (
            <button
              key={d}
              ref={active ? selected : undefined}
              role="tab"
              aria-selected={active}
              onClick={() => onChange(d)}
              className={`flex w-[4.25rem] shrink-0 flex-col items-center rounded-2xl border py-2 transition ${
                active ? 'border-flood bg-flood text-flood-ink' : 'border-line bg-pitch-2/80 text-chalk hover:border-chalk-faint'
              }`}
            >
              <span className={`text-[0.65rem] font-semibold uppercase tracking-wider ${active ? '' : 'text-chalk-dim'}`}>{top}</span>
              <span className="num text-3xl leading-none font-bold">{p.day}</span>
              <span className={`text-[0.65rem] font-semibold uppercase ${active ? '' : 'text-chalk-dim'}`}>{p.month}</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
