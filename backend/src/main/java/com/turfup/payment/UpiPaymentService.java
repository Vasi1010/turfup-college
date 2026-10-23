package com.turfup.payment;

import com.turfup.booking.BookingNotFoundException;
import com.turfup.booking.BookingRepository;
import com.turfup.booking.BookingView;
import com.turfup.booking.InvalidBookingStateException;
import com.turfup.settings.AppSettings;
import com.turfup.venue.VenueAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Advances paid by UPI straight to the venue owner ({@code turfup.payments.mode = upi}). There is no gateway, so no
 * webhook: the player claims the payment, and the owner, who can see their own bank or UPI app, decides.
 *
 * <ol>
 *   <li>"Pay advance" returns the venue's UPI ID ({@link CheckoutService}) and stamps {@code payment_started_at},
 *       so the hold's grace window applies while the player is in their UPI app.</li>
 *   <li>{@link #claim}: "I've paid", while the hold (with grace) lasts. The hold then never expires on its own
 *       ({@code hold_expired()}, V14): it keeps the slot until the owner decides. A player has at most one HELD
 *       booking (unique index), claimed or not, which limits unpaid claims to one slot at a time.</li>
 *   <li>{@link #markReceived}: CONFIRMED, with an ADVANCE payment (UPI) recorded. {@link #markNotReceived}:
 *       CANCELLED by the owner, reason PAYMENT_NOT_RECEIVED, shown to the player.</li>
 * </ol>
 * Refunds of a UPI advance are recorded PENDING ({@link PaymentService#recordRefund}) and listed for the owner, who
 * sends the money from their UPI app and then {@link #markRefundSent}.
 *
 * <p>Each method is one transaction and locks only the booking (or payment) row it changes.
 */
@Service
public class UpiPaymentService {

    /** A UPI transaction reference (UTR, usually 12 digits) as a player may type it. */
    static final Pattern UPI_REF = Pattern.compile("^[A-Za-z0-9]{6,35}$");

    /** A claimed payment waiting for the owner. Money in paise. */
    public record PaymentToCheck(long bookingId, String courtName, OffsetDateTime startAt, OffsetDateTime endAt,
                                 String playerName, String playerPhone, long amount, String paymentRef,
                                 OffsetDateTime claimedAt) {
    }

    /** A UPI advance refund the owner still has to send. Money in paise. */
    public record RefundToSend(long refundId, long bookingId, String courtName, OffsetDateTime startAt,
                               String playerName, String playerPhone, long amount, String reason, String paymentRef,
                               OffsetDateTime recordedAt) {
    }

    public record Inbox(List<PaymentToCheck> paymentsToCheck, List<RefundToSend> refundsToSend) {
    }

    private final JdbcClient jdbc;
    private final BookingRepository bookings;
    private final PaymentsProperties props;
    private final AppSettings settings;
    private final VenueAccess access;

    public UpiPaymentService(JdbcClient jdbc, BookingRepository bookings, PaymentsProperties props,
                             AppSettings settings, VenueAccess access) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.props = props;
        this.settings = settings;
        this.access = access;
    }

    /**
     * The player says they paid the advance to the venue's UPI ID. Allowed while the hold lasts, including the grace
     * window once payment started. Saying it again only adds a missing reference.
     *
     * @param ref the UPI reference the player typed, or blank
     * @throws BookingNotFoundException     if the booking does not exist or is not this player's
     * @throws InvalidBookingStateException if not in upi mode, the booking is not waiting for an advance, or the
     *                                      hold ran out
     * @throws IllegalArgumentException     if the reference is malformed
     */
    @Transactional
    public BookingView claim(long bookingId, long playerId, String ref) {
        if (!props.upiMode()) {
            throw new InvalidBookingStateException("UPI payments are not in use.");
        }
        String reference = ref == null ? "" : ref.replaceAll("\\s", "");
        if (!reference.isEmpty() && !UPI_REF.matcher(reference).matches()) {
            throw new IllegalArgumentException("The UPI reference is the 12-digit number in your UPI app's receipt.");
        }
        record Row(String status, long advanceRequired, boolean claimed, boolean expired) {
        }
        Row row = jdbc.sql("""
                        SELECT status, advance_required, payment_claimed_at IS NOT NULL AS claimed,
                               (status = 'HELD' AND hold_expired(hold_expires_at, payment_started_at, :graceMinutes))
                                   AS expired
                        FROM bookings
                        WHERE id = :id AND player_id = :playerId
                        FOR UPDATE
                        """)
                .param("id", bookingId)
                .param("playerId", playerId)
                .param("graceMinutes", settings.graceMinutes())
                .query(Row.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (!row.status().equals("HELD") || row.advanceRequired() == 0) {
            throw new InvalidBookingStateException("This booking is not waiting for an advance.");
        }
        if (row.claimed()) {
            jdbc.sql("UPDATE bookings SET payment_ref = COALESCE(payment_ref, :ref) WHERE id = :id")
                    .param("ref", reference.isEmpty() ? null : reference)
                    .param("id", bookingId)
                    .update();
        } else {
            if (row.expired()) {
                throw new InvalidBookingStateException(
                        "The hold ran out before you told the venue. If money left your account, call the venue.");
            }
            jdbc.sql("""
                            UPDATE bookings SET payment_claimed_at = now(), payment_ref = :ref,
                                                payment_started_at = COALESCE(payment_started_at, now())
                            WHERE id = :id
                            """)
                    .param("ref", reference.isEmpty() ? null : reference)
                    .param("id", bookingId)
                    .update();
        }
        return BookingView.of(bookings.findById(bookingId).orElseThrow());
    }

    /**
     * The owner found the advance in their account: the booking is CONFIRMED and the advance recorded. Idempotent
     * for a booking this already confirmed.
     *
     * @throws BookingNotFoundException     if the booking does not exist or is at another owner's venue
     * @throws InvalidBookingStateException if no payment was claimed for it
     */
    @Transactional
    public BookingView markReceived(long ownerId, long bookingId) {
        ClaimRow row = lockOwned(ownerId, bookingId);
        if (row.status().equals("CONFIRMED") && row.advancePaid()) {
            return BookingView.of(bookings.findById(bookingId).orElseThrow());
        }
        requireClaimed(row);
        jdbc.sql("UPDATE bookings SET status = 'CONFIRMED' WHERE id = :id").param("id", bookingId).update();
        jdbc.sql("""
                        INSERT INTO payments (booking_id, kind, amount, method, status)
                        VALUES (:id, 'ADVANCE', :amount, 'UPI', 'SUCCEEDED')
                        """)
                .param("id", bookingId)
                .param("amount", row.advanceRequired())
                .update();
        return BookingView.of(bookings.findById(bookingId).orElseThrow());
    }

    /**
     * The owner found no such payment: the booking is cancelled by the venue (reason PAYMENT_NOT_RECEIVED), which
     * frees the slot and tells the player why. Nothing was recorded as paid, so nothing is refunded.
     *
     * @throws BookingNotFoundException     if the booking does not exist or is at another owner's venue
     * @throws InvalidBookingStateException if no payment was claimed for it
     */
    @Transactional
    public BookingView markNotReceived(long ownerId, long bookingId) {
        requireClaimed(lockOwned(ownerId, bookingId));
        jdbc.sql("""
                        UPDATE bookings SET status = 'CANCELLED', cancelled_by = 'OWNER',
                                            cancel_reason = 'PAYMENT_NOT_RECEIVED'
                        WHERE id = :id
                        """)
                .param("id", bookingId)
                .update();
        return BookingView.of(bookings.findById(bookingId).orElseThrow());
    }

    /** The owner sent a UPI advance refund from their UPI app. */
    @Transactional
    public void markRefundSent(long ownerId, long refundId) {
        int updated = jdbc.sql("""
                        UPDATE payments p SET status = 'SUCCEEDED', refund_sent_at = now()
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                        WHERE p.id = :id AND p.booking_id = b.id AND v.owner_id = :ownerId
                          AND p.kind = 'REFUND' AND p.method = 'UPI' AND p.status = 'PENDING'
                        """)
                .param("id", refundId)
                .param("ownerId", ownerId)
                .update();
        if (updated == 0) {
            throw new RefundNotOpenException(refundId);
        }
    }

    /** What needs the owner's attention at one venue: claimed payments to check, refunds to send. Oldest first. */
    @Transactional(readOnly = true)
    public Inbox inbox(long ownerId, long venueId) {
        access.requireOwnedVenue(ownerId, venueId);
        List<PaymentToCheck> payments = jdbc.sql("""
                        SELECT b.id AS booking_id, c.name AS court_name, b.start_at, b.end_at,
                               u.name AS player_name, u.phone AS player_phone, b.advance_required AS amount,
                               b.payment_ref, b.payment_claimed_at AS claimed_at
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN users u ON u.id = b.player_id
                        WHERE c.venue_id = :venueId AND b.status = 'HELD' AND b.payment_claimed_at IS NOT NULL
                        ORDER BY b.payment_claimed_at, b.id
                        """)
                .param("venueId", venueId)
                .query(PaymentToCheck.class)
                .list();
        List<RefundToSend> refunds = jdbc.sql("""
                        SELECT p.id AS refund_id, b.id AS booking_id, c.name AS court_name, b.start_at,
                               u.name AS player_name, u.phone AS player_phone, p.amount, p.refund_reason AS reason,
                               b.payment_ref, p.created_at AS recorded_at
                        FROM payments p JOIN bookings b ON b.id = p.booking_id JOIN courts c ON c.id = b.court_id
                        JOIN users u ON u.id = b.player_id
                        WHERE c.venue_id = :venueId AND p.kind = 'REFUND' AND p.method = 'UPI' AND p.status = 'PENDING'
                        ORDER BY p.created_at, p.id
                        """)
                .param("venueId", venueId)
                .query(RefundToSend.class)
                .list();
        return new Inbox(payments, refunds);
    }

    private record ClaimRow(String status, long advanceRequired, boolean claimed, boolean advancePaid) {
    }

    private ClaimRow lockOwned(long ownerId, long bookingId) {
        return jdbc.sql("""
                        SELECT b.status, b.advance_required, b.payment_claimed_at IS NOT NULL AS claimed,
                               EXISTS (SELECT 1 FROM payments p WHERE p.booking_id = b.id AND p.kind = 'ADVANCE'
                                         AND p.status = 'SUCCEEDED') AS advance_paid
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                        WHERE b.id = :id AND v.owner_id = :ownerId
                        FOR UPDATE OF b
                        """)
                .param("id", bookingId)
                .param("ownerId", ownerId)
                .query(ClaimRow.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    private static void requireClaimed(ClaimRow row) {
        if (!row.status().equals("HELD") || !row.claimed()) {
            throw new InvalidBookingStateException("This booking has no UPI payment waiting to be checked.");
        }
    }
}
