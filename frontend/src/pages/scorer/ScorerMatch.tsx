import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeftRight, CloudOff, Flag, Loader2, RotateCcw, Trash2, Undo2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useParams } from 'react-router'
import { BallChips } from '../../components/BallChips'
import { useToast } from '../../components/toast-context'
import { Button, Chip, ErrorState, PageTitle, Sheet, Spinner } from '../../components/ui'
import { api, ApiError, NetworkError } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { overlay } from '../../lib/liveScore'
import {
  delivery,
  ends,
  hasFielder,
  lastBallId,
  namesOf,
  nextInningsTeams,
  noPicks,
  squadOf,
  step,
  waysOut,
  type Picks,
  type Tap,
} from '../../lib/scoring'
import type { AddDeliveryRequest, MatchDetail, WicketType } from '../../lib/tournamentTypes'
import { s } from '../../strings'
import { useBallQueue, type QueueState } from './useBallQueue'

/** Runs a scorer call and redraws from its answer. quietOffline: the caller shows its own offline banner. */
type Act = (call: () => Promise<MatchDetail>, ok?: string, quietOffline?: boolean) => Promise<boolean>

/**
 * The scorer's screen for one match: toss, openers, then one tap per ball. Each ball goes into the offline queue
 * (useBallQueue), which sends them in order whenever there is a connection; the screen shows the match as the server
 * last confirmed it with the waiting balls applied by the same rules (liveScore). The server scores every ball again
 * and its answer replaces the local one. The last confirmed match is kept on the phone, so the screen also opens
 * without a signal.
 */
