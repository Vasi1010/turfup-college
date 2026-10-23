package com.turfup.booking;

import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.payment.RefundReason;
import com.turfup.payment.Rupees;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Cancelling confirmed bookings and releasing holds.
 *
 * <p>Player cancellation refunds the full advance only if (a) it is at least the court's
 * {@code refund_full_hours_before} ahead of the slot and (b) the player has received fewer than the venue's
 * {@code refunds_per_month} cancellation refunds at this venue (any of its courts) in the current IST calendar
 * month, counted from {@code payments} rows with {@code refund_reason = PLAYER_CANCELLATION}. Otherwise nothing
 * is refunded, and a late cancellation does not use up the allowance. Owner cancellations always refund
 * everything paid and never count.
 *
 * <p>Cancellations lock the venue row first, then the booking (lock order: venue, court, rows), so two
 * cancellations by the same player at the same venue, even on different courts, run one after the other and the
 * second sees the first one's refund.
 */
@Service
public class CancellationService {

    public enum RefundOutcome {
        REFUND,
        NO_REFUND_TOO_LATE,
        NO_REFUND_LIMIT_REACHED,
        NOTHING_PAID
    }

    /**
     * What cancelling would do (a quote) or did. Money is paise. The allowance fields are null for owner
     * cancellations, which do not use one.
     */
    public record CancellationTerms(
            long bookingId,
            RefundOutcome outcome,
            long refundAmount,
            OffsetDateTime fullRefundDeadline,
            Integer refundsPerMonth,
            Integer refundsUsedThisMonth,
            String month,
            String message) {
    }

    public record CancellationResult(CancellationTerms terms, BookingView booking) {
    }

    record PlayerTerms(
            String status,
            long advanceRequired,
            OffsetDateTime startAt,
            boolean started,
            boolean earlyEnough,
            int refundFullHoursBefore,
            int refundsPerMonth,
            long refundsUsed,
            boolean balancePaid,
            String month) {
    }

    record PaidAdvance(long id, PaymentMethod method, String gatewayOrderId) {
    }

    private final JdbcClient jdbc;
    private final BookingRepository bookings;
    private final PaymentService payments;

