import { describe, expect, it } from 'vitest'
import { mockMatches } from '../mocks/tournament'
import type { MatchDetail } from './tournamentTypes'
import { delivery, ends, lastBallId, namesOf, nextInningsTeams, noPicks, step, uuid, waysOut } from './scoring'

const live = mockMatches[4] // second innings being played
const done = mockMatches[1]
const upcoming = mockMatches[5]

function withLive(patch: Partial<NonNullable<MatchDetail['live']>>): MatchDetail {
  return { ...live, live: { ...live.live!, ...patch } }
}

describe('step', () => {
  it('follows the match from toss to result', () => {
    expect(step(upcoming, noPicks)).toBe('toss')
    expect(step({ ...upcoming, toss: { winnerId: upcoming.teamA.teamId!, decision: 'BAT' } }, noPicks)).toBe(
      'startInnings',
    )
    expect(step(live, noPicks)).toBe('ready')
    expect(step(done, noPicks)).toBe('over')
  })

  it('waits for a new batter after a wicket and a bowler at a new over', () => {
    const wicket = withLive({ strikerId: null, needsNewBatter: true })
    expect(step(wicket, noPicks)).toBe('newBatter')
    expect(step(wicket, { ...noPicks, newBatterId: 99 })).toBe('ready')
    const newOver = withLive({ bowlerId: null, newOver: true })
    expect(step(newOver, noPicks)).toBe('newBowler')
    expect(step(newOver, { ...noPicks, bowlerId: 12 })).toBe('ready')
  })

  it('starts the second innings once the first is over, and waits for an admin after rain', () => {
    const between: MatchDetail = { ...live, live: null, innings: [{ ...live.innings[0], status: 'DONE' }] }
    expect(step(between, noPicks)).toBe('startInnings')
    const closed: MatchDetail = { ...live, live: null, innings: live.innings.map((i) => ({ ...i, status: 'DONE' })) }
    expect(step(closed, noPicks)).toBe('awaitingResult')
  })
})

describe('ends', () => {
  it('fills the empty end with the new batter and swaps when asked', () => {
    const l = live.live!
    expect(ends(live, noPicks)).toEqual({ strikerId: l.strikerId, nonStrikerId: l.nonStrikerId, bowlerId: l.bowlerId })
    expect(ends(live, { ...noPicks, swapped: true })?.strikerId).toBe(l.nonStrikerId)
    const wicket = withLive({ nonStrikerId: null, needsNewBatter: true })
    expect(ends(wicket, noPicks)).toBeNull()
    expect(ends(wicket, { ...noPicks, newBatterId: 77 })?.nonStrikerId).toBe(77)
  })
})

describe('delivery', () => {
  const e = { strikerId: 1, nonStrikerId: 2, bowlerId: 3 }
  it('turns taps into balls, with the wide and no-ball runs the rules set', () => {
    expect(delivery(live, e, { kind: 'runs', runs: 4 }, 'x')).toMatchObject({ seq: live.live!.nextSeq, runsBat: 4 })
    expect(delivery(live, e, { kind: 'wide', ran: 4 }, 'x')).toMatchObject({ extraType: 'WD', extraRuns: 5, runsBat: 0 })
    expect(delivery(live, e, { kind: 'noBall', bat: 6 }, 'x')).toMatchObject({ extraType: 'NB', extraRuns: 1, runsBat: 6 })
    expect(delivery(live, e, { kind: 'legBye', runs: 2 }, 'x')).toMatchObject({ extraType: 'LB', extraRuns: 2 })
  })

  it('names who is out; only a run out keeps the runs completed', () => {
    const runOut = delivery(live, e, { kind: 'wicket', how: 'RUN_OUT', out: 'nonStriker', ran: 1, fielderId: 9 }, 'x')
    expect(runOut).toMatchObject({ wicketType: 'RUN_OUT', playerOutId: 2, runsBat: 1, fielderId: 9 })
    const bowled = delivery(live, e, { kind: 'wicket', how: 'BOWLED', out: 'striker', ran: 1, fielderId: 9 }, 'x')
    expect(bowled).toMatchObject({ wicketType: 'BOWLED', playerOutId: 1, runsBat: 0, fielderId: null })
  })
})

describe('the rest', () => {
  it('offers only the ways out the rules allow', () => {
    expect(waysOut(live.rules, false)).not.toContain('LBW') // the mock tournament plays without LBW
    expect(waysOut({ ...live.rules, noLbw: false }, false)).toContain('LBW')
    expect(waysOut(live.rules, true)).toEqual(['RUN_OUT'])
  })

  it('knows every name, the next innings, and the ball to undo', () => {
    expect(namesOf(live).get(live.live!.bowlerId!)).toBeTruthy()
    expect(nextInningsTeams({ ...live, innings: [live.innings[0]] })).toEqual({
      battingTeamId: live.innings[0].bowlingTeamId,
      bowlingTeamId: live.innings[0].battingTeamId,
    })
    expect(lastBallId(live)).toBe(live.innings[1].lastDeliveryId)
    expect(lastBallId(upcoming)).toBeNull()
  })

  it('makes UUIDs', () => {
    expect(uuid()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
})
