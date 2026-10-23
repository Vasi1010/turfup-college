package com.turfup.payment;

import com.turfup.booking.BookingRepository;
import com.turfup.booking.BookingView;
import com.turfup.payment.PaymentService.AdvanceOrder;
import com.turfup.payment.PaymentService.CaptureResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * "Pay advance". In simulated mode it starts the payment and immediately delivers a captured-payment event,
 * through the same {@link PaymentService} methods (and transactions) a real gateway webhook uses. In razorpay
 * mode it creates the Razorpay order and returns what the browser needs to open Razorpay Checkout; the booking
 * confirms later, from the webhook.
 */
@Service
public class CheckoutService {

    public enum PayOutcome {
        /** The booking is CONFIRMED (simulated mode). */
        CONFIRMED,
        /** The payment arrived too late and was refunded (simulated mode). */
        REFUNDED,
        /** Already recorded; nothing changed. */
        DUPLICATE,
        /** Razorpay order created: open Checkout, then wait for the booking to confirm. */
        AWAITING_PAYMENT,
        /** upi mode: pay the venue's UPI ID from a UPI app, then tell the venue ("I've paid"). */
        PAY_BY_UPI
    }

    /**
     * What a UPI app needs ({@code upi://pay?pa=...&pn=...&am=...&tn=...}): the venue's UPI ID, the name on the
     * payment, the advance in paise, and a note that lets the owner match the payment to the booking.
     */
    public record UpiPayment(String upiId, String payeeName, long amount, String note) {
    }

    /** Options for Razorpay Checkout ({@code new Razorpay(options)}). Amount in paise. */
    public record RazorpayCheckout(String keyId, String orderId, long amount, String currency, String name,
                                   String description, String prefillName, String prefillContact) {
    }

    public record PayResult(PayOutcome outcome, BookingView booking, RazorpayCheckout checkout, UpiPayment upi) {
    }

    private final PaymentService payments;
    private final BookingRepository bookings;
    private final PaymentsProperties props;
    private final JdbcClient jdbc;

    public CheckoutService(PaymentService payments, BookingRepository bookings, PaymentsProperties props,
                           JdbcClient jdbc) {
        this.payments = payments;
        this.bookings = bookings;
        this.props = props;
        this.jdbc = jdbc;
    }

    public PayResult payAdvance(long bookingId, long playerId) {
        AdvanceOrder order = payments.startAdvancePayment(bookingId, playerId);
        if (props.razorpayMode()) {
            return new PayResult(PayOutcome.AWAITING_PAYMENT, view(bookingId), checkout(order, playerId), null);
        }
        if (props.upiMode()) {
            return new PayResult(PayOutcome.PAY_BY_UPI, view(bookingId), null, upi(order));
        }
        CaptureResult result = payments.recordCapturedAdvance(
                order.gatewayOrderId(), "sim_pay_" + UUID.randomUUID(), order.amount());
        return new PayResult(PayOutcome.valueOf(result.outcome().name()), view(bookingId), null, null);
    }

    private UpiPayment upi(AdvanceOrder order) {
        record Venue(String upiId, String upiName, String name) {
        }
        Venue v = jdbc.sql("""
                        SELECT v.upi_id, v.upi_name, v.name
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                        WHERE b.id = :bookingId
                        """)
                .param("bookingId", order.bookingId())
                .query(Venue.class)
                .single();
        if (v.upiId() == null) {
            throw new OnlinePaymentUnavailableException();
        }
        return new UpiPayment(v.upiId(), v.upiName() != null ? v.upiName() : v.name(), order.amount(),
                "TurfUp booking " + order.bookingId());
    }

    private RazorpayCheckout checkout(AdvanceOrder order, long playerId) {
        record Info(String venueName, String courtName, String playerName, String playerPhone) {
        }
        Info info = jdbc.sql("""
                        SELECT v.name AS venue_name, c.name AS court_name, u.name AS player_name,
                               u.phone AS player_phone
                        FROM bookings b JOIN courts c ON c.id = b.court_id JOIN venues v ON v.id = c.venue_id
                        JOIN users u ON u.id = :playerId
                        WHERE b.id = :bookingId
                        """)
                .param("bookingId", order.bookingId())
                .param("playerId", playerId)
                .query(Info.class)
                .single();
        return new RazorpayCheckout(props.razorpay().keyId(), order.gatewayOrderId(), order.amount(), "INR",
                "TurfUp", info.venueName() + " · " + info.courtName() + " · advance", info.playerName(),
                info.playerPhone());
    }

    private BookingView view(long bookingId) {
        return BookingView.of(bookings.findById(bookingId).orElseThrow());
    }
}
