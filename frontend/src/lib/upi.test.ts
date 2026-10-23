import { describe, expect, it } from 'vitest'
import { cleanUpiRef, upiLink, upiNote, upiRefValid } from './upi'

describe('UPI payment link', () => {
  it('fills in the venue, the amount in rupees and the booking note', () => {
    expect(upiLink({ upiId: 'kickarena@ybl', payeeName: 'Kick Arena', amount: 20_000, note: upiNote(42) })).toBe(
      'upi://pay?pa=kickarena%40ybl&pn=Kick%20Arena&am=200.00&cu=INR&tn=TurfUp%20booking%2042',
    )
  })

  it('keeps paise', () => {
    expect(upiLink({ upiId: 'a@b', payeeName: 'X', amount: 12_345, note: 'n' })).toContain('am=123.45')
  })
})

describe('UPI reference', () => {
  it('is optional, and otherwise 6-35 letters or digits', () => {
    expect(upiRefValid('')).toBe(true)
    expect(upiRefValid(' 4123 4567 8901 ')).toBe(true)
    expect(cleanUpiRef(' 4123 4567 8901 ')).toBe('412345678901')
    expect(upiRefValid('12-34')).toBe(false)
    expect(upiRefValid('123')).toBe(false)
  })
})
