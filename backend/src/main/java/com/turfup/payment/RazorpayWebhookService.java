package com.turfup.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay webhooks: the only way a Razorpay payment confirms a booking (reliability rule 2). The signature is
 * an HMAC-SHA256 of the raw body with the webhook secret; anything unsigned or wrongly signed is rejected
 * before it is parsed. Handling is idempotent: Razorpay retries deliveries, and a payment id already recorded
 * is a no-op.
 */
@Service
public class RazorpayWebhookService {

    public enum Result {
        HANDLED,
        IGNORED
    }

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);

    private final PaymentsProperties props;
    private final PaymentService payments;
    private final RazorpayRefunds refunds;
    private final ObjectMapper json;

    public RazorpayWebhookService(PaymentsProperties props, PaymentService payments, RazorpayRefunds refunds,
                                  ObjectMapper json) {
        this.props = props;
        this.payments = payments;
        this.refunds = refunds;
        this.json = json;
    }

    /** @throws InvalidWebhookSignatureException if the signature does not match the body */
    public Result handle(String body, String signature) {
        verify(body, signature);
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Webhook body is not JSON");
        }
        String type = event.path("event").asText();
        JsonNode payload = event.path("payload");
        switch (type) {
            case "payment.captured" -> {
                JsonNode p = payload.path("payment").path("entity");
                String orderId = p.path("order_id").asText(null);
                if (orderId == null) {
                    return Result.IGNORED;
                }
                try {
                    var result = payments.recordCapturedAdvance(orderId, p.path("id").asText(), p.path("amount").asLong());
                    log.info("Webhook payment.captured {} for order {}: {}", p.path("id").asText(), orderId,
                            result.outcome());
                    return Result.HANDLED;
                } catch (UnknownPaymentOrderException e) {
                    // Another integration on the same Razorpay account; not ours to handle.
                    log.info("Ignoring captured payment for unknown order {}", orderId);
                    return Result.IGNORED;
                }
            }
            case "refund.created", "refund.processed", "refund.failed" -> {
                refunds.onGatewayRefund(HttpRazorpayGateway.toRefund(payload.path("refund").path("entity")));
                return Result.HANDLED;
            }
            default -> {
                return Result.IGNORED;
            }
        }
    }

    private void verify(String body, String signature) {
        if (signature == null || signature.isBlank()) {
            throw new InvalidWebhookSignatureException();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.razorpay().webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, signature.strip().getBytes(StandardCharsets.US_ASCII))) {
                throw new InvalidWebhookSignatureException();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
