import type { PublicCourt } from './types'

/** "any" (any free court of the sport) or a court id. */
export type CourtChoice = 'any' | number

/**
 * The court shown: the one in the URL if it offers the sport, else "any" when offered, else the sport's first court.
 * Null while the venue is loading.
 */
export function chooseCourt(wanted: string | null, anyCourt: boolean, courts: PublicCourt[]): CourtChoice | null {
  if (wanted === 'any' && anyCourt) return 'any'
  const asked = courts.find((c) => String(c.id) === wanted)
  if (asked) return asked.id
  if (anyCourt) return 'any'
  return courts[0]?.id ?? null
}

/** Advance and refund rules to show: the chosen court's, or for "any" the shared ones if every court agrees. */
export function rulesFor(court: PublicCourt | null, courts: PublicCourt[]): PublicCourt | null {
  if (court) return court
  const [first, ...rest] = courts
  if (!first) return null
  const same = rest.every((c) => c.advanceAmount === first.advanceAmount
    && c.refundFullHoursBefore === first.refundFullHoursBefore && c.refundPolicy === first.refundPolicy)
  return same ? first : null
}
