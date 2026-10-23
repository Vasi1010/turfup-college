package com.turfup.booking;

/** Why the owner cancelled ({@code bookings.cancel_reason}). OTHER needs a note. */
public enum CancelReason {
    RAIN,
    MAINTENANCE,
    OTHER,
    /** Set by the system when a recurring booking is ended or changed; owners do not pick it. */
    RECURRING_CHANGED
}
