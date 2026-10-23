package com.turfup.payment;

import com.turfup.auth.Role;
import com.turfup.booking.Booking;
import com.turfup.booking.BookingService;
import com.turfup.booking.CancelReason;
import com.turfup.booking.CancellationService;
import com.turfup.payment.RazorpayGateway.GatewayRefund;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Keeping advances refundable: Route transfers held until the slot ends, and refunds Razorpay rejects or
 * fails surfaced to the admin, retried, and settled by hand when needed.
 */
class RefundSafetyTest extends RazorpayTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);
    private static final String NO_BALANCE = "The linked account does not have sufficient balance for the reversal";

    @Autowired
    MockMvc mvc;

    @Autowired
    BookingService bookingService;

    @Autowired
    CheckoutService checkout;

    @Autowired
    CancellationService cancellations;

    @Autowired
    RazorpayReconciler reconciler;

    long courtId;
    long alice;
    String admin;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        linkAccount(venueOf(courtId), LinkedAccountService.ACTIVATED);
        alice = createPlayer(1);
        admin = bearer(createUser("9999999999", "ADMIN"), Role.ADMIN);
    }

    // --- holding the transfer ---

    @Test
    void theAdvanceTransferIsHeldUntilTheSlotEnds() throws Exception {
        long bookingId = confirmedOnline();

        assertThat(onlyOrder().transfer().onHoldUntil()).isEqualTo(END.toInstant());
        assertThat(holdUntil(bookingId)).isEqualTo(END.toInstant());
    }

    @Test
    void theHoldDoesNotDependOnTheRefundHours() {
        // Even with the refund deadline long past, owner cancellations can still reverse the transfer.
        sql("UPDATE courts SET refund_full_hours_before = 1000000 WHERE id = :id", courtId);
        Booking held = bookingService.hold(courtId, alice, START, END);

        checkout.payAdvance(held.getId(), alice);

        assertThat(onlyOrder().transfer().onHoldUntil()).isEqualTo(END.toInstant());
    }

    @Test
    void aSlotEndingWithinMinutesIsNotHeld() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        sql("UPDATE bookings SET start_at = now(), end_at = now() + interval '4 minutes' WHERE id = :id", held.getId());

        checkout.payAdvance(held.getId(), alice);

        assertThat(onlyOrder().transfer().onHoldUntil()).isNull();
        assertThat(holdUntil(held.getId())).isNull();
    }

    // --- refunds Razorpay rejects ---

    @Test
    void aRejectedReversalIsListedForTheAdminAndRetriedUntilItGoesThrough() throws Exception {
        long bookingId = confirmedOnline();
        razorpay.rejectRefunds = NO_BALANCE;

        cancellations.cancelByOwner(ownerOf(courtId), bookingId, CancelReason.RAIN, null);

        assertThat(razorpay.refunds).isEmpty();
        assertThat(refundRow(bookingId)).isEqualTo("PENDING " + NO_BALANCE);
        issues().andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value(bookingId))
                .andExpect(jsonPath("$[0].amount").value(20_000))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].reason").value("OWNER_CANCELLATION"))
                .andExpect(jsonPath("$[0].error").value(NO_BALANCE))
                .andExpect(jsonPath("$[0].playerPhone").value("9000000001"))
                .andExpect(jsonPath("$[0].gatewayPaymentId").isNotEmpty());

        // Still short: retried, still listed.
        ageRefunds();
        reconciler.reconcile();
        assertThat(razorpay.refunds).isEmpty();
        issues().andExpect(jsonPath("$.length()").value(1));

        // The owner's account has the money again.
        razorpay.rejectRefunds = null;
        reconciler.reconcile();

        assertThat(razorpay.refunds).singleElement().satisfies(r -> assertThat(r.amount()).isEqualTo(20_000));
        assertThat(refundRow(bookingId)).isEqualTo("PENDING null");
        issues().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theAdminCanSettleARejectedRefundByHandWhichStopsTheRetries() throws Exception {
        long bookingId = confirmedOnline();
        razorpay.rejectRefunds = NO_BALANCE;
        cancellations.cancelByOwner(ownerOf(courtId), bookingId, CancelReason.RAIN, null);
        long refundId = refundId(bookingId);

        settle(refundId).andExpect(status().isNoContent());

        assertThat(refundRow(bookingId)).isEqualTo("SUCCEEDED null");
        assertThat(jdbc.sql("SELECT settled_manually_at IS NOT NULL FROM payments WHERE id = :id")
                .param("id", refundId).query(Boolean.class).single()).isTrue();
        issues().andExpect(jsonPath("$.length()").value(0));

        // Razorpay would take it now, but it must not be refunded twice.
        razorpay.rejectRefunds = null;
        ageRefunds();
        reconciler.reconcile();
        assertThat(razorpay.refunds).isEmpty();

        settle(refundId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_OPEN"));
    }

    @Test
    void aFailedRefundIsListedUntilTheAdminSettlesIt() throws Exception {
        long bookingId = confirmedOnline();
        cancellations.cancelByPlayer(bookingId, alice, 20_000L);
        GatewayRefund failed = razorpay.settle(razorpay.refunds.getFirst().id(), "failed");
        webhook(refundEvent("refund.failed", failed)).andExpect(status().isOk());

        issues().andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].reason").value("PLAYER_CANCELLATION"))
                .andExpect(jsonPath("$[0].gatewayRefundId").value(failed.id()));

        settle(refundId(bookingId)).andExpect(status().isNoContent());

        assertThat(refundRow(bookingId)).isEqualTo("SUCCEEDED null");
        issues().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aRefundGoingThroughNormallyIsNotListedAndCannotBeSettledByHand() throws Exception {
        long bookingId = confirmedOnline();
        cancellations.cancelByPlayer(bookingId, alice, 20_000L);

        issues().andExpect(jsonPath("$.length()").value(0));
        settle(refundId(bookingId)).andExpect(status().isConflict());
        assertThat(refundRow(bookingId)).isEqualTo("PENDING null");
    }

    @Test
    void onlyAdminsSeeRefundIssues() throws Exception {
        mvc.perform(get("/api/admin/refund-issues").header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/refund-issues/{id}/settled-manually", 1)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerOf(courtId), Role.OWNER)))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private long confirmedOnline() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String orderId = checkout.payAdvance(held.getId(), alice).checkout().orderId();
        webhook(capturedEvent(orderId, razorpay.capture(orderId, 20_000), 20_000)).andExpect(status().isOk());
        return held.getId();
    }

    private FakeRazorpayGateway.Order onlyOrder() {
        assertThat(razorpay.orders).hasSize(1);
        return razorpay.orders.values().iterator().next();
    }

    private Instant holdUntil(long bookingId) {
        OffsetDateTime at = jdbc.sql("SELECT transfer_hold_until FROM payments WHERE booking_id = :id AND kind = 'ADVANCE'")
                .param("id", bookingId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                .list()
                .getFirst();
        return at == null ? null : at.toInstant();
    }

    private long refundId(long bookingId) {
        return jdbc.sql("SELECT id FROM payments WHERE booking_id = :id AND kind = 'REFUND' AND method = 'RAZORPAY'")
                .param("id", bookingId).query(Long.class).single();
    }

    /** Status and gateway_error of the booking's Razorpay refund. */
    private String refundRow(long bookingId) {
        return jdbc.sql("""
                        SELECT status || ' ' || coalesce(gateway_error, 'null') FROM payments
                        WHERE booking_id = :id AND kind = 'REFUND' AND method = 'RAZORPAY'
                        """)
                .param("id", bookingId).query(String.class).single();
    }

    private ResultActions issues() throws Exception {
        return mvc.perform(get("/api/admin/refund-issues").header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions settle(long refundId) throws Exception {
        return mvc.perform(post("/api/admin/refund-issues/{id}/settled-manually", refundId)
                .header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions webhook(String body) throws Exception {
        return mvc.perform(post("/api/payments/razorpay/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", sign(body))
                .content(body));
    }

    private void ageRefunds() {
        jdbc.sql("UPDATE payments SET created_at = now() - interval '2 minutes' WHERE kind = 'REFUND'").update();
    }

    private void sql(String sql, long id) {
        jdbc.sql(sql).param("id", id).update();
    }
}
