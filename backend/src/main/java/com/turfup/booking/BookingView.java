package com.turfup.booking;

import java.time.OffsetDateTime;

/**
 * A booking as returned by the API. Money is paise.
 *
 * <p>For a HELD booking shown to its player, {@code holdExpiresAt} is when the hold ends and
 * {@code payCutoffAt} is the last moment a payment may start (server settings, so the UI never hard-codes
 * them). {@code payCutoffAt} is null in other contexts.
 */
public record BookingView(
        long id,
        long courtId,
        Long playerId,
        String walkinName,
        String walkinPhone,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        BookingStatus status,
        BookingSource source,
        long totalPrice,
        long advanceRequired,
        OffsetDateTime holdExpiresAt,
        OffsetDateTime paymentStartedAt,
        String cancelledBy,
        String cancelReason,
        String cancelNote,
        OffsetDateTime createdAt,
        OffsetDateTime payCutoffAt) {

    public static BookingView of(Booking b) {
        return new BookingView(b.getId(), b.getCourtId(), b.getPlayerId(), b.getWalkinName(), b.getWalkinPhone(),
                b.getStartAt(), b.getEndAt(), b.getStatus(), b.getSource(), b.getTotalPrice(),
                b.getAdvanceRequired(), b.getHoldExpiresAt(), b.getPaymentStartedAt(), b.getCancelledBy(),
                b.getCancelReason(), b.getCancelNote(), b.getCreatedAt(), null);
    }

    /** The player's view: adds {@code payCutoffAt} for a HELD booking. */
    public static BookingView forPlayer(Booking b, int minPaySecondsRemaining) {
        BookingView v = of(b);
        if (b.getStatus() != BookingStatus.HELD || b.getHoldExpiresAt() == null) {
            return v;
        }
        return new BookingView(v.id, v.courtId, v.playerId, v.walkinName, v.walkinPhone, v.startAt, v.endAt,
                v.status, v.source, v.totalPrice, v.advanceRequired, v.holdExpiresAt, v.paymentStartedAt,
                v.cancelledBy, v.cancelReason, v.cancelNote, v.createdAt,
                b.getHoldExpiresAt().minusSeconds(minPaySecondsRemaining));
    }
}
