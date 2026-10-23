/** API shapes. Money is always integer paise; times are ISO instants. */

export type Role = 'PLAYER' | 'OWNER' | 'ADMIN'

export interface AuthResult {
  token: string
  userId: number
  name: string
  role: Role
}

export interface AreaCount {
  area: string
  venues: number
}

export interface SportCount {
  sport: string
  venues: number
}

/** How many of a venue's courts offer a sport. */
export interface SportCourts {
  sport: string
  courts: number
}

/** A venue in the browse list. With a sport filter, the prices cover only that sport's courts. */
export interface VenueCard {
  id: number
  name: string
  area: string
  address: string
  sports: SportCourts[]
  advanceFrom: number
  fromRate: number | null
}

/** A court as players see it. */
export interface PublicCourt {
  id: number
  name: string
  sports: string[]
  slotMinutes: number
  advanceAmount: number
  refundFullHoursBefore: number
  refundPolicy: string | null
  fromRate: number | null
}

/** A sport at a venue: its courts (display order), and whether "any free court" is offered. */
export interface SportOption {
  sport: string
  courtIds: number[]
  anyCourt: boolean
}

export interface VenueDetail {
  id: number
  name: string
  area: string
  address: string
  lat: number | null
  lng: number | null
  openMinute: number
  closeMinute: number
  refundsPerMonth: number
  /** False while the venue cannot take online advances (only waived regulars can book online). */
  onlinePayments: boolean
  courts: PublicCourt[]
  sports: SportOption[]
}

export type SlotStatus = 'available' | 'held' | 'booked'

export interface SlotView {
  start: string
  end: string
  price: number
  status: SlotStatus
}

export interface CourtSlots {
  courtId: number
  date: string
  slotMinutes: number
  slots: SlotView[]
}

/** "Any free court": the price is the cheapest free court's. */
export interface AnyCourtSlot extends SlotView {
  freeCourts: number
}

export interface AnyCourtSlots {
  venueId: number
  sport: string
  date: string
  slotMinutes: number
  slots: AnyCourtSlot[]
}

export type BookingStatus = 'HELD' | 'CONFIRMED' | 'COMPLETED' | 'EXPIRED' | 'CANCELLED' | 'NO_SHOW'
export type BookingSource = 'ONLINE' | 'WALKIN' | 'PHONE'
export type CancelReason = 'RAIN' | 'MAINTENANCE' | 'OTHER' | 'RECURRING_CHANGED' | 'PAYMENT_NOT_RECEIVED'

export interface BookingView {
  id: number
  /** The court the slot is on (for "any free court", the one it landed on). */
  courtId: number
  playerId: number | null
  walkinName: string | null
  walkinPhone: string | null
  startAt: string
  endAt: string
  status: BookingStatus
  source: BookingSource
  totalPrice: number
  advanceRequired: number
  holdExpiresAt: string | null
  paymentStartedAt: string | null
  cancelledBy: 'PLAYER' | 'OWNER' | null
  cancelReason: CancelReason | null
  cancelNote: string | null
  createdAt: string
  /** HELD only: last moment a payment may start (server setting). */
  payCutoffAt: string | null
}

export interface MyBooking {
  id: number
  venueId: number
  venueName: string
  venueArea: string
  courtId: number
  courtName: string
  startAt: string
  endAt: string
  status: BookingStatus
  source: BookingSource
  totalPrice: number
  advanceRequired: number
  holdExpiresAt: string | null
  paymentStartedAt: string | null
  cancelledBy: 'PLAYER' | 'OWNER' | null
  cancelReason: CancelReason | null
  cancelNote: string | null
  refunded: number
  createdAt: string
  /** HELD only: last moment a payment may start (server setting). */
  payCutoffAt: string | null
  /** UPI mode: when the player said they paid; the hold then waits for the venue to check. */
  paymentClaimedAt: string | null
  paymentRef: string | null
  /** The part of `refunded` the venue still has to send (UPI mode). */
  refundPending: number
  /** Where the advance is paid in UPI mode, and the venue's phone for questions. */
  upiId: string | null
  upiName: string | null
  venuePhone: string
}

export type PayOutcome = 'CONFIRMED' | 'REFUNDED' | 'DUPLICATE' | 'AWAITING_PAYMENT' | 'PAY_BY_UPI'

/** UPI mode: pay this to the venue's UPI ID. Amount in paise. */
export interface UpiPayment {
  upiId: string
  payeeName: string
  amount: number
  note: string
}

/** Options for Razorpay Checkout. Amount in paise. */
export interface RazorpayCheckout {
  keyId: string
  orderId: string
  amount: number
  currency: string
  name: string
  description: string
  prefillName: string
  prefillContact: string
}

export interface PayResult {
  outcome: PayOutcome
  booking: BookingView
  /** Razorpay mode: open Checkout with this, then wait for the booking to confirm. */
  checkout: RazorpayCheckout | null
  /** UPI mode: the venue's UPI details. */
  upi: UpiPayment | null
}

export type PaymentsMode = 'SIMULATED' | 'UPI' | 'RAZORPAY'

export interface ClientConfig {
  paymentsMode: PaymentsMode
}

export type RefundOutcome = 'REFUND' | 'NO_REFUND_TOO_LATE' | 'NO_REFUND_LIMIT_REACHED' | 'NOTHING_PAID'

export interface CancellationTerms {
  bookingId: number
  outcome: RefundOutcome
  refundAmount: number
  fullRefundDeadline: string | null
  refundsPerMonth: number | null
  refundsUsedThisMonth: number | null
  month: string | null
  message: string
}

