package com.turfup.booking;

import com.turfup.settings.AppSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** A player's own bookings, with the venue, court and money details My bookings shows. Money is paise. */
@Service
public class MyBookingsService {

    /**
     * {@code refunded} is the sum of refunds recorded for the booking (pending gateway refunds included);
     * {@code cancelledBy}/{@code cancelReason}/{@code cancelNote} explain a cancellation. For a HELD booking,
     * {@code payCutoffAt} is the last moment a payment may start ({@code holdExpiresAt} minus
     * {@code min_pay_seconds_remaining}); {@code createdAt} is when the hold began.
     *
     * <p>upi mode: {@code paymentClaimedAt} is when the player said they paid (the hold then waits for the venue),
     * {@code paymentRef} the UPI reference they gave; {@code refundPending} is the part of {@code refunded} the venue
     * has yet to send. {@code upiId}/{@code upiName} are where the advance is paid, {@code venuePhone} the owner's
     * number for questions.
     */
    public record MyBooking(
            long id,
            long venueId,
            String venueName,
            String venueArea,
            long courtId,
            String courtName,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            String source,
            long totalPrice,
            long advanceRequired,
            OffsetDateTime holdExpiresAt,
            OffsetDateTime paymentStartedAt,
            String cancelledBy,
            String cancelReason,
            String cancelNote,
            long refunded,
            OffsetDateTime createdAt,
            OffsetDateTime payCutoffAt,
            OffsetDateTime paymentClaimedAt,
            String paymentRef,
            long refundPending,
            String upiId,
            String upiName,
            String venuePhone) {
    }

    private static final String SELECT = """
            SELECT b.id, v.id AS venue_id, v.name AS venue_name, v.area AS venue_area, c.id AS court_id,
                   c.name AS court_name, b.start_at, b.end_at, b.status,
                   b.source, b.total_price, b.advance_required, b.hold_expires_at, b.payment_started_at,
                   b.cancelled_by, b.cancel_reason, b.cancel_note,
                   COALESCE((SELECT sum(p.amount) FROM payments p
                             WHERE p.booking_id = b.id AND p.kind = 'REFUND' AND p.status <> 'FAILED'), 0) AS refunded,
                   b.created_at,
                   CASE WHEN b.status = 'HELD'
                        THEN b.hold_expires_at - make_interval(secs => :minPaySeconds) END AS pay_cutoff_at,
                   b.payment_claimed_at, b.payment_ref,
                   COALESCE((SELECT sum(p.amount) FROM payments p
                             WHERE p.booking_id = b.id AND p.kind = 'REFUND' AND p.method = 'UPI'
                               AND p.status = 'PENDING'), 0) AS refund_pending,
                   v.upi_id, COALESCE(v.upi_name, v.name) AS upi_name, o.phone AS venue_phone
            FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
            JOIN users o ON o.id = v.owner_id
            """;

    private final JdbcClient jdbc;
    private final AppSettings settings;

    public MyBookingsService(JdbcClient jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Every booking except abandoned holds (EXPIRED), latest slot first. */
    @Transactional(readOnly = true)
    public List<MyBooking> list(long playerId) {
        return jdbc.sql(SELECT + " WHERE b.player_id = :playerId AND b.status <> 'EXPIRED' ORDER BY b.start_at DESC")
                .param("playerId", playerId)
                .param("minPaySeconds", settings.minPaySecondsRemaining())
                .query(MyBooking.class)
                .list();
    }

    /** One booking in any status, for the hold/payment screen. */
    @Transactional(readOnly = true)
    public MyBooking get(long playerId, long bookingId) {
        return jdbc.sql(SELECT + " WHERE b.id = :id AND b.player_id = :playerId")
                .param("id", bookingId)
                .param("playerId", playerId)
                .param("minPaySeconds", settings.minPaySecondsRemaining())
                .query(MyBooking.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }
}
