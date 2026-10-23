package com.turfup.payment;

/** A payment event refers to an order this system never created. */
public class UnknownPaymentOrderException extends RuntimeException {

    public UnknownPaymentOrderException(String gatewayOrderId) {
        super("Unknown payment order " + gatewayOrderId);
    }
}
