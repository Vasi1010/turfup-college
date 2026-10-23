package com.turfup.payment;

/** Why a REFUND payment was made ({@code payments.refund_reason}). */
public enum RefundReason {
    /** A captured payment that could not confirm the booking (late, slot gone, duplicate, wrong amount). */
    PAYMENT_REJECTED,
    /** The player cancelled in time and within the turf's monthly allowance. Counts toward the allowance. */
    PLAYER_CANCELLATION,
    /** The owner cancelled. Always a full refund; never counts toward the player's allowance. */
    OWNER_CANCELLATION
}
