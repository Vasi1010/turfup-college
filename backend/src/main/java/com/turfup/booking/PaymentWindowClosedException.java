package com.turfup.booking;

/** Too little of the hold is left to start a payment ({@code min_pay_seconds_remaining}). */
public class PaymentWindowClosedException extends RuntimeException {

    public PaymentWindowClosedException() {
        super("Your hold on this slot is about to expire. Please hold the slot again to pay.");
    }
}
