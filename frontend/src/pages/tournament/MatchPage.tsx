import { useQuery } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { BallChips } from '../../components/BallChips'
import { Chip, ErrorState, PageTitle, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { dayAndTime } from '../../lib/format'
import type { InningsCard, MatchDetail } from '../../lib/tournamentTypes'
import { s } from '../../strings'

const POLL_MS = 5_000

/**
 * A match's public page (task B12): the live state while it is being played (refreshed every 5 s; nothing redraws
 * unless the match changed) and the full scorecard of each innings.
 */
export function MatchPage() {
  const id = Number(useParams().matchId)
  const match = useQuery({
    queryKey: ['match', id],
    queryFn: () => api<MatchDetail>(`/api/matches/${id}`),
    refetchInterval: (q) => (q.state.data?.status === 'LIVE' ? POLL_MS : false),
  })
  if (match.isPending) return <Spinner />
  if (match.isError) return <ErrorState message={errorMessage(match.error)} onRetry={() => match.refetch()} />
  const m = match.data
  const name = (teamId: number) => (m.teamA.teamId === teamId ? m.teamA.name : m.teamB.name) ?? ''
  const shortName = (teamId: number) => (m.teamA.teamId === teamId ? m.teamA.shortName : m.teamB.shortName) ?? ''
  const live = m.live
  const current = live ? m.innings.find((i) => i.id === live.inningsId) : undefined

  return (
    <div className="space-y-4">
      <Link to={`/t/${m.tournamentId}`} className="inline-flex items-center gap-1 text-sm text-chalk-dim hover:text-chalk">
        <ChevronLeft className="size-4" aria-hidden /> {s.cup.back}
      </Link>
      <PageTitle
        kicker={`${s.cup.matchNo(m.matchNo)}${s.cup.stage[m.stage] ? ` · ${s.cup.stage[m.stage]}` : ''}`}
        right={<Chip tone={m.status === 'LIVE' ? 'flood' : 'neutral'}>{s.cup.status[m.status]}</Chip>}
      >
        {m.teamA.shortName ?? m.teamA.label} {s.cup.vs} {m.teamB.shortName ?? m.teamB.label}
      </PageTitle>

      {m.resultText && <p className="font-display text-2xl font-bold uppercase text-flood">{m.resultText}</p>}
      {m.resultNote && <p className="text-sm text-chalk-dim">{m.resultNote}</p>}
      {m.toss && <p className="text-sm text-chalk-dim">{s.cup.toss(name(m.toss.winnerId), m.toss.decision)}</p>}
      {m.status === 'UPCOMING' && m.innings.length === 0 && (
        <p className="rounded-2xl border border-line bg-pitch-2/80 p-4 text-sm text-chalk-dim">
          {s.cup.notStarted}{m.scheduledAt ? ` ${dayAndTime(m.scheduledAt)}.` : ''}
        </p>
      )}

      {live && current && (
        <section className="rounded-2xl border border-flood/40 bg-pitch-2/80 p-4" aria-live="polite">
          <div className="flex items-baseline justify-between gap-3">
            <p className="font-display text-5xl font-bold tabular-nums">
              {shortName(current.battingTeamId)} {current.runs}/{current.wickets}
            </p>
            <p className="tabular-nums text-chalk-dim">{s.cup.oversShort(current.overs)}</p>
          </div>
          {live.runsNeeded !== null && (
            <p className="mt-1 font-semibold text-flood">{s.cup.need(name(current.battingTeamId), live.runsNeeded, live.ballsLeft)}</p>
          )}
          <p className="mt-1 text-xs text-chalk-dim">
            {s.cup.rates(live.currentRunRate.toFixed(2), live.requiredRunRate === null ? null : live.requiredRunRate.toFixed(2))}
          </p>
          <div className="mt-3 border-t border-line pt-3 text-sm">
            {current.batting.filter((b) => b.status === 'BATTING').map((b) => (
              <p key={b.cricketerId} className={b.onStrike ? 'font-semibold' : 'text-chalk-dim'}>
                {b.name}{b.onStrike && ' *'} <span className="ml-1 tabular-nums">{b.runs} ({b.balls})</span>
              </p>
            ))}
            {current.bowling.filter((b) => b.bowling).map((b) => (
              <p key={b.cricketerId} className="mt-2 text-chalk-dim">
                {b.name} <span className="tabular-nums">{b.overs}-{b.maidens}-{b.runs}-{b.wickets}</span>
              </p>
            ))}
          </div>
          {live.thisOver.length > 0 && <div className="mt-3"><BallChips balls={live.thisOver} label={s.cup.thisOver} /></div>}
          <p className="mt-3 text-xs text-chalk-faint">{s.cup.autoRefresh}</p>
        </section>
      )}

      {[...m.innings].reverse().map((inn) => (
        <Scorecard key={inn.id} inn={inn} team={name(inn.battingTeamId)} />
      ))}
    </div>
  )
}

function Scorecard({ inn, team }: { inn: InningsCard; team: string }) {
  const b = s.cup.batHead
  const w = s.cup.bowlHead
  return (
    <section className="overflow-hidden rounded-2xl border border-line bg-pitch-2/80">
      <header className="flex items-baseline justify-between gap-3 border-b border-line px-4 py-3">
        <h2 className="font-semibold">{s.cup.innings(team)}</h2>
        <p className="tabular-nums">
          <span className="font-display text-2xl font-bold">{inn.runs}/{inn.wickets}</span>{' '}
          <span className="text-xs text-chalk-dim">{s.cup.oversShort(inn.overs)}</span>
        </p>
      </header>
      {inn.target !== null && <p className="px-4 pt-2 text-xs text-chalk-dim">{s.cup.target(inn.target)}</p>}
      <table className="w-full text-sm tabular-nums">
        <thead className="text-xs text-chalk-dim">
          <tr>
            <th className="px-4 py-2 text-left font-semibold">{b.batter}</th>
            {[b.runs, b.balls, b.fours, b.sixes, b.sr].map((c) => <th key={c} className="px-1.5 py-2 text-right font-semibold">{c}</th>)}
          </tr>
        </thead>
        <tbody>
          {inn.batting.map((l) => (
            <tr key={l.cricketerId} className="border-t border-line align-top">
              <td className="px-4 py-2">
                <p className={l.status === 'OUT' ? '' : 'font-semibold'}>{l.name}</p>
                <p className="text-xs text-chalk-dim">{l.dismissal ?? (l.status === 'BATTING' ? s.cup.batting : s.cup.notOut)}</p>
              </td>
              <td className="px-1.5 py-2 text-right font-semibold">{l.runs}</td>
              <td className="px-1.5 py-2 text-right">{l.balls}</td>
              <td className="px-1.5 py-2 text-right">{l.fours}</td>
              <td className="px-1.5 py-2 text-right">{l.sixes}</td>
              <td className="px-1.5 py-2 pr-4 text-right">{l.strikeRate.toFixed(1)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="border-t border-line px-4 py-2 text-xs text-chalk-dim">
        {s.cup.extras(inn.extras.total, inn.extras.wides, inn.extras.noBalls, inn.extras.byes, inn.extras.legByes)}
      </p>
      {inn.yetToBat.length > 0 && (
        <p className="border-t border-line px-4 py-2 text-xs text-chalk-dim">
          {s.cup.yetToBat}: {inn.yetToBat.map((y) => y.name).join(', ')}
        </p>
      )}
      <table className="w-full border-t border-line text-sm tabular-nums">
        <thead className="text-xs text-chalk-dim">
          <tr>
            <th className="px-4 py-2 text-left font-semibold">{w.bowler}</th>
            {[w.overs, w.maidens, w.runs, w.wickets, w.economy].map((c) => <th key={c} className="px-1.5 py-2 text-right font-semibold">{c}</th>)}
          </tr>
        </thead>
        <tbody>
          {inn.bowling.map((l) => (
            <tr key={l.cricketerId} className="border-t border-line">
              <td className="px-4 py-2">{l.name}</td>
              <td className="px-1.5 py-2 text-right">{l.overs}</td>
              <td className="px-1.5 py-2 text-right">{l.maidens}</td>
              <td className="px-1.5 py-2 text-right">{l.runs}</td>
              <td className="px-1.5 py-2 text-right font-semibold">{l.wickets}</td>
              <td className="px-1.5 py-2 pr-4 text-right">{l.economy.toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {inn.fallOfWickets.length > 0 && (
        <p className="border-t border-line px-4 py-2 text-xs text-chalk-dim">
          {s.cup.fallOfWickets}: {inn.fallOfWickets.map((f) => `${f.runs}-${f.wicket} (${f.name}, ${f.overs})`).join(', ')}
        </p>
      )}
    </section>
  )
}
