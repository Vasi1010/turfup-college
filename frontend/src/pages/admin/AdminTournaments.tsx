import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronRight, Plus, Trophy } from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { Button, EmptyState, ErrorState, InlineError, PageTitle, Sheet, Spinner } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { dayOf } from '../../lib/format'
import type { CreateTournamentRequest, TournamentDetail, TournamentSummary } from '../../lib/tournamentTypes'
import { s } from '../../strings'

/** The admin's tournaments, and creating one with its rules (task B10). */
export function AdminTournaments() {
  const list = useQuery({ queryKey: ['tournaments'], queryFn: () => api<TournamentSummary[]>('/api/tournaments') })
  const [creating, setCreating] = useState(false)

  return (
    <div>
      <PageTitle
        kicker={s.cupAdmin.kicker}
        right={<Button onClick={() => setCreating(true)}><Plus className="size-4" aria-hidden /> {s.cupAdmin.newTournament}</Button>}
      >
        {s.cupAdmin.title}
      </PageTitle>
      {list.isPending && <Spinner />}
      {list.isError && <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} />}
      {list.data?.length === 0 && <EmptyState title={s.cupAdmin.none} />}
      <ul className="space-y-3">
        {list.data?.map((t) => (
          <li key={t.id}>
            <Link to={`/admin/tournaments/${t.id}`} className="flex items-center gap-3 rounded-2xl border border-line bg-pitch-2/80 p-4 hover:bg-turf">
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
      <Sheet open={creating} onClose={() => setCreating(false)} title={s.cupAdmin.newTournament}>
        <CreateForm />
      </Sheet>
    </div>
  )
}

export function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: ReactNode }) {
  return (
    <div>
      <label className="label" htmlFor={htmlFor}>{label}</label>
      {children}
    </div>
  )
}

function CreateForm() {
  const toast = useToast()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [f, setF] = useState({
    name: '', venueName: '', startDate: '', endDate: '', oversPerInnings: '8', maxOversPerBowler: '2',
    ballsPerOver: '6', playersPerSide: '8', wideRuns: '1', noBallRuns: '1', pointsWin: '2', pointsTie: '1',
    pointsNoResult: '1', tiebreaker: 'NRR', noLbw: false, freeHit: false,
  })
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const create = useMutation({
    mutationFn: (body: CreateTournamentRequest) => api<TournamentDetail>('/api/tournaments', { method: 'POST', body }),
    onSuccess: (t) => {
      void queryClient.invalidateQueries({ queryKey: ['tournaments'] })
      toast(s.cupAdmin.created)
      navigate(`/admin/tournaments/${t.id}`)
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate({
      name: f.name, venueName: f.venueName, startDate: f.startDate, endDate: f.endDate,
      rules: {
        oversPerInnings: Number(f.oversPerInnings),
        maxOversPerBowler: f.maxOversPerBowler.trim() === '' ? null : Number(f.maxOversPerBowler),
        ballsPerOver: Number(f.ballsPerOver), playersPerSide: Number(f.playersPerSide),
        wideRuns: Number(f.wideRuns), noBallRuns: Number(f.noBallRuns), pointsWin: Number(f.pointsWin),
        pointsTie: Number(f.pointsTie), pointsNoResult: Number(f.pointsNoResult),
        tiebreaker: f.tiebreaker as 'NRR' | 'HEAD_TO_HEAD',
        localRules: { noLbw: f.noLbw, freeHit: f.freeHit, lastManStands: false },
      },
    })
  }
  const number = (k: keyof typeof f, label: string, required = true) => (
    <Field label={label} htmlFor={`t-${k}`}>
      <input id={`t-${k}`} type="number" inputMode="numeric" min={0} className="field num" required={required}
             value={f[k] as string} onChange={set(k)} />
    </Field>
  )

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field label={s.cupAdmin.name} htmlFor="t-name">
        <input id="t-name" className="field" required maxLength={120} value={f.name} onChange={set('name')} />
      </Field>
      <Field label={s.cupAdmin.venue} htmlFor="t-venue">
        <input id="t-venue" className="field" required maxLength={120} value={f.venueName} onChange={set('venueName')} />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={s.cupAdmin.startDate} htmlFor="t-start">
          <input id="t-start" type="date" className="field" required value={f.startDate} onChange={set('startDate')} />
        </Field>
        <Field label={s.cupAdmin.endDate} htmlFor="t-end">
          <input id="t-end" type="date" className="field" required value={f.endDate} onChange={set('endDate')} />
        </Field>
      </div>
      <p className="label pt-2">{s.cupAdmin.rules}</p>
      <div className="grid grid-cols-2 gap-3">
        {number('oversPerInnings', s.cupAdmin.overs)}
        {number('maxOversPerBowler', s.cupAdmin.maxOvers, false)}
        {number('ballsPerOver', s.cupAdmin.ballsPerOver)}
        {number('playersPerSide', s.cupAdmin.playersPerSide)}
        {number('wideRuns', s.cupAdmin.wideRuns)}
        {number('noBallRuns', s.cupAdmin.noBallRuns)}
        {number('pointsWin', s.cupAdmin.pointsWin)}
        {number('pointsTie', s.cupAdmin.pointsTie)}
        {number('pointsNoResult', s.cupAdmin.pointsNoResult)}
      </div>
      <Field label={s.cupAdmin.tiebreaker} htmlFor="t-tiebreaker">
        <select id="t-tiebreaker" className="field" value={f.tiebreaker} onChange={set('tiebreaker')}>
          {Object.entries(s.cupAdmin.tiebreakers).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </Field>
      {(['noLbw', 'freeHit'] as const).map((k) => (
        <label key={k} className="flex items-center gap-3">
          <input type="checkbox" className="size-5 accent-flood" checked={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.checked })} />
          {s.cupAdmin[k]}
        </label>
      ))}
      <InlineError message={create.isError ? errorMessage(create.error) : null} />
      <Button type="submit" className="w-full" busy={create.isPending}>{s.cupAdmin.create}</Button>
    </form>
  )
}
