import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Timer } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useTimeLeft } from '../../components/useTimeLeft'
import { Button, Chip, EmptyState, ErrorState, InlineError, PageTitle, Sheet, Spinner } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { ApiError, api, serverNow } from '../../lib/api'
import { errorCode, errorMessage } from '../../lib/errors'
import { dateParts, dayAndTime, istDate, mmss, money, timeRange } from '../../lib/format'
import type { CancellationResult, CancellationTerms, MyBooking } from '../../lib/types'
import { s } from '../../strings'

type Tab = 'upcoming' | 'past'

export function MyBookings() {
  const [tab, setTab] = useState<Tab>('upcoming')
  const [cancelling, setCancelling] = useState<MyBooking | null>(null)
  const bookings = useQuery({ queryKey: ['my-bookings'], queryFn: () => api<MyBooking[]>('/api/bookings/mine') })

  const now = serverNow()
  const all = bookings.data ?? []
  const upcoming = all
    .filter((b) => (b.status === 'HELD' || b.status === 'CONFIRMED') && new Date(b.endAt).getTime() > now)
    .sort((a, b) => a.startAt.localeCompare(b.startAt))
  const past = all.filter((b) => !upcoming.includes(b))
  const shown = tab === 'upcoming' ? upcoming : past

  return (
    <div>
      <PageTitle>{s.bookings.title}</PageTitle>

      <div className="mb-5 grid grid-cols-2 rounded-xl border border-line bg-pitch-2 p-1" role="tablist">
        {(['upcoming', 'past'] as Tab[]).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={`min-h-11 rounded-lg text-sm font-semibold transition ${tab === t ? 'bg-turf-2 text-chalk' : 'text-chalk-dim'}`}
          >
            {t === 'upcoming' ? s.bookings.upcoming : s.bookings.past}
            <span className="num ml-2 text-base text-chalk-faint">{(t === 'upcoming' ? upcoming : past).length}</span>
          </button>
        ))}
      </div>

      {bookings.isPending && <Spinner />}
      {bookings.isError && <ErrorState message={errorMessage(bookings.error)} onRetry={() => bookings.refetch()} />}
      {bookings.data && shown.length === 0 && (
        <EmptyState
          title={s.bookings.empty}
          hint={s.bookings.emptyHint}
          action={<Link to="/"><Button>{s.bookings.explore}</Button></Link>}
        />
      )}

      <ul className="space-y-3">
        {shown.map((b, i) => (
          <li key={b.id} className="animate-rise" style={{ animationDelay: `${i * 40}ms` }}>
            <BookingCard booking={b} onCancel={() => setCancelling(b)} />
          </li>
        ))}
      </ul>

      <CancelSheet key={cancelling?.id ?? 'none'} booking={cancelling} onClose={() => setCancelling(null)} />
    </div>
  )
}

