package com.turfup.payment;

import com.jayway.jsonpath.JsonPath;
import com.turfup.auth.Role;
import com.turfup.booking.Booking;
import com.turfup.booking.BookingService;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.booking.BookingSource;
import com.turfup.booking.BookingStatus;
import com.turfup.booking.CancellationService;
import com.turfup.payment.RazorpayGateway.GatewayRefund;
import com.turfup.venue.VenueCustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Razorpay mode against the fake gateway: orders with Route transfers, webhook confirmation, refunds that
 * reverse the transfer, reconciliation, and the linked-account rules. Hold 5 min, grace 2 min, advance ₹200.
 */
class RazorpayPaymentTest extends RazorpayTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);

    @Autowired
    MockMvc mvc;

    @Autowired
    BookingService bookingService;

    @Autowired
    CheckoutService checkout;

    @Autowired
    PaymentService payments;

    @Autowired
    CancellationService cancellations;

    @Autowired
    RazorpayReconciler reconciler;

    @Autowired
    LinkedAccountService linkedAccounts;

    @Autowired
    VenueCustomerService customers;

    long courtId;
    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        linkAccount(venueOf(courtId), LinkedAccountService.ACTIVATED);
        alice = createPlayer(1);
        bob = createPlayer(2);
    }

    @Test
    void configTellsTheFrontendItIsRazorpayMode() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsMode").value("RAZORPAY"));
    }

    // --- paying ---

    @Test
    void payCreatesAnOrderThatTransfersTheAdvanceToTheOwnersLinkedAccount() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);

        String body = mvc.perform(post("/api/bookings/{id}/pay", held.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.booking.status").value("HELD"))
                .andExpect(jsonPath("$.checkout.keyId").value("rzp_test_turfup"))
                .andExpect(jsonPath("$.checkout.amount").value(20_000))
                .andExpect(jsonPath("$.checkout.currency").value("INR"))
                .andExpect(jsonPath("$.checkout.prefillContact").value("9000000001"))
                .andReturn().getResponse().getContentAsString();

        String orderId = JsonPath.read(body, "$.checkout.orderId");
        FakeRazorpayGateway.Order order = razorpay.orders.get(orderId);
        assertThat(order.amount()).isEqualTo(20_000);
        // Held until the slot ends; see RefundSafetyTest.
        assertThat(order.transfer()).isEqualTo(
                new RazorpayGateway.Transfer(OWNER_ACCOUNT, 20_000, END.toInstant()));
        assertThat(paymentRows(held.getId())).containsExactly("ADVANCE PENDING 20000 RAZORPAY");
        assertThat(statusOf(held.getId())).isEqualTo("HELD");
    }

    @Test
    void razorpayBeingDownLeavesTheHoldPayableLater() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        razorpay.failOrders = true;

        mvc.perform(post("/api/bookings/{id}/pay", held.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RAZORPAY"));

        assertThat(paymentRows(held.getId())).isEmpty();
        razorpay.failOrders = false;
        assertThat(checkout.payAdvance(held.getId(), alice).outcome()).isEqualTo(CheckoutService.PayOutcome.AWAITING_PAYMENT);
    }

    // --- webhook ---

    @Test
    void signedCaptureWebhookConfirmsTheBooking() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        String paymentId = razorpay.capture(orderId, 20_000);

        webhook(capturedEvent(orderId, paymentId, 20_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("HANDLED"));

        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        assertThat(paymentRows(held.getId())).containsExactly("ADVANCE SUCCEEDED 20000 RAZORPAY");
    }

    @Test
    void duplicateWebhookDeliveryConfirmsOnce() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        String paymentId = razorpay.capture(orderId, 20_000);
        String event = capturedEvent(orderId, paymentId, 20_000);

        webhook(event).andExpect(status().isOk());
        webhook(event).andExpect(status().isOk());
        webhook(event).andExpect(status().isOk());

        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        assertThat(paymentRows(held.getId())).containsExactly("ADVANCE SUCCEEDED 20000 RAZORPAY");
        assertThat(razorpay.refunds).isEmpty();
    }

    @Test
    void webhookWithABadSignatureIsRejectedAndChangesNothing() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        String event = capturedEvent(orderId, razorpay.capture(orderId, 20_000), 20_000);

        mvc.perform(post("/api/payments/razorpay/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", sign(event.replace("20000", "1")))
                        .content(event))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(held.getId())).isEqualTo("HELD");
        assertThat(paymentRows(held.getId())).containsExactly("ADVANCE PENDING 20000 RAZORPAY");
    }

    @Test
    void captureForAnOrderTurfUpDidNotCreateIsAcknowledgedAndIgnored() throws Exception {
        webhook(capturedEvent("order_someone_else", "pay_other", 50_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("IGNORED"));
    }

    @Test
    void captureInsideTheGraceWindowConfirms() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        sql("""
                UPDATE bookings SET payment_started_at = now() - interval '3 minutes',
                                    hold_expires_at = now() - interval '1 minute' WHERE id = :id
                """, held.getId());

        webhook(capturedEvent(orderId, razorpay.capture(orderId, 20_000), 20_000)).andExpect(status().isOk());

        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        assertThat(razorpay.refunds).isEmpty();
    }

    @Test
    void captureAfterTheGraceWindowIsRefundedThroughRazorpay() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        sql("""
                UPDATE bookings SET payment_started_at = now() - interval '4 minutes',
                                    hold_expires_at = now() - interval '2 minutes 1 second' WHERE id = :id
                """, held.getId());
        String paymentId = razorpay.capture(orderId, 20_000);

        webhook(capturedEvent(orderId, paymentId, 20_000)).andExpect(status().isOk());

        assertThat(statusOf(held.getId())).isEqualTo("EXPIRED");
        // Sent to Razorpay right after the booking transaction committed.
        assertThat(razorpay.refunds).singleElement().satisfies(r -> {
            assertThat(r.paymentId()).isEqualTo(paymentId);
            assertThat(r.amount()).isEqualTo(20_000);
        });
        assertThat(paymentRows(held.getId()))
                .containsExactly("ADVANCE SUCCEEDED 20000 RAZORPAY", "REFUND PENDING 20000 RAZORPAY");
        assertThat(refundGatewayId(held.getId())).isEqualTo(razorpay.refunds.getFirst().id());

        // The player sees the refund on the booking.
        mvc.perform(get("/api/bookings/{id}", held.getId()).header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.refunded").value(20_000));
    }

    @Test
    void refundWebhooksSettleTheRefund() throws Exception {
        long bookingId = lateCaptureRefunded();
        GatewayRefund processed = razorpay.settle(razorpay.refunds.getFirst().id(), "processed");

        webhook(refundEvent("refund.processed", processed)).andExpect(status().isOk());
        webhook(refundEvent("refund.processed", processed)).andExpect(status().isOk());

        assertThat(paymentRows(bookingId))
                .containsExactly("ADVANCE SUCCEEDED 20000 RAZORPAY", "REFUND SUCCEEDED 20000 RAZORPAY");
    }

    @Test
    void aFailedRefundIsRecordedAsFailedAndNoLongerCounted() throws Exception {
        long bookingId = lateCaptureRefunded();
        GatewayRefund failed = razorpay.settle(razorpay.refunds.getFirst().id(), "failed");

        webhook(refundEvent("refund.failed", failed)).andExpect(status().isOk());

        assertThat(paymentRows(bookingId))
                .containsExactly("ADVANCE SUCCEEDED 20000 RAZORPAY", "REFUND FAILED 20000 RAZORPAY");
    }

    // --- refunds that could not be sent ---

    @Test
    void aRefundRazorpayDidNotReceiveIsSentByReconciliation() throws Exception {
        razorpay.failRefundsBeforeSending = 1;
        long bookingId = lateCaptureRefunded();
        assertThat(razorpay.refunds).isEmpty();
        assertThat(refundGatewayId(bookingId)).isNull();

        ageRefunds();
        reconciler.reconcile();
        reconciler.reconcile();

        assertThat(razorpay.refunds).hasSize(1);
        assertThat(refundGatewayId(bookingId)).isEqualTo(razorpay.refunds.getFirst().id());
    }

    @Test
    void aRefundWhoseAnswerWasLostIsFoundNotRepeated() throws Exception {
        razorpay.loseRefundAnswers = 1;
        long bookingId = lateCaptureRefunded();
        assertThat(razorpay.refunds).hasSize(1); // Razorpay made it, TurfUp never heard back
        assertThat(refundGatewayId(bookingId)).isNull();

        ageRefunds();
        reconciler.reconcile();

        assertThat(razorpay.refunds).hasSize(1);
        assertThat(refundGatewayId(bookingId)).isEqualTo(razorpay.refunds.getFirst().id());
    }

    // --- reconciliation of missed webhooks ---

    @Test
    void reconciliationConfirmsAPaymentWhoseWebhookNeverCame() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        razorpay.capture(orderId, 20_000);
        ageOrders();

        RazorpayReconciler.Report first = reconciler.reconcile();
        RazorpayReconciler.Report second = reconciler.reconcile();

        assertThat(first.capturesRecorded()).isEqualTo(1);
        assertThat(second.ordersChecked()).isZero();
        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        assertThat(paymentRows(held.getId())).containsExactly("ADVANCE SUCCEEDED 20000 RAZORPAY");
    }

    @Test
    void reconciliationRefundsAMissedCaptureWhoseHoldIsGone() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        String paymentId = razorpay.capture(orderId, 20_000);
        sql("""
                UPDATE bookings SET payment_started_at = now() - interval '10 minutes',
                                    hold_expires_at = now() - interval '5 minutes' WHERE id = :id
                """, held.getId());
        ageOrders();

        reconciler.reconcile();

        assertThat(statusOf(held.getId())).isEqualTo("EXPIRED");
        assertThat(razorpay.refunds).singleElement().satisfies(r -> assertThat(r.paymentId()).isEqualTo(paymentId));
    }

    @Test
    void anOrderNobodyPaidIsDroppedAfterAnHourAndALatePaymentIsStillRefunded() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        sql("UPDATE payments SET created_at = now() - interval '61 minutes' WHERE booking_id = :id", held.getId());
        sql("UPDATE bookings SET status = 'EXPIRED' WHERE id = :id", held.getId());

        assertThat(reconciler.reconcile().ordersAbandoned()).isEqualTo(1);
        assertThat(paymentRows(held.getId())).containsExactly("ADVANCE FAILED 20000 RAZORPAY");
        assertThat(reconciler.reconcile().ordersChecked()).isZero();

        String paymentId = razorpay.capture(orderId, 20_000);
        webhook(capturedEvent(orderId, paymentId, 20_000)).andExpect(status().isOk());
        assertThat(razorpay.refunds).singleElement().satisfies(r -> assertThat(r.paymentId()).isEqualTo(paymentId));
    }

    // --- cancellations ---

    @Test
    void playerCancellationRefundGoesBackThroughRazorpay() throws Exception {
        long bookingId = confirmedOnline(alice);

        cancellations.cancelByPlayer(bookingId, alice, 20_000L);

        assertThat(razorpay.refunds).singleElement().satisfies(r -> {
            assertThat(r.amount()).isEqualTo(20_000);
            assertThat(r.notes()).containsKey(RazorpayRefunds.REFUND_ID_NOTE);
        });
        assertThat(paymentRows(bookingId))
                .containsExactly("ADVANCE SUCCEEDED 20000 RAZORPAY", "REFUND PENDING 20000 RAZORPAY");
    }

    @Test
    void ownerCancellationRefundsTheAdvanceThroughRazorpayAndTheBalanceInHand() throws Exception {
        long bookingId = confirmedOnline(alice);
        payments.recordBalanceReceived(ownerOf(courtId), bookingId, PaymentMethod.CASH);

        cancellations.cancelByOwner(ownerOf(courtId), bookingId, com.turfup.booking.CancelReason.RAIN, null);

        assertThat(razorpay.refunds).singleElement().satisfies(r -> assertThat(r.amount()).isEqualTo(20_000));
        assertThat(paymentRows(bookingId)).containsExactly(
                "ADVANCE SUCCEEDED 20000 RAZORPAY", "BALANCE SUCCEEDED 80000 CASH",
                "REFUND PENDING 20000 RAZORPAY", "REFUND SUCCEEDED 80000 CASH");
    }

    // --- linked accounts ---

    @Test
    void approvalIsBlockedUntilTheOwnersLinkedAccountIsActivated() throws Exception {
        long pending = venueOf(createCourt());
        sql("UPDATE venues SET approved = false WHERE id = :id", pending);
        String admin = bearer(createUser("9999999999", "ADMIN"), Role.ADMIN);

        approve(admin, pending)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENUE_APPROVAL_BLOCKED"))
                .andExpect(jsonPath("$.detail").value("Link the owner's Razorpay account before approving this venue."));

        razorpay.accountStatuses.put("acc_SecondOwner0001", "under_review");
        mvc.perform(put("/api/admin/venues/{id}/linked-account", pending)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"acc_SecondOwner0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razorpayAccountStatus").value("under_review"))
                .andExpect(jsonPath("$.onlinePayments").value(false));
        approve(admin, pending)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "The owner's Razorpay account is under review. Approve the venue once Razorpay has activated it."));

        // Razorpay activates it; approving re-checks with Razorpay.
        razorpay.accountStatuses.put("acc_SecondOwner0001", "activated");
        approve(admin, pending)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.onlinePayments").value(true));
    }

    @Test
    void linkingRejectsMalformedAndUnknownAccounts() {
        assertThatThrownBy(() -> linkedAccounts.link(venueOf(courtId), "not-an-account"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> linkedAccounts.link(venueOf(courtId), "acc_Nobody00000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no linked account");
    }

    @Test
    void aSuspendedAccountPausesOnlineAdvancesButNotWaivedRegularsOrWalkIns() throws Exception {
        customers.setAdvanceWaived(ownerOf(courtId), venueOf(courtId), bob, true);
        razorpay.accountStatuses.put(OWNER_ACCOUNT, "suspended");
        linkedAccounts.refreshAll();

        mvc.perform(get("/api/venues/{id}", venueOf(courtId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlinePayments").value(false));
        assertThatThrownBy(() -> bookingService.hold(courtId, alice, START, END))
                .isInstanceOf(OnlinePaymentUnavailableException.class);

        Booking waived = bookingService.hold(courtId, bob, START, END);
        assertThat(waived.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        Booking walkIn = bookingService.enterForOwner(ownerOf(courtId), courtId,
                new OwnerEntry(START.plusHours(1), END.plusHours(1), BookingSource.WALKIN, null, null, null));
        assertThat(walkIn.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        // Reactivated: online advances resume.
        razorpay.accountStatuses.put(OWNER_ACCOUNT, "activated");
        linkedAccounts.refreshAll();
        assertThat(bookingService.hold(courtId, alice, START.plusHours(2), END.plusHours(2)).getStatus())
                .isEqualTo(BookingStatus.HELD);
    }

    @Test
    void aZeroAdvanceTurfStillBooksWhileTheAccountIsSuspended() {
        sql("UPDATE courts SET advance_amount = 0 WHERE id = :id", courtId);
        razorpay.accountStatuses.put(OWNER_ACCOUNT, "suspended");
        linkedAccounts.refreshAll();

        assertThat(bookingService.hold(courtId, alice, START, END).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void anAccountSuspendedAfterTheHoldStopsThePayment() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        razorpay.accountStatuses.put(OWNER_ACCOUNT, "suspended");
        linkedAccounts.refreshAll();

        mvc.perform(post("/api/bookings/{id}/pay", held.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONLINE_PAYMENT_UNAVAILABLE"));
        assertThat(razorpay.orders).isEmpty();
        assertThat(paymentRows(held.getId())).isEmpty();
    }

    @Test
    void aFailedStatusCheckKeepsTheLastKnownStatus() {
        razorpay.accountStatuses.remove(OWNER_ACCOUNT); // Razorpay answers "not found" for now

        linkedAccounts.refreshAll();

        assertThat(linkedAccounts.acceptsOnlineAdvance(venueOf(courtId))).isTrue();
    }

    // --- helpers ---

    /** A booking confirmed by a captured online advance. */
    private long confirmedOnline(long playerId) throws Exception {
        Booking held = bookingService.hold(courtId, playerId, START, END);
        String orderId = checkout.payAdvance(held.getId(), playerId).checkout().orderId();
        webhook(capturedEvent(orderId, razorpay.capture(orderId, 20_000), 20_000)).andExpect(status().isOk());
        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        return held.getId();
    }

    /** A capture after the grace window, refunded. Returns the booking id. */
    private long lateCaptureRefunded() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        sql("""
                UPDATE bookings SET payment_started_at = now() - interval '4 minutes',
                                    hold_expires_at = now() - interval '3 minutes' WHERE id = :id
                """, held.getId());
        webhook(capturedEvent(orderId, razorpay.capture(orderId, 20_000), 20_000)).andExpect(status().isOk());
        return held.getId();
    }

    private ResultActions webhook(String body) throws Exception {
        return mvc.perform(post("/api/payments/razorpay/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", sign(body))
                .content(body));
    }

    private ResultActions approve(String admin, long venue) throws Exception {
        return mvc.perform(put("/api/admin/venues/{id}/approval", venue)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true}"));
    }

    private void ageOrders() {
        jdbc.sql("UPDATE payments SET created_at = now() - interval '3 minutes' WHERE kind = 'ADVANCE'").update();
    }

    private void ageRefunds() {
        jdbc.sql("UPDATE payments SET created_at = now() - interval '2 minutes' WHERE kind = 'REFUND'").update();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId).query(String.class).single();
    }

    private String refundGatewayId(long bookingId) {
        return jdbc.sql("SELECT gateway_refund_id FROM payments WHERE booking_id = :id AND kind = 'REFUND'")
                .param("id", bookingId)
                .query((rs, n) -> rs.getString(1))
                .list()
                .getFirst();
    }

    private List<String> paymentRows(long bookingId) {
        return jdbc.sql("""
                        SELECT kind || ' ' || status || ' ' || amount || ' ' || method FROM payments
                        WHERE booking_id = :id ORDER BY id
                        """)
                .param("id", bookingId)
                .query(String.class)
                .list();
    }

    private void sql(String sql, long id) {
        jdbc.sql(sql).param("id", id).update();
    }
}
