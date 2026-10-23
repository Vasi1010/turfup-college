package com.turfup.payment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory Razorpay for tests: remembers orders (with their Route transfer), the payments a test says
 * were made against them, refunds, and linked account statuses. Failures can be switched on per call type.
 */
public class FakeRazorpayGateway implements RazorpayGateway {

    public record Order(String id, long amount, String receipt, Transfer transfer) {
    }

    public final Map<String, Order> orders = new HashMap<>();
    public final Map<String, List<GatewayPayment>> paymentsByOrder = new HashMap<>();
    public final List<GatewayRefund> refunds = new ArrayList<>();
    public final Map<String, String> accountStatuses = new HashMap<>();

    /** Fail the next N refund calls before reaching Razorpay. */
    public int failRefundsBeforeSending;
    /** Razorpay rejects refunds with this description while set (e.g. the linked account cannot cover the reversal). */
    public String rejectRefunds;
    /** Make the next N refund calls reach Razorpay (the refund is made) but lose the answer. */
    public int loseRefundAnswers;
    public boolean failOrders;

    private final AtomicInteger ids = new AtomicInteger();

    public synchronized void reset() {
        orders.clear();
        paymentsByOrder.clear();
        refunds.clear();
        accountStatuses.clear();
        failRefundsBeforeSending = 0;
        loseRefundAnswers = 0;
        rejectRefunds = null;
        failOrders = false;
    }

    /** The player paid {@code order} and Razorpay captured it. Returns the payment id. */
    public synchronized String capture(String orderId, long amount) {
        String id = "pay_fake" + ids.incrementAndGet();
        paymentsByOrder.computeIfAbsent(orderId, k -> new ArrayList<>())
                .add(new GatewayPayment(id, orderId, "captured", amount));
        return id;
    }

    /** Razorpay finished a refund. */
    public synchronized GatewayRefund settle(String refundId, String status) {
        for (int i = 0; i < refunds.size(); i++) {
            GatewayRefund r = refunds.get(i);
            if (r.id().equals(refundId)) {
                GatewayRefund settled = new GatewayRefund(r.id(), r.paymentId(), r.amount(), status, r.notes());
                refunds.set(i, settled);
                return settled;
            }
        }
        throw new IllegalArgumentException(refundId);
    }

    @Override
    public synchronized String createOrder(long amount, String receipt, Transfer transfer, Map<String, String> notes) {
        if (failOrders) {
            throw new RazorpayException("Payments are not responding right now. Please try again.");
        }
        String id = "order_fake" + ids.incrementAndGet();
        orders.put(id, new Order(id, amount, receipt, transfer));
        return id;
    }

    @Override
    public synchronized List<GatewayPayment> orderPayments(String orderId) {
        return List.copyOf(paymentsByOrder.getOrDefault(orderId, List.of()));
    }

    @Override
    public synchronized GatewayRefund refund(String paymentId, long amount, Map<String, String> notes) {
        if (failRefundsBeforeSending > 0) {
            failRefundsBeforeSending--;
            throw new RazorpayException("Payments are not responding right now. Please try again.");
        }
        if (rejectRefunds != null) {
            throw new RazorpayException("Razorpay could not refund.", RazorpayException.Failure.REJECTED, rejectRefunds,
                    null);
        }
        long alreadyRefunded = refunds.stream().filter(r -> r.paymentId().equals(paymentId))
                .mapToLong(GatewayRefund::amount).sum();
        long captured = paymentsByOrder.values().stream().flatMap(List::stream)
                .filter(p -> p.id().equals(paymentId)).mapToLong(GatewayPayment::amount).findFirst()
                .orElse(amount);
        if (alreadyRefunded + amount > captured) {
            throw new RazorpayException("Razorpay could not refund.", RazorpayException.Failure.REJECTED,
                    "The total refund amount is greater than the refund payment amount", null);
        }
        GatewayRefund refund = new GatewayRefund("rfnd_fake" + ids.incrementAndGet(), paymentId, amount, "pending",
                Map.copyOf(notes));
        refunds.add(refund);
        if (loseRefundAnswers > 0) {
            loseRefundAnswers--;
            throw new RazorpayException("Payments are not responding right now. Please try again.");
        }
        return refund;
    }

    @Override
    public synchronized List<GatewayRefund> refunds(String paymentId) {
        return refunds.stream().filter(r -> r.paymentId().equals(paymentId)).toList();
    }

    @Override
    public synchronized String accountStatus(String accountId) {
        String status = accountStatuses.get(accountId);
        if (status == null) {
            throw new RazorpayException("Razorpay could not fetch linked account.", RazorpayException.Failure.NOT_FOUND,
                    null, null);
        }
        return status;
    }
}
