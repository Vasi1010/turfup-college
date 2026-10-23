import { describe, expect, it } from 'vitest'
import { mockLeaderboards, mockMatches, mockPointsTable, mockTournament } from './tournament'

// The mocks must add up the way the server's figures will, so pages built on them show consistent numbers.
// If a hand edit breaks one of these, fix the mock rather than the test.

const rules = mockTournament.rules
const maxBalls = rules.oversPerInnings * rules.ballsPerOver
const cards = Object.values(mockMatches).flatMap((m) => m.innings.map((card) => ({ match: m, card })))

describe('tournament mocks', () => {
  it('every innings total is batting runs plus extras', () => {
    for (const { card } of cards) {
      const bat = card.batting.reduce((s, l) => s + l.runs, 0)
      expect(bat + card.extras.total).toBe(card.runs)
      expect(card.extras.wides + card.extras.noBalls + card.extras.byes + card.extras.legByes).toBe(card.extras.total)
      expect(card.batting.filter((l) => l.status === 'OUT').length).toBe(card.wickets)
      expect(card.fallOfWickets.length).toBe(card.wickets)
    }
  })

  it('bowling figures account for every run except byes and leg byes, and every legal ball', () => {
    for (const { card } of cards) {
      const bowled = card.bowling.reduce((s, l) => s + l.runs, 0)
      expect(bowled + card.extras.byes + card.extras.legByes).toBe(card.runs)
      expect(card.bowling.reduce((s, l) => s + l.legalBalls, 0)).toBe(card.legalBalls)
      for (const l of card.bowling) {
        expect(l.legalBalls).toBeLessThanOrEqual((rules.maxOversPerBowler ?? rules.oversPerInnings) * rules.ballsPerOver)
      }
    }
  })

  it('the fixture list shows the same scores as the scorecards', () => {
    for (const f of mockTournament.matches) {
      const m = mockMatches[f.id]
      expect(m.status).toBe(f.status)
      expect(f.innings.map((i) => [i.runs, i.wickets, i.legalBalls])).toEqual(
        m.innings.map((i) => [i.runs, i.wickets, i.legalBalls]),
      )
    }
  })

  it('a chase shows the target and what is left', () => {
    for (const m of Object.values(mockMatches)) {
      if (m.innings.length === 2) expect(m.innings[1].target).toBe(m.innings[0].runs + 1)
      if (m.live?.runsNeeded != null) {
        const chase = m.innings[1]
        expect(m.live.runsNeeded).toBe(chase.target! - chase.runs)
        expect(m.live.ballsLeft).toBe(maxBalls - chase.legalBalls)
      }
    }
  })

  it('the points table follows from the finished matches, NRR included', () => {
    const rows = mockPointsTable.groups[0].rows
    for (const r of rows) {
      expect(r.points).toBe(r.won * rules.pointsWin + r.tied * rules.pointsTie + r.noResult * rules.pointsNoResult)
      expect(r.played).toBe(r.won + r.lost + r.tied + r.noResult)
      const nrr = r.runsFor / (r.ballsFaced / 6) - r.runsAgainst / (r.ballsBowled / 6)
      expect(r.nrr).toBeCloseTo(nrr, 3)
    }
    const done = mockTournament.matches.filter((f) => f.status === 'DONE')
    expect(rows.reduce((s, r) => s + r.played, 0)).toBe(done.length * 2)
    expect(rows.reduce((s, r) => s + r.runsFor, 0)).toBe(rows.reduce((s, r) => s + r.runsAgainst, 0))
  })

  it('leaderboards add up the scorecards', () => {
    for (const p of mockLeaderboards.topRunScorers) {
      const lines = cards.flatMap(({ card }) => card.batting.filter((l) => l.cricketerId === p.cricketerId))
      expect(lines.reduce((s, l) => s + l.runs, 0)).toBe(p.runs)
      expect(lines.length).toBe(p.innings)
    }
    for (const p of mockLeaderboards.topWicketTakers) {
      const lines = cards.flatMap(({ card }) => card.bowling.filter((l) => l.cricketerId === p.cricketerId))
      expect(lines.reduce((s, l) => s + l.wickets, 0)).toBe(p.wickets)
      expect(lines.reduce((s, l) => s + l.runs, 0)).toBe(p.runs)
    }
  })
})
