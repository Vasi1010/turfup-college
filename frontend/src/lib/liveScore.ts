import { namesOf, squadOf } from './scoring'
import type {
  AddDeliveryRequest,
  BallSummary,
  BattingLine,
  BowlingLine,
  InningsCard,
  InningsEndReason,
  LiveState,
  MatchDetail,
  WicketType,
} from './tournamentTypes'

/**
 * The scorer screen's offline engine: the match as the server last confirmed it, plus the balls still waiting on
 * this phone, applied with the same rules as the server's scoring engine (InningsScorer): strike changes on odd runs
 * completed and at the end of an over, wides and no-balls are not legal balls, a free hit follows a no-ball until a
 * legal ball, the innings ends on overs, all out or the target. The result has the server's shape, so the screen
 * draws it the same way. When the balls reach the server, its answer replaces this.
 */
export interface Overlaid {
  match: MatchDetail
  /** The waiting balls end the innings: no more balls until they are sent. */
  ended: InningsEndReason | null
}

const BOWLER_WICKETS: WicketType[] = ['BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET']

export function overlay(m: MatchDetail, queued: AddDeliveryRequest[]): Overlaid {
  const live = m.live
  // Balls the server already has (its answer arrived before the queue let go of them) are not applied twice.
  const waiting = live ? queued.filter((d) => d.seq >= live.nextSeq) : []
  if (waiting.length === 0 || !live) return { match: m, ended: null }
  const r = m.rules
  const bpo = r.ballsPerOver
  const inn = m.innings.find((i) => i.id === live.inningsId)!
  const names = namesOf(m)
  const battingSquad = squadOf(m, inn.battingTeamId).map((c) => c.cricketerId)
  const bowlingSquad = squadOf(m, inn.bowlingTeamId).map((c) => c.cricketerId)
  const allOut = Math.min(r.playersPerSide - 1, battingSquad.length - 1)
  const maxBalls = r.oversPerInnings * bpo

  let runs = inn.runs
  let wickets = inn.wickets
  let legal = inn.legalBalls
  let striker = live.strikerId
  let nonStriker = live.nonStrikerId
  // Before the first ball live.bowlerId is the opening bowler, but no over has started yet.
  let overBowler = live.newOver ? null : live.bowlerId
  let lastOverBowler = live.lastOverBowlerId
  let ballsThisOver = live.newOver ? 0 : live.thisOver.length
  let overBowlerRuns = live.newOver ? 0 : live.thisOver.reduce((sum, b) => sum + bowlerRunsOf(b), 0)
  let freeHit = live.freeHit
  let ended: InningsEndReason | null = null
  let thisOver = live.newOver ? [] : [...live.thisOver]
  const recent = [...live.recentBalls]
  const batting = new Map(inn.batting.map((b) => [b.cricketerId, { ...b }]))
  const bowling = new Map(inn.bowling.map((b) => [b.cricketerId, { ...b }]))
  const extras = { ...inn.extras }
  const fallOfWickets = [...inn.fallOfWickets]

  const batLine = (id: number): BattingLine => {
    if (!batting.has(id)) {
      batting.set(id, { cricketerId: id, name: names.get(id) ?? '', status: 'BATTING', dismissal: null, runs: 0,
        balls: 0, fours: 0, sixes: 0, strikeRate: 0, onStrike: false })
    }
    return batting.get(id)!
  }
  const bowlLine = (id: number): BowlingLine => {
    if (!bowling.has(id)) {
      bowling.set(id, { cricketerId: id, name: names.get(id) ?? '', legalBalls: 0, overs: '0.0', maidens: 0, runs: 0,
        wickets: 0, wides: 0, noBalls: 0, economy: 0, bowling: false })
    }
    return bowling.get(id)!
  }

  for (const d of waiting) {
    if (ended) break
    batLine(d.strikerId)
    batLine(d.nonStrikerId)
    striker = d.strikerId
    nonStriker = d.nonStrikerId
    if (ballsThisOver === 0) {
      overBowler = d.bowlerId
      overBowlerRuns = 0
    }
    const isLegal = d.extraType === null || d.extraType === 'B' || d.extraType === 'LB'
    const bowlerRuns = d.runsBat + (d.extraType === 'WD' || d.extraType === 'NB' ? d.extraRuns : 0)
    const penalty = d.extraType === 'WD' ? r.wideRuns : d.extraType === 'NB' ? r.noBallRuns : 0
    const wasFreeHit = freeHit

    const bat = batLine(d.strikerId)
    if (d.extraType !== 'WD') bat.balls++
    bat.runs += d.runsBat
    if (d.runsBat === 4) bat.fours++
    if (d.runsBat === 6) bat.sixes++
    const bowl = bowlLine(d.bowlerId)
    bowl.runs += bowlerRuns
    overBowlerRuns += bowlerRuns
    if (d.extraType === 'WD') {
      bowl.wides++
      extras.wides += d.extraRuns
    } else if (d.extraType === 'NB') {
      bowl.noBalls++
      extras.noBalls += d.extraRuns
    } else if (d.extraType === 'B') extras.byes += d.extraRuns
    else if (d.extraType === 'LB') extras.legByes += d.extraRuns
    extras.total = extras.wides + extras.noBalls + extras.byes + extras.legByes
    runs += d.runsBat + d.extraRuns
    if (isLegal) {
      legal++
      bowl.legalBalls++
    }
    if ((d.runsBat + d.extraRuns - penalty) % 2 === 1) [striker, nonStriker] = [nonStriker, striker]
    if (d.wicketType !== null && d.playerOutId !== null) {
      const out = batLine(d.playerOutId)
      out.status = 'OUT'
      out.dismissal = dismissal(d.wicketType, d.bowlerId, d.fielderId, names)
      if (BOWLER_WICKETS.includes(d.wicketType)) bowl.wickets++
      if (d.wicketType !== 'RETIRED_HURT') {
        wickets++
        fallOfWickets.push({ wicket: wickets, runs, overs: overs(legal, bpo), cricketerId: d.playerOutId,
          name: names.get(d.playerOutId) ?? '' })
      }
      if (striker === d.playerOutId) striker = null
      else nonStriker = null
    }
    if (r.freeHit && d.extraType === 'NB') freeHit = true
    else if (isLegal) freeHit = false

    const ball: BallSummary = { deliveryId: d.id, label: label(d, r.noBallRuns), runs: d.runsBat + d.extraRuns,
      legal: isLegal, wicket: d.wicketType !== null,
      boundary: d.extraType !== 'WD' && (d.runsBat === 4 || d.runsBat === 6), freeHit: wasFreeHit }
    thisOver.push(ball)
    recent.push(ball)
    ballsThisOver++
    if (isLegal && legal % bpo === 0) {
      if (overBowlerRuns === 0) bowl.maidens++
      ;[striker, nonStriker] = [nonStriker, striker]
      lastOverBowler = overBowler
      overBowler = null
      ballsThisOver = 0
      thisOver = []
    }
    if (inn.target !== null && runs >= inn.target) ended = 'TARGET'
    else if (wickets >= allOut) ended = 'ALL_OUT'
    else if (legal >= maxBalls) ended = 'OVERS'
  }

  const nextSeq = live.nextSeq + waiting.length
  for (const b of batting.values()) {
    b.strikeRate = b.balls ? round((b.runs * 100) / b.balls, 1) : 0
    b.onStrike = !ended && b.cricketerId === striker && b.status !== 'OUT'
  }
  for (const b of bowling.values()) {
    b.overs = overs(b.legalBalls, bpo)
    b.economy = b.legalBalls ? round((b.runs * bpo) / b.legalBalls, 2) : 0
    b.bowling = !ended && b.cricketerId === overBowler
  }
  const card: InningsCard = {
    ...inn,
    runs,
    wickets,
    legalBalls: legal,
    overs: overs(legal, bpo),
    status: ended ? 'DONE' : 'LIVE',
    endReason: ended,
    extras,
    batting: [...batting.values()],
    yetToBat: inn.yetToBat.filter((y) => !batting.has(y.cricketerId)),
    bowling: [...bowling.values()],
    fallOfWickets,
    lastDeliveryId: waiting[waiting.length - 1].id,
  }
  const runsNeeded = inn.target === null ? null : Math.max(0, inn.target - runs)
  const ballsLeft = maxBalls - legal
  const limit = r.maxOversPerBowler
  const started = (id: number) => Math.ceil((bowling.get(id)?.legalBalls ?? 0) / bpo)
  const newOver = ballsThisOver === 0
  const nextLive: LiveState | null = ended ? null : {
    ...live,
    strikerId: striker,
    nonStrikerId: nonStriker,
    bowlerId: overBowler,
    lastOverBowlerId: lastOverBowler,
    nextSeq,
    thisOver,
    recentBalls: recent.slice(-12),
    currentRunRate: legal ? round((runs * bpo) / legal, 2) : 0,
    runsNeeded,
    ballsLeft,
    requiredRunRate: runsNeeded === null || ballsLeft === 0 ? null : round((runsNeeded * bpo) / ballsLeft, 2),
    eligibleBowlerIds: newOver
      ? bowlingSquad.filter((id) => id !== lastOverBowler && (limit === null || started(id) < limit))
      : [overBowler!],
    availableBatterIds: battingSquad.filter((id) => !batting.has(id)),
    newOver,
    needsNewBatter: striker === null || nonStriker === null,
    freeHit,
  }
  return {
    match: { ...m, innings: m.innings.map((i) => (i.id === inn.id ? card : i)), live: nextLive },
    ended,
  }
}

