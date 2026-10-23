import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, Copy, Loader2, Phone, RotateCcw, Smartphone } from 'lucide-react'
import { useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { CountdownRing } from '../../components/Countdown'
import { useTimeLeft } from '../../components/useTimeLeft'
import { Button, ErrorState, InlineError, Spinner } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { useAppConfig } from '../../lib/appConfig'
import { errorMessage } from '../../lib/errors'
import { dayOf, istDate, money, timeRange } from '../../lib/format'
import { openCheckout } from '../../lib/razorpay'
import type { BookingView, MyBooking, PayResult, UpiPayment } from '../../lib/types'
import { cleanUpiRef, upiLink, upiNote, upiRefValid } from '../../lib/upi'
import { s } from '../../strings'

/**
 * The hold countdown and payment, then the booked ticket. Simulated mode confirms in the pay call. Razorpay mode
 * opens Razorpay Checkout; once the player has paid, the page waits for the server to confirm the booking from
 * Razorpay's webhook (the browser never confirms a booking). UPI mode opens the player's UPI app with the venue's UPI
 * ID; the player then says "I've paid", and the page waits while the venue checks the money arrived.
 */
export function HoldPage() {
  const { bookingId } = useParams()
  const id = Number(bookingId)
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()

  const config = useAppConfig()
  const razorpay = config.data?.paymentsMode === 'RAZORPAY'
  const upiMode = config.data?.paymentsMode === 'UPI'
  // Razorpay: the player paid in Checkout and we are waiting for the webhook to confirm the booking.
  const [awaitingConfirmation, setAwaitingConfirmation] = useState(false)
  const [checkoutError, setCheckoutError] = useState<string | null>(null)

  const booking = useQuery({
    queryKey: ['booking', id],
    queryFn: () => api<MyBooking>(`/api/bookings/${id}`),
    refetchInterval: (query) => {
      const data = query.state.data
      if (data?.status !== 'HELD') return false
      if (awaitingConfirmation) return 2000
      // UPI: the venue confirms by hand, so check less often.
      return data.paymentClaimedAt ? 5000 : false
    },
  })
  const held = booking.data?.status === 'HELD'
  const msLeft = useTimeLeft(held ? booking.data!.holdExpiresAt : null)
  const msToCutoff = useTimeLeft(held ? booking.data!.payCutoffAt : null)

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['booking', id] })
    queryClient.invalidateQueries({ queryKey: ['my-bookings'] })
  }

  const pay = useMutation({
    mutationFn: async () => {
      setCheckoutError(null)
      const result = await api<PayResult>(`/api/bookings/${id}/pay`, { method: 'POST' })
      if (result.outcome === 'PAY_BY_UPI' && result.upi) {
        // Opens the UPI app on a phone; on a computer nothing happens and the UPI ID below is used instead.
        window.location.href = upiLink(result.upi)
      }
      if (result.outcome === 'AWAITING_PAYMENT' && result.checkout) {
        try {
          await openCheckout(result.checkout, { onPaid: () => setAwaitingConfirmation(true), onClosed: () => {} })
        } catch {
          setCheckoutError(s.hold.checkoutUnavailable)
        }
      }
      return result
    },
    onSettled: refresh,
  })

  const release = useMutation({
    mutationFn: () => api<BookingView>(`/api/bookings/${id}/release`, { method: 'POST' }),
    onSuccess: () => {
      refresh()
      toast(s.hold.released)
      const b = booking.data
      navigate(b ? `/venues/${b.venueId}?date=${istDate(b.startAt)}` : '/', { replace: true })
    },
  })

  if (booking.isPending) return <Spinner />
  if (booking.isError) return <ErrorState message={errorMessage(booking.error)} onRetry={() => booking.refetch()} />
  const b = booking.data

  if (pay.data?.outcome === 'REFUNDED') return <Refunded booking={b} />
  if (b.status === 'CONFIRMED' || b.status === 'COMPLETED') return <Ticket booking={b} />
  // A payment that arrived too late was refunded automatically.
  if (b.status === 'EXPIRED' && b.refunded > 0) return <Refunded booking={b} />
  // Paid in Razorpay Checkout; the hold's grace window covers the wait for the webhook.
  if (awaitingConfirmation && b.status === 'HELD') return <Confirming />
  if (b.status === 'CANCELLED' && b.cancelReason === 'PAYMENT_NOT_RECEIVED') return <PaymentNotFound booking={b} />
  if (b.status !== 'HELD') {
    if (b.status === 'EXPIRED') return <Expired booking={b} />
    return <Navigate to="/bookings" replace />
  }
  // UPI: the player said they paid; the slot is kept until the venue checks.
  if (b.paymentClaimedAt) return <WaitingForVenue booking={b} />

  const expired = msLeft <= 0
  // A UPI payment started in time may still be claimed in the grace window; the server decides.
  if (expired && !(upiMode && b.paymentStartedAt)) return <Expired booking={b} />
  // Both times come from the server: when the hold ends, and the last moment a payment may start.
  const tooLate = msToCutoff <= 0
  const totalMs = new Date(b.holdExpiresAt!).getTime() - new Date(b.createdAt).getTime()

  return (
    <div className="mx-auto max-w-sm text-center">
      <p className="mb-4 animate-rise text-xs font-semibold uppercase tracking-[0.22em] text-flood">{s.hold.title}</p>
      <CountdownRing msLeft={msLeft} totalMs={totalMs} pastCutoff={tooLate} />

      <div className="mt-6 animate-rise rounded-2xl border border-line bg-pitch-2/80 p-5 text-left" style={{ animationDelay: '80ms' }}>
        <p className="font-display text-3xl leading-none font-extrabold uppercase">{b.venueName}</p>
        <p className="mt-1 text-sm text-chalk-dim">{b.courtName} · {b.venueArea}</p>
        <p className="num mt-3 text-2xl font-bold">{dayOf(b.startAt)} · {timeRange(b.startAt, b.endAt)}</p>
        <div className="chalk-rule my-4" />
        <MoneyRow label={s.hold.total} value={money(b.totalPrice)} />
        <MoneyRow label={s.hold.payNow} value={money(b.advanceRequired)} strong />
        <MoneyRow label={s.hold.atVenue} value={money(b.totalPrice - b.advanceRequired)} />
      </div>

      {upiMode && b.paymentStartedAt && b.upiId ? (
        <UpiPanel
          booking={b}
          payment={{ upiId: b.upiId, payeeName: b.upiName ?? b.venueName, amount: b.advanceRequired, note: upiNote(b.id) }}
          onClaimed={refresh}
        />
      ) : (
      <div className="mt-5 space-y-3">
        <InlineError message={pay.isError ? errorMessage(pay.error) : checkoutError ?? (tooLate ? s.hold.tooLate : null)} />
        <Button className="w-full text-lg" busy={pay.isPending} disabled={tooLate || config.isPending} onClick={() => pay.mutate()}>
          {pay.isPending
            ? razorpay || upiMode ? s.hold.opening : s.hold.paying
            : upiMode ? s.hold.upiPay(money(b.advanceRequired)) : s.hold.pay(money(b.advanceRequired))}
        </Button>
        <p className="text-xs text-chalk-faint">{razorpay ? s.hold.secure : upiMode ? s.hold.upiSecure : s.hold.simulated}</p>
        <Button variant="quiet" className="w-full" busy={release.isPending} onClick={() => release.mutate()}>
          {s.hold.release}
        </Button>
        <InlineError message={release.isError ? errorMessage(release.error) : null} />
      </div>
      )}
    </div>
  )
}

