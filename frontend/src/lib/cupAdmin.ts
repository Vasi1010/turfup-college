/** Helpers for the tournament admin screens (task B10). Times are IST; inputs are datetime-local ("2026-11-07T07:00"). */

const IST_OFFSET_MS = 330 * 60_000

/** A datetime-local value, read as IST, as an ISO instant with the IST offset; null when empty. */
export function istInputToIso(value: string): string | null {
  return value ? `${value}:00+05:30` : null
}

/** An instant as a datetime-local value in IST; empty for null. */
export function isoToIstInput(iso: string | null): string {
  if (!iso) return ''
  return new Date(new Date(iso).getTime() + IST_OFFSET_MS).toISOString().slice(0, 16)
}

/**
 * What a pasted squad will become, read the way the server reads it: one cricketer per line, an optional jersey
 * number first ("7 Rahul Patil", "10. Sameer Khan"), "(c)" for the captain, blank lines skipped.
 */
export function squadPreview(text: string): { players: string[]; captain: string | null } {
  const players: string[] = []
  let captain: string | null = null
  for (const line of text.split(/\r?\n/)) {
    let t = line.trim()
    if (!t) continue
    const isCaptain = /\(\s*c\s*\)/i.test(t)
    t = t.replace(/\(\s*c\s*\)/gi, '').trim()
    t = t.replace(/^#?\d{1,3}[.)]?\s+/, '').replace(/\s+/g, ' ').trim()
    if (!t) continue
    players.push(t)
    if (isCaptain && captain === null) captain = t
  }
  return { players, captain }
}