export function ScorerMatch() {
  const { matchId } = useParams()
  const id = Number(matchId)
  const queryClient = useQueryClient()
  const toast = useToast()
  const match = useQuery({
    queryKey: ['match', id],
    queryFn: () => api<MatchDetail>(`/api/matches/${id}`),
    initialData: () => loadMatch(id),
    initialDataUpdatedAt: 0, // always fetch; the saved copy only bridges a missing signal
  })
  useEffect(() => {
    if (match.data) saveMatch(match.data)
  }, [match.data])
  const confirm = useCallback((m: MatchDetail) => queryClient.setQueryData(['match', id], m), [queryClient, id])
  const queue = useBallQueue(id, confirm)
  const [picks, setPicks] = useState<Picks>(noPicks)
  const [busy, setBusy] = useState(false)
  const inFlight = useRef(false)
  const act: Act = async (call, ok, quietOffline = false) => {
    if (inFlight.current) return false // a second tap while the first is on its way
    inFlight.current = true
    setBusy(true)
    try {
      const m = await call()
      queryClient.setQueryData(['match', id], m)
      setPicks(noPicks)
      if (ok) toast(ok)
      return true
    } catch (e) {
      if (e instanceof ApiError && e.body && typeof e.body.match === 'object' && e.body.match) {
        queryClient.setQueryData(['match', id], e.body.match as MatchDetail) // the match moved on: catch up
      }
      if (!(quietOffline && e instanceof NetworkError)) toast(errorMessage(e), 'error')
      return false
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  if (match.isPending) return <Spinner />
  if (!match.data) return <ErrorState message={errorMessage(match.error)} onRetry={() => match.refetch()} />
  const server = match.data
  // While the server refuses a ball, show only what it has recorded, not the refused ball and those after it.
  const waiting = queue.state.status === 'stuck'
    ? []
    : queue.items.filter((q) => q.inningsId === server.live?.inningsId).map((q) => q.ball)
  const { match: m, ended } = overlay(server, waiting)
  const pending = queue.items.length
  const current = pending > 0 && ended ? 'sendingLast' : step(m, picks)

  const scoring = current === 'ready' || current === 'newBatter' || current === 'newBowler'
  const teams = `${m.teamA.shortName ?? m.teamA.label} ${s.score.vs} ${m.teamB.shortName ?? m.teamB.label}`

  return (
    <div className="pb-6">
      {scoring ? (
        // While scoring, a one-line header, so the score and the whole pad fit on a phone screen together.
        <p className="mb-3 flex items-center justify-between gap-3 text-sm text-chalk-dim">
          <span>{s.score.matchNo(m.matchNo)} · <span className="font-semibold text-chalk">{teams}</span></span>
          <Chip tone="flood">{s.score.status[m.status]}</Chip>
        </p>
      ) : (
        <PageTitle
          kicker={s.score.matchNo(m.matchNo)}
          right={<Chip tone={m.status === 'LIVE' ? 'flood' : 'neutral'}>{s.score.status[m.status]}</Chip>}
        >
          {teams}
        </PageTitle>
      )}

      {current === 'toss' && <TossPanel m={m} busy={busy} act={act} />}
      {current === 'startInnings' && <StartPanel m={m} busy={busy} act={act} />}
      {scoring && (
        <LivePanel
          m={m}
          picks={picks}
          setPicks={setPicks}
          busy={busy || queue.state.status === 'stuck'}
          act={act}
          pending={pending}
          onBall={(inningsId, ball) => {
            queue.add(inningsId, ball)
            setPicks(noPicks)
          }}
          onUndo={() => {
            if (queue.undoLast()) toast(s.score.undone)
            else void act(() => api<MatchDetail>(`/api/deliveries/${lastBallId(server)}`, { method: 'DELETE' }), s.score.undone)
          }}
          canUndo={pending > 0 || lastBallId(server) !== null}
          alert={<QueueBar state={queue.state} pending={pending} onRetry={queue.resume} onDiscard={queue.discard} />}
        />
      )}
      {current === 'sendingLast' && (
        <Notice>
          <p className="flex items-center justify-center gap-2">
            <Loader2 className="size-4 animate-spin" aria-hidden /> {s.score.sendingLast(pending)}
          </p>
          <QueueBar state={queue.state} pending={pending} onRetry={queue.resume} onDiscard={queue.discard} />
          <Button variant="secondary" className="w-full" onClick={() => queue.undoLast() && toast(s.score.undone)}>
            <Undo2 className="size-4" aria-hidden /> {s.score.undo}
          </Button>
        </Notice>
      )}
      {current === 'awaitingResult' && (
        <Notice>
          {s.score.awaitingResult}
          <UndoButton m={m} busy={busy} act={act} />
        </Notice>
      )}
      {current === 'over' && (
        <Notice title={s.score.resultTitle}>
          <p className="font-display text-2xl font-bold uppercase">{m.resultText ?? s.score.status[m.status]}</p>
          {m.resultNote && <p className="text-sm text-chalk-dim">{m.resultNote}</p>}
          {m.status === 'DONE' && <UndoButton m={m} busy={busy} act={act} />}
        </Notice>
      )}
    </div>
  )
}

function Notice({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="space-y-3 rounded-2xl border border-line bg-pitch-2/80 p-5 text-center">
      {title && <h2 className="text-xs font-semibold uppercase tracking-[0.14em] text-chalk-dim">{title}</h2>}
      {children}
    </section>
  )
}

/** A big tap target that shows when it is chosen. */
function Choice({ selected, disabled, onClick, children }: {
  selected: boolean
  disabled?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      disabled={disabled}
      onClick={onClick}
      className={`min-h-12 rounded-xl border px-3 py-2 text-left text-base font-semibold transition disabled:opacity-35 ${
        selected ? 'border-flood bg-flood text-flood-ink' : 'border-line bg-turf text-chalk hover:bg-turf-2'
      }`}
    >
      {children}
    </button>
  )
}

function Label({ children }: { children: ReactNode }) {
  return <p className="mt-5 mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-chalk-dim">{children}</p>
}

// --- Toss ---

function TossPanel({ m, busy, act }: { m: MatchDetail; busy: boolean; act: Act }) {
  const [winner, setWinner] = useState<number | null>(null)
  const [decision, setDecision] = useState<'BAT' | 'BOWL' | null>(null)
  return (
    <section className="rounded-2xl border border-line bg-pitch-2/80 p-5">
      <h2 className="font-display text-2xl font-bold uppercase">{s.score.tossTitle}</h2>
      <Label>{s.score.tossWonBy}</Label>
      <div className="grid grid-cols-2 gap-3">
        {[m.teamA, m.teamB].map((side) => (
          <Choice key={side.teamId} selected={winner === side.teamId} onClick={() => setWinner(side.teamId)}>
            {side.name}
          </Choice>
        ))}
      </div>
      <Label>{s.score.tossChose}</Label>
      <div className="grid grid-cols-2 gap-3">
        <Choice selected={decision === 'BAT'} onClick={() => setDecision('BAT')}>{s.score.bat}</Choice>
        <Choice selected={decision === 'BOWL'} onClick={() => setDecision('BOWL')}>{s.score.bowl}</Choice>
      </div>
      <Button
        className="mt-6 w-full"
        busy={busy}
        disabled={winner === null || decision === null}
        onClick={() =>
          act(() => api<MatchDetail>(`/api/matches/${m.id}/toss`, { method: 'POST', body: { winnerId: winner, decision } }))
        }
      >
        {s.score.saveToss}
      </Button>
    </section>
  )
}

// --- Openers ---

function StartPanel({ m, busy, act }: { m: MatchDetail; busy: boolean; act: Act }) {
  const teams = nextInningsTeams(m)
  const [striker, setStriker] = useState<number | null>(null)
  const [nonStriker, setNonStriker] = useState<number | null>(null)
  const [bowler, setBowler] = useState<number | null>(null)
  if (!teams) return null
  const batting = squadOf(m, teams.battingTeamId)
  const bowling = squadOf(m, teams.bowlingTeamId)
  const battingName = m.teamA.teamId === teams.battingTeamId ? m.teamA.name : m.teamB.name
  const number = m.innings.length + 1

  return (
    <section className="rounded-2xl border border-line bg-pitch-2/80 p-5">
      <h2 className="font-display text-2xl font-bold uppercase">{s.score.startTitle(number)}</h2>
      <p className="text-sm text-chalk-dim">{s.score.battingNow(battingName ?? '')}</p>
      <Label>{s.score.striker}</Label>
      <div className="grid grid-cols-2 gap-2">
        {batting.map((c) => (
          <Choice key={c.cricketerId} selected={striker === c.cricketerId} disabled={nonStriker === c.cricketerId}
                  onClick={() => setStriker(c.cricketerId)}>{c.name}</Choice>
        ))}
      </div>
      <Label>{s.score.nonStriker}</Label>
      <div className="grid grid-cols-2 gap-2">
        {batting.map((c) => (
          <Choice key={c.cricketerId} selected={nonStriker === c.cricketerId} disabled={striker === c.cricketerId}
                  onClick={() => setNonStriker(c.cricketerId)}>{c.name}</Choice>
        ))}
      </div>
      <Label>{s.score.openingBowler}</Label>
      <div className="grid grid-cols-2 gap-2">
        {bowling.map((c) => (
          <Choice key={c.cricketerId} selected={bowler === c.cricketerId} onClick={() => setBowler(c.cricketerId)}>
            {c.name}
          </Choice>
        ))}
      </div>
      <Button
        className="mt-6 w-full"
        busy={busy}
        disabled={striker === null || nonStriker === null || bowler === null}
        onClick={() =>
          act(() =>
            api<MatchDetail>(`/api/matches/${m.id}/innings`, {
              method: 'POST',
              body: { strikerId: striker, nonStrikerId: nonStriker, bowlerId: bowler },
            }),
          )
        }
      >
        {s.score.start}
      </Button>
    </section>
  )
}

// --- Scoring ---

type SheetKind = 'wide' | 'noBall' | 'bye' | 'legBye' | 'wicket' | 'end'

const EXTRA_RUNS: Record<'wide' | 'noBall' | 'bye' | 'legBye', number[]> = {
  wide: [0, 1, 2, 3, 4],
  noBall: [0, 1, 2, 3, 4, 6],
  bye: [1, 2, 3, 4],
  legBye: [1, 2, 3, 4],
}

function LivePanel({ m, picks, setPicks, busy, act, pending, onBall, onUndo, canUndo, alert }: {
  m: MatchDetail
  picks: Picks
  setPicks: (p: Picks) => void
  busy: boolean
  act: Act
  /** Balls scored on this phone and not yet confirmed. */
  pending: number
  onBall: (inningsId: number, ball: AddDeliveryRequest) => void
  onUndo: () => void
  canUndo: boolean
  /** Shown right above the pad, where the scorer looks: the offline queue. */
  alert?: ReactNode
}) {
  const live = m.live!
  const inn = m.innings.find((i) => i.id === live.inningsId)!
  const names = namesOf(m)
  const current = step(m, picks)
  const e = ends(m, picks)
  const [sheet, setSheet] = useState<SheetKind | null>(null)
  // A pick sheet the scorer closed stays closed for this ball; it reopens with a button.
  const [dismissed, setDismissed] = useState<string | null>(null)
  const pickKey = `${current}:${live.nextSeq}`
  const battingSide = m.teamA.teamId === inn.battingTeamId ? m.teamA : m.teamB
  const bowlerId = live.bowlerId ?? picks.bowlerId
  const bowlerLine = inn.bowling.find((b) => b.cricketerId === bowlerId)
  const ready = current === 'ready' && e !== null && !busy

  const send = (tap: Tap) => {
    if (!e) return
    setSheet(null)
    onBall(live.inningsId, delivery(m, e, tap))
  }

  const batter = (id: number | null, onStrike: boolean) => {
    if (id === null) return <p className="text-chalk-dim">—</p>
    const line = inn.batting.find((b) => b.cricketerId === id)
    return (
      <p className={onStrike ? 'font-semibold' : 'text-chalk-dim'}>
        {names.get(id)}
        {onStrike && ' *'}
        <span className="ml-2 tabular-nums">{line ? `${line.runs} (${line.balls})` : '0 (0)'}</span>
      </p>
    )
  }

  return (
    <div className="space-y-3">
      <section className="rounded-2xl border border-line bg-pitch-2/80 p-4">
        <div className="flex items-baseline justify-between gap-3">
          <p className="font-display text-4xl font-bold tabular-nums">
            {inn.runs}/{inn.wickets}
          </p>
          <p className="text-lg tabular-nums text-chalk-dim">{s.score.overs(inn.overs)}</p>
        </div>
        <p className="mt-1 text-sm text-chalk-dim">
          {battingSide.shortName} · {s.score.runRate(String(live.currentRunRate))}
          {live.runsNeeded !== null && (
            <>
              {' · '}
              <span className="font-semibold text-flood">{s.score.need(live.runsNeeded, live.ballsLeft)}</span>
              {live.requiredRunRate !== null && ` · ${s.score.requiredRate(String(live.requiredRunRate))}`}
            </>
          )}
        </p>
        {live.freeHit && <div className="mt-2"><Chip tone="amber">{s.score.freeHit}</Chip></div>}
        <div className="mt-3 flex items-start justify-between gap-3 border-t border-line pt-3">
          <div className="space-y-1">
            {batter(e?.strikerId ?? live.strikerId, true)}
            {batter(e?.nonStrikerId ?? live.nonStrikerId, false)}
          </div>
          <Button variant="secondary" disabled={!e || busy} onClick={() => setPicks({ ...picks, swapped: !picks.swapped })}
                  aria-pressed={picks.swapped}>
            <ArrowLeftRight className="size-4" aria-hidden /> {s.score.swap}
          </Button>
        </div>
        <div className="mt-3 border-t border-line pt-3 text-sm">
          {bowlerId !== null ? (
            <p>
              {names.get(bowlerId)}
              {bowlerLine && (
                <span className="ml-2 tabular-nums text-chalk-dim">
                  {s.score.figures(bowlerLine.overs, bowlerLine.maidens, bowlerLine.runs, bowlerLine.wickets)}
                </span>
              )}
            </p>
          ) : (
            <p className="text-chalk-dim">{s.score.newOverNext}</p>
          )}
        </div>
        <div className="mt-3">
          <BallChips balls={live.thisOver} label={s.score.thisOver} />
        </div>
      </section>

      {alert}
      {current === 'newBatter' && (
        <Button className="w-full" onClick={() => setDismissed(null)}>{s.score.pickNewBatter}</Button>
      )}
      {current === 'newBowler' && (
        <Button className="w-full" onClick={() => setDismissed(null)}>{s.score.pickBowler}</Button>
      )}

      <section className="grid grid-cols-3 gap-2" aria-label={s.score.title}>
        {[0, 1, 2, 3, 4, 6].map((r) => (
          <PadButton key={r} disabled={!ready} onClick={() => send({ kind: 'runs', runs: r })} tone={r >= 4 ? 'flood' : 'plain'}>
            {r}
          </PadButton>
        ))}
        <PadButton disabled={!ready} onClick={() => setSheet('wide')} tone="extra">{s.score.pad.wide}</PadButton>
        <PadButton disabled={!ready} onClick={() => setSheet('noBall')} tone="extra">{s.score.pad.noBall}</PadButton>
        <PadButton disabled={!ready} onClick={() => setSheet('bye')} tone="extra">{s.score.pad.bye}</PadButton>
        <PadButton disabled={!ready} onClick={() => setSheet('legBye')} tone="extra">{s.score.pad.legBye}</PadButton>
        <PadButton disabled={!ready} onClick={() => setSheet('wicket')} tone="out">{s.score.pad.wicket}</PadButton>
        <PadButton tone="plain" disabled={!canUndo || busy} onClick={onUndo}>
          <span className="flex items-center justify-center gap-1 text-xl">
            <Undo2 className="size-5" aria-hidden /> {s.score.undo}
          </span>
        </PadButton>
      </section>

      <Button variant="quiet" className="w-full" disabled={busy || pending > 0} onClick={() => setSheet('end')}>
        <Flag className="size-4" aria-hidden /> {pending > 0 ? s.score.endInningsAfterSync : s.score.endInnings}
      </Button>

      {(['wide', 'noBall', 'bye', 'legBye'] as const).map((kind) => (
        <Sheet key={kind} open={sheet === kind} onClose={() => setSheet(null)} title={s.score.extraTitle[kind]}>
          <div className="grid grid-cols-3 gap-2">
            {EXTRA_RUNS[kind].map((r) => (
              <PadButton
                key={r}
                disabled={!ready}
                tone="plain"
                onClick={() =>
                  send(kind === 'wide' ? { kind, ran: r } : kind === 'noBall' ? { kind, bat: r } : { kind, runs: r })
                }
              >
                {r}
              </PadButton>
            ))}
          </div>
        </Sheet>
      ))}

      <Sheet open={sheet === 'wicket'} onClose={() => setSheet(null)} title={s.score.wicketTitle}>
        {e && <WicketForm m={m} ends={e} onOut={send} />}
      </Sheet>

      <Sheet open={sheet === 'end'} onClose={() => setSheet(null)} title={s.score.endInnings}>
        <p className="text-sm text-chalk-dim">{s.score.endInningsHint}</p>
        <Button
          variant="danger"
          className="mt-5 w-full"
          busy={busy}
          onClick={async () => {
            if (await act(() => api<MatchDetail>(`/api/innings/${inn.id}/end`, { method: 'POST', body: { reason: 'CLOSED' } }))) {
              setSheet(null)
            }
          }}
        >
          {s.score.endInningsYes}
        </Button>
      </Sheet>

      <Sheet
        open={current === 'newBatter' && dismissed !== pickKey}
        onClose={() => setDismissed(pickKey)}
        title={s.score.newBatterTitle}
      >
        <p className="mb-3 text-sm text-chalk-dim">{s.score.newBatterHint}</p>
        <div className="grid grid-cols-2 gap-2">
          {live.availableBatterIds.map((id) => (
            <Choice key={id} selected={false} onClick={() => setPicks({ ...picks, newBatterId: id })}>
              {names.get(id)}
            </Choice>
          ))}
        </div>
      </Sheet>

      <Sheet
        open={current === 'newBowler' && dismissed !== pickKey}
        onClose={() => setDismissed(pickKey)}
        title={s.score.newBowlerTitle}
      >
        <p className="mb-3 text-sm text-chalk-dim">{s.score.newBowlerHint}</p>
        <div className="grid grid-cols-2 gap-2">
          {live.eligibleBowlerIds.map((id) => (
            <Choice key={id} selected={false} onClick={() => setPicks({ ...picks, bowlerId: id })}>
              {names.get(id)}
            </Choice>
          ))}
        </div>
      </Sheet>
    </div>
  )
}

function PadButton({ tone, disabled, onClick, children }: {
  tone: 'plain' | 'flood' | 'extra' | 'out'
  disabled?: boolean
  onClick: () => void
  children: ReactNode
}) {
  const tones = {
    plain: 'border-line bg-turf text-chalk hover:bg-turf-2',
    flood: 'border-flood/50 bg-turf text-flood hover:bg-turf-2',
    extra: 'border-amber/40 bg-amber/10 text-amber hover:bg-amber/20',
    out: 'border-red/50 bg-red/15 text-red hover:bg-red/25',
  }
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`min-h-14 rounded-2xl border font-display text-3xl font-bold uppercase transition active:translate-y-px disabled:opacity-35 ${tones[tone]}`}
    >
      {children}
    </button>
  )
}