/** UPI mode, after "Pay by UPI": the venue's UPI details (for paying from another device), then "I've paid". */
function UpiPanel({ booking: b, payment, onClaimed }: { booking: MyBooking; payment: UpiPayment; onClaimed: () => void }) {
  const toast = useToast()
  const [ref, setRef] = useState('')
  const valid = upiRefValid(ref)

  const claim = useMutation({
    mutationFn: () =>
      api<BookingView>(`/api/bookings/${b.id}/upi-paid`, { method: 'POST', body: { ref: cleanUpiRef(ref) || undefined } }),
    onSettled: onClaimed,
  })

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(payment.upiId)
      toast(s.hold.copied)
    } catch {
      // Clipboard blocked: the UPI ID is on screen to type.
    }
  }

  return (
    <div className="mt-5 space-y-4 text-left">
      <div className="rounded-2xl border border-line bg-pitch-2/80 p-4">
        <p className="text-sm text-chalk-dim">{s.hold.upiManual}</p>
        <div className="mt-2 flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="num truncate text-lg font-bold">{payment.upiId}</p>
            <p className="text-xs text-chalk-dim">{s.hold.upiTo(payment.payeeName)} · {money(payment.amount)}</p>
            <p className="text-xs text-chalk-faint">{s.hold.upiNote(payment.note)}</p>
          </div>
          <Button variant="secondary" className="min-h-10 shrink-0 text-sm" onClick={copy}>
            <Copy className="size-4" aria-hidden /> {s.hold.copy}
          </Button>
        </div>
        <a href={upiLink(payment)} className="mt-3 block">
          <Button variant="quiet" className="w-full min-h-10 text-sm">
            <Smartphone className="size-4" aria-hidden /> {s.hold.upiPay(money(payment.amount))}
          </Button>
        </a>
      </div>

      <label className="block">
        <span className="label">{s.hold.refLabel}</span>
        <input
          className="field num mt-1 w-full"
          inputMode="numeric"
          autoComplete="off"
          value={ref}
          onChange={(e) => setRef(e.target.value)}
          aria-invalid={!valid}
        />
        <span className={`mt-1 block text-xs ${valid ? 'text-chalk-faint' : 'text-red'}`}>
          {valid ? s.hold.refHint : s.hold.refInvalid}
        </span>
      </label>

      <InlineError message={claim.isError ? errorMessage(claim.error) : null} />
      <Button className="w-full text-lg" busy={claim.isPending} disabled={!valid} onClick={() => claim.mutate()}>
        {claim.isPending ? s.hold.telling : s.hold.iPaid}
      </Button>
      <p className="text-center text-xs text-chalk-faint">{s.hold.iPaidHint}</p>
    </div>
  )
}

