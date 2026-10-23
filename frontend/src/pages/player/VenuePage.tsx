import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, Info, MapPin, Minus, Plus } from 'lucide-react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { DateStrip } from '../../components/DateStrip'
import { Button, EmptyState, ErrorState, InlineError, Spinner } from '../../components/ui'
import { api, serverNow } from '../../lib/api'
import { errorCode, errorMessage } from '../../lib/errors'
import { currentOperatingDate, dayPart, money, timeRange, weekdayOf } from '../../lib/format'
import { useSession } from '../../lib/session'
import type { AnyCourtSlot, AnyCourtSlots, BookingView, CourtSlots, SlotView, VenueDetail } from '../../lib/types'
import { s } from '../../strings'
import { chooseCourt, rulesFor, type CourtChoice } from '../../lib/courts'

const PARTS = ['morning', 'afternoon', 'evening', 'lateNight'] as const

/**
 * A venue's courts and slots. The sport (?sport=), court (?court=any or an id), date and selection live in the URL,
 * so they survive the login detour and can be shared.
 */
export function VenuePage() {
  const { venueId } = useParams()
  const id = Number(venueId)
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()

  const venue = useQuery({ queryKey: ['venue', id], queryFn: () => api<VenueDetail>(`/api/venues/${id}`) })
  const v = venue.data
  const option = v ? (v.sports.find((o) => o.sport === params.get('sport')) ?? v.sports[0]) : undefined
  const courtsOfSport = v && option ? option.courtIds.map((cid) => v.courts.find((c) => c.id === cid)!).filter(Boolean) : []
  const choice = chooseCourt(params.get('court'), option?.anyCourt ?? false, courtsOfSport)
  const court = choice === 'any' ? null : (courtsOfSport.find((c) => c.id === choice) ?? null)

  const today = v ? currentOperatingDate(serverNow(), v.openMinute) : null
  const date = params.get('date') ?? today
  const slots = useQuery<CourtSlots | AnyCourtSlots>({
    queryKey: ['slots', id, option?.sport, choice, date],
    queryFn: () =>
      choice === 'any'
        ? api<AnyCourtSlots>(`/api/venues/${id}/slots`, { query: { sport: option!.sport, date: date! } })
        : api<CourtSlots>(`/api/courts/${choice}/slots`, { query: { date: date! } }),
    enabled: !!date && !!option && choice !== null,
    refetchInterval: 30_000,
  })

  // Selection lives in the URL, so it survives the login detour.
  const from = params.get('from')
  const count = Number(params.get('n') ?? '1')
  const list = slots.data?.slots ?? []
  const startIndex = from ? list.findIndex((sl) => sl.start === from && sl.status === 'available') : -1
  const selected = startIndex >= 0 ? contiguous(list, startIndex, count) : []

  /** Changing sport or court starts the selection over. */
  const choose = (sport: string, courtChoice: CourtChoice | null) => {
    const next = new URLSearchParams(params)
    next.set('sport', sport)
    if (courtChoice === null) next.delete('court')
    else next.set('court', String(courtChoice))
    next.delete('from')
    next.delete('n')
    setParams(next, { replace: true })
  }

  const setSelection = (start: string | null, n = 1) => {
    const next = new URLSearchParams(params)
    if (start) {
      next.set('from', start)
      next.set('n', String(n))
    } else {
      next.delete('from')
      next.delete('n')
    }
    setParams(next, { replace: true })
  }

  const hold = useMutation({
    mutationFn: () =>
      api<BookingView>('/api/bookings/holds', {
        method: 'POST',
        body: {
          ...(choice === 'any' ? { venueId: id, sport: option!.sport } : { courtId: choice }),
          startAt: selected[0].start,
          endAt: selected[selected.length - 1].end,
        },
      }),
    onSuccess: (b) => {
      queryClient.invalidateQueries({ queryKey: ['my-bookings'] })
      navigate(`/hold/${b.id}`)
    },
    onError: (e) => {
      if (errorCode(e) === 'SLOT_UNAVAILABLE') {
        setSelection(null)
        slots.refetch()
      }
    },
  })

  if (venue.isPending) return <Spinner />
  if (venue.isError) return <ErrorState message={errorMessage(venue.error)} onRetry={() => venue.refetch()} />
  const t = venue.data

  const canExtend = selected.length > 0 && extendable(list, startIndex + selected.length - 1)
  const total = selected.reduce((sum, sl) => sum + sl.price, 0)
  const loginNext = encodeURIComponent(`/venues/${id}?${params.toString()}`)
  const rules = rulesFor(court, courtsOfSport)

  return (
    <div className={selected.length ? 'pb-40' : ''}>
      <Link to="/" className="mb-3 inline-flex items-center gap-1 text-sm text-chalk-dim hover:text-chalk">
        <ChevronLeft className="size-4" aria-hidden /> {s.nav.explore}
      </Link>

      <header className="mb-5 animate-rise">
        <p className="flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.16em] text-chalk-dim">
          <MapPin className="size-3.5" aria-hidden /> {t.area}
        </p>
        <h1 className="font-display text-[2.8rem] leading-[0.9] font-black uppercase">{t.name}</h1>
        <p className="mt-1 text-sm text-chalk-dim">{t.address}</p>
      </header>

      {t.sports.length > 1 && (
        <div className="mb-3 grid auto-cols-fr grid-flow-col gap-1 rounded-xl border border-line bg-pitch-2 p-1" role="tablist" aria-label={s.venue.sport}>
          {t.sports.map((o) => (
            <button key={o.sport} role="tab" aria-selected={o.sport === option?.sport} onClick={() => choose(o.sport, null)}
                    className={`min-h-11 rounded-lg px-2 text-sm font-semibold ${o.sport === option?.sport ? 'bg-turf-2 text-chalk' : 'text-chalk-dim'}`}>
              {s.sports[o.sport] ?? o.sport}
            </button>
          ))}
        </div>
      )}

      {option && (option.anyCourt || courtsOfSport.length > 1) && (
        <div className="-mx-4 mb-4 overflow-x-auto px-4 [scrollbar-width:none]">
          <div className="flex gap-2" role="group" aria-label={s.venue.court}>
            {option.anyCourt && (
              <CourtChip label={s.venue.anyCourt} active={choice === 'any'} onClick={() => choose(option.sport, 'any')} />
            )}
            {courtsOfSport.map((c) => (
              <CourtChip key={c.id} label={c.name} active={choice === c.id} onClick={() => choose(option.sport, c.id)} />
            ))}
          </div>
        </div>
      )}
      {choice === 'any' && <p className="-mt-2 mb-4 text-xs text-chalk-faint">{s.venue.anyCourtHint}</p>}

      {!t.onlinePayments && (
        <div role="status" className="mb-4 rounded-xl border border-amber/40 bg-amber/10 px-4 py-3 text-sm text-amber">
          <p className="font-semibold">{s.venue.onlinePaused}</p>
          <p className="mt-1 text-amber/85">{s.venue.onlinePausedHint}</p>
        </div>
      )}

      <div className="mb-5 rounded-2xl border border-line bg-pitch-2/70 p-4 text-sm animate-rise" style={{ animationDelay: '60ms' }}>
        {rules ? (
          <>
            <p className="flex gap-2"><Info className="mt-0.5 size-4 shrink-0 text-flood" aria-hidden />{s.venue.advanceNote(money(rules.advanceAmount))}</p>
            <p className="mt-2 pl-6 text-chalk-dim">{s.venue.refundRule(rules.refundFullHoursBefore, t.refundsPerMonth)}</p>
            {rules.refundPolicy && <p className="mt-1 pl-6 text-xs text-chalk-faint">{rules.refundPolicy}</p>}
          </>
        ) : (
          <p className="flex gap-2"><Info className="mt-0.5 size-4 shrink-0 text-flood" aria-hidden />{s.venue.rulesVary}</p>
        )}
      </div>

      {today && (
        <DateStrip from={today} days={14} value={date!} onChange={(d) => setParams({ date: d }, { replace: true })} />
      )}

      <div className="mt-5">
        {slots.isPending && <Spinner />}
        {slots.isError && <ErrorState message={errorMessage(slots.error)} onRetry={() => slots.refetch()} />}
        {slots.data && list.length === 0 && <EmptyState title={s.venue.noSlots} hint={s.venue.noSlotsHint} />}

        {PARTS.map((part) => {
          const rows = list.map((sl, i) => ({ sl, i })).filter(({ sl }) => dayPart(sl.start, date!) === part)
          if (!rows.length) return null
          const heading = part === 'lateNight' ? `${s.venue.lateNight} · ${s.common.lateNight(weekdayOf(date!))}` : s.venue[part]
          return (
            <section key={part} className="mb-5">
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-[0.2em] text-chalk-dim">{heading}</h2>
              <ul className="space-y-1.5">
                {rows.map(({ sl, i }) => (
                  <li key={sl.start}>
                    <SlotRow
                      slot={sl}
                      freeCourts={choice === 'any' ? (sl as AnyCourtSlot).freeCourts : undefined}
                      selected={i >= startIndex && i < startIndex + selected.length && startIndex >= 0}
                      onSelect={() => {
                        if (sl.status !== 'available') return
                        // Tapping the slot right after the selection extends it; anything else starts over.
                        if (startIndex >= 0 && i === startIndex + selected.length) setSelection(from, selected.length + 1)
                        else if (startIndex === i && selected.length === 1) setSelection(null)
                        else setSelection(sl.start, 1)
                      }}
                    />
                  </li>
                ))}
              </ul>
            </section>
          )
        })}
      </div>

      {selected.length > 0 && (
        <div className="fixed inset-x-0 bottom-16 z-30 animate-sheet border-t border-flood/30 bg-pitch-2/95 pb-[env(safe-area-inset-bottom)] backdrop-blur-md">
          <div className="mx-auto max-w-2xl px-4 py-3">
            <div className="mb-3 flex items-center justify-between gap-3">
              <div>
                <p className="text-xs font-semibold uppercase tracking-[0.16em] text-chalk-dim">{s.venue.selected}</p>
                <p className="num text-2xl leading-tight font-bold">{timeRange(selected[0].start, selected[selected.length - 1].end)}</p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  aria-label={s.venue.removeHour}
                  disabled={selected.length <= 1}
                  onClick={() => setSelection(from, selected.length - 1)}
                  className="flex size-10 items-center justify-center rounded-full border border-line disabled:opacity-30"
                >
                  <Minus className="size-4" />
                </button>
                <button
                  aria-label={s.venue.addHour}
                  disabled={!canExtend}
                  onClick={() => setSelection(from, selected.length + 1)}
                  className="flex size-10 items-center justify-center rounded-full border border-line disabled:opacity-30"
                >
                  <Plus className="size-4" />
                </button>
                <p className="num ml-1 text-3xl font-extrabold text-flood">{money(total)}</p>
              </div>
            </div>
            <InlineError message={hold.isError ? errorMessage(hold.error) : null} />
            {hold.isError && errorCode(hold.error) === 'ACTIVE_HOLD_EXISTS' && (
              <Link to="/bookings" className="mt-2 block text-sm font-semibold text-flood underline">{s.bookings.title}</Link>
            )}
            {session?.role === 'PLAYER' ? (
              <Button className="mt-2 w-full" busy={hold.isPending} onClick={() => hold.mutate()}>{s.venue.hold}</Button>
            ) : !session ? (
              <Button className="mt-2 w-full" onClick={() => navigate(`/login?next=${loginNext}`)}>{s.venue.loginToHold}</Button>
            ) : null}
            <p className="mt-2 text-center text-xs text-chalk-faint">{s.venue.holdHint}</p>
          </div>
        </div>
      )}
    </div>
  )
}