    public CancellationService(JdbcClient jdbc, BookingRepository bookings, PaymentService payments) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.payments = payments;
    }

    /**
     * What cancelling the player's booking would refund right now, to show before they confirm.
     *
     * @throws BookingNotFoundException     if the booking does not exist or is not this player's
     * @throws InvalidBookingStateException if the booking cannot be cancelled by the player
     */
    @Transactional(readOnly = true)
    public CancellationTerms quote(long bookingId, long playerId) {
        PlayerTerms t = loadPlayerTerms(bookingId, playerId);
        requireCancellableByPlayer(t);
        return playerTerms(bookingId, t);
    }

    /**
     * Cancels the player's CONFIRMED booking and refunds per the court's and venue's rules.
     *
     * @param expectedRefund the refund (paise) the player was shown and agreed to, or null to accept any.
     *                       If cancelling now would refund a different amount, nothing changes.
     * @throws CancellationTermsChangedException if {@code expectedRefund} no longer matches
     * @throws BookingNotFoundException          if the booking does not exist or is not this player's
     * @throws InvalidBookingStateException      if the booking cannot be cancelled by the player
     */
    @Transactional
    public CancellationResult cancelByPlayer(long bookingId, long playerId, Long expectedRefund) {
        long venueId = jdbc.sql("""
                        SELECT c.venue_id FROM bookings b JOIN courts c ON c.id = b.court_id
                        WHERE b.id = :id AND b.player_id = :playerId
                        """)
                .param("id", bookingId)
                .param("playerId", playerId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        lock(venueId, bookingId);

        PlayerTerms t = loadPlayerTerms(bookingId, playerId);
        requireCancellableByPlayer(t);
        CancellationTerms terms = playerTerms(bookingId, t);
        if (expectedRefund != null && expectedRefund != terms.refundAmount()) {
            throw new CancellationTermsChangedException(terms);
        }

        setCancelled(bookingId, "PLAYER", null, null);
        if (terms.outcome() == RefundOutcome.REFUND) {
            PaidAdvance advance = paidAdvance(bookingId)
                    .orElseThrow(() -> new IllegalStateException("Confirmed booking " + bookingId + " has no advance payment"));
            payments.recordRefund(bookingId, terms.refundAmount(), advance.method(), advance.gatewayOrderId(),
                    RefundReason.PLAYER_CANCELLATION, advance.id());
        }
        return new CancellationResult(terms, BookingView.of(bookings.findById(bookingId).orElseThrow()));
    }

    /**
     * Owner cancels a CONFIRMED booking at their venue, giving a reason the player sees in My bookings.
     * Everything paid (advance and any balance) goes back, and the player's monthly allowance is not touched.
     *
     * @throws BookingNotFoundException     if the booking does not exist or is at another owner's venue
     * @throws InvalidBookingStateException if the booking is not CONFIRMED
     * @throws IllegalArgumentException     if the reason is missing, or OTHER without a note
     */
    @Transactional
    public CancellationResult cancelByOwner(long ownerId, long bookingId, CancelReason reason, String note) {
        String trimmedNote = note == null || note.isBlank() ? null : note.strip();
        if (reason == null) {
            throw new IllegalArgumentException("Choose a reason for cancelling");
        }
        if (reason == CancelReason.OTHER && trimmedNote == null) {
            throw new IllegalArgumentException("Add a note explaining the cancellation");
        }
        long venueId = jdbc.sql("""
                        SELECT c.venue_id FROM bookings b JOIN courts c ON c.id = b.court_id
                        JOIN venues v ON v.id = c.venue_id
                        WHERE b.id = :id AND v.owner_id = :ownerId
                        """)
                .param("id", bookingId)
                .param("ownerId", ownerId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        lock(venueId, bookingId);

        record Row(String status, long advanceRequired) {
        }
        Row row = jdbc.sql("SELECT status, advance_required FROM bookings WHERE id = :id")
                .param("id", bookingId)
                .query(Row.class)
                .single();
        if (!row.status().equals(BookingStatus.CONFIRMED.name())) {
            throw new InvalidBookingStateException("Only a confirmed booking can be cancelled.");
        }
        setCancelled(bookingId, "OWNER", reason.name(), trimmedNote);

        long refunded = 0;
        if (row.advanceRequired() > 0) {
            Optional<PaidAdvance> advance = paidAdvance(bookingId);
            if (advance.isPresent()) {
                payments.recordRefund(bookingId, row.advanceRequired(), advance.get().method(),
                        advance.get().gatewayOrderId(), RefundReason.OWNER_CANCELLATION, advance.get().id());
                refunded += row.advanceRequired();
            }
        }
        record Balance(long id, long amount, String method) {
        }
        Optional<Balance> balance = jdbc.sql("""
                        SELECT id, amount, method FROM payments
                        WHERE booking_id = :id AND kind = 'BALANCE' AND status = 'SUCCEEDED'
                        """)
                .param("id", bookingId)
                .query(Balance.class)
                .optional();
        if (balance.isPresent()) {
            payments.recordRefund(bookingId, balance.get().amount(), PaymentMethod.valueOf(balance.get().method()), null,
                    RefundReason.OWNER_CANCELLATION, balance.get().id());
            refunded += balance.get().amount();
        }

        CancellationTerms terms = refunded > 0
                ? new CancellationTerms(bookingId, RefundOutcome.REFUND, refunded, null, null, null, null,
                Rupees.format(refunded) + " refunded to the player")
                : new CancellationTerms(bookingId, RefundOutcome.NOTHING_PAID, 0, null, null, null, null,
                "Nothing was paid, so there is nothing to refund");
        return new CancellationResult(terms, BookingView.of(bookings.findById(bookingId).orElseThrow()));
    }

    /**
     * The player gives up their HELD slot before paying. Not a cancellation: no refund, nothing counted.
     * A payment that still arrives for it is refunded automatically.
     *
     * @throws BookingNotFoundException     if the booking does not exist or is not this player's
     * @throws InvalidBookingStateException if the booking is not HELD
     */
    @Transactional
    public BookingView releaseHold(long bookingId, long playerId) {
        record Row(String status, boolean claimed) {
        }
        Row row = jdbc.sql("""
                        SELECT status, payment_claimed_at IS NOT NULL AS claimed FROM bookings
                        WHERE id = :id AND player_id = :playerId
                        FOR UPDATE
                        """)
                .param("id", bookingId)
                .param("playerId", playerId)
                .query(Row.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (!row.status().equals(BookingStatus.HELD.name())) {
            throw new InvalidBookingStateException("Only a slot on hold can be released.");
        }
        if (row.claimed()) {
            // upi mode: the player said they paid the venue; only the venue can settle it now.
            throw new InvalidBookingStateException(
                    "You told the venue you paid. Ask the venue to cancel if you no longer want this slot.");
        }
        jdbc.sql("UPDATE bookings SET status = 'EXPIRED' WHERE id = :id").param("id", bookingId).update();
        return BookingView.of(bookings.findById(bookingId).orElseThrow());
    }

    // --- rules ---

    private static void requireCancellableByPlayer(PlayerTerms t) {
        if (t.status().equals(BookingStatus.HELD.name())) {
            throw new InvalidBookingStateException("This slot is only on hold. Release the hold instead.");
        }
        if (!t.status().equals(BookingStatus.CONFIRMED.name())) {
            throw new InvalidBookingStateException("Only a confirmed booking can be cancelled.");
        }
        if (t.started()) {
            throw new InvalidBookingStateException("This slot has already started and can no longer be cancelled.");
        }
        if (t.balancePaid()) {
            throw new InvalidBookingStateException("This booking was paid at the venue. Ask the venue to cancel it.");
        }
    }

    private static CancellationTerms playerTerms(long bookingId, PlayerTerms t) {
        OffsetDateTime deadline = t.startAt().minusHours(t.refundFullHoursBefore());
        int used = (int) t.refundsUsed();
        RefundOutcome outcome;
        String message;
        if (t.advanceRequired() == 0) {
            outcome = RefundOutcome.NOTHING_PAID;
            message = "No advance was paid, so there is nothing to refund";
        } else if (!t.earlyEnough()) {
            outcome = RefundOutcome.NO_REFUND_TOO_LATE;
            message = "No refund: cancelling less than " + hours(t.refundFullHoursBefore()) + " before the slot";
        } else if (used >= t.refundsPerMonth()) {
            outcome = RefundOutcome.NO_REFUND_LIMIT_REACHED;
            message = switch (t.refundsPerMonth()) {
                case 0 -> "No refund: this venue does not refund cancellations";
                case 1 -> "No refund: already used this month";
                default -> "No refund: all " + t.refundsPerMonth() + " refunds for " + t.month() + " already used";
            };
        } else {
            outcome = RefundOutcome.REFUND;
            message = Rupees.format(t.advanceRequired()) + " back, uses "
                    + (t.refundsPerMonth() == 1 ? "your 1 refund" : "1 of your " + t.refundsPerMonth() + " refunds")
                    + " for " + t.month();
        }
        long amount = outcome == RefundOutcome.REFUND ? t.advanceRequired() : 0;
        return new CancellationTerms(bookingId, outcome, amount, deadline, t.refundsPerMonth(), used, t.month(), message);
    }

    private static String hours(int h) {
        return h == 1 ? "1 hour" : h + " hours";
    }

    // --- data ---

    /** Venue row first, then the booking (lock order: venue, court, rows). */
    private void lock(long venueId, long bookingId) {
        jdbc.sql("SELECT 1 FROM venues WHERE id = :id FOR NO KEY UPDATE").param("id", venueId).query(Integer.class).single();
        jdbc.sql("SELECT 1 FROM bookings WHERE id = :id FOR UPDATE").param("id", bookingId).query(Integer.class).single();
    }

    private PlayerTerms loadPlayerTerms(long bookingId, long playerId) {
        return jdbc.sql("""
                        SELECT b.status, b.advance_required, b.start_at,
                               b.start_at <= now() AS started,
                               b.start_at - now() >= make_interval(hours => c.refund_full_hours_before) AS early_enough,
                               c.refund_full_hours_before, v.refunds_per_month,
                               (SELECT count(*) FROM payments p JOIN bookings pb ON pb.id = p.booking_id
                                JOIN courts pc ON pc.id = pb.court_id
                                WHERE p.kind = 'REFUND' AND p.refund_reason = 'PLAYER_CANCELLATION'
                                  AND p.status <> 'FAILED'
                                  AND pc.venue_id = c.venue_id AND pb.player_id = b.player_id
                                  AND p.created_at >= date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                                                      AT TIME ZONE 'Asia/Kolkata') AS refunds_used,
                               EXISTS (SELECT 1 FROM payments p
                                       WHERE p.booking_id = b.id AND p.kind = 'BALANCE' AND p.status = 'SUCCEEDED')
                                   AS balance_paid,
                               to_char(now() AT TIME ZONE 'Asia/Kolkata', 'FMMonth') AS month
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                        WHERE b.id = :id AND b.player_id = :playerId
                        """)
                .param("id", bookingId)
                .param("playerId", playerId)
                .query(PlayerTerms.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /** The advance payment that confirmed the booking. */
    private Optional<PaidAdvance> paidAdvance(long bookingId) {
        record Row(long id, String method, String gatewayOrderId) {
        }
        return jdbc.sql("""
                        SELECT id, method, gateway_order_id FROM payments
                        WHERE booking_id = :id AND kind = 'ADVANCE' AND status = 'SUCCEEDED'
                        ORDER BY id
                        LIMIT 1
                        """)
                .param("id", bookingId)
                .query(Row.class)
                .optional()
                .map(r -> new PaidAdvance(r.id(), PaymentMethod.valueOf(r.method()), r.gatewayOrderId()));
    }

    private void setCancelled(long bookingId, String by, String reason, String note) {
        jdbc.sql("""
                        UPDATE bookings SET status = 'CANCELLED', cancelled_by = :by,
                                            cancel_reason = :reason, cancel_note = :note
                        WHERE id = :id
                        """)
                .param("by", by)
                .param("reason", reason)
                .param("note", note)
                .param("id", bookingId)
                .update();
    }
}