function BookingCard({ booking: b, onCancel }: { booking: MyBooking; onCancel: () => void }) {
  const day = dateParts(istDate(b.startAt))
  const msLeft = useTimeLeft(b.status === 'HELD' ? b.holdExpiresAt : null)
  const canCancel = b.status === 'CONFIRMED' && new Date(b.startAt).getTime() > serverNow()
  const dim = b.status === 'CANCELLED' || b.status === 'NO_SHOW' || b.status === 'EXPIRED'
  const tone = b.status === 'CONFIRMED' ? 'flood' : b.status === 'HELD' ? 'amber' : b.status === 'CANCELLED' || b.status === 'NO_SHOW' ? 'red' : 'neutral'

  return (
    <article className={`flex overflow-hidden rounded-2xl border border-line bg-pitch-2/80 ${dim ? 'opacity-80' : ''}`}>
      <div className={`flex w-20 shrink-0 flex-col items-center justify-center border-r-2 border-dashed border-line py-4 ${b.status === 'CONFIRMED' ? 'bg-flood/10' : 'bg-turf/40'}`}>
        <span className="text-[0.65rem] font-semibold uppercase tracking-wider text-chalk-dim">{day.weekday}</span>
        <span className="num text-4xl leading-none font-black">{day.day}</span>
        <span className="text-[0.65rem] font-semibold uppercase text-chalk-dim">{day.month}</span>
      </div>
      <div className="min-w-0 flex-1 p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="truncate font-display text-2xl leading-none font-extrabold uppercase">{b.venueName}</p>
            <p className="truncate text-xs text-chalk-dim">{b.courtName}</p>
            <p className="num mt-1 text-xl font-bold">{timeRange(b.startAt, b.endAt)}</p>
          </div>
          <Chip tone={tone}>{s.bookings.status[b.status]}</Chip>
        </div>

        <p className="mt-2 text-sm text-chalk-dim">
          {money(b.totalPrice)}
          {b.advanceRequired > 0 && b.status === 'CONFIRMED' && <> · {s.ticket.paidAdvance(money(b.advanceRequired))}</>}
        </p>

        {b.status === 'CANCELLED' && (
          <div className="mt-2 rounded-lg bg-pitch/60 px-3 py-2 text-sm">
            <p>
              {b.cancelReason === 'RECURRING_CHANGED'
                ? s.bookings.recurringChanged
                : b.cancelledBy === 'OWNER'
                ? s.bookings.cancelledByVenue(
                    (b.cancelReason ? s.cancelReason[b.cancelReason] : '') + (b.cancelNote ? ` · ${b.cancelNote}` : ''),
                  )
                : s.bookings.cancelledByYou}
            </p>
            {b.refunded - b.refundPending > 0 && (
              <p className="mt-0.5 font-semibold text-ok">{s.bookings.refunded(money(b.refunded - b.refundPending))}</p>
            )}
            {b.refundPending > 0 && (
              <p className="mt-0.5 font-semibold text-amber">{s.bookings.refundPending(money(b.refundPending))}</p>
            )}
          </div>
        )}

        {b.status === 'HELD' && b.paymentClaimedAt && (
          <Link to={`/hold/${b.id}`} className="mt-2 block text-sm font-semibold text-amber">
            {s.bookings.waitingForVenue}
          </Link>
        )}
        {b.status === 'HELD' && !b.paymentClaimedAt && msLeft > 0 && (
          <Link to={`/hold/${b.id}`} className="mt-3 block">
            <Button className="w-full min-h-11 text-sm"><Timer className="size-4" aria-hidden /> {s.bookings.resume(mmss(msLeft))}</Button>
          </Link>
        )}
        {canCancel && (
          <Button variant="quiet" className="mt-1 -ml-5 min-h-10 text-sm" onClick={onCancel}>{s.bookings.cancel}</Button>
        )}
      </div>
    </article>
  )
}

/** Shows exactly what cancelling will refund before the player confirms. */
function CancelSheet({ booking, onClose }: { booking: MyBooking | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  // Terms from a 409 replace the quote; the sheet is keyed by booking, so this resets per booking.
  const [override, setOverride] = useState<CancellationTerms | null>(null)

  const quote = useQuery({
    queryKey: ['cancel-quote', booking?.id],
    queryFn: () => api<CancellationTerms>(`/api/bookings/${booking!.id}/cancellation`),
    enabled: !!booking,
    gcTime: 0,
  })
  const terms = override ?? quote.data ?? null
  const changed = override !== null

  const cancel = useMutation({
    mutationFn: () =>
      api<CancellationResult>(`/api/bookings/${booking!.id}/cancel`, {
        method: 'POST',
        body: { expectedRefund: terms?.refundAmount ?? 0 },
      }),
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ['my-bookings'] })
      toast(`${s.bookings.cancelled} ${r.terms.message}.`)
      onClose()
    },
    onError: (e) => {
      if (errorCode(e) === 'CANCELLATION_TERMS_CHANGED' && e instanceof ApiError) {
        setOverride(e.body?.terms as CancellationTerms)
      }
    },
  })

  const refund = terms?.outcome === 'REFUND'
  return (
    <Sheet open={!!booking} onClose={onClose} title={s.bookings.cancelTitle}>
      {booking && (
        <p className="mb-4 text-sm text-chalk-dim">
          {booking.venueName} · {booking.courtName} · {dayAndTime(booking.startAt)}
        </p>
      )}
      {quote.isPending && <Spinner />}
      {quote.isError && <InlineError message={errorMessage(quote.error)} />}
      {terms && (
        <div className={`rounded-2xl border p-4 ${refund ? 'border-flood/40 bg-flood/10' : 'border-amber/40 bg-amber/10'}`}>
          {changed && <p className="mb-2 text-sm font-semibold text-amber">{s.bookings.termsChanged}</p>}
          <p className={`font-display text-2xl leading-tight font-bold ${refund ? 'text-flood' : 'text-amber'}`}>{terms.message}</p>
          {refund && terms.fullRefundDeadline && (
            <p className="mt-1 text-xs text-chalk-dim">{s.bookings.deadline(dayAndTime(terms.fullRefundDeadline))}</p>
          )}
        </div>
      )}
      <div className="mt-5 grid gap-2">
        <InlineError message={cancel.isError && !changed ? errorMessage(cancel.error) : null} />
        <Button variant="danger" busy={cancel.isPending} disabled={!terms} onClick={() => cancel.mutate()}>
          {s.bookings.cancelConfirm}
        </Button>
        <Button variant="secondary" onClick={onClose}>{s.bookings.keep}</Button>
      </div>
    </Sheet>
  )
}
