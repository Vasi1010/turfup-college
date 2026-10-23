package com.turfup.payment;

/** {@code turfup.payments.mode}. */
public enum PaymentMode {
    /** College version and tests: "Pay advance" confirms at once through the same service methods. */
    SIMULATED,
    /** The player pays the venue's UPI ID directly; the owner confirms the money arrived. No gateway, no fees. */
    UPI,
    /** Razorpay Route: the advance goes to the owner's linked account; the signed webhook confirms the booking. */
    RAZORPAY
}
