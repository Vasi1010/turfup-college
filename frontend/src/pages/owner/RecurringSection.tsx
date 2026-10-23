import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Repeat } from 'lucide-react'
import { useState } from 'react'
import { useToast } from '../../components/toast-context'
import { Button, Chip, InlineError, Sheet, Spinner } from '../../components/ui'
import { api, serverNow } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { currentOperatingDate, dateParts, minuteLabel, minuteRange } from '../../lib/format'
import type { RecurringSaved, RecurringView, Regular, VenueView } from '../../lib/types'
import { s } from '../../strings'

/** The owner's standing slots at one venue (each on one of its courts): list, add, change, end. */
export function RecurringSection({ venue, players }: { venue: VenueView; players: Regular[] }) {
  const [editing, setEditing] = useState<RecurringView | 'new' | null>(null)
  const [ending, setEnding] = useState<RecurringView | null>(null)
  const list = useQuery({
    queryKey: ['recurring', venue.id],
    queryFn: () => api<RecurringView[]>(`/api/owner/venues/${venue.id}/recurring`),
  })
  const multi = venue.courts.length > 1

  return (
    <section className="mb-6 animate-rise">
      <div className="flex items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.2em] text-flood">
          <Repeat className="size-4" aria-hidden /> {s.recurring.title}
        </h2>
        <button onClick={() => setEditing('new')} className="flex min-h-10 items-center gap-1 rounded-full border border-line px-3 text-xs font-semibold uppercase hover:border-flood">
          <Plus className="size-3.5" aria-hidden /> {s.recurring.add}
        </button>
      </div>
      <p className="mt-1 text-sm text-chalk-dim">{s.recurring.hint}</p>

      {list.isPending && <Spinner />}
      {list.isError && <InlineError message={errorMessage(list.error)} />}
      <ul className="mt-3 space-y-2">
        {list.data?.map((r) => (
          <li key={r.id} className="rounded-2xl border border-flood/25 bg-pitch-2/80 p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-lg font-semibold">{r.playerName}</p>
                <p className="num text-xl font-bold">
                  {s.recurring.every(s.setup.days[r.dayOfWeek])} · {minuteRange(r.startMinute, r.endMinute)}
                </p>
                {multi && <p className="text-sm text-chalk-dim">{r.courtName}</p>}
                <p className="text-xs text-chalk-dim">
                  {s.recurring.since(fmtDate(r.validFrom))}
                  {r.validTo && <> · {s.recurring.till(fmtDate(r.validTo))}</>}
                </p>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  <Chip tone="flood">{s.recurring.upcoming(r.upcoming)}</Chip>
                  {r.skipped > 0 && <Chip tone="amber">{s.recurring.skipped(r.skipped)}</Chip>}
                </div>
              </div>
              <div className="flex shrink-0 flex-col gap-1">
                <Button variant="secondary" className="min-h-9 px-3 text-xs" onClick={() => setEditing(r)}>{s.recurring.edit}</Button>
                <Button variant="danger" className="min-h-9 px-3 text-xs" onClick={() => setEnding(r)}>{s.recurring.end}</Button>
              </div>
            </div>
          </li>
        ))}
      </ul>

      <RecurringSheet
        key={editing === null ? 'closed' : editing === 'new' ? 'new' : editing.id}
        venue={venue}
        players={players}
        editing={editing}
        onClose={() => setEditing(null)}
      />
      <EndSheet key={ending?.id ?? 'none'} venueId={venue.id} recurring={ending} onClose={() => setEnding(null)} />
    </section>
  )
}

function fmtDate(date: string) {
  const p = dateParts(date)
  return `${p.day} ${p.month}`
}

