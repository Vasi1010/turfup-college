import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Phone, Sparkles } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Button, Chip, EmptyState, ErrorState, InlineError, PageTitle, Spinner, Switch } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import type { Regular, Regulars as RegularsData } from '../../lib/types'
import { s } from '../../strings'
import { RecurringSection } from './RecurringSection'
import { VenuePicker } from './VenuePicker'
import { useOwnerVenue } from './useOwnerVenue'

export function Regulars() {
  const { venues, venue, select } = useOwnerVenue()
  const regulars = useQuery({
    queryKey: ['regulars', venue?.id],
    queryFn: () => api<RegularsData>(`/api/owner/venues/${venue!.id}/customers`),
    enabled: !!venue,
  })

  if (venues.isPending) return <Spinner />
  if (venues.isError) return <ErrorState message={errorMessage(venues.error)} onRetry={() => venues.refetch()} />
  if (!venue) {
    return <EmptyState title={s.owner.empty} action={<Link to="/owner/venue/new"><Button>{s.owner.createVenue}</Button></Link>} />
  }

  const data = regulars.data
  const suggested = data?.customers.filter((c) => c.suggested) ?? []
  const waived = data?.customers.filter((c) => c.advanceWaived) ?? []
  const others = data?.customers.filter((c) => !c.suggested && !c.advanceWaived) ?? []

  return (
    <div>
      <VenuePicker venues={venues.data} value={venue.id} onChange={select} />
      <PageTitle kicker={venue.name}>{s.regulars.title}</PageTitle>

      {regulars.isPending && <Spinner />}
      {regulars.isError && <ErrorState message={errorMessage(regulars.error)} onRetry={() => regulars.refetch()} />}
      {data && <RecurringSection venue={venue} players={data.customers} />}
      {data && data.customers.length === 0 && <EmptyState title={s.regulars.empty} />}

      {data && suggested.length > 0 && (
        <Section title={s.regulars.suggested} hint={s.regulars.suggestedHint(data.suggestionMinBookings, data.suggestionDays)} accent>
          {suggested.map((r) => <Row key={r.playerId} venueId={venue.id} regular={r} days={data.suggestionDays} />)}
        </Section>
      )}
      {data && waived.length > 0 && (
        <Section title={s.regulars.waived}>
          {waived.map((r) => <Row key={r.playerId} venueId={venue.id} regular={r} days={data.suggestionDays} />)}
        </Section>
      )}
      {data && others.length > 0 && (
        <Section title={s.regulars.others}>
          {others.map((r) => <Row key={r.playerId} venueId={venue.id} regular={r} days={data.suggestionDays} />)}
        </Section>
      )}
    </div>
  )
}

function Section({ title, hint, accent, children }: { title: string; hint?: string; accent?: boolean; children: ReactNode }) {
  return (
    <section className="mb-6 animate-rise">
      <h2 className={`flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.2em] ${accent ? 'text-flood' : 'text-chalk-dim'}`}>
        {accent && <Sparkles className="size-4" aria-hidden />} {title}
      </h2>
      {hint && <p className="mt-1 text-sm text-chalk-dim">{hint}</p>}
      <ul className="mt-3 space-y-2">{children}</ul>
    </section>
  )
}

function Row({ venueId, regular: r, days }: { venueId: number; regular: Regular; days: number }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const waiver = useMutation({
    mutationFn: (advanceWaived: boolean) =>
      api(`/api/owner/venues/${venueId}/customers/${r.playerId}/waiver`, { method: 'PUT', body: { advanceWaived } }),
    onSuccess: (_, advanceWaived) => {
      queryClient.invalidateQueries({ queryKey: ['regulars', venueId] })
      toast(advanceWaived ? s.regulars.waiverOn(r.name) : s.regulars.waiverOff(r.name))
    },
  })
  const checked = waiver.isPending ? (waiver.variables as boolean) : r.advanceWaived

  return (
    <li className={`rounded-2xl border bg-pitch-2/80 p-4 ${r.suggested ? 'border-flood/40' : 'border-line'}`}>
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-lg font-semibold">{r.name}</p>
          <a href={`tel:${r.phone}`} className="inline-flex items-center gap-1 text-sm text-chalk-dim hover:text-chalk">
            <Phone className="size-3.5" aria-hidden /> {r.phone}
          </a>
          <div className="mt-2 flex flex-wrap gap-1.5">
            <Chip tone={r.suggested ? 'flood' : 'neutral'}>{s.regulars.played(r.recentPlayed, days)}</Chip>
            {r.noShowCount > 0 && <Chip tone="red">{s.regulars.noShows(r.noShowCount)}</Chip>}
          </div>
        </div>
        <div className="flex flex-col items-center gap-1">
          <Switch checked={checked} onChange={(v) => waiver.mutate(v)} label={`${s.regulars.waiver}: ${r.name}`} disabled={waiver.isPending} />
          <span className="text-[0.65rem] font-semibold uppercase tracking-wider text-chalk-dim">{s.regulars.waiver}</span>
        </div>
      </div>
      {waiver.isError && <div className="mt-3"><InlineError message={errorMessage(waiver.error)} /></div>}
    </li>
  )
}