/** UPI mode: "I've paid" sent; the venue checks the money. The page polls and turns into the ticket. */
function WaitingForVenue({ booking: b }: { booking: MyBooking }) {
  return (
    <div className="mx-auto max-w-sm py-10 text-center" role="status">
      <Loader2 className="mx-auto size-10 animate-spin text-flood" aria-hidden />
      <p className="mt-4 font-display text-3xl font-extrabold uppercase">{s.hold.waitingTitle}</p>
      <p className="mt-2 text-sm text-chalk-dim">{s.hold.waitingHint(b.venueName, money(b.advanceRequired))}</p>
      <p className="num mt-3 text-sm">{dayOf(b.startAt)} · {timeRange(b.startAt, b.endAt)} · {b.courtName}</p>
      <a href={`tel:${b.venuePhone}`} className="mt-6 inline-flex items-center gap-2 text-sm text-flood">
        <Phone className="size-4" aria-hidden /> {s.hold.callVenue(b.venuePhone)}
      </a>
    </div>
  )
}

function PaymentNotFound({ booking: b }: { booking: MyBooking }) {
  return (
    <div className="mx-auto max-w-sm py-10 text-center">
      <p className="font-display text-3xl font-extrabold uppercase">{s.hold.notReceivedTitle}</p>
      <p className="mt-2 text-sm text-chalk-dim">{s.hold.notReceivedHint}</p>
      <a href={`tel:${b.venuePhone}`} className="mt-4 inline-flex items-center gap-2 text-sm text-flood">
        <Phone className="size-4" aria-hidden /> {s.hold.callVenue(b.venuePhone)}
      </a>
      <Link to={`/venues/${b.venueId}?date=${istDate(b.startAt)}`} className="mt-6 block">
        <Button className="w-full">{s.hold.tryAgain}</Button>
      </Link>
    </div>
  )
}

