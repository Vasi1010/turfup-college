import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MapPin } from 'lucide-react'
import { useState } from 'react'
import { Button, Chip, EmptyState, ErrorState, InlineError, PageTitle, Spinner } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { useAppConfig } from '../../lib/appConfig'
import { errorMessage } from '../../lib/errors'
import { minuteLabel, money } from '../../lib/format'
import type { AdminVenue, VenueView } from '../../lib/types'
import { s } from '../../strings'
import { RefundIssues } from './RefundIssues'

export function AdminApprovals() {
  const [approved, setApproved] = useState(false)
  const list = useQuery({
    queryKey: ['admin-venues', approved],
    queryFn: () => api<AdminVenue[]>('/api/admin/venues', { query: { approved: String(approved) } }),
  })

  const razorpay = useAppConfig().data?.paymentsMode === 'RAZORPAY'

  return (
    <div>
      <PageTitle>{s.admin.title}</PageTitle>
      {razorpay && <RefundIssues />}
      <div className="mb-5 grid grid-cols-2 rounded-xl border border-line bg-pitch-2 p-1" role="tablist">
        {[false, true].map((a) => (
          <button key={String(a)} role="tab" aria-selected={approved === a} onClick={() => setApproved(a)}
                  className={`min-h-11 rounded-lg text-sm font-semibold ${approved === a ? 'bg-turf-2 text-chalk' : 'text-chalk-dim'}`}>
            {a ? s.admin.approved : s.admin.pending}
          </button>
        ))}
      </div>

      {list.isPending && <Spinner />}
      {list.isError && <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} />}
      {list.data?.length === 0 && <EmptyState title={s.admin.empty} />}
      <ul className="space-y-3">
        {list.data?.map((item, i) => (
          <li key={item.venue.id} className="animate-rise" style={{ animationDelay: `${i * 40}ms` }}>
            <ApprovalCard item={item} />
          </li>
        ))}
      </ul>
    </div>
  )
}

function ApprovalCard({ item }: { item: AdminVenue }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const t = item.venue
  const toggle = useMutation({
    mutationFn: () => api<VenueView>(`/api/admin/venues/${t.id}/approval`, { method: 'PUT', body: { approved: !t.approved } }),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: ['admin-venues'] })
      queryClient.invalidateQueries({ queryKey: ['venues'] })
      queryClient.invalidateQueries({ queryKey: ['areas'] })
      toast(updated.approved ? s.admin.approvedToast(t.name) : s.admin.unapprovedToast(t.name))
    },
  })
  const rates = t.courts.flatMap((c) => c.priceRules.map((r) => r.rate))
  const fromRate = rates.length ? Math.min(...rates) : null
  const mode = useAppConfig().data?.paymentsMode
  const razorpay = mode === 'RAZORPAY'

  return (
    <article className="rounded-2xl border border-line bg-pitch-2/80 p-4">
      <p className="flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.16em] text-chalk-dim">
        <MapPin className="size-3.5" aria-hidden /> {t.area}
      </p>
      <p className="font-display text-3xl leading-none font-extrabold uppercase">{t.name}</p>
      <p className="mt-1 text-sm text-chalk-dim">{t.address}</p>
      <p className="mt-2 text-sm">{s.admin.owner(item.ownerName, item.ownerPhone)}</p>
      <p className="num mt-1 text-lg text-chalk-dim">
        {minuteLabel(t.openMinute)}–{minuteLabel(t.closeMinute)}
        {fromRate !== null && <> · {s.common.from} {money(fromRate)}{s.common.perHour}</>}
      </p>
      <ul className="mt-1 text-sm text-chalk-dim">
        {t.courts.map((c) => (
          <li key={c.id}>
            {c.name} · {c.sports.map((sp) => s.sports[sp] ?? sp).join(', ')}{!c.active && <> · {s.owner.paused}</>}
          </li>
        ))}
      </ul>
      {razorpay && <LinkedAccount venue={t} />}
      {mode === 'UPI' && (
        <p className={`num mt-2 text-sm ${t.upiId ? 'text-chalk-dim' : 'font-semibold text-amber'}`}>
          {t.upiId ? s.admin.upiId(t.upiId) : s.admin.upiMissing}
        </p>
      )}
      <div className="mt-3 space-y-2">
        <InlineError message={toggle.isError ? errorMessage(toggle.error) : null} />
        <Button variant={t.approved ? 'secondary' : 'primary'} className="w-full" busy={toggle.isPending} onClick={() => toggle.mutate()}>
          {t.approved ? s.admin.unapprove : s.admin.approve}
        </Button>
      </div>
    </article>
  )
}

/** Razorpay mode: where this venue's advances go (every court). Approval needs Razorpay to have activated the account. */
function LinkedAccount({ venue: t }: { venue: VenueView }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [accountId, setAccountId] = useState(t.razorpayAccountId ?? '')
  const link = useMutation({
    mutationFn: () =>
      api<VenueView>(`/api/admin/venues/${t.id}/linked-account`, { method: 'PUT', body: { accountId: accountId.trim() } }),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: ['admin-venues'] })
      toast(updated.razorpayAccountStatus ? s.admin.accountSaved(updated.razorpayAccountStatus) : s.admin.accountCleared)
    },
  })
  const status = t.razorpayAccountStatus
  const active = status === 'activated'
  const inputId = `account-${t.id}`

  return (
    <div className="mt-3 rounded-xl border border-line bg-pitch/60 p-3">
      <div className="flex items-center justify-between gap-2">
        <label htmlFor={inputId} className="text-xs font-semibold uppercase tracking-[0.14em] text-chalk-dim">
          {s.admin.account}
        </label>
        <Chip tone={active ? 'ok' : status ? 'amber' : 'neutral'}>
          {status ? (s.admin.accountStatus[status] ?? status) : s.admin.accountNone}
        </Chip>
      </div>
      <div className="mt-2 flex gap-2">
        <input
          id={inputId}
          value={accountId}
          onChange={(e) => setAccountId(e.target.value)}
          placeholder={s.admin.accountPlaceholder}
          autoComplete="off"
          spellCheck={false}
          className="min-h-11 min-w-0 flex-1 rounded-lg border border-line bg-pitch-2 px-3 font-mono text-sm"
        />
        <Button variant="secondary" busy={link.isPending} onClick={() => link.mutate()}>{s.admin.accountSave}</Button>
      </div>
      <InlineError message={link.isError ? errorMessage(link.error) : null} />
      {!active && !t.approved && <p className="mt-2 text-xs text-chalk-faint">{s.admin.accountNeeded}</p>}
    </div>
  )
}
