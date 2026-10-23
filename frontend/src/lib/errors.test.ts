import { describe, expect, it } from 'vitest'
import { s } from '../strings'
import { ApiError, NetworkError } from './api'
import { errorMessage } from './errors'

describe('errorMessage', () => {
  it('uses our own wording for known codes', () => {
    expect(errorMessage(new ApiError(409, 'SLOT_UNAVAILABLE', 'server text'))).toBe(s.errors.SLOT_UNAVAILABLE)
  })

  it('picks the slot reason', () => {
    const e = new ApiError(400, 'INVALID_SLOT', 'x', { reason: 'EARLIER_DAY' })
    expect(errorMessage(e)).toBe(s.errors.INVALID_SLOT_EARLIER_DAY)
  })

  it('falls back to the server message for other domain codes', () => {
    expect(errorMessage(new ApiError(409, 'INVALID_BOOKING_STATE', 'Only a confirmed booking can be cancelled.')))
      .toBe('Only a confirmed booking can be cancelled.')
  })

  it('covers transport and auth failures', () => {
    expect(errorMessage(new NetworkError('offline'))).toBe(s.errors.offline)
    expect(errorMessage(new ApiError(401, undefined, 'Unauthorized'))).toBe(s.errors.sessionExpired)
    expect(errorMessage(new ApiError(403, undefined, 'Forbidden'))).toBe(s.errors.forbidden)
    expect(errorMessage(new ApiError(400, undefined, 'Bad Request'))).toBe(s.errors.invalid)
    expect(errorMessage(new Error('boom'))).toBe(s.errors.generic)
  })
})
