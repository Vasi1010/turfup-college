import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarX, ChevronLeft, ChevronRight, Footprints, Globe, Minus, Phone, Plus, Repeat } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Button, Chip, EmptyState, ErrorState, InlineError, Sheet, Spinner } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api, serverNow } from '../../lib/api'
import { useAppConfig } from '../../lib/appConfig'
import { UNNAMED_ENTRY } from '../../lib/config'
import { errorMessage } from '../../lib/errors'
import { addDays, clock, currentOperatingDate, dateParts, money, timeRange } from '../../lib/format'
import type {
  BalanceReceipt,
  BookingView,
  CancelReason,
  CancellationResult,
  CourtColumn,
  Regulars,
  Schedule,
  ScheduleBooking,
  ScheduleSlot,
} from '../../lib/types'
import { s } from '../../strings'
import { UpiInbox } from './UpiInbox'
import { VenuePicker } from './VenuePicker'
import { useOwnerVenue } from './useOwnerVenue'

/** The free slot the owner tapped: which court, and its index in that court's slots. */
interface EntryTarget {
  court: CourtColumn
  index: number
}

export function OwnerToday() {
  const { venues, venue, select } = useOwnerVenue()
  const upiMode = useAppConfig().data?.paymentsMode === 'UPI'
  const [params, setParams] = useSearchParams()
  const [entry, setEntry] = useState<EntryTarget | null>(null)
  const [acting, setActing] = useState<ScheduleBooking | null>(null)

  const today = venue ? currentOperatingDate(serverNow(), venue.openMinute) : null
  const date = params.get('date') ?? today
  const schedule = useQuery({
    queryKey: ['schedule', venue?.id, date],
    queryFn: () => api<Schedule>(`/api/owner/venues/${venue!.id}/schedule`, { query: { date: date! } }),
    enabled: !!venue && !!date,
    refetchInterval: 30_000,
  })

  if (venues.isPending) return <Spinner />
  if (venues.isError) return <ErrorState message={errorMessage(venues.error)} onRetry={() => venues.refetch()} />
  if (!venue) {
    return (
      <EmptyState
        title={s.owner.empty}
        action={<Link to="/owner/venue/new"><Button>{s.owner.createVenue}</Button></Link>}
      />
    )
  }

  const setDate = (d: string) => {
    const next = new URLSearchParams(params)
    next.set('date', d)
    setParams(next, { replace: true })
  }
  const data = schedule.data
  const isToday = data ? data.date === data.currentOperatingDate : date === today
  const p = dateParts(date!)
  const byId = new Map(data?.bookings.map((b) => [b.id, b]))
  // Balance can still be owed after a booking is marked COMPLETED (a day after the slot).
  const confirmed = data?.bookings.filter((b) => b.status === 'CONFIRMED' || b.status === 'COMPLETED') ?? []
  const due = confirmed.reduce((sum, b) => sum + b.balanceDue, 0)
  const allSlots = data?.courts.flatMap((c) => c.slots) ?? []
  const bookedSlots = allSlots.filter((sl) => sl.bookingId !== null).length
  const courtName = (id: number) => data?.courts.find((c) => c.courtId === id)?.name ?? ''
  const multi = (data?.courts.length ?? 0) > 1

  return (
    <div>
      <VenuePicker venues={venues.data} value={venue.id} onChange={select} />

      <header className="mb-4 animate-rise">
        <p className="text-xs font-semibold uppercase tracking-[0.2em] text-chalk-dim">{venue.name}</p>
        <div className="flex items-end justify-between gap-3">
          <h1 className="font-display text-[2.8rem] leading-[0.9] font-black uppercase">
            {isToday ? s.owner.today : `${p.weekday} ${p.day} ${p.month}`}
          </h1>
          <div className="flex items-center gap-1">
            <IconButton label="Previous day" onClick={() => setDate(addDays(date!, -1))}><ChevronLeft className="size-5" /></IconButton>
            {!isToday && today && (
              <button onClick={() => setDate(today)} className="min-h-10 rounded-full border border-line px-3 text-xs font-semibold uppercase">
                {s.common.today}
              </button>
            )}
            <IconButton label="Next day" onClick={() => setDate(addDays(date!, 1))}><ChevronRight className="size-5" /></IconButton>
          </div>
        </div>
      </header>

      {!venue.approved && (
        <p className="mb-4 rounded-xl border border-amber/40 bg-amber/10 px-4 py-3 text-sm text-amber">{s.owner.pendingApproval}</p>
      )}
      {upiMode && !venue.upiId && (
        <div role="status" className="mb-4 rounded-xl border border-amber/40 bg-amber/10 px-4 py-3 text-sm">
          <p className="font-semibold text-amber">{s.owner.upiMissing}</p>
          <p className="mt-1 text-chalk-dim">{s.owner.upiMissingHint}</p>
          <Link to="/owner/venue" className="mt-2 inline-block font-semibold text-flood underline">{s.setup.upiTitle}</Link>
        </div>
      )}
      {!upiMode && venue.approved && !venue.onlinePayments && (
        <div role="status" className="mb-4 rounded-xl border border-red/40 bg-red/10 px-4 py-3 text-sm text-red">
          <p className="font-semibold">{s.owner.onlinePaused}</p>
          <p className="mt-1 text-chalk-dim">{s.owner.onlinePausedHint(venue.razorpayAccountStatus)}</p>
        </div>
      )}
      {upiMode && <UpiInbox venueId={venue.id} />}

      {data && (
        <div className="mb-4 grid grid-cols-2 gap-2 animate-rise" style={{ animationDelay: '40ms' }}>
          <Stat label={s.owner.statBooked} value={`${bookedSlots}/${allSlots.length}`} />
          <Stat label={s.owner.statDue} value={money(due)} accent={due > 0} />
        </div>
      )}

      {data && data.recurringSkips.length > 0 && (
        <div className="mb-4 rounded-2xl border border-amber/40 bg-amber/10 p-4 text-sm animate-rise">
          <p className="flex items-center gap-2 font-semibold text-amber">
            <CalendarX className="size-4" aria-hidden /> {s.recurring.skipTitle}
          </p>
          <ul className="mt-2 space-y-1">
            {data.recurringSkips.map((k) => (
              <li key={`${k.recurringId}-${k.startAt}`}>
                {s.recurring.skipLine(k.playerName, timeRange(k.startAt, k.endAt), s.recurring.skipReason[k.reason])}
                {multi && <> · {courtName(k.courtId)}</>}
                {' '}
                <a href={`tel:${k.playerPhone}`} className="text-flood underline">{s.common.call}</a>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-chalk-dim">{s.recurring.skipHint}</p>
        </div>
      )}

      {schedule.isPending && <Spinner />}
      {schedule.isError && <ErrorState message={errorMessage(schedule.error)} onRetry={() => schedule.refetch()} />}
      {data && allSlots.length === 0 && <EmptyState title={s.owner.closedDay} />}

      {data && !multi && data.courts[0] && (
        <CourtList court={data.courts[0]} byId={byId} isToday={isToday}
                   onFree={(index) => setEntry({ court: data.courts[0], index })} onBooking={setActing} />
      )}
      {data && multi && (
        <CourtGrid schedule={data} byId={byId} isToday={isToday}
                   onFree={(court, index) => setEntry({ court, index })} onBooking={setActing} />
      )}

      {data && (
        <QuickEntry
          venueId={venue.id}
          target={entry}
          showCourt={multi}
          onClose={() => setEntry(null)}
        />
      )}
      <BookingActions booking={acting} courtName={multi && acting ? courtName(acting.courtId) : null}
                      onClose={() => setActing(null)} />
    </div>
  )
}

/** One court: the slots as a list, a booking spanning several slots shown once. */
function CourtList({ court, byId, isToday, onFree, onBooking }: {
  court: CourtColumn
  byId: Map<number, ScheduleBooking>
  isToday: boolean
  onFree: (index: number) => void
  onBooking: (b: ScheduleBooking) => void
}) {
  const nowMs = serverNow()
  const nowIndex = isToday ? court.slots.findIndex((sl) => new Date(sl.end).getTime() > nowMs) : -1
  return (
    <ol className="relative space-y-1.5">
      {court.slots.map((slot, i) => {
        const booking = slot.bookingId !== null ? byId.get(slot.bookingId) : undefined
        const startsHere = booking && new Date(booking.startAt).getTime() === new Date(slot.start).getTime()
        const continued = booking && !startsHere && court.slots[i - 1]?.bookingId === slot.bookingId
        return (
          <li key={slot.start}>
            {i === nowIndex && <NowLine />}
            {booking ? (
              continued ? null : <BookingBlock booking={booking} onClick={() => onBooking(booking)} />
            ) : (
              <FreeSlot slot={slot} past={new Date(slot.end).getTime() <= nowMs} onClick={() => onFree(i)} />
            )}
          </li>
        )
      })}
    </ol>
  )
}

/** Pixels per minute in the multi-court grid: a 30-minute slot stays a comfortable 48 px tap target. */
const PX_PER_MIN = 1.6

/**
 * Several courts: a sticky time column and one column per court, positioned by time so courts with different
 * slot lengths line up. Scrolls sideways when the courts do not fit (phones).
 */
function CourtGrid({ schedule: d, byId, isToday, onFree, onBooking }: {
  schedule: Schedule
  byId: Map<number, ScheduleBooking>
  isToday: boolean
  onFree: (court: CourtColumn, index: number) => void
  onBooking: (b: ScheduleBooking) => void
}) {
  const starts = d.courts.flatMap((c) => c.slots.map((sl) => new Date(sl.start).getTime()))
  const ends = d.courts.flatMap((c) => c.slots.map((sl) => new Date(sl.end).getTime()))
  const nowLabel = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    nowLabel.current?.scrollIntoView({ block: 'center' })
  }, [d.date])
  if (!starts.length) return null
  const dayStart = Math.min(...starts)
  const dayEnd = Math.max(...ends)
  const top = (ms: number) => ((ms - dayStart) / 60_000) * PX_PER_MIN
  const height = top(dayEnd)
  const nowMs = serverNow()
  const showNow = isToday && nowMs > dayStart && nowMs < dayEnd
  const hours: number[] = []
  // Whole IST hours (UTC+5:30), not whole UTC hours.
  const IST_MS = 330 * 60_000
  for (let t = Math.ceil((dayStart + IST_MS) / 3_600_000) * 3_600_000 - IST_MS; t < dayEnd; t += 3_600_000) hours.push(t)

  return (
    <div className="-mx-4 overflow-x-auto px-4 pb-2 [scrollbar-width:thin]">
      <div className="grid gap-x-1.5" style={{ gridTemplateColumns: `3rem repeat(${d.courts.length}, minmax(8.5rem, 1fr))` }}>
        {/* Headers scroll with the grid: inside a sideways-scrolling box they cannot stick to the page. */}
        <div className="sticky left-0 z-10 bg-pitch" />
        {d.courts.map((c) => (
          <div key={c.courtId} className="mb-2 border-b border-line pb-2">
            <p className="truncate text-sm font-semibold">{c.name}</p>
            <p className="truncate text-[0.7rem] text-chalk-dim">
              {c.sports.map((sp) => s.sports[sp] ?? sp).join(' · ')}
              {!c.active && <> · <span className="text-amber">{s.owner.paused}</span></>}
            </p>
          </div>
        ))}

        <div className="sticky left-0 z-[5] bg-pitch" style={{ height }}>
          <div className="relative h-full">
            {hours.map((t) => (
              <span key={t} className={`num absolute text-xs text-chalk-faint ${t > dayStart ? '-translate-y-1/2' : ''}`} style={{ top: top(t) }}>{clock(t)}</span>
            ))}
            {showNow && (
              <span ref={nowLabel} aria-label={s.owner.now} style={{ top: top(nowMs) }}
                    className="num absolute z-10 -translate-y-1/2 scroll-mt-24 rounded-full bg-red px-1.5 text-[0.65rem] font-bold text-pitch">
                {clock(nowMs)}
              </span>
            )}
          </div>
        </div>

        {d.courts.map((c) => (
          <div key={c.courtId} role="group" aria-label={c.name} className="relative" style={{ height }}>
            {c.slots.map((slot, i) => {
              const booking = slot.bookingId !== null ? byId.get(slot.bookingId) : undefined
              const start = new Date(slot.start).getTime()
              const end = new Date(slot.end).getTime()
              if (booking) {
                const startsHere = new Date(booking.startAt).getTime() === start
                const continued = !startsHere && c.slots[i - 1]?.bookingId === slot.bookingId
                if (continued) return null
                const bEnd = Math.min(new Date(booking.endAt).getTime(), dayEnd)
                return (
                  <div key={slot.start} className="absolute inset-x-0" style={{ top: top(start), height: top(bEnd) - top(start) - 4 }}>
                    <BookingBlock booking={booking} compact onClick={() => onBooking(booking)} />
                  </div>
                )
              }
              return (
                <div key={slot.start} className="absolute inset-x-0" style={{ top: top(start), height: top(end) - top(start) - 4 }}>
                  <FreeSlot slot={slot} compact past={end <= nowMs} onClick={() => onFree(c, i)} />
                </div>
              )
            })}
            {showNow && <div className="pointer-events-none absolute inset-x-0 z-[6] h-0.5 bg-red/70" style={{ top: top(nowMs) }} />}
          </div>
        ))}
      </div>
    </div>
  )
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button aria-label={label} onClick={onClick} className="flex size-10 items-center justify-center rounded-full border border-line hover:border-chalk-faint">
      {children}
    </button>
  )
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div className="rounded-2xl border border-line bg-pitch-2/80 px-4 py-3">
      <p className="text-[0.65rem] font-semibold uppercase tracking-[0.18em] text-chalk-dim">{label}</p>
      <p className={`num text-3xl leading-tight font-extrabold ${accent ? 'text-amber' : ''}`}>{value}</p>
    </div>
  )
}