export interface CancellationResult {
  terms: CancellationTerms
  booking: BookingView
}

export interface PriceRule {
  dayOfWeek: number | null
  startMinute: number
  endMinute: number
  rate: number
}

/** A court as its owner sees it. A paused (inactive) court is hidden from players. */
export interface CourtView {
  id: number
  venueId: number
  name: string
  sports: string[]
  slotMinutes: number
  advanceAmount: number
  refundFullHoursBefore: number
  refundPolicy: string | null
  active: boolean
  sortOrder: number
  priceRules: PriceRule[]
}

export interface VenueView {
  id: number
  name: string
  area: string
  address: string
  lat: number | null
  lng: number | null
  openMinute: number
  closeMinute: number
  refundsPerMonth: number
  active: boolean
  approved: boolean
  /** Razorpay Route linked account that receives advances (razorpay mode). */
  razorpayAccountId: string | null
  /** Razorpay's status for it: created, under_review, needs_clarification, activated, suspended, rejected. */
  razorpayAccountStatus: string | null
  /** UPI ID (VPA) advances are paid to in UPI mode, and the name players see on the payment. */
  upiId: string | null
  upiName: string | null
  /** False while the venue cannot take online advances. Always true in simulated mode. */
  onlinePayments: boolean
  courts: CourtView[]
}

export type VenueSetupRequest = Pick<
  VenueView,
  'name' | 'area' | 'address' | 'lat' | 'lng' | 'openMinute' | 'closeMinute' | 'refundsPerMonth'
>

export type CourtSetupRequest = Omit<CourtView, 'id' | 'venueId' | 'sortOrder'>

export interface ScheduleSlot {
  start: string
  end: string
  price: number | null
  bookingId: number | null
}

export interface ScheduleBooking {
  id: number
  courtId: number
  startAt: string
  endAt: string
  status: BookingStatus
  source: BookingSource
  playerId: number | null
  customerName: string
  customerPhone: string | null
  totalPrice: number
  advanceRequired: number
  balanceDue: number
  balanceMethod: 'CASH' | 'UPI' | null
  holdExpiresAt: string | null
  recurringId: number | null
  /** UPI mode: a hold whose player says they paid; the owner checks it. */
  paymentClaimedAt: string | null
}

/** UPI mode: what needs the owner's attention. Money in paise. */
export interface PaymentToCheck {
  bookingId: number
  courtName: string
  startAt: string
  endAt: string
  playerName: string
  playerPhone: string
  amount: number
  paymentRef: string | null
  claimedAt: string
}

export interface RefundToSend {
  refundId: number
  bookingId: number
  courtName: string
  startAt: string
  playerName: string
  playerPhone: string
  amount: number
  reason: string
  paymentRef: string | null
  recordedAt: string
}

export interface UpiInbox {
  paymentsToCheck: PaymentToCheck[]
  refundsToSend: RefundToSend[]
}

export interface RecurringSkip {
  recurringId: number
  courtId: number
  playerId: number
  playerName: string
  playerPhone: string
  startAt: string
  endAt: string
  reason: 'SLOT_TAKEN' | 'OUTSIDE_HOURS' | 'NO_PRICE'
}

/** One court's column on the owner's day. */
export interface CourtColumn {
  courtId: number
  name: string
  sports: string[]
  slotMinutes: number
  active: boolean
  slots: ScheduleSlot[]
}

export interface Schedule {
  venueId: number
  venueName: string
  date: string
  currentOperatingDate: string
  openMinute: number
  closeMinute: number
  serverNow: string
  courts: CourtColumn[]
  bookings: ScheduleBooking[]
  recurringSkips: RecurringSkip[]
}

/** A standing slot. Times are operating-day minutes; dates are operating days. */
export interface RecurringView {
  id: number
  courtId: number
  courtName: string
  playerId: number
  playerName: string
  playerPhone: string
  dayOfWeek: number
  startMinute: number
  endMinute: number
  validFrom: string
  validTo: string | null
  upcoming: number
  skipped: number
}

export interface RecurringSaved {
  recurring: RecurringView
  generated: { created: number; skipped: number }
}

export interface Regular {
  playerId: number
  name: string
  phone: string
  advanceWaived: boolean
  noShowCount: number
  notes: string | null
  recentPlayed: number
  totalPlayed: number
  lastSlotAt: string | null
  suggested: boolean
}

export interface Regulars {
  suggestionMinBookings: number
  suggestionDays: number
  customers: Regular[]
}

export interface BalanceReceipt {
  bookingId: number
  amount: number
  method: 'CASH' | 'UPI'
}

export interface AdminVenue {
  venue: VenueView
  ownerName: string
  ownerPhone: string
}

/**
 * A Razorpay refund that needs a person: FAILED at Razorpay, or still PENDING because Razorpay keeps rejecting it
 * (`error` says why; it is retried every few minutes). Amount in paise.
 */
export interface RefundIssue {
  refundId: number
  bookingId: number
  venueName: string
  courtName: string
  ownerPhone: string
  playerName: string | null
  playerPhone: string | null
  amount: number
  status: 'PENDING' | 'FAILED'
  reason: 'PLAYER_CANCELLATION' | 'OWNER_CANCELLATION' | 'PAYMENT_REJECTED'
  error: string | null
  createdAt: string
  errorAt: string | null
  bookingStart: string
  gatewayPaymentId: string | null
  gatewayRefundId: string | null
}
