/** Fixed lists used by the UI. Hold and payment timings come from the server with each booking. */
/** Sport codes in display order (the backend's sports table). */
export const SPORTS = ['football', 'cricket', 'box-cricket', 'badminton', 'tennis', 'pickleball']

/** Names the server stores when the owner leaves a walk-in/phone entry unnamed (BookingService). */
export const UNNAMED_ENTRY = { WALKIN: 'Walk-in', PHONE: 'Phone booking' } as Record<string, string>
