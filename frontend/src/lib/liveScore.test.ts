import { describe, expect, it } from 'vitest'
import { mockMatches } from '../mocks/tournament'
import { label, overlay } from './liveScore'
import type { AddDeliveryRequest, ExtraType, MatchDetail, WicketType } from './tournamentTypes'

// Match 4 of the mocks: the chase is on, 16 needed off 16, two balls into the 6th over (8 overs, 2 per bowler).
const base = mockMatches[4]
const live = base.live!
const inningsOf = (m: MatchDetail) => m.innings.find((i) => i.id === live.inningsId)!

/** Balls built the way the scorer screen does, each from the state the previous ones left. */
function play(...taps: Partial<AddDeliveryRequest>[]): { m: MatchDetail; waiting: AddDeliveryRequest[] } {
  return playOn(base, ...taps)
}

function playOn(start: MatchDetail, ...taps: Partial<AddDeliveryRequest>[]) {
  const waiting: AddDeliveryRequest[] = []
  let m = start
  for (const [i, tap] of taps.entries()) {
    const l = m.live!
    const striker = l.strikerId ?? l.availableBatterIds[0]
    const nonStriker = l.nonStrikerId ?? (l.strikerId === null ? l.nonStrikerId! : l.availableBatterIds[0])
    waiting.push({
      id: `w${i}`, seq: l.nextSeq, strikerId: striker, nonStrikerId: nonStriker,
      bowlerId: l.bowlerId ?? l.eligibleBowlerIds[0], runsBat: 0, extraType: null, extraRuns: 0, wicketType: null,
      playerOutId: null, fielderId: null, ...tap,
    })
    m = overlay(start, waiting).match
  }
  return { m, waiting }
}

const extra = (extraType: ExtraType, extraRuns: number, runsBat = 0) => ({ extraType, extraRuns, runsBat })

describe('overlay', () => {
  it('changes nothing with no balls waiting', () => {
    expect(overlay(base, [])).toEqual({ match: base, ended: null })
  })

  it('a single swaps the ends and counts for the batter and bowler', () => {
    const { m } = play({ runsBat: 1 })
    expect(m.live!.strikerId).toBe(live.nonStrikerId)
    expect(m.live!.nextSeq).toBe(live.nextSeq + 1)
    expect(inningsOf(m).runs).toBe(inningsOf(base).runs + 1)
    expect(m.live!.runsNeeded).toBe(live.runsNeeded! - 1)
    const batter = inningsOf(m).batting.find((b) => b.cricketerId === live.strikerId)!
    const before = inningsOf(base).batting.find((b) => b.cricketerId === live.strikerId)!
    expect([batter.runs, batter.balls]).toEqual([before.runs + 1, before.balls + 1])
  })

  it('the end of the over swaps the ends and asks for a bowler who is not the last one or at the limit', () => {
    const { m } = play({}, {}, {}, {}) // four dots finish the 6th over
    const l = m.live!
    expect(inningsOf(m).overs).toBe('6.0')
    expect([l.newOver, l.bowlerId, l.lastOverBowlerId]).toEqual([true, null, live.bowlerId])
    expect(l.strikerId).toBe(live.nonStrikerId)
    expect(l.thisOver).toEqual([])
    const atLimit = inningsOf(m).bowling.filter((b) => b.legalBalls >= 12).map((b) => b.cricketerId)
    expect(l.eligibleBowlerIds).not.toContain(live.bowlerId)
    for (const id of atLimit) expect(l.eligibleBowlerIds).not.toContain(id)
  })

  it('wides and no-balls are not legal balls; four byes off a wide are not run', () => {
    const { m } = play(extra('WD', 5), extra('NB', 1, 1))
    expect(inningsOf(m).legalBalls).toBe(inningsOf(base).legalBalls)
    expect(inningsOf(m).runs).toBe(inningsOf(base).runs + 7)
    expect(m.live!.strikerId).toBe(live.nonStrikerId) // only the single off the no-ball was run
    expect(m.live!.thisOver.map((b) => b.label).slice(-2)).toEqual(['5wd', 'nb1'])
  })

  it('a free hit follows a no-ball, survives a wide and ends with a legal ball', () => {
    expect(play(extra('NB', 1)).m.live!.freeHit).toBe(true)
    expect(play(extra('NB', 1), extra('WD', 1)).m.live!.freeHit).toBe(true)
    const { m } = play(extra('NB', 1), {})
    expect(m.live!.freeHit).toBe(false)
    expect(m.live!.recentBalls.at(-1)!.freeHit).toBe(true)
  })

  const caught = { wicketType: 'CAUGHT' as WicketType, playerOutId: live.strikerId, fielderId: live.eligibleBowlerIds[0] }

  it('a catch empties the striker’s end and asks for a new batter', () => {
    // 11 a side here: the mock side is 8 a side and already 6 down, so a 7th wicket would be all out.
    const { m } = playOn({ ...base, rules: { ...base.rules, playersPerSide: 11 } }, caught)
    const l = m.live!
    expect([l.strikerId, l.needsNewBatter]).toEqual([null, true])
    expect(inningsOf(m).wickets).toBe(inningsOf(base).wickets + 1)
    expect(inningsOf(m).fallOfWickets.at(-1)!.cricketerId).toBe(live.strikerId)
    expect(l.availableBatterIds).not.toContain(live.strikerId)
    expect(inningsOf(m).batting.find((b) => b.cricketerId === live.strikerId)!.dismissal).toMatch(/^c .+ b .+$/)
  })

  it('the 7th wicket of an 8-a-side team is all out', () => {
    const done = overlay(base, play(caught).waiting)
    expect(done.ended).toBe('ALL_OUT')
    expect(done.match.live).toBeNull()
  })

  it('ends the innings the moment the target is reached, and takes no balls after it', () => {
    const { waiting } = play({ runsBat: 6 }, { runsBat: 6 }, { runsBat: 4 })
    const done = overlay(base, waiting)
    expect(done.ended).toBe('TARGET')
    expect(done.match.live).toBeNull()
    expect(inningsOf(done.match).status).toBe('DONE')
    const after = overlay(base, [...waiting, { ...waiting[0], id: 'late', seq: 99 }])
    expect(inningsOf(after.match).runs).toBe(inningsOf(done.match).runs)
  })

  it('labels balls like the server', () => {
    const d = (o: Partial<AddDeliveryRequest>): AddDeliveryRequest => ({ id: 'x', seq: 1, strikerId: 1, nonStrikerId: 2,
      bowlerId: 3, runsBat: 0, extraType: null, extraRuns: 0, wicketType: null, playerOutId: null, fielderId: null, ...o })
    expect([
      label(d({ runsBat: 4 }), 1),
      label(d(extra('WD', 5)), 1),
      label(d(extra('NB', 3)), 1),
      label(d(extra('LB', 1)), 1),
      label(d({ runsBat: 1, wicketType: 'RUN_OUT', playerOutId: 1 }), 1),
    ]).toEqual(['4', '5wd', 'nb+2', '1lb', '1W'])
  })
})
