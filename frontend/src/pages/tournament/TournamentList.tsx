import { useQuery } from '@tanstack/react-query'
import { ChevronRight, Trophy } from 'lucide-react'
import { Link } from 'react-router'
import { EmptyState, ErrorState, PageTitle, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { dayOf } from '../../lib/format'
import type { TournamentSummary } from '../../lib/tournamentTypes'
import { s } from '../../strings'

/** Every tournament, the latest first (public). */
export function TournamentList() {
  const list = useQuery({ queryKey: ['tournaments'], queryFn: () => api<TournamentSummary[]>('/api/tournaments') })
  if (list.isPending) return <Spinner />
  if (list.isError) return <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} />
  return (
    <div>
      <PageTitle kicker={s.app.city}>{s.cup.title}</PageTitle>
      {list.data.length === 0 ? (
        <EmptyState title={s.cup.none} />
      ) : (
        <ul className="space-y-3">
          {list.data.map((t) => (
            <li key={t.id}>
              <Link to={`/t/${t.id}`} className="flex items-center gap-3 rounded-2xl border border-line bg-pitch-2/80 p-4 hover:bg-turf">
                <Trophy className="size-5 shrink-0 text-flood" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="font-semibold">{t.name}</p>
                  <p className="text-xs text-chalk-dim">{t.venueName} · {dayOf(t.startDate)} – {dayOf(t.endDate)}</p>
                </div>
                <ChevronRight className="size-4 text-chalk-dim" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
