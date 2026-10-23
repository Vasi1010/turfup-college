import { describe, expect, it } from 'vitest'
import {
  addDays,
  clock,
  currentOperatingDate,
  dayOf,
  dayPart,
  isAfterMidnight,
  minuteLabel,
  minuteRange,
  minutesToTime,
  mmss,
  money,
  timeRange,
} from './format'

describe('money', () => {
  it('formats paise as rupees with Indian grouping', () => {
    expect(money(20_000)).toBe('₹200')
    expect(money(100_000)).toBe('₹1,000')
    expect(money(10_000_000)).toBe('₹1,00,000')
    expect(money(120_050)).toBe('₹1,200.5')
  })
})

describe('IST times', () => {
  // 2030-01-15 20:00 IST = 14:30 UTC
  const eightPm = '2030-01-15T14:30:00Z'

  it('shows wall-clock time in IST whatever the input offset', () => {
    expect(clock(eightPm)).toBe('8 PM')
    expect(clock('2030-01-15T20:00:00+05:30')).toBe('8 PM')
    expect(clock('2030-01-15T15:00:00Z')).toBe('8:30 PM')
    expect(clock('2030-01-15T18:30:00Z')).toBe('12 AM')
  })

  it('compacts ranges within the same half of the day', () => {
    expect(timeRange('2030-01-15T20:00:00+05:30', '2030-01-15T21:00:00+05:30')).toBe('8–9 PM')
    expect(timeRange('2030-01-15T23:00:00+05:30', '2030-01-16T01:00:00+05:30')).toBe('11 PM–1 AM')
    expect(timeRange('2030-01-15T23:00:00+05:30', '2030-01-16T00:00:00+05:30')).toBe('11 PM–12 AM')
    expect(timeRange('2030-01-15T11:00:00+05:30', '2030-01-15T12:00:00+05:30')).toBe('11 AM–12 PM')
  })

  it('names the IST calendar day', () => {
    expect(dayOf('2030-01-19T18:00:00Z')).toBe('Sat 19 Jan') // 11:30 PM IST
    expect(dayOf('2030-01-19T19:00:00Z')).toBe('Sun 20 Jan') // 12:30 AM IST
  })
})

describe('operating day', () => {
  it('stays on yesterday until the turf opens', () => {
    const open = 360 // 6 AM
    expect(currentOperatingDate(Date.parse('2030-01-16T01:30:00+05:30'), open)).toBe('2030-01-15')
    expect(currentOperatingDate(Date.parse('2030-01-16T05:59:00+05:30'), open)).toBe('2030-01-15')
    expect(currentOperatingDate(Date.parse('2030-01-16T06:00:00+05:30'), open)).toBe('2030-01-16')
  })

  it('groups after-midnight slots as late night of the operating day', () => {
    expect(isAfterMidnight('2030-01-16T01:00:00+05:30', '2030-01-15')).toBe(true)
    expect(dayPart('2030-01-16T01:00:00+05:30', '2030-01-15')).toBe('lateNight')
    expect(dayPart('2030-01-15T16:59:00+05:30', '2030-01-15')).toBe('afternoon')
    expect(dayPart('2030-01-15T17:00:00+05:30', '2030-01-15')).toBe('evening')
  })

  it('adds days across month ends', () => {
    expect(addDays('2030-01-31', 1)).toBe('2030-02-01')
    expect(addDays('2030-03-01', -1)).toBe('2030-02-28')
  })

  it('formats operating-day minute ranges like time ranges', () => {
    expect(minuteRange(1200, 1260)).toBe('8–9 PM')
    expect(minuteRange(1380, 1500)).toBe('11 PM–1 AM')
    expect(minuteRange(1380, 1440)).toBe('11 PM–12 AM')
    expect(minuteRange(1500, 1560)).toBe('1–2 AM')
    expect(minuteRange(660, 720)).toBe('11 AM–12 PM')
  })

  it('converts operating-day minutes past midnight', () => {
    expect(minutesToTime(1560)).toBe('02:00')
    expect(minuteLabel(1560)).toBe('2 AM')
    expect(minuteLabel(1020)).toBe('5 PM')
  })
})

describe('countdown', () => {
  it('rounds up and never goes negative', () => {
    expect(mmss(300_000)).toBe('5:00')
    expect(mmss(29_001)).toBe('0:30')
    expect(mmss(-5)).toBe('0:00')
  })
})
