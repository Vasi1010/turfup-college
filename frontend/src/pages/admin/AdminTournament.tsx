import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ExternalLink, Pencil, Plus, Trophy } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { Button, Chip, ErrorState, InlineError, PageTitle, Sheet, Spinner } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { isoToIstInput, istInputToIso, squadPreview } from '../../lib/cupAdmin'
import { errorMessage } from '../../lib/errors'
import { dayAndTime, dayOf } from '../../lib/format'
import type {
  Fixture,
  FixtureSide,
  MatchDetail,
  MatchStage,
  Team,
  TournamentDetail,
  UpdateMatchRequest,
} from '../../lib/tournamentTypes'
import { s } from '../../strings'
import { Field } from './AdminTournaments'

interface MatchScorer {
  matchId: number
  name: string
  phone: string
}

/** Setting up one tournament (task B10): teams with pasted squads, matches, and match changes on the day. */
export function AdminTournament() {
  const id = Number(useParams().tournamentId)
  const detail = useQuery({ queryKey: ['tournament', id], queryFn: () => api<TournamentDetail>(`/api/tournaments/${id}`) })
  const scorers = useQuery({
    queryKey: ['admin-scorers', id],
    queryFn: () => api<MatchScorer[]>(`/api/admin/tournaments/${id}/scorers`),
  })
  const [sheet, setSheet] = useState<null | 'team' | 'match' | { edit: Fixture }>(null)

  if (detail.isPending) return <Spinner />
  if (detail.isError) return <ErrorState message={errorMessage(detail.error)} onRetry={() => detail.refetch()} />
  const t = detail.data
  const teams = new Map(t.teams.map((x) => [x.id, x]))
  const scorerOf = new Map((scorers.data ?? []).map((x) => [x.matchId, x]))
  const sideName = (side: FixtureSide) => (side.teamId !== null ? teams.get(side.teamId)?.name ?? side.label : side.label)

  return (
    <div className="space-y-6">
      <PageTitle
        kicker={`${t.venueName} · ${dayOf(t.startDate)} – ${dayOf(t.endDate)}`}
        right={
          <Link to={`/t/${t.id}`} className="inline-flex items-center gap-1 whitespace-nowrap text-sm text-chalk-dim hover:text-chalk">
            <ExternalLink className="size-4" aria-hidden /> {s.cupAdmin.publicPage}
          </Link>
        }
      >
        {t.name}
      </PageTitle>

      <section>
        <div className="mb-2 flex items-center justify-between">
          <h2 className="label mb-0">{s.cupAdmin.teams}</h2>
          <Button variant="secondary" onClick={() => setSheet('team')}><Plus className="size-4" aria-hidden /> {s.cupAdmin.addTeam}</Button>
        </div>
        {t.teams.length === 0 && <p className="text-sm text-chalk-dim">{s.cupAdmin.noTeams}</p>}
        <ul className="space-y-2">
          {t.teams.map((team) => (
            <li key={team.id}>
              <details className="rounded-2xl border border-line bg-pitch-2/80 p-4">
                <summary className="cursor-pointer">
                  <span className="font-semibold">{team.name}</span>{' '}
                  <span className="text-chalk-dim">{team.shortName}{team.groupName ? ` · ${team.groupName}` : ''}</span>
                  <p className="text-xs text-chalk-dim">
                    {s.cupAdmin.teamLine(team.squad.length, team.squad.find((c) => c.id === team.captainId)?.name ?? null)}
                  </p>
                </summary>
                <ol className="mt-3 grid grid-cols-2 gap-1 text-sm">
                  {team.squad.map((c) => (
                    <li key={c.id}>{c.jerseyNo !== null && <span className="mr-1 text-chalk-faint">{c.jerseyNo}</span>}{c.name}</li>
                  ))}
                </ol>
              </details>
            </li>
          ))}
        </ul>
      </section>

      <section>
        <div className="mb-2 flex items-center justify-between">
          <h2 className="label mb-0">{s.cupAdmin.matches}</h2>
          <Button variant="secondary" onClick={() => setSheet('match')} disabled={t.teams.length < 2}>
            <Plus className="size-4" aria-hidden /> {s.cupAdmin.addMatch}
          </Button>
        </div>
        {t.matches.length === 0 && <p className="text-sm text-chalk-dim">{s.cupAdmin.noMatches}</p>}
        <ul className="space-y-3">
          {t.matches.map((f) => {
            const scorer = scorerOf.get(f.id)
            return (
              <li key={f.id} className="rounded-2xl border border-line bg-pitch-2/80 p-4">
                <div className="flex items-center justify-between gap-3 text-xs text-chalk-dim">
                  <span>
                    {s.cup.matchNo(f.matchNo)}{s.cup.stage[f.stage] ? ` · ${s.cup.stage[f.stage]}` : ''}
                    {f.scheduledAt ? ` · ${dayAndTime(f.scheduledAt)}` : ''}
                  </span>
                  <Chip tone={f.status === 'LIVE' ? 'flood' : 'neutral'}>{s.cup.status[f.status]}</Chip>
                </div>
                <p className="mt-2 font-semibold">{sideName(f.teamA)} <span className="font-normal text-chalk-dim">{s.cup.vs}</span> {sideName(f.teamB)}</p>
                {f.resultText && <p className="text-sm text-flood">{f.resultText}</p>}
                <p className="mt-1 text-xs text-chalk-dim">{scorer ? s.cupAdmin.scorer(scorer.name, scorer.phone) : s.cupAdmin.noScorer}</p>
                <div className="mt-3 grid grid-cols-3 gap-2">
                  <Button variant="secondary" onClick={() => setSheet({ edit: f })}><Pencil className="size-4" aria-hidden /> {s.cupAdmin.edit}</Button>
                  <Link to={`/score/${f.id}`} className="inline-flex min-h-12 items-center justify-center gap-2 whitespace-nowrap rounded-xl border border-line bg-turf px-3 font-semibold hover:bg-turf-2">
                    <Trophy className="size-4" aria-hidden /> {s.cupAdmin.score}
                  </Link>
                  <Link to={`/m/${f.id}`} className="inline-flex min-h-12 items-center justify-center gap-2 whitespace-nowrap rounded-xl border border-line bg-turf px-3 font-semibold hover:bg-turf-2">
                    <ExternalLink className="size-4" aria-hidden /> {s.cupAdmin.watch}
                  </Link>
                </div>
              </li>
            )
          })}
        </ul>
      </section>

      <Sheet open={sheet === 'team'} onClose={() => setSheet(null)} title={s.cupAdmin.addTeam}>
        <TeamForm tournamentId={id} onDone={() => setSheet(null)} />
      </Sheet>
      <Sheet open={sheet === 'match'} onClose={() => setSheet(null)} title={s.cupAdmin.addMatch}>
        <MatchForm t={t} onDone={() => setSheet(null)} />
      </Sheet>
      <Sheet open={typeof sheet === 'object' && sheet !== null} onClose={() => setSheet(null)}
             title={typeof sheet === 'object' && sheet ? s.cupAdmin.editTitle(sheet.edit.matchNo) : ''}>
        {typeof sheet === 'object' && sheet && (
          <EditMatch t={t} f={sheet.edit} scorerPhone={scorerOf.get(sheet.edit.id)?.phone ?? ''} onDone={() => setSheet(null)} />
        )}
      </Sheet>
    </div>
  )
}

