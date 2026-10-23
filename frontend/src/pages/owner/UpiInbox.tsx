import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BadgeIndianRupee, Phone, Undo2 } from 'lucide-react'
import { useState } from 'react'
import { Button, InlineError } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { dayOf, money, timeRange } from '../../lib/format'
import type { BookingView, PaymentToCheck, RefundToSend, UpiInbox as Inbox } from '../../lib/types'
import { s } from '../../strings'

/**
 * UPI mode: what needs the owner's hands at this venue, on any day. Payments players say they made (Received / Not
 * received) and refunds of UPI advances the owner must send back (Sent). Hidden when there is nothing to do.
 */
export function UpiInbox({ venueId }: { venueId: number }) {
  const inbox = useQuery({
    queryKey: ['upi-inbox', venueId],
    queryFn: () => api<Inbox>(`/api/owner/venues/${venueId}/upi-inbox`),
    refetchInterval: 15_000,
  })
  const data = inbox.data
  if (!data || (data.paymentsToCheck.length === 0 && data.refundsToSend.length === 0)) return null

  return (
    <div className="mb-4 space-y-3 animate-rise">
      {data.paymentsToCheck.length > 0 && (
        <section className="rounded-2xl border border-amber/40 bg-amber/10 p-4">
          <h2 className="flex items-center gap-2 font-semibold text-amber">
            <BadgeIndianRupee className="size-4" aria-hidden /> {s.owner.upiChecksTitle} · {data.paymentsToCheck.length}
          </h2>
          <p className="mt-1 text-xs text-chalk-dim">{s.owner.upiChecksHint}</p>
          <ul className="mt-3 space-y-3">
            {data.paymentsToCheck.map((p) => <PaymentCheck key={p.bookingId} venueId={venueId} payment={p} />)}
          </ul>
        </section>
      )}
      {data.refundsToSend.length > 0 && (
        <section className="rounded-2xl border border-line bg-pitch-2/80 p-4">
          <h2 className="flex items-center gap-2 font-semibold">
            <Undo2 className="size-4" aria-hidden /> {s.owner.refundsTitle} · {data.refundsToSend.length}
          </h2>
          <p className="mt-1 text-xs text-chalk-dim">{s.owner.refundsHint}</p>
          <ul className="mt-3 space-y-3">
            {data.refundsToSend.map((r) => <RefundLine key={r.refundId} venueId={venueId} refund={r} />)}
          </ul>
        </section>
      )}
    </div>
  )
}

function useRefreshInbox(venueId: number) {
  const queryClient = useQueryClient()
  return () => {
    queryClient.invalidateQueries({ queryKey: ['upi-inbox', venueId] })
    queryClient.invalidateQueries({ queryKey: ['schedule', venueId] })
  }
}

function Who({ name, phone }: { name: string; phone: string }) {
  return (
    <p className="flex items-center gap-2 font-semibold">
      {name}
      <a href={`tel:${phone}`} className="inline-flex items-center gap-1 text-xs font-normal text-flood" aria-label={phone}>
        <Phone className="size-3.5" aria-hidden /> {phone}
      </a>
    </p>
  )
}

function PaymentCheck({ venueId, payment: p }: { venueId: number; payment: PaymentToCheck }) {
  const toast = useToast()
  const refresh = useRefreshInbox(venueId)
  const [confirmReject, setConfirmReject] = useState(false)

  const received = useMutation({
    mutationFn: () => api<BookingView>(`/api/owner/bookings/${p.bookingId}/upi-received`, { method: 'POST' }),
    onSuccess: () => toast(s.owner.upiReceivedToast),
    onSettled: refresh,
  })
  const notReceived = useMutation({
    mutationFn: () => api<BookingView>(`/api/owner/bookings/${p.bookingId}/upi-not-received`, { method: 'POST' }),
    onSuccess: () => toast(s.owner.upiNotReceivedToast),
    onSettled: refresh,
  })
  const error = received.error ?? notReceived.error

  return (
    <li className="rounded-xl border border-line bg-pitch/60 p-3 text-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <Who name={p.playerName} phone={p.playerPhone} />
          <p className="num text-chalk-dim">{dayOf(p.startAt)} · {timeRange(p.startAt, p.endAt)} · {p.courtName}</p>
          <p className="num text-xs text-chalk-faint">{p.paymentRef ? s.owner.upiRef(p.paymentRef) : s.owner.upiNoRef}</p>
        </div>
        <p className="num shrink-0 text-xl font-bold text-flood">{money(p.amount)}</p>
      </div>
      {confirmReject ? (
        <div className="mt-3 space-y-2">
          <p className="text-xs text-chalk-dim">{s.owner.upiNotReceivedConfirm}</p>
          <div className="grid grid-cols-2 gap-2">
            <Button variant="secondary" className="min-h-10 text-sm" onClick={() => setConfirmReject(false)}>
              {s.common.back}
            </Button>
            <Button variant="danger" className="min-h-10 text-sm" busy={notReceived.isPending} onClick={() => notReceived.mutate()}>
              {s.owner.upiNotReceived}
            </Button>
          </div>
        </div>
      ) : (
        <div className="mt-3 grid grid-cols-2 gap-2">
          <Button className="min-h-10 text-sm" busy={received.isPending} onClick={() => received.mutate()}>
            {s.owner.upiReceived}
          </Button>
          <Button variant="secondary" className="min-h-10 text-sm" onClick={() => setConfirmReject(true)}>
            {s.owner.upiNotReceived}
          </Button>
        </div>
      )}
      <InlineError message={error ? errorMessage(error) : null} />
    </li>
  )
}

function RefundLine({ venueId, refund: r }: { venueId: number; refund: RefundToSend }) {
  const toast = useToast()
  const refresh = useRefreshInbox(venueId)
  const sent = useMutation({
    mutationFn: () => api<void>(`/api/owner/refunds/${r.refundId}/sent`, { method: 'POST' }),
    onSuccess: () => toast(s.owner.refundSentToast),
    onSettled: refresh,
  })
  return (
    <li className="rounded-xl border border-line bg-pitch/60 p-3 text-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <Who name={r.playerName} phone={r.playerPhone} />
          <p className="num text-chalk-dim">{dayOf(r.startAt)} · {r.courtName}</p>
          <p className="num text-xs text-chalk-faint">{r.paymentRef ? s.owner.upiRef(r.paymentRef) : s.owner.upiNoRef}</p>
        </div>
        <p className="num shrink-0 text-xl font-bold">{money(r.amount)}</p>
      </div>
      <Button variant="secondary" className="mt-3 min-h-10 w-full text-sm" busy={sent.isPending} onClick={() => sent.mutate()}>
        {s.owner.refundSent}
      </Button>
      <InlineError message={sent.isError ? errorMessage(sent.error) : null} />
    </li>
  )
}