/** Runs a ball charged to the bowler: everything except byes and leg byes ("2b", "1lb"). */
function bowlerRunsOf(b: BallSummary): number {
  return /^\d+(b|lb)W?$/.test(b.label) ? 0 : b.runs
}

/** The same labels as the server: "0", "4", "W", "1W", "1wd", "5wd", "nb", "nb4", "nb+2", "2b", "1lb". */
export function label(d: AddDeliveryRequest, noBallRuns: number): string {
  const w = d.wicketType !== null ? 'W' : ''
  switch (d.extraType) {
    case null:
      return w ? (d.runsBat > 0 ? `${d.runsBat}${w}` : w) : String(d.runsBat)
    case 'WD':
      return `${d.extraRuns}wd${w}`
    case 'NB': {
      const byes = d.extraRuns - noBallRuns
      return `nb${d.runsBat > 0 ? d.runsBat : byes > 0 ? `+${byes}` : ''}${w}`
    }
    case 'B':
      return `${d.extraRuns}b${w}`
    case 'LB':
      return `${d.extraRuns}lb${w}`
  }
}

function dismissal(how: WicketType, bowlerId: number, fielderId: number | null, names: Map<number, string>): string {
  const surname = (id: number | null) => (id === null ? null : (names.get(id) ?? '?').trim().split(/\s+/).pop()!)
  const bowler = surname(bowlerId)
  const fielder = surname(fielderId)
  switch (how) {
    case 'BOWLED':
      return `b ${bowler}`
    case 'CAUGHT':
      return fielderId === bowlerId ? `c & b ${bowler}` : `${fielder ? `c ${fielder}` : 'c'} b ${bowler}`
    case 'LBW':
      return `lbw b ${bowler}`
    case 'RUN_OUT':
      return fielder ? `run out (${fielder})` : 'run out'
    case 'STUMPED':
      return `${fielder ? `st ${fielder}` : 'st'} b ${bowler}`
    case 'HIT_WICKET':
      return `hit wicket b ${bowler}`
    case 'RETIRED_OUT':
      return 'retired out'
    case 'RETIRED_HURT':
      return 'retired hurt'
  }
}

function overs(legalBalls: number, ballsPerOver: number): string {
  return `${Math.floor(legalBalls / ballsPerOver)}.${legalBalls % ballsPerOver}`
}

function round(x: number, decimals: number): number {
  const f = 10 ** decimals
  return Math.round(x * f) / f
}
