import type { PriceRule } from './types'

/**
 * Times in the turf form are wall-clock "HH:MM"; the API uses operating-day minutes (0..2880).
 * A start before opening time belongs to after midnight; an end at or before its start runs past midnight.
 */
export function startMinute(time: string, openMinute: number): number {
  const [h, m] = time.split(':').map(Number)
  const t = h * 60 + m
  return t < openMinute ? t + 1440 : t
}

export function endMinute(time: string, start: number): number {
  const [h, m] = time.split(':').map(Number)
  let t = h * 60 + m
  while (t <= start) t += 1440
  return Math.min(t, 2880)
}

export function closeMinute(time: string, openMinute: number): number {
  const [h, m] = time.split(':').map(Number)
  const t = h * 60 + m
  return t <= openMinute ? t + 1440 : t
}

/** The first minute of opening hours that no general rule prices, or null if all are covered. */
export function firstGap(rules: PriceRule[], open: number, close: number): number | null {
  let covered = open
  for (const r of rules.filter((x) => x.dayOfWeek === null).sort((a, b) => a.startMinute - b.startMinute)) {
    if (r.startMinute > covered) break
    covered = Math.max(covered, r.endMinute)
  }
  return covered < close ? covered : null
}

/** Pairs of rules of the same kind (same day, or both general) that overlap. */
export function overlaps(rules: PriceRule[]): [PriceRule, PriceRule][] {
  const found: [PriceRule, PriceRule][] = []
  const groups = new Map<number, PriceRule[]>()
  for (const r of rules) groups.set(r.dayOfWeek ?? 0, [...(groups.get(r.dayOfWeek ?? 0) ?? []), r])
  for (const group of groups.values()) {
    const sorted = [...group].sort((a, b) => a.startMinute - b.startMinute)
    for (let i = 1; i < sorted.length; i++) {
      if (sorted[i].startMinute < sorted[i - 1].endMinute) found.push([sorted[i - 1], sorted[i]])
    }
  }
  return found
}

/** Coverage of opening hours by general rules, as fractions for a bar: [{ from, to, covered }]. */
export function coverageBar(rules: PriceRule[], open: number, close: number) {
  const span = close - open
  const general = rules.filter((r) => r.dayOfWeek === null)
  const cuts = new Set([open, close])
  for (const r of general) {
    if (r.startMinute > open && r.startMinute < close) cuts.add(r.startMinute)
    if (r.endMinute > open && r.endMinute < close) cuts.add(r.endMinute)
  }
  const points = [...cuts].sort((a, b) => a - b)
  return points.slice(1).map((to, i) => {
    const from = points[i]
    const covered = general.some((r) => r.startMinute <= from && from < r.endMinute)
    return { from: (from - open) / span, to: (to - open) / span, covered }
  })
}