function useRefresh(tournamentId: number) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['tournament', tournamentId] })
    void queryClient.invalidateQueries({ queryKey: ['admin-scorers', tournamentId] })
    void queryClient.invalidateQueries({ queryKey: ['tournaments'] })
  }
}

function TeamForm({ tournamentId, onDone }: { tournamentId: number; onDone: () => void }) {
  const toast = useToast()
  const refresh = useRefresh(tournamentId)
  const [name, setName] = useState('')
  const [shortName, setShortName] = useState('')
  const [group, setGroup] = useState('')
  const [squad, setSquad] = useState('')
  const preview = squadPreview(squad)
  const add = useMutation({
    mutationFn: () => api<Team>(`/api/tournaments/${tournamentId}/teams`, {
      method: 'POST', body: { name, shortName, groupName: group.trim() || null, squad },
    }),
    onSuccess: (team) => {
      refresh()
      toast(s.cupAdmin.teamAdded(team.name))
      onDone()
    },
  })
  return (
    <form className="space-y-4" onSubmit={(e: FormEvent) => { e.preventDefault(); add.mutate() }}>
      <Field label={s.cupAdmin.teamName} htmlFor="team-name">
        <input id="team-name" className="field" required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={s.cupAdmin.shortName} htmlFor="team-short">
          <input id="team-short" className="field uppercase" required maxLength={5} value={shortName} onChange={(e) => setShortName(e.target.value)} />
        </Field>
        <Field label={s.cupAdmin.group} htmlFor="team-group">
          <input id="team-group" className="field" maxLength={20} value={group} onChange={(e) => setGroup(e.target.value)} />
        </Field>
      </div>
      <Field label={s.cupAdmin.squad} htmlFor="team-squad">
        <textarea id="team-squad" className="field min-h-48 font-mono text-sm" required value={squad} onChange={(e) => setSquad(e.target.value)} />
      </Field>
      <p className="text-xs text-chalk-dim">{s.cupAdmin.squadHint}</p>
      {preview.players.length > 0 && <p className="text-sm text-flood">{s.cupAdmin.squadPreview(preview.players.length, preview.captain)}</p>}
      <InlineError message={add.isError ? errorMessage(add.error) : null} />
      <Button type="submit" className="w-full" busy={add.isPending}>{s.cupAdmin.addTeam}</Button>
    </form>
  )
}

