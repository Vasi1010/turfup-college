import type { AddDeliveryRequest, MatchDetail, MatchRules, WicketType } from './tournamentTypes'

/**
 * The scorer screen's logic, kept out of the components so it can be tested: what the screen is waiting for, who is
 * on for the next ball, and the ball a tap makes. The server checks every ball again (the scoring engine).
 */

/** A random UUID (v4). crypto.randomUUID needs a secure context, which a phone on the LAN over http is not. */
export function uuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/** What the scorer picked on top of the server's suggestion, for the next ball only. */
export interface Picks {
  /** The batter coming in at the empty end after a wicket. */
  newBatterId: number | null
  /** The bowler of a new over. */
  bowlerId: number | null
  /** The scorer swapped the suggested ends (who's on strike). */
  swapped: boolean
}

export const noPicks: Picks = { newBatterId: null, bowlerId: null, swapped: false }

export type Step =
  | 'toss'
  | 'startInnings'
  | 'newBatter'
  | 'newBowler'
  | 'ready'
  /** No innings is being played and the match isn't decided: closed early (rain); an admin records the result. */
  | 'awaitingResult'
  | 'over'

export function step(m: MatchDetail, picks: Picks): Step {
  if (m.status === 'DONE' || m.status === 'ABANDONED') return 'over'
  if (!m.toss) return 'toss'
  const live = m.live
  if (!live) {
    const first = m.innings[0]
    if (m.innings.length === 0 || (m.innings.length === 1 && first.status === 'DONE')) return 'startInnings'
    return 'awaitingResult'
  }
  if (live.needsNewBatter && picks.newBatterId === null) return 'newBatter'
  if (live.bowlerId === null && picks.bowlerId === null) return 'newBowler'
  return 'ready'
}

export interface Ends {
  strikerId: number
  nonStrikerId: number
  bowlerId: number
}

/** Who faces, who's at the other end and who bowls the next ball; null until all three are known. */
export function ends(m: MatchDetail, picks: Picks): Ends | null {
  const live = m.live
  if (!live) return null
  let striker = live.strikerId
  let nonStriker = live.nonStrikerId
  if (striker === null) striker = picks.newBatterId
  else if (nonStriker === null) nonStriker = picks.newBatterId
  const bowler = live.bowlerId ?? picks.bowlerId
  if (striker === null || nonStriker === null || bowler === null) return null
  return picks.swapped
    ? { strikerId: nonStriker, nonStrikerId: striker, bowlerId: bowler }
    : { strikerId: striker, nonStrikerId: nonStriker, bowlerId: bowler }
}

/** One tap on the pad (with its sheet's choice, for extras and wickets). */
export type Tap =
  | { kind: 'runs'; runs: number }
  /** Runs taken on a wide, on top of the wide itself. */
  | { kind: 'wide'; ran: number }
  /** Runs off the bat on a no-ball. */
  | { kind: 'noBall'; bat: number }
  | { kind: 'bye'; runs: number }
  | { kind: 'legBye'; runs: number }
  /** ran: runs completed before a run out (0 otherwise). */
  | { kind: 'wicket'; how: WicketType; out: 'striker' | 'nonStriker'; ran: number; fielderId: number | null }

/** The ball a tap makes, for POST /api/innings/{id}/deliveries. */
export function delivery(m: MatchDetail, e: Ends, tap: Tap, id: string = uuid()): AddDeliveryRequest {
  const base: AddDeliveryRequest = {
    id,
    seq: m.live?.nextSeq ?? 1,
    strikerId: e.strikerId,
    nonStrikerId: e.nonStrikerId,
    bowlerId: e.bowlerId,
    runsBat: 0,
    extraType: null,
    extraRuns: 0,
    wicketType: null,
    playerOutId: null,
    fielderId: null,
  }
  switch (tap.kind) {
    case 'runs':
      return { ...base, runsBat: tap.runs }
    case 'wide':
      return { ...base, extraType: 'WD', extraRuns: m.rules.wideRuns + tap.ran }
    case 'noBall':
      return { ...base, extraType: 'NB', extraRuns: m.rules.noBallRuns, runsBat: tap.bat }
    case 'bye':
      return { ...base, extraType: 'B', extraRuns: tap.runs }
    case 'legBye':
      return { ...base, extraType: 'LB', extraRuns: tap.runs }
    case 'wicket':
      return {
        ...base,
        runsBat: tap.how === 'RUN_OUT' ? tap.ran : 0,
        wicketType: tap.how,
        playerOutId: tap.out === 'striker' ? e.strikerId : e.nonStrikerId,
        fielderId: hasFielder(tap.how) ? tap.fielderId : null,
      }
  }
}

/** The ways out the scorer can pick: no LBW when the local rules say so, and only a run out on a free hit. */
export function waysOut(rules: MatchRules, freeHit: boolean): WicketType[] {
  if (freeHit) return ['RUN_OUT']
  const all: WicketType[] = ['BOWLED', 'CAUGHT', 'LBW', 'RUN_OUT', 'STUMPED', 'HIT_WICKET']
  return rules.noLbw ? all.filter((w) => w !== 'LBW') : all
}

export function hasFielder(how: WicketType): boolean {
  return how === 'CAUGHT' || how === 'RUN_OUT' || how === 'STUMPED'
}

/** Every cricketer's name in the match, by id. */
export function namesOf(m: MatchDetail): Map<number, string> {
  const names = new Map<number, string>()
  for (const side of [m.teamA, m.teamB]) for (const c of side.squad) names.set(c.cricketerId, c.name)
  return names
}

/** Who bats and who bowls in the innings to start: the toss decides the first, the second swaps them. */
export function nextInningsTeams(m: MatchDetail): { battingTeamId: number; bowlingTeamId: number } | null {
  const a = m.teamA.teamId
  const b = m.teamB.teamId
  if (a === null || b === null || !m.toss) return null
  if (m.innings.length === 1) {
    return { battingTeamId: m.innings[0].bowlingTeamId, bowlingTeamId: m.innings[0].battingTeamId }
  }
  const winner = m.toss.winnerId
  const other = winner === a ? b : a
  return m.toss.decision === 'BAT'
    ? { battingTeamId: winner, bowlingTeamId: other }
    : { battingTeamId: other, bowlingTeamId: winner }
}

export function squadOf(m: MatchDetail, teamId: number): { cricketerId: number; name: string }[] {
  return m.teamA.teamId === teamId ? m.teamA.squad : m.teamB.teamId === teamId ? m.teamB.squad : []
}

/** The ball an undo removes: the latest of the latest innings (also after it ended); null if none. */
export function lastBallId(m: MatchDetail): string | null {
  return m.innings.length ? m.innings[m.innings.length - 1].lastDeliveryId : null
}
