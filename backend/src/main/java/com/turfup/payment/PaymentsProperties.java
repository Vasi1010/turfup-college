package com.turfup.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code turfup.payments.*}. In {@code razorpay} mode the key id, key secret and webhook secret are required:
 * the app refuses to start without them, as it does without a JWT secret.
 */
@ConfigurationProperties("turfup.payments")
public record PaymentsProperties(PaymentMode mode, Razorpay razorpay) {

    /**
     * {@code turfup.payments.razorpay.*}, from RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET.
     * Test-mode keys start with {@code rzp_test_}.
     */
    public record Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl) {

        public Razorpay {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.razorpay.com";
            }
        }
    }

    public PaymentsProperties {
        if (mode == null) {
            mode = PaymentMode.SIMULATED;
        }
        if (razorpay == null) {
            razorpay = new Razorpay(null, null, null, null);
        }
        if (mode == PaymentMode.RAZORPAY) {
            require(razorpay.keyId(), "turfup.payments.razorpay.key-id (RAZORPAY_KEY_ID)");
            require(razorpay.keySecret(), "turfup.payments.razorpay.key-secret (RAZORPAY_KEY_SECRET)");
            require(razorpay.webhookSecret(), "turfup.payments.razorpay.webhook-secret (RAZORPAY_WEBHOOK_SECRET)");
        }
    }

    public boolean razorpayMode() {
        return mode == PaymentMode.RAZORPAY;
    }

    public boolean upiMode() {
        return mode == PaymentMode.UPI;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when turfup.payments.mode is razorpay");
        }
    }
}
