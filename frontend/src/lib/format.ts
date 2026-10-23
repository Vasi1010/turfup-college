/**
 * Money and time formatting. All times are shown in IST (UTC+5:30, no daylight saving), whatever the phone's
 * time zone. Dates as plain strings are "YYYY-MM-DD" operating days.
 */

const IST_OFFSET_MS = 330 * 60_000
const rupees = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 })

/** 20000 -> "₹200", 120050 -> "₹1,200.5"; Indian digit grouping. */
export function money(paise: number): string {
  return `₹${rupees.format(paise / 100)}`
}

/** Fields of an instant as seen on an IST wall clock. */
function ist(instant: string | number | Date) {
  const d = new Date(new Date(instant).getTime() + IST_OFFSET_MS)
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth(),
    day: d.getUTCDate(),
    weekday: d.getUTCDay(),
    hour: d.getUTCHours(),
    minute: d.getUTCMinutes(),
  }
}

function pad(n: number) {
  return String(n).padStart(2, '0')
}

/** "8 PM", "8:30 PM", "12 AM". */
export function clock(instant: string | number | Date): string {
  const { hour, minute } = ist(instant)
  return hourLabel(hour, minute)
}

function hourLabel(hour: number, minute: number, withMeridiem = true): string {
  const h12 = hour % 12 === 0 ? 12 : hour % 12
  const m = minute === 0 ? '' : `:${pad(minute)}`
  return withMeridiem ? `${h12}${m} ${hour < 12 ? 'AM' : 'PM'}` : `${h12}${m}`
}

/** "8–9 PM", "11 PM–1 AM", "11:30 AM–12:30 PM". */
export function timeRange(start: string, end: string): string {
  const a = ist(start)
  const b = ist(end)
  const sameHalf = a.hour < 12 === b.hour < 12 && !(b.hour === 0 && b.minute === 0)
  return sameHalf
    ? `${hourLabel(a.hour, a.minute, false)}–${hourLabel(b.hour, b.minute)}`
    : `${hourLabel(a.hour, a.minute)}–${hourLabel(b.hour, b.minute)}`
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "Sat 19 Oct" for an instant (its IST calendar date). */
export function dayOf(instant: string | number | Date): string {
  const { weekday, day, month } = ist(instant)
  return `${WEEKDAYS[weekday]} ${day} ${MONTHS[month]}`
}

/** "Sat 19 Oct, 8 PM". */
export function dayAndTime(instant: string | number | Date): string {
  return `${dayOf(instant)}, ${clock(instant)}`
}

/** IST calendar date of an instant, "YYYY-MM-DD". */
export function istDate(instant: string | number | Date): string {
  const { year, month, day } = ist(instant)
  return `${year}-${pad(month + 1)}-${pad(day)}`
}

export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/** Parts of an operating date for the date strip: { weekday: "Sat", day: 19, month: "Oct" }. */
export function dateParts(date: string) {
  const d = new Date(`${date}T00:00:00Z`)
  return { weekday: WEEKDAYS[d.getUTCDay()], day: d.getUTCDate(), month: MONTHS[d.getUTCMonth()] }
}

export function weekdayOf(date: string): string {
  return dateParts(date).weekday
}

/**
 * The operating day a turf is on right now: before today's opening time it is still yesterday's
 * operating day (its late-night slots belong to yesterday).
 */
export function currentOperatingDate(nowMs: number, openMinute: number): string {
  const { hour, minute } = ist(nowMs)
  const today = istDate(nowMs)
  return hour * 60 + minute >= openMinute ? today : addDays(today, -1)
}

/** True when an instant falls on the calendar day after its operating date (a late-night slot). */
export function isAfterMidnight(instant: string, operatingDate: string): boolean {
  return istDate(instant) > operatingDate
}

/** Operating-day minutes -> "HH:MM" (24 h, wraps past midnight). */
export function minutesToTime(minute: number): string {
  const m = ((minute % 1440) + 1440) % 1440
  return `${pad(Math.floor(m / 60))}:${pad(m % 60)}`
}

/** "HH:MM" -> minutes from midnight. */
export function timeToMinutes(value: string): number {
  const [h, m] = value.split(':').map(Number)
  return h * 60 + m
}

/** Operating-day minutes -> "8–9 PM", "11 PM–1 AM" (same style as timeRange). */
export function minuteRange(start: number, end: number): string {
  const a = ((start % 1440) + 1440) % 1440
  const b = ((end % 1440) + 1440) % 1440
  const ah = Math.floor(a / 60)
  const bh = Math.floor(b / 60)
  const sameHalf = ah < 12 === bh < 12 && b !== 0 && end - start < 720
  return sameHalf
    ? `${hourLabel(ah, a % 60, false)}–${hourLabel(bh, b % 60)}`
    : `${hourLabel(ah, a % 60)}–${hourLabel(bh, b % 60)}`
}

/** Operating-day minutes -> "6 AM", "1 AM". */
export function minuteLabel(minute: number): string {
  const m = ((minute % 1440) + 1440) % 1440
  return hourLabel(Math.floor(m / 60), m % 60)
}

/** "4:12" for a countdown. */
export function mmss(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(total / 60)}:${pad(total % 60)}`
}

/** Which part of the day a slot belongs to, for grouping the slot list. */
export function dayPart(instant: string, operatingDate: string): 'morning' | 'afternoon' | 'evening' | 'lateNight' {
  if (isAfterMidnight(instant, operatingDate)) return 'lateNight'
  const { hour } = ist(instant)
  if (hour < 12) return 'morning'
  if (hour < 17) return 'afternoon'
  return 'evening'
}
