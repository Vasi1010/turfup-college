import { describe, expect, it } from 'vitest'
import { BUSINESS } from '../lib/business'
import { policies } from './policies.en'

const text = (which: keyof ReturnType<typeof policies>) => {
  const p = policies(BUSINESS)[which]
  return [p.intro, ...p.sections.flatMap((s) => [s.heading, ...s.paragraphs])].join('\n')
}

// The refund page is a promise to players; these are the rules in CLAUDE.md (Cancellation, Advance payment).
describe('refund & cancellation policy', () => {
  const refunds = text('refunds')

  it('states the default advance and refund window', () => {
    expect(refunds).toContain('₹200')
    expect(refunds).toContain('24 hours')
  })

  it('limits cancellation refunds to one per month per turf, and late cancellations do not use it', () => {
    expect(refunds).toMatch(/one such refund per player per month/)
    expect(refunds).toMatch(/late cancellation does not use up your monthly refund/)
  })

  it('always refunds owner cancellations in full, without counting them', () => {
    expect(refunds).toMatch(/always get back everything you paid/)
    expect(refunds).toMatch(/never count toward your monthly limit/)
  })

  it('refunds payments that arrive after the hold', () => {
    expect(refunds).toMatch(/refunded automatically/)
  })
})

describe('business details', () => {
  it('appear on the contact and privacy pages', () => {
    expect(text('contact')).toContain(BUSINESS.email)
    expect(text('privacy')).toContain(BUSINESS.grievanceOfficer.name)
  })
})
