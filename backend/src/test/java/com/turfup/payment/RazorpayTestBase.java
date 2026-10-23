package com.turfup.payment;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * The app in razorpay mode, talking to {@link FakeRazorpayGateway} instead of Razorpay. A separate Spring
 * context (and Postgres container) from the simulated-mode tests.
 */
@TestPropertySource(properties = {
        "turfup.payments.mode=razorpay",
        "turfup.payments.razorpay.key-id=rzp_test_turfup",
        "turfup.payments.razorpay.key-secret=test-key-secret",
        "turfup.payments.razorpay.webhook-secret=" + RazorpayTestBase.WEBHOOK_SECRET})
@Import(RazorpayTestBase.FakeGatewayConfig.class)
public abstract class RazorpayTestBase extends PostgresTestBase {

    static final String WEBHOOK_SECRET = "test-webhook-secret";
    static final String OWNER_ACCOUNT = "acc_TestOwner00001";

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGatewayConfig {

        @Bean
        @Primary
        FakeRazorpayGateway fakeRazorpayGateway() {
            return new FakeRazorpayGateway();
        }
    }

    @Autowired
    protected FakeRazorpayGateway razorpay;

    @BeforeEach
    void resetRazorpay() {
        razorpay.reset();
        razorpay.accountStatuses.put(OWNER_ACCOUNT, LinkedAccountService.ACTIVATED);
    }

    /** Links the venue to {@link #OWNER_ACCOUNT} with the given status, as if an admin had done it. */
    protected void linkAccount(long venueId, String status) {
        razorpay.accountStatuses.put(OWNER_ACCOUNT, status);
        jdbc.sql("""
                        UPDATE venues SET razorpay_account_id = :account, razorpay_account_status = :status,
                                         razorpay_account_checked_at = now()
                        WHERE id = :id
                        """)
                .param("account", OWNER_ACCOUNT)
                .param("status", status)
                .param("id", venueId)
                .update();
    }

    /** X-Razorpay-Signature for a body: hex HMAC-SHA256 with the webhook secret. */
    protected static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String capturedEvent(String orderId, String paymentId, long amount) {
        return """
                {"entity":"event","event":"payment.captured","contains":["payment"],
                 "payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%d,"currency":"INR",
                 "status":"captured","order_id":"%s","method":"upi","notes":[]}}},"created_at":1900000000}
                """.formatted(paymentId, amount, orderId);
    }

    protected static String refundEvent(String event, RazorpayGateway.GatewayRefund refund) {
        StringBuilder notes = new StringBuilder("{");
        refund.notes().forEach((k, v) -> notes.append(notes.length() > 1 ? "," : "").append('"').append(k)
                .append("\":\"").append(v).append('"'));
        notes.append('}');
        return """
                {"entity":"event","event":"%s","contains":["refund","payment"],
                 "payload":{"refund":{"entity":{"id":"%s","entity":"refund","amount":%d,"currency":"INR",
                 "payment_id":"%s","notes":%s,"status":"%s"}}},"created_at":1900000000}
                """.formatted(event, refund.id(), refund.amount(), refund.paymentId(), notes, refund.status());
    }
}
