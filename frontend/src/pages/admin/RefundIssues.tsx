import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { Button, Chip, InlineError } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { dayAndTime, money } from '../../lib/format'
import type { RefundIssue } from '../../lib/types'
import { s } from '../../strings'

/**
 * Razorpay mode: refunds Razorpay failed or keeps rejecting (usually the owner's linked account could not cover the
 * reversal). Hidden while there are none.
 */
export function RefundIssues() {
  const issues = useQuery({
    queryKey: ['refund-issues'],
    queryFn: () => api<RefundIssue[]>('/api/admin/refund-issues'),
    refetchInterval: 60_000,
  })
  if (!issues.data?.length) return null

  return (
    <section className="mb-6 rounded-2xl border border-red/40 bg-red/10 p-4" aria-labelledby="refund-issues">
      <h2 id="refund-issues" className="flex items-center gap-2 font-semibold text-red">
        <TriangleAlert className="size-5" aria-hidden />
        {s.admin.refundIssues} ({issues.data.length})
      </h2>
      <p className="mt-1 text-sm text-chalk-dim">{s.admin.refundIssuesHint}</p>
      <ul className="mt-3 space-y-3">
        {issues.data.map((issue) => (
          <li key={issue.refundId}>
            <IssueCard issue={issue} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function IssueCard({ issue: r }: { issue: RefundIssue }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [confirming, setConfirming] = useState(false)
  const settle = useMutation({
    mutationFn: () => api<void>(`/api/admin/refund-issues/${r.refundId}/settled-manually`, { method: 'POST' }),
    onSuccess: () => toast(s.admin.refundSettled),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['refund-issues'] }),
  })

  return (
    <article className="rounded-xl border border-line bg-pitch-2/80 p-3 text-sm">
      <div className="flex items-start justify-between gap-2">
        <p className="font-display text-xl font-extrabold">{money(r.amount)}</p>
        <Chip tone={r.status === 'FAILED' ? 'red' : 'amber'}>
          {r.status === 'FAILED' ? s.admin.refundFailed : s.admin.refundRejected}
        </Chip>
      </div>
      <p className="mt-1">{r.venueName} · {r.courtName} · {s.admin.refundReason[r.reason] ?? r.reason}</p>
      <p className="text-chalk-dim">
        {s.admin.refundFor(
          [r.playerName, r.playerPhone].filter(Boolean).join(' · ') || '—',
          dayAndTime(r.bookingStart),
        )}
      </p>
      {r.error && <p className="mt-2 rounded-lg bg-pitch/60 px-2 py-1 font-mono text-xs text-chalk-dim">{r.error}</p>}
      {r.gatewayPaymentId && (
        <p className="mt-1 font-mono text-xs text-chalk-faint">{s.admin.refundPayment(r.gatewayPaymentId)}</p>
      )}
      <div className="mt-3 space-y-2">
        <InlineError message={settle.isError ? errorMessage(settle.error) : null} />
        {confirming && <p className="text-xs text-amber">{s.admin.refundSettleConfirm}</p>}
        <Button
          variant={confirming ? 'primary' : 'secondary'}
          className="w-full"
          busy={settle.isPending}
          onClick={() => (confirming ? settle.mutate() : setConfirming(true))}
        >
          {s.admin.refundSettle}
        </Button>
      </div>
    </article>
  )
}
