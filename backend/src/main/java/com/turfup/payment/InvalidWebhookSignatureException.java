package com.turfup.payment;

/** A webhook whose X-Razorpay-Signature does not match its body: not from Razorpay, or tampered with. */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
