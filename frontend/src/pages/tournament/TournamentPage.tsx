import { useQuery } from '@tanstack/react-query'
import { ChevronRight } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'
import { Chip, EmptyState, ErrorState, PageTitle, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { clock, dayOf } from '../../lib/format'
import type { Fixture, Leaderboards, PointsTable, TournamentDetail } from '../../lib/tournamentTypes'
import { s } from '../../strings'

type Tab = 'matches' | 'table' | 'leaders'
const TABS: Tab[] = ['matches', 'table', 'leaders']
const REFRESH_MS = 15_000

/**
 * A tournament's public page (task B11), where the QR posters point: fixtures and results by day, the points table
 * with NRR, and the leaderboards. Refreshes itself while matches are being played.
 */
export function TournamentPage() {
  const id = Number(useParams().tournamentId)
  const [params, setParams] = useSearchParams()
  const tab = (TABS.includes(params.get('tab') as Tab) ? params.get('tab') : 'matches') as Tab
  const detail = useQuery({
    queryKey: ['tournament', id],
    queryFn: () => api<TournamentDetail>(`/api/tournaments/${id}`),
    refetchInterval: (q) => (q.state.data?.status === 'LIVE' ? REFRESH_MS : false),
  })

  if (detail.isPending) return <Spinner />
  if (detail.isError) return <ErrorState message={errorMessage(detail.error)} onRetry={() => detail.refetch()} />
  const t = detail.data

  return (
    <div>
      <PageTitle
        kicker={`${t.venueName} · ${dayOf(t.startDate)} – ${dayOf(t.endDate)}`}
        right={<Chip tone={t.status === 'LIVE' ? 'flood' : 'neutral'}>{s.cup.status[t.status]}</Chip>}
      >
        {t.name}
      </PageTitle>
      <div className="mb-5 grid grid-cols-3 rounded-xl border border-line bg-pitch-2 p-1" role="tablist">
        {TABS.map((x) => (
          <button
            key={x}
            role="tab"
            aria-selected={tab === x}
            onClick={() => setParams({ tab: x }, { replace: true })}
            className={`min-h-11 rounded-lg text-sm font-semibold transition ${tab === x ? 'bg-turf-2 text-chalk' : 'text-chalk-dim'}`}
          >
            {s.cup.tabs[x]}
          </button>
        ))}
      </div>
      {tab === 'matches' && <Matches t={t} />}
      {tab === 'table' && <Table id={id} live={t.status === 'LIVE'} />}
      {tab === 'leaders' && <Leaders id={id} live={t.status === 'LIVE'} />}
    </div>
  )
}

function Matches({ t }: { t: TournamentDetail }) {
  const teams = new Map(t.teams.map((x) => [x.id, x]))
  const days = new Map<string, Fixture[]>()
  for (const f of t.matches) {
    const day = f.scheduledAt ? dayOf(f.scheduledAt) : s.cup.tbc
    days.set(day, [...(days.get(day) ?? []), f])
  }
  const sideName = (side: Fixture['teamA']) => (side.teamId !== null ? teams.get(side.teamId)?.name ?? side.label : side.label)

  return (
    <div className="space-y-6">
      {[...days.entries()].map(([day, fixtures]) => (
        <section key={day}>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-chalk-dim">{day}</h2>
          <ul className="space-y-3">
            {fixtures.map((f) => (
              <li key={f.id}>
                <Link to={`/m/${f.id}`} className="block rounded-2xl border border-line bg-pitch-2/80 p-4 transition hover:bg-turf">
                  <div className="flex items-center justify-between gap-3 text-xs text-chalk-dim">
                    <span>
                      {s.cup.matchNo(f.matchNo)}
                      {s.cup.stage[f.stage] ? ` · ${s.cup.stage[f.stage]}` : ''}
                      {f.scheduledAt ? ` · ${clock(f.scheduledAt)}` : ''}
                    </span>
                    {f.status === 'LIVE' ? <Chip tone="flood">{s.cup.status.LIVE}</Chip> : <ChevronRight className="size-4" aria-hidden />}
                  </div>
                  {[f.teamA, f.teamB].map((side, i) => {
                    const inn = f.innings.find((x) => x.battingTeamId === side.teamId)
                    const won = f.winnerId !== null && f.winnerId === side.teamId
                    return (
                      <div key={i} className="mt-2 flex items-baseline justify-between gap-3">
                        <span className={won ? 'font-semibold' : f.status === 'DONE' ? 'text-chalk-dim' : 'font-semibold'}>
                          {sideName(side)}
                        </span>
                        {inn && (
                          <span className="tabular-nums">
                            <span className="font-semibold">{inn.runs}/{inn.wickets}</span>{' '}
                            <span className="text-xs text-chalk-dim">{s.cup.oversShort(inn.overs)}</span>
                          </span>
                        )}
                      </div>
                    )
                  })}
                  {f.resultText && <p className="mt-2 text-sm text-flood">{f.resultText}</p>}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

function Table({ id, live }: { id: number; live: boolean }) {
  const table = useQuery({
    queryKey: ['tournament-table', id],
    queryFn: () => api<PointsTable>(`/api/tournaments/${id}/table`),
    refetchInterval: live ? REFRESH_MS : false,
  })
  if (table.isPending) return <Spinner />
  if (table.isError) return <ErrorState message={errorMessage(table.error)} onRetry={() => table.refetch()} />
  const h = s.cup.tableHead
  return (
    <div className="space-y-6">
      {table.data.groups.map((g) => (
        <section key={g.name ?? 'all'}>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-chalk-dim">{s.cup.group(g.name)}</h2>
          {g.rows.every((r) => r.played === 0) && <p className="mb-2 text-sm text-chalk-dim">{s.cup.tableEmpty}</p>}
          <div className="overflow-hidden rounded-2xl border border-line">
            <table className="w-full text-sm tabular-nums">
              <thead className="bg-pitch-2 text-xs text-chalk-dim">
                <tr>
                  <th className="px-3 py-2 text-left font-semibold">{h.team}</th>
                  {[h.played, h.won, h.lost, h.noResult, h.points, h.nrr].map((c) => (
                    <th key={c} className="px-2 py-2 text-right font-semibold">{c}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {g.rows.map((r) => (
                  <tr key={r.teamId} className="border-t border-line">
                    <td className="px-3 py-2">
                      <span className="mr-2 text-chalk-faint">{r.position}</span>
                      <span className="font-semibold">{r.shortName}</span>
                      <span className="ml-2 hidden text-chalk-dim sm:inline">{r.teamName}</span>
                    </td>
                    <td className="px-2 py-2 text-right">{r.played}</td>
                    <td className="px-2 py-2 text-right">{r.won}</td>
                    <td className="px-2 py-2 text-right">{r.lost}</td>
                    <td className="px-2 py-2 text-right">{r.noResult + r.tied}</td>
                    <td className="px-2 py-2 text-right font-bold text-flood">{r.points}</td>
                    <td className="px-2 py-2 text-right">{r.nrr > 0 ? `+${r.nrr.toFixed(3)}` : r.nrr.toFixed(3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
      <p className="text-xs text-chalk-faint">{s.cup.nrrNote}</p>
    </div>
  )
}

function Leaders({ id, live }: { id: number; live: boolean }) {
  const leaders = useQuery({
    queryKey: ['tournament-leaders', id],
    queryFn: () => api<Leaderboards>(`/api/tournaments/${id}/leaderboards`),
    refetchInterval: live ? REFRESH_MS : false,
  })
  if (leaders.isPending) return <Spinner />
  if (leaders.isError) return <ErrorState message={errorMessage(leaders.error)} onRetry={() => leaders.refetch()} />
  const { topRunScorers, topWicketTakers } = leaders.data
  if (topRunScorers.length === 0) return <EmptyState title={s.cup.leadersEmpty} />
  return (
    <div className="space-y-6">
      <LeaderList
        title={s.cup.topRuns}
        rows={topRunScorers.map((p) => ({ id: p.cricketerId, name: p.name, team: p.teamShortName, value: p.runs,
          line: s.cup.runsLine(p.balls, p.highest, p.strikeRate.toFixed(1)) }))}
      />
      <LeaderList
        title={s.cup.topWickets}
        rows={topWicketTakers.filter((p) => p.wickets > 0).map((p) => ({ id: p.cricketerId, name: p.name,
          team: p.teamShortName, value: p.wickets, line: s.cup.wicketsLine(p.overs, p.best, p.economy.toFixed(2)) }))}
      />
    </div>
  )
}

function LeaderList({ title, rows }: {
  title: string
  rows: { id: number; name: string; team: string; value: number; line: string }[]
}) {
  return (
    <section>
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-chalk-dim">{title}</h2>
      <ol className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-pitch-2/80">
        {rows.map((r, i) => (
          <li key={r.id} className="flex items-center gap-3 px-4 py-3">
            <span className="w-5 text-sm text-chalk-faint tabular-nums">{i + 1}</span>
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold">{r.name} <span className="text-xs font-normal text-chalk-dim">{r.team}</span></p>
              <p className="text-xs text-chalk-dim">{r.line}</p>
            </div>
            <span className="font-display text-2xl font-bold tabular-nums text-flood">{r.value}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}
