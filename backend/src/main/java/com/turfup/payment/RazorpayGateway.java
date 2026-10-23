package com.turfup.payment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The Razorpay API calls TurfUp makes. {@link HttpRazorpayGateway} is the real one; tests use a fake. Amounts
 * are paise, as in Razorpay's API. Every method throws {@link RazorpayException} when Razorpay refuses or
 * cannot be reached.
 */
public interface RazorpayGateway {

    /**
     * Route transfer of {@code amount} to the linked account {@code account}, created when the order is paid. With
     * {@code onHoldUntil} its settlement to the owner is held until then (Razorpay settles it the next business day
     * after); null settles on the account's normal schedule.
     */
    record Transfer(String account, long amount, Instant onHoldUntil) {
    }

    /** A payment against an order. {@code status}: created, authorized, captured, refunded, failed. */
    record GatewayPayment(String id, String orderId, String status, long amount) {
    }

    /** {@code status}: pending, processed, failed. {@code notes} carries {@code turfup_refund_id}. */
    record GatewayRefund(String id, String paymentId, long amount, String status, Map<String, String> notes) {
    }

    /** Creates an INR order whose captured amount is transferred on to {@code transfer.account()}. Returns its id. */
    String createOrder(long amount, String receipt, Transfer transfer, Map<String, String> notes);

    List<GatewayPayment> orderPayments(String orderId);

    /**
     * Refunds a captured payment and reverses its Route transfer. Razorpay rejects it (a {@link RazorpayException}
     * with {@code rejected()} true) if, for example, the linked account cannot cover the reversal.
     */
    GatewayRefund refund(String paymentId, long amount, Map<String, String> notes);

    List<GatewayRefund> refunds(String paymentId);

    /**
     * The linked account's status (created, needs_clarification, under_review, activated, suspended, rejected).
     *
     * @throws RazorpayException with {@code notFound()} true if Razorpay has no such account
     */
    String accountStatus(String accountId);
}
