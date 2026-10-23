package com.turfup.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public (no JWT): Razorpay calls it, and the signature check is the authentication. Takes the body as a raw
 * string, since the signature is over the exact bytes Razorpay sent.
 */
@RestController
class PaymentWebhookController {

    private final RazorpayWebhookService webhooks;
    private final PaymentsProperties props;

    PaymentWebhookController(RazorpayWebhookService webhooks, PaymentsProperties props) {
        this.webhooks = webhooks;
        this.props = props;
    }

    @PostMapping("/api/payments/razorpay/webhook")
    ResponseEntity<Map<String, String>> razorpay(@RequestBody String body,
                                                 @RequestHeader(name = "X-Razorpay-Signature", required = false)
                                                 String signature) {
        if (!props.razorpayMode()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("result", webhooks.handle(body, signature).name()));
    }
}