function MoneyRow({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex items-baseline justify-between py-1">
      <span className={strong ? 'font-semibold' : 'text-chalk-dim'}>{label}</span>
      <span className={`num ${strong ? 'text-3xl font-extrabold text-flood' : 'text-xl font-bold'}`}>{value}</span>
    </div>
  )
}

/** A match ticket: torn edge, stamp, the essentials. */
function Ticket({ booking: b }: { booking: MyBooking }) {
  const waived = b.advanceRequired === 0
  return (
    <div className="mx-auto max-w-sm">
      <div className="relative animate-rise overflow-hidden rounded-3xl border border-flood/40 bg-gradient-to-b from-turf-2 to-pitch-2 p-6 shadow-[0_20px_60px_-20px_rgb(214_255_61/0.35)]">
        <div className="absolute top-5 right-5 rotate-[-8deg] rounded-md border-2 border-flood px-2 py-0.5 font-display text-2xl font-black uppercase text-flood">
          {s.ticket.confirmed}
        </div>
        <CircleCheck className="size-10 text-flood" aria-hidden />
        <p className="mt-4 font-display text-4xl leading-none font-black uppercase">{b.venueName}</p>
        <p className="mt-1 text-sm text-chalk-dim">{b.courtName} · {b.venueArea}</p>
        <p className="num mt-5 text-5xl leading-none font-extrabold">{timeRange(b.startAt, b.endAt)}</p>
        <p className="mt-1 text-lg font-semibold text-chalk-dim">{dayOf(b.startAt)}</p>

        {/* Perforation */}
        <div className="relative -mx-6 my-6">
          <div className="absolute top-1/2 -left-3 size-6 -translate-y-1/2 rounded-full bg-pitch" />
          <div className="absolute top-1/2 -right-3 size-6 -translate-y-1/2 rounded-full bg-pitch" />
          <div className="mx-6 border-t-2 border-dashed border-line" />
        </div>

        <p className="text-sm">{waived ? s.ticket.waived : s.ticket.paidAdvance(money(b.advanceRequired))}</p>
        <p className="num mt-1 text-2xl font-bold text-flood">{s.ticket.balanceAtVenue(money(b.totalPrice - b.advanceRequired))}</p>
      </div>
      <Link to="/bookings" className="mt-5 block">
        <Button variant="secondary" className="w-full">{s.ticket.seeBookings}</Button>
      </Link>
    </div>
  )
}

function Confirming() {
  return (
    <div className="mx-auto max-w-sm py-10 text-center" role="status">
      <Loader2 className="mx-auto size-10 animate-spin text-flood" aria-hidden />
      <p className="mt-4 font-display text-3xl font-extrabold uppercase">{s.hold.confirmingPayment}</p>
      <p className="mt-2 text-sm text-chalk-dim">{s.hold.confirmingHint}</p>
    </div>
  )
}

function Expired({ booking: b }: { booking: MyBooking }) {
  return (
    <div className="mx-auto max-w-sm py-10 text-center">
      <p className="num text-7xl font-black text-red">0:00</p>
      <p className="mt-2 font-display text-3xl font-extrabold uppercase">{s.hold.expired}</p>
      <p className="mt-2 text-sm text-chalk-dim">{s.hold.expiredHint}</p>
      <Link to={`/venues/${b.venueId}?date=${istDate(b.startAt)}`} className="mt-6 block">
        <Button className="w-full"><RotateCcw className="size-4" aria-hidden /> {s.hold.tryAgain}</Button>
      </Link>
    </div>
  )
}

function Refunded({ booking: b }: { booking: MyBooking }) {
  return (
    <div className="mx-auto max-w-sm py-10 text-center">
      <p className="font-display text-3xl font-extrabold uppercase">{s.ticket.refunded}</p>
      <p className="mt-2 text-sm text-chalk-dim">{s.ticket.refundedHint}</p>
      <Link to={`/venues/${b.venueId}?date=${istDate(b.startAt)}`} className="mt-6 block">
        <Button className="w-full">{s.hold.tryAgain}</Button>
      </Link>
    </div>
  )
}
