import type { UpiPayment } from './types'

/**
 * A UPI payment link (`upi://pay?...`, NPCI's deep-link format). On a phone it opens the player's UPI app (GPay,
 * PhonePe, Paytm, BHIM...) with the venue's UPI ID, the amount and a note that identifies the booking. Each value is
 * percent-encoded with %20 for spaces, because some UPI apps do not turn `+` back into a space.
 */
export function upiLink(p: UpiPayment): string {
  const params: [string, string][] = [
    ['pa', p.upiId],
    ['pn', p.payeeName],
    ['am', (p.amount / 100).toFixed(2)],
    ['cu', 'INR'],
    ['tn', p.note],
  ]
  return `upi://pay?${params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')}`
}

/** The note the server puts on a booking's UPI payment, rebuilt after a page reload. */
export function upiNote(bookingId: number): string {
  return `TurfUp booking ${bookingId}`
}

/** A typed UPI reference without spaces; the server takes 6-35 letters or digits (usually 12 digits). */
export function cleanUpiRef(raw: string): string {
  return raw.replace(/\s/g, '')
}

/** True if the typed reference can be sent: empty (it is optional) or well formed. */
export function upiRefValid(raw: string): boolean {
  const ref = cleanUpiRef(raw)
  return ref === '' || /^[A-Za-z0-9]{6,35}$/.test(ref)
}