function WicketForm({ m, ends: e, onOut }: {
  m: MatchDetail
  ends: { strikerId: number; nonStrikerId: number; bowlerId: number }
  onOut: (tap: Tap) => void
}) {
  const live = m.live!
  const names = namesOf(m)
  const inn = m.innings.find((i) => i.id === live.inningsId)!
  const fielders = squadOf(m, inn.bowlingTeamId)
  const [how, setHow] = useState<WicketType | null>(null)
  const [out, setOut] = useState<'striker' | 'nonStriker'>('striker')
  const [ran, setRan] = useState(0)
  const [fielderId, setFielderId] = useState<number | null>(null)
  const runOut = how === 'RUN_OUT'

  return (
    <div>
      <Label>{s.score.howOut}</Label>
      <div className="grid grid-cols-2 gap-2">
        {waysOut(m.rules, live.freeHit).map((w) => (
          <Choice key={w} selected={how === w} onClick={() => { setHow(w); if (w !== 'RUN_OUT') setOut('striker') }}>
            {s.score.how[w]}
          </Choice>
        ))}
      </div>
      {runOut && (
        <>
          <Label>{s.score.whoOut}</Label>
          <div className="grid grid-cols-2 gap-2">
            <Choice selected={out === 'striker'} onClick={() => setOut('striker')}>{names.get(e.strikerId)}</Choice>
            <Choice selected={out === 'nonStriker'} onClick={() => setOut('nonStriker')}>{names.get(e.nonStrikerId)}</Choice>
          </div>
          <Label>{s.score.runsCompleted}</Label>
          <div className="grid grid-cols-4 gap-2">
            {[0, 1, 2, 3].map((r) => <Choice key={r} selected={ran === r} onClick={() => setRan(r)}>{r}</Choice>)}
          </div>
        </>
      )}
      {how && hasFielder(how) && (
        <>
          <Label>{s.score.fielder}</Label>
          <div className="grid grid-cols-2 gap-2">
            {fielders.map((c) => (
              <Choice key={c.cricketerId} selected={fielderId === c.cricketerId}
                      onClick={() => setFielderId(fielderId === c.cricketerId ? null : c.cricketerId)}>
                {c.name}
              </Choice>
            ))}
          </div>
        </>
      )}
      <Button
        variant="danger"
        className="mt-6 w-full"
        disabled={how === null}
        onClick={() => how && onOut({ kind: 'wicket', how, out, ran: runOut ? ran : 0, fielderId })}
      >
        {s.score.confirmOut}
      </Button>
    </div>
  )
}

