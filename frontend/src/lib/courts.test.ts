import { describe, expect, it } from 'vitest'
import { chooseCourt, rulesFor } from './courts'
import type { PublicCourt } from './types'

const court = (id: number, patch: Partial<PublicCourt> = {}): PublicCourt => ({
  id,
  name: `Court ${id}`,
  sports: ['badminton'],
  slotMinutes: 60,
  advanceAmount: 10_000,
  refundFullHoursBefore: 24,
  refundPolicy: null,
  fromRate: 40_000,
  ...patch,
})

describe('chooseCourt', () => {
  const courts = [court(1001), court(1002)]

  it('keeps the court in the URL when it offers the sport', () => {
    expect(chooseCourt('1002', true, courts)).toBe(1002)
  })

  it('defaults to any free court when offered, else the first court', () => {
    expect(chooseCourt(null, true, courts)).toBe('any')
    expect(chooseCourt(null, false, courts)).toBe(1001)
  })

  it('ignores "any" or an unknown court that does not apply', () => {
    expect(chooseCourt('any', false, courts)).toBe(1001)
    expect(chooseCourt('9', false, courts)).toBe(1001)
  })

  it('is null while there are no courts', () => {
    expect(chooseCourt(null, false, [])).toBeNull()
  })
})

describe('rulesFor', () => {
  it('uses the chosen court', () => {
    const c = court(1001, { advanceAmount: 5_000 })
    expect(rulesFor(c, [c, court(1002)])).toBe(c)
  })

  it('shows shared rules for any free court only when every court agrees', () => {
    expect(rulesFor(null, [court(1001), court(1002)])?.advanceAmount).toBe(10_000)
    expect(rulesFor(null, [court(1001), court(1002, { refundFullHoursBefore: 6 })])).toBeNull()
    expect(rulesFor(null, [court(1001), court(1002, { advanceAmount: 0 })])).toBeNull()
  })
})