function NowLine() {
  const line = useRef<HTMLDivElement>(null)
  useEffect(() => {
    line.current?.scrollIntoView({ block: 'center' })
  }, [])
  return (
    <div ref={line} className="my-2 flex scroll-mt-24 items-center gap-2" aria-label={s.owner.now}>
      <span className="rounded-full bg-red px-2 py-0.5 text-[0.65rem] font-bold uppercase tracking-wider text-pitch">
        {s.owner.now} {clock(serverNow())}
      </span>
      <span className="h-0.5 flex-1 bg-red/70" />
    </div>
  )
}

function FreeSlot({ slot, past, compact, onClick }: {
  slot: ScheduleSlot
  past: boolean
  /** Grid cell: fills its box, time and a plus only. */
  compact?: boolean
  onClick: () => void
}) {
  if (compact) {
    return (
      <button
        onClick={onClick}
        className={`flex h-full w-full items-start justify-between rounded-lg border border-dashed px-2 py-1 text-left transition hover:border-flood hover:bg-turf ${
          past ? 'border-line/60 text-chalk-faint' : 'border-chalk/25 bg-pitch-2/50'
        }`}
      >
        <span className="num text-sm font-semibold">{timeRange(slot.start, slot.end)}</span>
        <span className="sr-only">{s.owner.add}</span>
        <Plus className="size-4 shrink-0 text-flood" aria-hidden />
      </button>
    )
  }
  return (
    <button
      onClick={onClick}
      className={`flex min-h-14 w-full items-center justify-between rounded-xl border border-dashed px-4 text-left transition hover:border-flood hover:bg-turf ${
        past ? 'border-line/60 text-chalk-faint' : 'border-chalk/30 bg-pitch-2/50'
      }`}
    >
      <span className="num text-xl font-bold">{timeRange(slot.start, slot.end)}</span>
      <span className="flex items-center gap-3">
        {slot.price !== null && <span className="num text-lg text-chalk-dim">{money(slot.price)}</span>}
        <span className="flex items-center gap-1 rounded-full bg-turf-2 px-3 py-1 text-xs font-semibold uppercase text-flood">
          <Plus className="size-3.5" aria-hidden /> {s.owner.add}
        </span>
      </span>
    </button>
  )
}