function useUndo(m: MatchDetail, act: Act) {
  const ball = lastBallId(m)
  return {
    possible: ball !== null,
    undo: () => ball && act(() => api<MatchDetail>(`/api/deliveries/${ball}`, { method: 'DELETE' }), s.score.undone),
  }
}

/** The offline queue's state, above the pad: no signal, sending, or a refused ball. */
function QueueBar({ state, pending, onRetry, onDiscard }: {
  state: QueueState
  pending: number
  onRetry: () => void
  onDiscard: () => void
}) {
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  if (state.status === 'stuck') {
    return (
      <div role="alert" className="space-y-3 rounded-2xl border border-red/40 bg-red/10 p-3 text-left">
        <p className="text-sm">{s.score.queueStuck(state.message, pending)}</p>
        <div className="grid grid-cols-2 gap-2">
          <Button onClick={onRetry}><RotateCcw className="size-4" aria-hidden /> {s.common.retry}</Button>
          <Button variant="danger" onClick={() => setConfirmDiscard(true)}>
            <Trash2 className="size-4" aria-hidden /> {s.score.discardShort}
          </Button>
        </div>
        <Sheet open={confirmDiscard} onClose={() => setConfirmDiscard(false)} title={s.score.discard}>
          <p className="text-sm text-chalk-dim">{s.score.discardHint(pending)}</p>
          <Button variant="danger" className="mt-5 w-full" onClick={() => { setConfirmDiscard(false); onDiscard() }}>
            {s.score.discardYes(pending)}
          </Button>
        </Sheet>
      </div>
    )
  }
  if (pending === 0) return null
  if (state.status === 'offline') {
    return (
      <div role="status" className="flex items-center gap-3 rounded-2xl border border-amber/40 bg-amber/10 p-3 text-left">
        <CloudOff className="size-5 shrink-0 text-amber" aria-hidden />
        <p className="text-sm">{s.score.queueOffline(pending)}</p>
      </div>
    )
  }
  return (
    <p role="status" className="flex items-center gap-2 text-xs text-chalk-dim">
      <Loader2 className="size-3 animate-spin" aria-hidden /> {s.score.queueSending(pending)}
    </p>
  )
}

/** The match as last confirmed, kept on the phone so the scorer screen opens without a signal. */
function loadMatch(id: number): MatchDetail | undefined {
  try {
    const raw = localStorage.getItem(`turfup.match.${id}`)
    return raw ? (JSON.parse(raw) as MatchDetail) : undefined
  } catch {
    return undefined
  }
}

function saveMatch(m: MatchDetail) {
  try {
    localStorage.setItem(`turfup.match.${m.id}`, JSON.stringify(m))
  } catch {
    // Storage full or blocked: the screen still works online.
  }
}

function UndoButton({ m, busy, act }: { m: MatchDetail; busy: boolean; act: Act }) {
  const { possible, undo } = useUndo(m, act)
  if (!possible) return null
  return (
    <Button variant="secondary" className="w-full" busy={busy} onClick={() => void undo()}>
      <Undo2 className="size-4" aria-hidden /> {s.score.undo}
    </Button>
  )
}
