package com.turfup.payment;

import com.turfup.booking.BookingNotFoundException;
import com.turfup.booking.BookingService;
import com.turfup.booking.InvalidBookingStateException;
import com.turfup.booking.PaymentWindowClosedException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Payment records and the booking transitions they drive. These are the methods a gateway integration
 * calls: {@link #startAdvancePayment} when the player taps "Pay advance", and
 * {@link #recordCapturedAdvance} from the payment webhook. Simulated mode calls the same two methods
 * (see {@link CheckoutService}).
 */
@Service
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentService {

    public record AdvanceOrder(long bookingId, String gatewayOrderId, long amount) {
    }

    public enum CaptureOutcome {
        /** The booking is now CONFIRMED by this payment. */
        CONFIRMED,
        /** The hold was gone (expired after the grace window, or already settled); a refund was recorded. */
        REFUNDED,
        /** This gateway payment was already recorded; nothing changed. */
        DUPLICATE
    }

    public record CaptureResult(CaptureOutcome outcome, long bookingId) {
    }

    public record BalanceReceipt(long bookingId, long amount, PaymentMethod method) {
    }

    /** A gateway refund was recorded; it is sent to Razorpay once the recording transaction commits. */
    public record RazorpayRefundRecorded(long refundId) {
    }

    /** A slot ending sooner than this is not worth holding the transfer for (Razorpay needs a future time). */
    static final Duration MIN_TRANSFER_HOLD = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final BookingService bookings;
    private final PaymentsProperties props;
    private final RazorpayGateway gateway;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public PaymentService(JdbcClient jdbc, BookingService bookings, PaymentsProperties props, RazorpayGateway gateway,
                          PlatformTransactionManager transactions, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.props = props;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactions);
        this.events = events;
    }

    /**
     * Starts paying the advance for the player's HELD booking: stamps {@code payment_started_at}, creates the
     * gateway order and records a PENDING ADVANCE payment under it. In razorpay mode the order transfers the
     * advance to the venue's linked account (Route), held until the booking's slot ends, so any refund before then
     * (player or owner cancellation, late payment) can always reverse it; a slot ending less than
     * {@link #MIN_TRANSFER_HOLD} away is not held. The Razorpay call
     * happens between two short transactions, so no booking, court or venue lock is held while waiting on the network.
     *
     * @throws BookingNotFoundException          if the booking does not exist or is not this player's
     * @throws InvalidBookingStateException      if the booking is not HELD
     * @throws PaymentWindowClosedException      if too little of the hold is left
     * @throws OnlinePaymentUnavailableException if the venue's linked account is not active (razorpay mode)
     * @throws RazorpayException                 if Razorpay could not create the order
     */
    public AdvanceOrder startAdvancePayment(long bookingId, long playerId) {
        record Start(long amount, String linkedAccount, Instant holdUntil) {
        }
        Start start = tx.execute(status -> {
            long amount = bookings.startPayment(bookingId, playerId);
            if (!props.razorpayMode()) {
                return new Start(amount, null, null);
            }
            record Venue(String razorpayAccountId, String razorpayAccountStatus, OffsetDateTime endAt,
                         boolean holdable) {
            }
            Venue venue = jdbc.sql("""
                            SELECT v.razorpay_account_id, v.razorpay_account_status,
                                   b.end_at, b.end_at > now() + make_interval(secs => :minHold) AS holdable
                            FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                            WHERE b.id = :id
                            """)
                    .param("id", bookingId)
                    .param("minHold", MIN_TRANSFER_HOLD.toSeconds())
                    .query(Venue.class)
                    .single();
            if (!LinkedAccountService.ACTIVATED.equals(venue.razorpayAccountStatus())) {
                throw new OnlinePaymentUnavailableException();
            }
            return new Start(amount, venue.razorpayAccountId(), venue.holdable() ? venue.endAt().toInstant() : null);
        });

        if (props.upiMode()) {
            // Paid straight to the venue's UPI ID: no order, no payment row until the owner confirms the money.
            return new AdvanceOrder(bookingId, null, start.amount());
        }
        String orderId = switch (props.mode()) {
            case SIMULATED -> "sim_order_" + UUID.randomUUID();
            case RAZORPAY -> gateway.createOrder(start.amount(), "booking-" + bookingId,
                    new RazorpayGateway.Transfer(start.linkedAccount(), start.amount(), start.holdUntil()),
                    Map.of("booking_id", String.valueOf(bookingId)));
            case UPI -> throw new IllegalStateException("unreachable: returned above");
        };
        tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO payments (booking_id, kind, amount, method, status, gateway_order_id,
                                              transfer_hold_until)
                        VALUES (:bookingId, 'ADVANCE', :amount, :method, 'PENDING', :orderId, :holdUntil)
                        """)
                .param("bookingId", bookingId)
                .param("amount", start.amount())
                .param("method", gatewayMethod().name())
                .param("orderId", orderId)
                .param("holdUntil", start.holdUntil() == null ? null
                        : OffsetDateTime.ofInstant(start.holdUntil(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update());
        return new AdvanceOrder(bookingId, orderId, start.amount());
    }

    /**
     * Webhook entry point: the gateway captured {@code amount} paise for the order. Idempotent: a
     * {@code gatewayPaymentId} already recorded is a no-op, and concurrent deliveries for one order are
     * serialized by locking the order's payment row. Confirms the booking if its hold is still valid
     * (including the grace window); otherwise records a REFUND of the captured amount.
     *
     * @throws UnknownPaymentOrderException if no advance payment was started under this order
     */
    @Transactional
    public CaptureResult recordCapturedAdvance(String gatewayOrderId, String gatewayPaymentId, long amount) {
        record Order(long id, long bookingId, String status, String method) {
        }
        Order order = jdbc.sql("""
                        SELECT id, booking_id, status, method FROM payments
                        WHERE gateway_order_id = :orderId AND kind = 'ADVANCE'
                        ORDER BY id
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("orderId", gatewayOrderId)
                .query(Order.class)
                .optional()
                .orElseThrow(() -> new UnknownPaymentOrderException(gatewayOrderId));

        boolean seen = jdbc.sql("SELECT EXISTS (SELECT 1 FROM payments WHERE gateway_payment_id = :paymentId)")
                .param("paymentId", gatewayPaymentId)
                .query(Boolean.class)
                .single();
        if (seen) {
            return new CaptureResult(CaptureOutcome.DUPLICATE, order.bookingId());
        }

        long capturedId;
        if (order.status().equals("PENDING")) {
            jdbc.sql("""
                            UPDATE payments SET status = 'SUCCEEDED', gateway_payment_id = :paymentId, amount = :amount
                            WHERE id = :id
                            """)
                    .param("paymentId", gatewayPaymentId)
                    .param("amount", amount)
                    .param("id", order.id())
                    .update();
            capturedId = order.id();
        } else {
            // A second, different payment for an order that was already settled (e.g. the player paid twice),
            // or a very late payment for an order the reconciliation job gave up on (FAILED).
            capturedId = jdbc.sql("""
                            INSERT INTO payments (booking_id, kind, amount, method, status, gateway_order_id,
                                                  gateway_payment_id, transfer_hold_until)
                            VALUES (:bookingId, 'ADVANCE', :amount, :method, 'SUCCEEDED', :orderId, :paymentId,
                                    (SELECT transfer_hold_until FROM payments WHERE id = :orderPaymentId))
                            RETURNING id
                            """)
                    .param("orderPaymentId", order.id())
                    .param("bookingId", order.bookingId())
                    .param("amount", amount)
                    .param("method", order.method())
                    .param("orderId", gatewayOrderId)
                    .param("paymentId", gatewayPaymentId)
                    .query(Long.class)
                    .single();
        }

        if (bookings.confirmPaidHold(order.bookingId(), amount)) {
            return new CaptureResult(CaptureOutcome.CONFIRMED, order.bookingId());
        }
        recordRefund(order.bookingId(), amount, PaymentMethod.valueOf(order.method()), gatewayOrderId,
                RefundReason.PAYMENT_REJECTED, capturedId);
        return new CaptureResult(CaptureOutcome.REFUNDED, order.bookingId());
    }

    /**
     * Owner marks the balance (total price minus the advance) as received at the venue. Once per booking.
     *
     * @throws BookingNotFoundException     if the booking does not exist or is at another owner's venue
     * @throws InvalidBookingStateException if the booking is not CONFIRMED or COMPLETED, nothing is due, or the
     *                                      balance was already received
     * @throws IllegalArgumentException     if {@code method} is not CASH or UPI
     */
    @Transactional
    public BalanceReceipt recordBalanceReceived(long ownerId, long bookingId, PaymentMethod method) {
        if (method != PaymentMethod.CASH && method != PaymentMethod.UPI) {
            throw new IllegalArgumentException("Balance is received as CASH or UPI");
        }
        record Row(String status, long totalPrice, long advanceRequired, boolean balancePaid) {
        }
        Row row = jdbc.sql("""
                        SELECT b.status, b.total_price, b.advance_required,
                               EXISTS (SELECT 1 FROM payments p
                                       WHERE p.booking_id = b.id AND p.kind = 'BALANCE' AND p.status = 'SUCCEEDED')
                                   AS balance_paid
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                        WHERE b.id = :id AND v.owner_id = :ownerId
                        FOR UPDATE OF b
                        """)
                .param("id", bookingId)
                .param("ownerId", ownerId)
                .query(Row.class)
                .optional()
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        // Players sometimes pay late, so this stays possible after the booking is marked COMPLETED.
        if (!row.status().equals("CONFIRMED") && !row.status().equals("COMPLETED")) {
            throw new InvalidBookingStateException("The balance can be received only for a confirmed or played booking.");
        }
        if (row.balancePaid()) {
            throw new InvalidBookingStateException("The balance for this booking was already received.");
        }
        long balance = row.totalPrice() - row.advanceRequired();
        if (balance <= 0) {
            throw new InvalidBookingStateException("Nothing is due: the advance covered the full price.");
        }
        jdbc.sql("""
                        INSERT INTO payments (booking_id, kind, amount, method, status)
                        VALUES (:bookingId, 'BALANCE', :amount, :method, 'SUCCEEDED')
                        """)
                .param("bookingId", bookingId)
                .param("amount", balance)
                .param("method", method.name())
                .update();
        return new BalanceReceipt(bookingId, balance, method);
    }

    /**
     * Records money going back to the player, as a refund of payment {@code refundOf}. SIMULATED refunds complete
     * at once. RAZORPAY refunds are recorded PENDING and sent to Razorpay after this transaction commits
     * ({@link RazorpayRefunds}), reversing the Route transfer; the refund webhook marks them done. A UPI advance
     * (paid to the venue's UPI ID in upi mode) is refunded by the owner from their own UPI app: recorded PENDING
     * until the owner marks it sent ({@link UpiPaymentService#markRefundSent}). CASH and UPI balances are handed
     * back by the owner at the venue and are recorded as done. Call inside the transaction that changes the booking.
     *
     * @param refundOf the payment being refunded; required for RAZORPAY
     */
    public void recordRefund(long bookingId, long amount, PaymentMethod method, String gatewayOrderId,
                             RefundReason reason, Long refundOf) {
        boolean upiAdvance = method == PaymentMethod.UPI && refundOf != null && jdbc.sql(
                        "SELECT kind = 'ADVANCE' FROM payments WHERE id = :id")
                .param("id", refundOf)
                .query(Boolean.class)
                .single();
        String status = method == PaymentMethod.RAZORPAY || upiAdvance ? "PENDING" : "SUCCEEDED";
        long refundId = jdbc.sql("""
                        INSERT INTO payments (booking_id, kind, amount, method, status, gateway_order_id, refund_reason,
                                              refund_of)
                        VALUES (:bookingId, 'REFUND', :amount, :method, :status, :orderId, :reason, :refundOf)
                        RETURNING id
                        """)
                .param("bookingId", bookingId)
                .param("amount", amount)
                .param("method", method.name())
                .param("status", status)
                .param("orderId", gatewayOrderId)
                .param("reason", reason.name())
                .param("refundOf", refundOf, Types.BIGINT)
                .query(Long.class)
                .single();
        if (method == PaymentMethod.RAZORPAY) {
            events.publishEvent(new RazorpayRefundRecorded(refundId));
        }
    }

    private PaymentMethod gatewayMethod() {
        return props.mode() == PaymentMode.RAZORPAY ? PaymentMethod.RAZORPAY : PaymentMethod.SIMULATED;
    }
}
