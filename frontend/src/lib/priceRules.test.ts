import { describe, expect, it } from 'vitest'
import { closeMinute, coverageBar, endMinute, firstGap, overlaps, startMinute } from './priceRules'
import type { PriceRule } from './types'

const open = 360 // 6 AM
const close = 1560 // 2 AM

const rule = (start: number, end: number, day: number | null = null): PriceRule => ({
  dayOfWeek: day,
  startMinute: start,
  endMinute: end,
  rate: 80_000,
})

describe('form times to operating-day minutes', () => {
  it('puts times before opening after midnight', () => {
    expect(startMinute('06:00', open)).toBe(360)
    expect(startMinute('01:00', open)).toBe(1500)
    expect(endMinute('02:00', 1020)).toBe(1560)
    expect(endMinute('17:00', 360)).toBe(1020)
    expect(closeMinute('02:00', open)).toBe(1560)
    expect(closeMinute('23:00', open)).toBe(1380)
  })
})

describe('coverage', () => {
  it('accepts the standard ₹800 / ₹1,000 split at 5 PM', () => {
    expect(firstGap([rule(360, 1020), rule(1020, 1560)], open, close)).toBeNull()
  })

  it('finds the first unpriced minute', () => {
    expect(firstGap([rule(360, 960), rule(1020, 1560)], open, close)).toBe(960)
    expect(firstGap([rule(420, 1560)], open, close)).toBe(360)
    expect(firstGap([rule(360, 1440)], open, close)).toBe(1440)
  })

  it('ignores day-specific rules', () => {
    expect(firstGap([rule(360, 1560, 6)], open, close)).toBe(360)
  })

  it('draws gaps on the bar', () => {
    const bar = coverageBar([rule(360, 960), rule(1020, 1560)], open, close)
    expect(bar.map((p) => p.covered)).toEqual([true, false, true])
    expect(bar[1].from).toBeCloseTo(0.5)
  })
})

describe('overlaps', () => {
  it('flags overlapping rules of the same kind only', () => {
    expect(overlaps([rule(360, 1080), rule(1020, 1560)])).toHaveLength(1)
    expect(overlaps([rule(360, 1560), rule(1020, 1200, 6)])).toHaveLength(0)
    expect(overlaps([rule(1020, 1200, 6), rule(1140, 1560, 6)])).toHaveLength(1)
    expect(overlaps([rule(360, 1020), rule(1020, 1560)])).toHaveLength(0)
  })
})
