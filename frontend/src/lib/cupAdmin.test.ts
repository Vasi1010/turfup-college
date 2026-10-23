import { describe, expect, it } from 'vitest'
import { isoToIstInput, istInputToIso, squadPreview } from './cupAdmin'

describe('IST date-time inputs', () => {
  it('reads an input as IST and shows an instant in IST', () => {
    expect(istInputToIso('2026-11-07T07:00')).toBe('2026-11-07T07:00:00+05:30')
    expect(istInputToIso('')).toBeNull()
    expect(isoToIstInput('2026-11-07T01:30:00Z')).toBe('2026-11-07T07:00')
    expect(isoToIstInput('2026-11-07T07:00:00+05:30')).toBe('2026-11-07T07:00')
    expect(isoToIstInput(null)).toBe('')
  })
})

describe('squadPreview', () => {
  it('reads the pasted squad the way the server does', () => {
    const squad = squadPreview('7 Arbaaz Shaikh (c)\nFaizan Qureshi\n\n10. Imran Pathan\n  Sameer   Khan  \n#3 Zaid')
    expect(squad.players).toEqual(['Arbaaz Shaikh', 'Faizan Qureshi', 'Imran Pathan', 'Sameer Khan', 'Zaid'])
    expect(squad.captain).toBe('Arbaaz Shaikh')
    expect(squadPreview('').players).toEqual([])
  })
})