/** A side of a fixture: a team, or a label while it isn't known. */
function SidePicker({ id, label, teams, value, onChange }: {
  id: string
  label: string
  teams: Team[]
  value: { teamId: number | null; label: string }
  onChange: (v: { teamId: number | null; label: string }) => void
}) {
  return (
    <div className="space-y-2">
      <Field label={label} htmlFor={id}>
        <select id={id} className="field" value={value.teamId ?? ''}
                onChange={(e) => onChange({ ...value, teamId: e.target.value === '' ? null : Number(e.target.value) })}>
          <option value="">{s.cupAdmin.notKnownYet}</option>
          {teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
      </Field>
      {value.teamId === null && (
        <input aria-label={`${label}: ${s.cupAdmin.sideLabel}`} className="field" maxLength={40} required
               placeholder={s.cupAdmin.sideLabelPlaceholder} value={value.label} onChange={(e) => onChange({ ...value, label: e.target.value })} />
      )}
    </div>
  )
}

function MatchForm({ t, onDone }: { t: TournamentDetail; onDone: () => void }) {
  const toast = useToast()
  const refresh = useRefresh(t.id)
  const nextNo = Math.max(0, ...t.matches.map((m) => m.matchNo)) + 1
  const [matchNo, setMatchNo] = useState(String(nextNo))
  const [stage, setStage] = useState<MatchStage>('GROUP')
  const [a, setA] = useState<{ teamId: number | null; label: string }>({ teamId: t.teams[0]?.id ?? null, label: '' })
  const [b, setB] = useState<{ teamId: number | null; label: string }>({ teamId: t.teams[1]?.id ?? null, label: '' })
  const [when, setWhen] = useState('')
  const [scorerPhone, setScorerPhone] = useState('')
  const add = useMutation({
    mutationFn: () => api<MatchDetail>(`/api/tournaments/${t.id}/matches`, {
      method: 'POST',
      body: { matchNo: Number(matchNo), stage, teamA: a, teamB: b, scheduledAt: istInputToIso(when), scorerPhone: scorerPhone.trim() || null },
    }),
    onSuccess: (m) => {
      refresh()
      toast(s.cupAdmin.matchAdded(m.matchNo))
      onDone()
    },
  })
  return (
    <form className="space-y-4" onSubmit={(e: FormEvent) => { e.preventDefault(); add.mutate() }}>
      <div className="grid grid-cols-2 gap-3">
        <Field label={s.cupAdmin.matchNo} htmlFor="m-no">
          <input id="m-no" type="number" min={1} className="field num" required value={matchNo} onChange={(e) => setMatchNo(e.target.value)} />
        </Field>
        <Field label={s.cupAdmin.stage} htmlFor="m-stage">
          <select id="m-stage" className="field" value={stage} onChange={(e) => setStage(e.target.value as MatchStage)}>
            {Object.entries(s.cupAdmin.stages).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </Field>
      </div>
      <SidePicker id="m-a" label={s.cupAdmin.sideA} teams={t.teams} value={a} onChange={setA} />
      <SidePicker id="m-b" label={s.cupAdmin.sideB} teams={t.teams} value={b} onChange={setB} />
      <Field label={s.cupAdmin.when} htmlFor="m-when">
        <input id="m-when" type="datetime-local" className="field" value={when} onChange={(e) => setWhen(e.target.value)} />
      </Field>
      <Field label={s.cupAdmin.scorerPhone} htmlFor="m-scorer">
        <input id="m-scorer" type="tel" inputMode="numeric" className="field num" maxLength={14} value={scorerPhone} onChange={(e) => setScorerPhone(e.target.value)} />
      </Field>
      <p className="text-xs text-chalk-dim">{s.cupAdmin.scorerHint}</p>
      <InlineError message={add.isError ? errorMessage(add.error) : null} />
      <Button type="submit" className="w-full" busy={add.isPending}>{s.cupAdmin.addMatch}</Button>
    </form>
  )
}

function EditMatch({ t, f, scorerPhone: currentPhone, onDone }: {
  t: TournamentDetail
  f: Fixture
  scorerPhone: string
  onDone: () => void
}) {
  const toast = useToast()
  const refresh = useRefresh(t.id)
  const started = f.innings.length > 0 || f.status !== 'UPCOMING'
  const finished = f.status === 'DONE' || f.status === 'ABANDONED'
  const noInningsLive = f.innings.every((i) => i.endReason !== null)
  const [when, setWhen] = useState(isoToIstInput(f.scheduledAt))
  const [a, setA] = useState({ teamId: f.teamA.teamId, label: f.teamA.teamId === null ? f.teamA.label : '' })
  const [b, setB] = useState({ teamId: f.teamB.teamId, label: f.teamB.teamId === null ? f.teamB.label : '' })
  const [overs, setOvers] = useState('')
  const [phone, setPhone] = useState(currentPhone)
  const [result, setResult] = useState<'WIN' | 'TIE' | 'NO_RESULT'>(f.result ?? 'WIN')
  const [winnerId, setWinnerId] = useState<number | null>(f.winnerId)
  const [note, setNote] = useState('')
  const [confirmAbandon, setConfirmAbandon] = useState(false)

  const patch = useMutation({
    mutationFn: (body: UpdateMatchRequest) => api<MatchDetail>(`/api/matches/${f.id}`, { method: 'PATCH', body }),
    onSuccess: (_m, body) => {
      refresh()
      toast(body.status === 'ABANDONED' ? s.cupAdmin.abandoned : s.cupAdmin.saved)
      onDone()
    },
  })
  const save = (e: FormEvent) => {
    e.preventDefault()
    const body: UpdateMatchRequest = {}
    if (when !== isoToIstInput(f.scheduledAt)) body.scheduledAt = istInputToIso(when)
    if (!started) {
      if (a.teamId !== f.teamA.teamId || (a.teamId === null && a.label !== f.teamA.label)) body.teamA = a
      if (b.teamId !== f.teamB.teamId || (b.teamId === null && b.label !== f.teamB.label)) body.teamB = b
      if (overs.trim() !== '') body.oversPerInnings = Number(overs)
    }
    if (phone.trim() !== currentPhone) body.scorerPhone = phone.trim() || null
    patch.mutate(body)
  }
  const teamsOf = [f.teamA, f.teamB].filter((x) => x.teamId !== null)
  const teams = new Map(t.teams.map((x) => [x.id, x]))

  return (
    <div className="space-y-6">
      <form className="space-y-4" onSubmit={save}>
        <Field label={s.cupAdmin.when} htmlFor="e-when">
          <input id="e-when" type="datetime-local" className="field" value={when} onChange={(e) => setWhen(e.target.value)} />
        </Field>
        {started ? (
          <p className="text-sm text-chalk-dim">{s.cupAdmin.teamsLocked}</p>
        ) : (
          <>
            <SidePicker id="e-a" label={s.cupAdmin.sideA} teams={t.teams} value={a} onChange={setA} />
            <SidePicker id="e-b" label={s.cupAdmin.sideB} teams={t.teams} value={b} onChange={setB} />
            <Field label={s.cupAdmin.oversOverride} htmlFor="e-overs">
              <input id="e-overs" type="number" min={1} max={50} className="field num" value={overs} onChange={(e) => setOvers(e.target.value)} />
            </Field>
          </>
        )}
        <Field label={s.cupAdmin.scorerPhone} htmlFor="e-scorer">
          <input id="e-scorer" type="tel" inputMode="numeric" className="field num" maxLength={14} value={phone} onChange={(e) => setPhone(e.target.value)} />
        </Field>
        <InlineError message={patch.isError ? errorMessage(patch.error) : null} />
        <Button type="submit" className="w-full" busy={patch.isPending}>{s.cupAdmin.save}</Button>
      </form>

      {f.status !== 'UPCOMING' && f.status !== 'ABANDONED' && noInningsLive && teamsOf.length === 2 && (
        <section className="space-y-3 border-t border-line pt-5">
          <h3 className="label">{s.cupAdmin.resultTitle}</h3>
          <p className="text-xs text-chalk-dim">{s.cupAdmin.resultHint}</p>
          <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label={s.cupAdmin.resultTitle}>
            {(['WIN', 'TIE', 'NO_RESULT'] as const).map((r) => (
              <Button key={r} type="button" variant={result === r ? 'primary' : 'secondary'} aria-pressed={result === r}
                      onClick={() => { setResult(r); if (r === 'NO_RESULT') setWinnerId(null) }}>
                {s.cupAdmin.resultOptions[r]}
              </Button>
            ))}
          </div>
          {result !== 'NO_RESULT' && (
            <Field label={result === 'WIN' ? s.cupAdmin.winner : s.cupAdmin.superOver} htmlFor="e-winner">
              <select id="e-winner" className="field" value={winnerId ?? ''} onChange={(e) => setWinnerId(e.target.value === '' ? null : Number(e.target.value))}>
                <option value="">—</option>
                {teamsOf.map((x) => <option key={x.teamId} value={x.teamId!}>{teams.get(x.teamId!)?.name ?? x.label}</option>)}
              </select>
            </Field>
          )}
          <Field label={s.cupAdmin.resultNote} htmlFor="e-note">
            <input id="e-note" className="field" maxLength={120} value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
          <Button className="w-full" busy={patch.isPending}
                  onClick={() => patch.mutate({ result, winnerId: result === 'NO_RESULT' ? null : winnerId, resultNote: note.trim() || null })}>
            {s.cupAdmin.saveResult}
          </Button>
        </section>
      )}

      {!finished && (
        <section className="space-y-3 border-t border-line pt-5">
          <p className="text-xs text-chalk-dim">{s.cupAdmin.abandonHint}</p>
          {confirmAbandon ? (
            <Button variant="danger" className="w-full" busy={patch.isPending} onClick={() => patch.mutate({ status: 'ABANDONED' })}>
              {s.cupAdmin.abandonYes}
            </Button>
          ) : (
            <Button variant="quiet" className="w-full" onClick={() => setConfirmAbandon(true)}>{s.cupAdmin.abandon}</Button>
          )}
        </section>
      )}
    </div>
  )
}
