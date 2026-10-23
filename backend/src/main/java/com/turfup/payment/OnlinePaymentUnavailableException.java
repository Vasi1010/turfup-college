package com.turfup.payment;

/**
 * The venue cannot take online advances right now (its Razorpay linked account is not active). Waived regulars,
 * zero-advance courts and owner entries are not affected.
 */
public class OnlinePaymentUnavailableException extends RuntimeException {

    public OnlinePaymentUnavailableException() {
        super("Online booking is temporarily unavailable at this venue. Please contact the venue directly.");
    }
}
