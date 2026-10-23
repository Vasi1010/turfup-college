package com.turfup.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Razorpay mode only: reconciles payments every 5 minutes, and refreshes linked account statuses hourly. */
@Component
class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final RazorpayReconciler reconciler;
    private final LinkedAccountService linkedAccounts;

    PaymentReconciliationJob(RazorpayReconciler reconciler, LinkedAccountService linkedAccounts) {
        this.reconciler = reconciler;
        this.linkedAccounts = linkedAccounts;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    void reconcile() {
        RazorpayReconciler.Report report = reconciler.reconcile();
        if (report.capturesRecorded() > 0 || report.refundsSent() > 0 || report.ordersAbandoned() > 0) {
            log.info("Payment reconciliation: {}", report);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    void refreshLinkedAccounts() {
        linkedAccounts.refreshAll();
    }
}
