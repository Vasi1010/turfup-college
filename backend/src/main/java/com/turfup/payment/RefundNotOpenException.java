package com.turfup.payment;

/** The refund does not need attention (any more): Razorpay took it in the meantime, or it was already settled. */
public class RefundNotOpenException extends RuntimeException {

    public RefundNotOpenException(long refundId) {
        super("Refund " + refundId + " no longer needs attention. Reload to see its current state.");
    }
}
