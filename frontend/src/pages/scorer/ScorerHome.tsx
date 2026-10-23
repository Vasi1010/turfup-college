import { useQuery } from '@tanstack/react-query'
import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router'
import { Chip, EmptyState, ErrorState, PageTitle, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { dayAndTime } from '../../lib/format'
import type { ScorerMatch } from '../../lib/tournamentTypes'
import { s } from '../../strings'

/** The matches this account scores (an admin: every match), the one being played first. */
export function ScorerHome() {
  const matches = useQuery({
    queryKey: ['scorer-matches'],
    queryFn: () => api<ScorerMatch[]>('/api/scorer/matches'),
  })
  if (matches.isPending) return <Spinner />
  if (matches.isError) return <ErrorState message={errorMessage(matches.error)} onRetry={() => matches.refetch()} />

  return (
    <div>
      <PageTitle kicker={s.score.title}>{s.score.myMatches}</PageTitle>
      {matches.data.length === 0 ? (
        <EmptyState title={s.score.noMatches} />
      ) : (
        <ul className="space-y-3">
          {matches.data.map((m) => (
            <li key={m.id}>
              <Link
                to={`/score/${m.id}`}
                className="flex items-center gap-3 rounded-2xl border border-line bg-pitch-2/80 p-4 transition hover:bg-turf"
              >
                <div className="min-w-0 flex-1">
                  <p className="text-xs text-chalk-dim">
                    {m.tournamentName} · {s.score.matchNo(m.matchNo)}
                    {m.scheduledAt ? ` · ${dayAndTime(m.scheduledAt)}` : ''}
                  </p>
                  <p className="mt-1 font-semibold">
                    {m.teamA} <span className="font-normal text-chalk-dim">{s.score.vs}</span> {m.teamB}
                  </p>
                </div>
                <Chip tone={m.status === 'LIVE' ? 'flood' : 'neutral'}>{s.score.status[m.status]}</Chip>
                <ChevronRight className="size-4 shrink-0 text-chalk-dim" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