function RecurringSheet({ venue, players, editing, onClose }: {
  venue: VenueView
  players: Regular[]
  editing: RecurringView | 'new' | null
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const existing = editing && editing !== 'new' ? editing : null
  const [courtId, setCourtId] = useState(existing?.courtId ?? venue.courts[0]?.id)
  const court = venue.courts.find((c) => c.id === courtId) ?? venue.courts[0]
  const slotMinutes = court?.slotMinutes ?? 60
  const today = currentOperatingDate(serverNow(), venue.openMinute)
  const starts: number[] = []
  for (let m = venue.openMinute; m + slotMinutes <= venue.closeMinute; m += slotMinutes) starts.push(m)

  const [playerId, setPlayerId] = useState<number | null>(existing?.playerId ?? null)
  const [day, setDay] = useState(existing?.dayOfWeek ?? (new Date(`${today}T00:00:00Z`).getUTCDay() || 7))
  const [start, setStart] = useState(existing?.startMinute ?? (starts.includes(1200) ? 1200 : starts[0]))
  const [slots, setSlots] = useState(existing ? (existing.endMinute - existing.startMinute) / slotMinutes : 1)
  const [validFrom, setValidFrom] = useState(existing?.validFrom && existing.validFrom > today ? existing.validFrom : today)
  const [validTo, setValidTo] = useState(existing?.validTo ?? '')

  const maxSlots = Math.max(1, Math.floor((venue.closeMinute - start) / slotMinutes))
  const end = start + Math.min(slots, maxSlots) * slotMinutes

  const save = useMutation({
    mutationFn: () => {
      const body = { playerId, dayOfWeek: day, startMinute: start, endMinute: end, validFrom, validTo: validTo || null, courtId }
      return existing
        ? api<RecurringSaved>(`/api/owner/recurring/${existing.id}`, { method: 'PUT', body })
        : api<RecurringSaved>(`/api/owner/courts/${courtId}/recurring`, { method: 'POST', body })
    },
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ['recurring', venue.id] })
      queryClient.invalidateQueries({ queryKey: ['schedule', venue.id] })
      toast(s.recurring.saved(r.generated.created, r.generated.skipped))
      onClose()
    },
  })

  return (
    <Sheet open={editing !== null} onClose={onClose} title={existing ? s.recurring.editTitle : s.recurring.addTitle}>
      <div className="space-y-4">
        {existing ? (
          <p className="text-lg font-semibold">{existing.playerName}</p>
        ) : (
          <div>
            <p className="label">{s.recurring.player}</p>
            {players.length === 0 && <p className="text-sm text-chalk-dim">{s.recurring.noPlayers}</p>}
            <div className="flex flex-wrap gap-2">
              {players.map((p) => (
                <button
                  key={p.playerId}
                  aria-pressed={playerId === p.playerId}
                  onClick={() => setPlayerId(p.playerId)}
                  className={`min-h-10 rounded-full border px-3 text-sm font-semibold ${
                    playerId === p.playerId ? 'border-flood bg-flood text-flood-ink' : 'border-line'
                  }`}
                >
                  {p.name} <span className="num text-base opacity-70">·{p.phone.slice(-4)}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {venue.courts.length > 1 && (
          <div>
            <label className="label" htmlFor="rec-court">{s.recurring.court}</label>
            <select id="rec-court" className="field" value={courtId}
                    onChange={(e) => {
                      const next = venue.courts.find((c) => c.id === Number(e.target.value))!
                      setCourtId(next.id)
                      // Keep the start time if it is one of the new court's slots, else the next one that is.
                      const offset = (start - venue.openMinute) % next.slotMinutes
                      if (offset !== 0) setStart(Math.min(start + next.slotMinutes - offset, venue.closeMinute - next.slotMinutes))
                    }}>
              {venue.courts.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}

        <div>
          <p className="label">{s.recurring.day}</p>
          <div className="grid grid-cols-7 gap-1">
            {[1, 2, 3, 4, 5, 6, 7].map((d) => (
              <button
                key={d}
                aria-pressed={day === d}
                aria-label={s.setup.daysLong[d]}
                onClick={() => setDay(d)}
                className={`min-h-11 rounded-lg border text-sm font-semibold ${day === d ? 'border-flood bg-flood text-flood-ink' : 'border-line'}`}
              >
                {s.setup.days[d]}
              </button>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="label" htmlFor="rec-start">{s.recurring.start}</label>
            <select id="rec-start" className="field" value={start} onChange={(e) => setStart(Number(e.target.value))}>
              {starts.map((m) => <option key={m} value={m}>{minuteLabel(m)}</option>)}
            </select>
          </div>
          <div>
            <label className="label" htmlFor="rec-length">{s.recurring.length}</label>
            <select id="rec-length" className="field" value={Math.min(slots, maxSlots)} onChange={(e) => setSlots(Number(e.target.value))}>
              {Array.from({ length: Math.min(maxSlots, 4) }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>{s.setup.minutes(n * slotMinutes)}</option>
              ))}
            </select>
          </div>
        </div>
        <p className="num text-2xl font-bold text-flood">{s.recurring.every(s.setup.days[day])} · {minuteRange(start, end)}</p>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="label" htmlFor="rec-from">{s.recurring.from}</label>
            <input id="rec-from" type="date" className="field" min={today} value={validFrom} onChange={(e) => setValidFrom(e.target.value)} />
          </div>
          <div>
            <label className="label" htmlFor="rec-to">{s.recurring.until}</label>
            <input id="rec-to" type="date" className="field" min={validFrom} value={validTo} onChange={(e) => setValidTo(e.target.value)} />
          </div>
        </div>

        {existing && <p className="text-xs text-chalk-dim">{s.recurring.editHint}</p>}
        <InlineError message={save.isError ? errorMessage(save.error) : null} />
        <Button className="w-full" disabled={!existing && playerId === null} busy={save.isPending} onClick={() => save.mutate()}>
          {s.recurring.save}
        </Button>
      </div>
    </Sheet>
  )
}

function EndSheet({ venueId, recurring, onClose }: { venueId: number; recurring: RecurringView | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const end = useMutation({
    mutationFn: () => api<{ cancelled: number }>(`/api/owner/recurring/${recurring!.id}/end`, { method: 'POST' }),
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ['recurring', venueId] })
      queryClient.invalidateQueries({ queryKey: ['schedule', venueId] })
      toast(s.recurring.ended(r.cancelled))
      onClose()
    },
  })
  return (
    <Sheet open={!!recurring} onClose={onClose} title={s.recurring.endTitle}>
      {recurring && (
        <div className="space-y-4">
          <p className="num text-2xl font-bold">
            {recurring.playerName} · {s.recurring.every(s.setup.days[recurring.dayOfWeek])} · {minuteRange(recurring.startMinute, recurring.endMinute)}
          </p>
          <p className="text-sm text-chalk-dim">{s.recurring.endHint(recurring.upcoming, recurring.playerName)}</p>
          <InlineError message={end.isError ? errorMessage(end.error) : null} />
          <Button variant="danger" className="w-full" busy={end.isPending} onClick={() => end.mutate()}>{s.recurring.endConfirm}</Button>
          <Button variant="secondary" className="w-full" onClick={onClose}>{s.common.back}</Button>
        </div>
      )}
    </Sheet>
  )
}
