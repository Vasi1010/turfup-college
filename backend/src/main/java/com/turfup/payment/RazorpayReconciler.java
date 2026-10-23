package com.turfup.payment;

import com.turfup.payment.PaymentService.CaptureResult;
import com.turfup.payment.RazorpayGateway.GatewayPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Catches up with Razorpay when a webhook was missed or a call failed (reliability rule 3). Run by
 * {@link PaymentReconciliationJob} every 5 minutes in razorpay mode:
 * <ol>
 *   <li>Open orders: any payment Razorpay captured is fed to {@link PaymentService#recordCapturedAdvance}, which
 *       confirms the booking or refunds it by the usual rules (and ignores ones the webhook already recorded).
 *       An order with nothing captured an hour after it was created is marked FAILED and no longer polled; a
 *       payment that still arrives later is refunded when its webhook comes in.</li>
 *   <li>Refunds never sent (Razorpay was down when they were recorded) are sent.</li>
 *   <li>Refunds still pending at Razorpay after 15 minutes are checked.</li>
 * </ol>
 * Each item is handled on its own, so one failure never stops the rest.
 */
@Service
public class RazorpayReconciler {

    public record Report(int ordersChecked, int capturesRecorded, int ordersAbandoned, int refundsSent,
                         int refundsChecked) {
    }

    private static final Logger log = LoggerFactory.getLogger(RazorpayReconciler.class);

    private final JdbcClient jdbc;
    private final RazorpayGateway gateway;
    private final PaymentService payments;
    private final RazorpayRefunds refunds;
    private final PaymentsProperties props;

    public RazorpayReconciler(JdbcClient jdbc, RazorpayGateway gateway, PaymentService payments,
                              RazorpayRefunds refunds, PaymentsProperties props) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.payments = payments;
        this.refunds = refunds;
        this.props = props;
    }

    public Report reconcile() {
        if (!props.razorpayMode()) {
            return new Report(0, 0, 0, 0, 0);
        }
        int checked = 0;
        int captured = 0;
        int abandoned = 0;
        record Order(long id, String gatewayOrderId, boolean stale) {
        }
        // Orders younger than 2 minutes are left to the webhook.
        List<Order> open = jdbc.sql("""
                        SELECT id, gateway_order_id, created_at < now() - interval '1 hour' AS stale
                        FROM payments
                        WHERE method = 'RAZORPAY' AND kind = 'ADVANCE' AND status = 'PENDING'
                          AND created_at < now() - interval '2 minutes'
                        ORDER BY id
                        """)
                .query(Order.class)
                .list();
        for (Order order : open) {
            try {
                checked++;
                List<GatewayPayment> paid = gateway.orderPayments(order.gatewayOrderId()).stream()
                        .filter(p -> p.status().equals("captured"))
                        .toList();
                for (GatewayPayment p : paid) {
                    CaptureResult result = payments.recordCapturedAdvance(order.gatewayOrderId(), p.id(), p.amount());
                    if (result.outcome() != PaymentService.CaptureOutcome.DUPLICATE) {
                        captured++;
                        log.info("Reconciled missed capture {} for order {}: {}", p.id(), order.gatewayOrderId(),
                                result.outcome());
                    }
                }
                if (paid.isEmpty() && order.stale()) {
                    abandoned += jdbc.sql("UPDATE payments SET status = 'FAILED' WHERE id = :id AND status = 'PENDING'")
                            .param("id", order.id())
                            .update();
                }
            } catch (RuntimeException e) {
                log.warn("Could not reconcile order {}: {}", order.gatewayOrderId(), e.getMessage());
            }
        }

        List<Long> unsent = jdbc.sql("""
                        SELECT id FROM payments
                        WHERE method = 'RAZORPAY' AND kind = 'REFUND' AND status = 'PENDING'
                          AND gateway_refund_id IS NULL AND created_at < now() - interval '1 minute'
                        ORDER BY id
                        """)
                .query(Long.class)
                .list();
        int sent = 0;
        for (long id : unsent) {
            try {
                refunds.send(id);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not send refund {}: {}", id, e.getMessage());
            }
        }

        List<Long> waiting = jdbc.sql("""
                        SELECT id FROM payments
                        WHERE method = 'RAZORPAY' AND kind = 'REFUND' AND status = 'PENDING'
                          AND gateway_refund_id IS NOT NULL AND created_at < now() - interval '15 minutes'
                        ORDER BY id
                        """)
                .query(Long.class)
                .list();
        int synced = 0;
        for (long id : waiting) {
            try {
                refunds.sync(id);
                synced++;
            } catch (RuntimeException e) {
                log.warn("Could not check refund {}: {}", id, e.getMessage());
            }
        }
        return new Report(checked, captured, abandoned, sent, synced);
    }
}