function SourceIcon({ source }: { source: ScheduleBooking['source'] }) {
  const Icon = source === 'WALKIN' ? Footprints : source === 'PHONE' ? Phone : Globe
  const label = source === 'WALKIN' ? s.owner.walkIn : source === 'PHONE' ? s.owner.phoneBooking : s.owner.online
  return (
    <span className="flex items-center gap-1 text-xs text-chalk-dim">
      <Icon className="size-3.5" aria-hidden /> {label}
    </span>
  )
}

function BookingBlock({ booking: b, compact, onClick }: {
  booking: ScheduleBooking
  /** Grid cell: fills its box; name, time and what is owed. */
  compact?: boolean
  onClick: () => void
}) {
  const held = b.status === 'HELD'
  const noShow = b.status === 'NO_SHOW'
  const look = held ? 'hatch border-amber/40' : noShow ? 'border-red/30 bg-red/5' : 'border-flood/30 bg-gradient-to-r from-turf-2 to-turf hover:border-flood'
  if (compact) {
    const settled = b.status === 'CONFIRMED' || b.status === 'COMPLETED'
    return (
      <button onClick={onClick} className={`h-full w-full overflow-hidden rounded-lg border px-2 py-1 text-left transition ${look}`}>
        <p className="num truncate text-xs text-chalk-dim">{timeRange(b.startAt, b.endAt)}</p>
        <p className="flex items-center gap-1 truncate text-sm font-semibold">
          {b.customerName}
          {b.recurringId !== null && <Repeat className="size-3.5 shrink-0 text-flood" aria-label={s.recurring.badge} />}
        </p>
        {held && (
          <p className="text-[0.65rem] font-semibold uppercase text-amber">
            {b.paymentClaimedAt ? s.owner.checkPayment : s.owner.held}
          </p>
        )}
        {noShow && <p className="text-[0.65rem] font-semibold uppercase text-red">{s.owner.noShowChip}</p>}
        {settled && (b.balanceDue > 0
          ? <p className="num text-xs text-amber">{s.owner.balanceDue(money(b.balanceDue))}</p>
          : <p className="text-xs text-ok">{s.owner.balancePaid(b.balanceMethod ?? '')}</p>)}
      </button>
    )
  }
  return (
    <button
      onClick={onClick}
      className={`w-full rounded-xl border p-4 text-left transition ${look}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="num text-xl font-bold">{timeRange(b.startAt, b.endAt)}</p>
          <p className="flex items-center gap-1.5 truncate text-lg font-semibold">
            {b.customerName}
            {b.recurringId !== null && <Repeat className="size-4 shrink-0 text-flood" aria-label={s.recurring.badge} />}
          </p>
          {UNNAMED_ENTRY[b.source] !== b.customerName && <SourceIcon source={b.source} />}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          {held && <Chip tone="amber">{b.paymentClaimedAt ? s.owner.checkPayment : s.owner.held}</Chip>}
          {noShow && <Chip tone="red">{s.owner.noShowChip}</Chip>}
          {b.status === 'COMPLETED' && <Chip tone="neutral">{s.bookings.status.COMPLETED}</Chip>}
          {(b.status === 'CONFIRMED' || b.status === 'COMPLETED') && (b.balanceDue > 0
            ? <Chip tone="amber">{s.owner.balanceDue(money(b.balanceDue))}</Chip>
            : <Chip tone="ok">{s.owner.balancePaid(b.balanceMethod ?? '')}</Chip>)}
          <span className="num text-lg text-chalk-dim">{money(b.totalPrice)}</span>
        </div>
      </div>
    </button>
  )
}

/** Two taps: tap a free slot, tap Walk-in. Name, phone, regular and length are optional. */
function QuickEntry({ venueId, target, showCourt, onClose }: {
  venueId: number
  target: EntryTarget | null
  showCourt: boolean
  onClose: () => void
}) {
  const slots = target?.court.slots ?? []
  const index = target?.index ?? null
  const queryClient = useQueryClient()
  const toast = useToast()
  const [count, setCount] = useState(1)
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [playerId, setPlayerId] = useState<number | null>(null)
  const open = index !== null

  const regulars = useQuery({
    queryKey: ['regulars', venueId],
    queryFn: () => api<Regulars>(`/api/owner/venues/${venueId}/customers`),
    enabled: open,
  })

  const reset = () => {
    setCount(1)
    setName('')
    setPhone('')
    setPlayerId(null)
    entry.reset()
  }
  const close = () => {
    reset()
    onClose()
  }

  const run = open ? freeRun(slots, index!, count) : []
  const canExtend = open && freeRun(slots, index!, count + 1).length > count

  const entry = useMutation({
    mutationFn: (source: 'WALKIN' | 'PHONE') =>
      api<BookingView>(`/api/owner/courts/${target!.court.courtId}/bookings`, {
        method: 'POST',
        body: {
          startAt: run[0].start,
          endAt: run[run.length - 1].end,
          source,
          playerId,
          walkinName: playerId ? null : name,
          walkinPhone: playerId ? null : phone,
        },
      }),
    onSuccess: (b) => {
      queryClient.invalidateQueries({ queryKey: ['schedule', venueId] })
      toast(s.owner.entryAdded(b.walkinName ?? name ?? '', timeRange(b.startAt, b.endAt)))
      close()
    },
  })

  return (
    <Sheet open={open} onClose={close} title={showCourt && target ? `${s.owner.entryTitle} · ${target.court.name}` : s.owner.entryTitle}>
      {open && run.length > 0 && (
        <>
          <div className="mb-4 flex items-center justify-between rounded-2xl border border-line bg-pitch p-4">
            <div>
              <p className="num text-3xl leading-none font-extrabold">{timeRange(run[0].start, run[run.length - 1].end)}</p>
              <p className="num mt-1 text-xl text-flood">{money(run.reduce((sum, sl) => sum + (sl.price ?? 0), 0))}</p>
            </div>
            <div className="flex gap-2">
              <button aria-label={s.venue.removeHour} disabled={count <= 1} onClick={() => setCount(count - 1)}
                      className="flex size-11 items-center justify-center rounded-full border border-line disabled:opacity-30">
                <Minus className="size-4" />
              </button>
              <button aria-label={s.venue.addHour} disabled={!canExtend} onClick={() => setCount(count + 1)}
                      className="flex size-11 items-center justify-center rounded-full border border-line disabled:opacity-30">
                <Plus className="size-4" />
              </button>
            </div>
          </div>

          {playerId === null && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="label" htmlFor="entry-name">{s.owner.entryName}</label>
                <input id="entry-name" className="field" placeholder={s.owner.entryNamePlaceholder} value={name}
                       onChange={(e) => setName(e.target.value)} maxLength={120} />
              </div>
              <div>
                <label className="label" htmlFor="entry-phone">{s.owner.entryPhone}</label>
                <input id="entry-phone" className="field" inputMode="tel" value={phone} maxLength={20}
                       onChange={(e) => setPhone(e.target.value)} placeholder={s.common.optional} />
              </div>
            </div>
          )}

          {!!regulars.data?.customers.length && (
            <div className="mt-4">
              <p className="label">{s.owner.pickRegular}</p>
              <div className="flex flex-wrap gap-2">
                {regulars.data.customers.slice(0, 8).map((r) => (
                  <button
                    key={r.playerId}
                    onClick={() => setPlayerId(playerId === r.playerId ? null : r.playerId)}
                    aria-pressed={playerId === r.playerId}
                    className={`min-h-10 rounded-full border px-3 text-sm font-semibold ${
                      playerId === r.playerId ? 'border-flood bg-flood text-flood-ink' : 'border-line text-chalk'
                    }`}
                  >
                    {r.name} <span className="num text-base opacity-70">·{r.phone.slice(-4)}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="mt-5 space-y-2">
            <InlineError message={entry.isError ? errorMessage(entry.error) : null} />
            <div className="grid grid-cols-2 gap-2">
              <Button busy={entry.isPending && entry.variables === 'WALKIN'} disabled={entry.isPending} onClick={() => entry.mutate('WALKIN')}>
                <Footprints className="size-4" aria-hidden /> {s.owner.walkIn}
              </Button>
              <Button variant="secondary" busy={entry.isPending && entry.variables === 'PHONE'} disabled={entry.isPending} onClick={() => entry.mutate('PHONE')}>
                <Phone className="size-4" aria-hidden /> {s.owner.phoneBooking}
              </Button>
            </div>
          </div>
        </>
      )}
    </Sheet>
  )
}

/** Up to `count` back-to-back free slots starting at `start`. */
function freeRun(slots: ScheduleSlot[], start: number, count: number): ScheduleSlot[] {
  const run: ScheduleSlot[] = []
  for (let i = start; i < slots.length && run.length < count; i++) {
    if (slots[i].bookingId !== null) break
    if (run.length && slots[i].start !== run[run.length - 1].end) break
    run.push(slots[i])
  }
  return run
}

const REASONS: CancelReason[] = ['RAIN', 'MAINTENANCE', 'OTHER']

function BookingActions({ booking: b, courtName, onClose }: {
  booking: ScheduleBooking | null
  /** Shown when the venue has several courts. */
  courtName: string | null
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [cancelling, setCancelling] = useState(false)
  const [reason, setReason] = useState<CancelReason | null>(null)
  const [note, setNote] = useState('')

  const done = (message: string) => {
    queryClient.invalidateQueries({ queryKey: ['schedule'] })
    queryClient.invalidateQueries({ queryKey: ['regulars'] })
    toast(message)
    close()
  }
  const close = () => {
    setCancelling(false)
    setReason(null)
    setNote('')
    balance.reset()
    noShow.reset()
    cancel.reset()
    onClose()
  }

  const balance = useMutation({
    mutationFn: (method: 'CASH' | 'UPI') =>
      api<BalanceReceipt>(`/api/owner/bookings/${b!.id}/balance`, { method: 'POST', body: { method } }),
    onSuccess: (r) => done(s.owner.receivedToast(money(r.amount), r.method === 'CASH' ? s.owner.cash : s.owner.upi)),
  })
  const noShow = useMutation({
    mutationFn: () => api<BookingView>(`/api/owner/bookings/${b!.id}/no-show`, { method: 'POST' }),
    onSuccess: () => done(s.owner.noShowToast),
  })
  const cancel = useMutation({
    mutationFn: () =>
      api<CancellationResult>(`/api/owner/bookings/${b!.id}/cancel`, { method: 'POST', body: { reason, note } }),
    onSuccess: (r) => done(`${s.bookings.cancelled} ${r.terms.message}.`),
  })

  const error = [balance, noShow, cancel].find((m) => m.isError)?.error
  const started = b ? new Date(b.startAt).getTime() <= serverNow() : false
  const noteMissing = reason === 'OTHER' && !note.trim()

  return (
    <Sheet open={!!b} onClose={close} title={b?.customerName ?? ''}>
      {b && (
        <div className="space-y-4">
          <div className="flex items-center justify-between rounded-2xl border border-line bg-pitch p-4">
            <div>
              <p className="num text-3xl leading-none font-extrabold">{timeRange(b.startAt, b.endAt)}</p>
              {courtName && <p className="text-sm text-chalk-dim">{courtName}</p>}
              <SourceIcon source={b.source} />
            </div>
            <div className="text-right">
              <p className="num text-2xl font-bold">{money(b.totalPrice)}</p>
              {b.advanceRequired > 0 && <p className="text-xs text-chalk-dim">{s.owner.advancePaid(money(b.advanceRequired))}</p>}
            </div>
          </div>

          {b.customerPhone && (
            <a href={`tel:${b.customerPhone}`} className="flex min-h-11 items-center gap-2 rounded-xl border border-line px-4 text-sm">
              <Phone className="size-4 text-flood" aria-hidden /> {s.common.call} {b.customerPhone}
            </a>
          )}

          <InlineError message={error ? errorMessage(error) : null} />

          {(b.status === 'CONFIRMED' || b.status === 'COMPLETED') && !cancelling && (
            <>
              {b.balanceDue > 0 && (
                <div>
                  <p className="label">{s.owner.received} · <span className="num text-base text-amber">{money(b.balanceDue)}</span></p>
                  <div className="grid grid-cols-2 gap-2">
                    <Button busy={balance.isPending && balance.variables === 'CASH'} disabled={balance.isPending} onClick={() => balance.mutate('CASH')}>{s.owner.cash}</Button>
                    <Button busy={balance.isPending && balance.variables === 'UPI'} disabled={balance.isPending} onClick={() => balance.mutate('UPI')}>{s.owner.upi}</Button>
                  </div>
                </div>
              )}
              {b.status === 'CONFIRMED' && (
                <>
                  <div className="grid grid-cols-2 gap-2">
                    <Button variant="secondary" disabled={!started} busy={noShow.isPending} onClick={() => noShow.mutate()}>{s.owner.markNoShow}</Button>
                    <Button variant="danger" onClick={() => setCancelling(true)}>{s.owner.cancelBooking}</Button>
                  </div>
                  {!started && <p className="text-xs text-chalk-faint">{s.owner.noShowHint}</p>}
                </>
              )}
            </>
          )}

          {cancelling && (
            <div className="space-y-3">
              <p className="label">{s.owner.cancelReason}</p>
              <div className="grid grid-cols-3 gap-2">
                {REASONS.map((r) => (
                  <button
                    key={r}
                    aria-pressed={reason === r}
                    onClick={() => setReason(r)}
                    className={`min-h-11 rounded-xl border text-sm font-semibold ${reason === r ? 'border-flood bg-flood text-flood-ink' : 'border-line'}`}
                  >
                    {s.cancelReason[r]}
                  </button>
                ))}
              </div>
              <div>
                <label className="label" htmlFor="cancel-note">
                  {s.owner.cancelNote}{reason !== 'OTHER' && ` (${s.common.optional})`}
                </label>
                <textarea id="cancel-note" className="field min-h-20" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
                {noteMissing && <p className="mt-1 text-xs text-amber">{s.owner.cancelNoteRequired}</p>}
              </div>
              <p className="text-xs text-chalk-dim">{s.owner.cancelHint}</p>
              <Button variant="danger" className="w-full" disabled={!reason || noteMissing} busy={cancel.isPending} onClick={() => cancel.mutate()}>
                {s.owner.cancelConfirm}
              </Button>
              <Button variant="quiet" className="w-full" onClick={() => setCancelling(false)}>{s.common.back}</Button>
            </div>
          )}
        </div>
      )}
    </Sheet>
  )
}
