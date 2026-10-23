package com.turfup.payment;

import com.turfup.payment.PaymentService.RazorpayRefundRecorded;
import com.turfup.payment.RazorpayGateway.GatewayRefund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Sends recorded RAZORPAY refunds to Razorpay and tracks them until Razorpay settles them.
 * <p>
 * A refund is recorded PENDING inside the booking transaction; once that commits it is sent here. Sending
 * locks the refund row (so the after-commit send and the reconciliation job never send it twice at once), first
 * looks for a refund Razorpay already made for it (tagged {@code turfup_refund_id} in its notes, in case an
 * earlier attempt reached Razorpay but its answer was lost), and only then asks for a new one. Every refund
 * reverses the Route transfer, so the money comes back out of the owner's linked account.
 */
@Component
public class RazorpayRefunds {

    static final String REFUND_ID_NOTE = "turfup_refund_id";

    private static final Logger log = LoggerFactory.getLogger(RazorpayRefunds.class);

    private final JdbcClient jdbc;
    private final RazorpayGateway gateway;
    private final TransactionTemplate newTx;

    public RazorpayRefunds(JdbcClient jdbc, RazorpayGateway gateway, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRefundRecorded(RazorpayRefundRecorded event) {
        try {
            send(event.refundId());
        } catch (RuntimeException e) {
            // The reconciliation job retries refunds that were never sent.
            log.warn("Refund {} not sent to Razorpay yet: {}", event.refundId(), e.getMessage());
        }
    }

    /** Sends one PENDING refund that Razorpay does not know about yet. A no-op if it was already sent. */
    public void send(long refundId) {
        newTx.executeWithoutResult(status -> {
            record Row(long amount, String gatewayRefundId, String paymentId, String gatewayError) {
            }
            Optional<Row> row = jdbc.sql("""
                            SELECT r.amount, r.gateway_refund_id, p.gateway_payment_id AS payment_id, r.gateway_error
                            FROM payments r JOIN payments p ON p.id = r.refund_of
                            WHERE r.id = :id AND r.kind = 'REFUND' AND r.method = 'RAZORPAY' AND r.status = 'PENDING'
                            FOR UPDATE OF r SKIP LOCKED
                            """)
                    .param("id", refundId)
                    .query(Row.class)
                    .optional();
            if (row.isEmpty() || row.get().gatewayRefundId() != null) {
                return;
            }
            String paymentId = row.get().paymentId();
            GatewayRefund refund;
            try {
                refund = findSent(paymentId, refundId)
                        .orElseGet(() -> gateway.refund(paymentId, row.get().amount(),
                                Map.of(REFUND_ID_NOTE, String.valueOf(refundId))));
            } catch (RazorpayException e) {
                if (e.rejected()) {
                    recordRejection(refundId, e.detail(), row.get().gatewayError() == null);
                    return;
                }
                throw e;
            }
            store(refundId, refund);
            log.info("Refund {} sent to Razorpay as {} ({})", refundId, refund.id(), refund.status());
        });
    }

    /**
     * Razorpay refused the refund (usually: the owner's linked account cannot cover the reversal, e.g. a transfer
     * that already settled after an owner cancellation). It stays PENDING, so reconciliation keeps retrying, and
     * is listed for the admin. Logged at ERROR the first time.
     */
    private void recordRejection(long refundId, String detail, boolean first) {
        jdbc.sql("""
                        UPDATE payments SET gateway_error = :detail, gateway_error_at = coalesce(gateway_error_at, now())
                        WHERE id = :id
                        """)
                .param("detail", detail)
                .param("id", refundId)
                .update();
        if (first) {
            log.error("Razorpay rejected refund {}: {}. Retrying; listed for the admin", refundId, detail);
        } else {
            log.warn("Razorpay rejected refund {} again: {}", refundId, detail);
        }
    }

    /**
     * An admin refunded the player from the Razorpay dashboard. Allowed only for refunds needing attention
     * (FAILED, or PENDING after Razorpay rejected it); a PENDING one then stops being retried.
     *
     * @return false if the refund does not need attention (any more): Razorpay took it in the meantime, or it was
     * already settled
     */
    public boolean markSettledManually(long refundId) {
        Boolean done = newTx.execute(status -> jdbc.sql("""
                        UPDATE payments SET status = 'SUCCEEDED', settled_manually_at = now(),
                                            gateway_error = NULL, gateway_error_at = NULL
                        WHERE id = :id AND kind = 'REFUND' AND method = 'RAZORPAY'
                          AND (status = 'FAILED' OR (status = 'PENDING' AND gateway_error IS NOT NULL))
                        """)
                .param("id", refundId)
                .update() > 0);
        if (Boolean.TRUE.equals(done)) {
            log.info("Refund {} marked as settled manually from the Razorpay dashboard", refundId);
        }
        return Boolean.TRUE.equals(done);
    }

    /** Asks Razorpay how a sent refund is doing (for when its webhook did not arrive). */
    public void sync(long refundId) {
        newTx.executeWithoutResult(status -> {
            record Row(String gatewayRefundId, String paymentId) {
            }
            Optional<Row> row = jdbc.sql("""
                            SELECT r.gateway_refund_id, p.gateway_payment_id AS payment_id
                            FROM payments r JOIN payments p ON p.id = r.refund_of
                            WHERE r.id = :id AND r.status = 'PENDING' AND r.gateway_refund_id IS NOT NULL
                            FOR UPDATE OF r SKIP LOCKED
                            """)
                    .param("id", refundId)
                    .query(Row.class)
                    .optional();
            row.flatMap(r -> gateway.refunds(r.paymentId()).stream()
                            .filter(g -> g.id().equals(r.gatewayRefundId()))
                            .findFirst())
                    .ifPresent(g -> store(refundId, g));
        });
    }

    /**
     * Refund webhook ({@code refund.processed}, {@code refund.failed}, {@code refund.created}). Matches the row by
     * Razorpay's refund id, or by the {@code turfup_refund_id} note if the webhook beat the send's answer.
     */
    public void onGatewayRefund(GatewayRefund refund) {
        newTx.executeWithoutResult(status -> {
            Long refundId = jdbc.sql("SELECT id FROM payments WHERE gateway_refund_id = :id FOR UPDATE")
                    .param("id", refund.id())
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (refundId == null) {
                String note = refund.notes().get(REFUND_ID_NOTE);
                if (note == null || !note.matches("\\d+")) {
                    log.info("Ignoring refund {}: not made by TurfUp", refund.id());
                    return;
                }
                refundId = jdbc.sql("SELECT id FROM payments WHERE id = :id AND kind = 'REFUND' FOR UPDATE")
                        .param("id", Long.parseLong(note))
                        .query(Long.class)
                        .optional()
                        .orElse(null);
                if (refundId == null) {
                    log.warn("Ignoring refund {}: its TurfUp refund {} does not exist", refund.id(), note);
                    return;
                }
            }
            store(refundId, refund);
        });
    }

    private Optional<GatewayRefund> findSent(String paymentId, long refundId) {
        String tag = String.valueOf(refundId);
        return gateway.refunds(paymentId).stream()
                .filter(r -> tag.equals(r.notes().get(REFUND_ID_NOTE)))
                .findFirst();
    }

    /** Records Razorpay's refund id and maps its status. A settled refund (SUCCEEDED or FAILED) never changes again. */
    private void store(long refundId, GatewayRefund refund) {
        String status = switch (refund.status()) {
            case "processed" -> "SUCCEEDED";
            case "failed" -> "FAILED";
            default -> "PENDING";
        };
        int updated = jdbc.sql("""
                        UPDATE payments SET gateway_refund_id = :gatewayId, status = :status,
                                            gateway_error = NULL, gateway_error_at = NULL
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("gatewayId", refund.id())
                .param("status", status)
                .param("id", refundId)
                .update();
        if (updated > 0 && status.equals("FAILED")) {
            // Money the player is owed did not go back (e.g. the owner's linked account could not cover the
            // reversal). Needs a person: settle it from the Razorpay dashboard. Listed for the admin.
            log.error("Razorpay refund {} for TurfUp refund {} FAILED; settle it manually", refund.id(), refundId);
        }
    }
}
