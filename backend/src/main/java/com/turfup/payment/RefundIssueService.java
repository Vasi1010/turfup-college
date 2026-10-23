package com.turfup.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Razorpay refunds a person has to look at: ones Razorpay reported FAILED, and ones it keeps rejecting (still
 * PENDING and retried, with {@code gateway_error} saying why). Typically the owner's linked account could not cover
 * the transfer reversal. The admin refunds the player from the Razorpay dashboard, then records it here.
 */
@Service
public class RefundIssueService {

    /** {@code error} is Razorpay's reason (rejected refunds); null for FAILED ones, whose reason is in the dashboard. */
    public record RefundIssue(long refundId, long bookingId, String venueName, String courtName, String ownerPhone,
                              String playerName,
                              String playerPhone, long amount, String status, String reason, String error,
                              OffsetDateTime createdAt, OffsetDateTime errorAt, OffsetDateTime bookingStart,
                              String gatewayPaymentId, String gatewayRefundId) {
    }

    private final JdbcClient jdbc;
    private final RazorpayRefunds refunds;

    public RefundIssueService(JdbcClient jdbc, RazorpayRefunds refunds) {
        this.jdbc = jdbc;
        this.refunds = refunds;
    }

    /** Oldest first. Empty in simulated mode. */
    @Transactional(readOnly = true)
    public List<RefundIssue> list() {
        return jdbc.sql("""
                        SELECT r.id AS refund_id, b.id AS booking_id, v.name AS venue_name, c.name AS court_name,
                               o.phone AS owner_phone,
                               coalesce(u.name, b.walkin_name) AS player_name,
                               coalesce(u.phone, b.walkin_phone) AS player_phone,
                               r.amount, r.status, r.refund_reason AS reason, r.gateway_error AS error,
                               r.created_at, r.gateway_error_at AS error_at, b.start_at AS booking_start,
                               p.gateway_payment_id, r.gateway_refund_id
                        FROM payments r
                        JOIN bookings b ON b.id = r.booking_id
                        JOIN courts c ON c.id = b.court_id
                        JOIN venues v ON v.id = c.venue_id
                        JOIN users o ON o.id = v.owner_id
                        LEFT JOIN users u ON u.id = b.player_id
                        LEFT JOIN payments p ON p.id = r.refund_of
                        WHERE r.kind = 'REFUND' AND r.method = 'RAZORPAY'
                          AND (r.status = 'FAILED' OR (r.status = 'PENDING' AND r.gateway_error IS NOT NULL))
                        ORDER BY r.created_at, r.id
                        """)
                .query(RefundIssue.class)
                .list();
    }

    /**
     * The admin refunded the player from the Razorpay dashboard: the refund counts as done, and a PENDING one is no
     * longer retried.
     *
     * @throws RefundNotOpenException if it does not need attention (any more)
     */
    public void markSettledManually(long refundId) {
        if (!refunds.markSettledManually(refundId)) {
            throw new RefundNotOpenException(refundId);
        }
    }
}
