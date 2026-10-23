package com.turfup.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a real deployment ({@code prod}) with simulated payments: there "Pay advance" would confirm a
 * booking with no money paid. Production uses {@code upi} or {@code razorpay}.
 */
@Component
@Profile("prod")
@EnableConfigurationProperties(PaymentsProperties.class)
class ProdPaymentsGuard {

    ProdPaymentsGuard(PaymentsProperties payments) {
        if (payments.mode() == PaymentMode.SIMULATED) {
            throw new IllegalStateException(
                    "Simulated payments must not run in production. Set TURFUP_PAYMENTS_MODE to upi or razorpay.");
        }
    }
}
