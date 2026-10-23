/**
 * Who runs TurfUp, as shown on the Terms, Privacy, Refund and Contact pages. Razorpay checks these pages
 * before activating live payments, and Indian privacy law requires a named grievance officer.
 *
 * FILL IN every value marked [FILL IN] before going live. Until then the pages show the marker highlighted.
 */
export const BUSINESS = {
  /** Registered name of the business (proprietorship, LLP or company) that operates TurfUp. */
  legalName: '[FILL IN: registered business name]',
  /** Postal address for notices. */
  address: '[FILL IN: registered address], Aurangabad (Chhatrapati Sambhajinagar), Maharashtra',
  email: '[FILL IN: support email]',
  phone: '[FILL IN: support phone]',
  /** Hours someone answers the support phone. */
  supportHours: '10 AM to 7 PM, Monday to Saturday',
  grievanceOfficer: {
    name: '[FILL IN: grievance officer name]',
    email: '[FILL IN: grievance officer email]',
  },
  /** City whose courts handle disputes. */
  jurisdiction: 'Aurangabad (Chhatrapati Sambhajinagar), Maharashtra',
  /** Date the policies below were last changed. Update it whenever the text changes. */
  policiesUpdated: '12 October 2026',
}

export type Business = typeof BUSINESS

/** True for a value that still needs to be filled in. */
export function isPlaceholder(value: string): boolean {
  return value.includes('[FILL IN')
}