function CourtChip({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={`min-h-10 shrink-0 rounded-full border px-4 text-sm font-semibold transition ${
        active ? 'border-flood bg-flood text-flood-ink' : 'border-line bg-pitch-2/70 text-chalk hover:border-chalk-faint'
      }`}
    >
      {label}
    </button>
  )
}

function SlotRow({ slot, selected, freeCourts, onSelect }: {
  slot: SlotView
  selected: boolean
  freeCourts?: number
  onSelect: () => void
}) {
  const available = slot.status === 'available'
  const base = 'flex w-full min-h-14 items-center justify-between rounded-xl border px-4 text-left transition'
  const look = selected
    ? 'border-flood bg-flood text-flood-ink shadow-[0_0_24px_-6px_rgb(214_255_61/0.7)]'
    : slot.status === 'available'
      ? 'border-dashed border-chalk/35 bg-pitch-2/60 hover:border-flood hover:bg-turf'
      : slot.status === 'held'
        ? 'hatch border-amber/40 bg-pitch-2/40 text-chalk-dim'
        : 'border-transparent bg-turf/40 text-chalk-faint'
  return (
    <button onClick={onSelect} disabled={!available} aria-pressed={selected} className={`${base} ${look}`}>
      <span>
        <span className="num block text-xl font-bold">{timeRange(slot.start, slot.end)}</span>
        {freeCourts !== undefined && available && (
          <span className={`block text-xs ${selected ? '' : 'text-chalk-faint'}`}>{s.venue.courtsFree(freeCourts)}</span>
        )}
      </span>
      <span className="flex items-center gap-3">
        <span className={`num text-xl font-bold ${!available && !selected ? 'line-through decoration-1' : ''}`}>{money(slot.price)}</span>
        <span className={`w-16 text-right text-[0.7rem] font-semibold uppercase tracking-wider ${
          selected ? '' : slot.status === 'held' ? 'text-amber' : slot.status === 'booked' ? 'text-chalk-faint' : 'text-flood'
        }`}>
          {s.venue.status[slot.status]}
        </span>
      </span>
    </button>
  )
}

/** The run of `count` back-to-back available slots starting at `start` (shorter if the run breaks). */
function contiguous(list: SlotView[], start: number, count: number): SlotView[] {
  const run = [list[start]]
  for (let i = start + 1; i < list.length && run.length < count; i++) {
    if (list[i].status !== 'available' || list[i].start !== list[i - 1].end) break
    run.push(list[i])
  }
  return run
}

function extendable(list: SlotView[], last: number): boolean {
  const next = list[last + 1]
  return !!next && next.status === 'available' && next.start === list[last].end
}
