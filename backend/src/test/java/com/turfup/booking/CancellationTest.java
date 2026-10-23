package com.turfup.booking;

import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.booking.CancellationService.CancellationResult;
import com.turfup.booking.CancellationService.CancellationTerms;
import com.turfup.booking.CancellationService.RefundOutcome;
import com.turfup.payment.CheckoutService;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.venue.VenueCustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cancellation refunds. Turf defaults: full advance (₹200) back if cancelled 24 h ahead, once per
 * player per turf per IST calendar month. Bookings are in 2030, so they are "early" unless a test raises
 * refund_full_hours_before past that.
 */
class CancellationTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    /** More hours than there are until 2030, so every test booking counts as a late cancellation. */
    private static final int ALWAYS_LATE = 100_000;

    @Autowired
    BookingService bookingService;

    @Autowired
    CheckoutService checkout;

    @Autowired
    CancellationService cancellations;

    @Autowired
    PaymentService payments;

    @Autowired
    VenueCustomerService customers;

    @Autowired
    MockMvc mvc;

    long courtId;
    long owner;
    long alice;
    String month;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        owner = ownerOf(courtId);
        alice = createPlayer(1);
        month = jdbc.sql("SELECT to_char(now() AT TIME ZONE 'Asia/Kolkata', 'FMMonth')").query(String.class).single();
    }

    // --- player cancellation ---

    @Test
    void earlyFirstCancellationOfTheMonthRefundsTheAdvance() {
        long id = paidBooking(courtId, alice, 0);

        CancellationTerms quote = cancellations.quote(id, alice);
        CancellationResult result = cancellations.cancelByPlayer(id, alice, quote.refundAmount());

        assertThat(quote.outcome()).isEqualTo(RefundOutcome.REFUND);
        assertThat(quote.message()).isEqualTo("₹200 back, uses your 1 refund for " + month);
        assertThat(quote.fullRefundDeadline().toInstant()).isEqualTo(START.minusHours(24).toInstant());
        assertThat(result.terms()).isEqualTo(quote);
        assertThat(result.booking().status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(refunds(id)).containsExactly("20000 SIMULATED SUCCEEDED PLAYER_CANCELLATION");
    }

    @Test
    void secondCancellationInTheSameMonthGetsNoRefund() {
        long first = paidBooking(courtId, alice, 0);
        long second = paidBooking(courtId, alice, 1);
        cancellations.cancelByPlayer(first, alice, null);

        CancellationTerms quote = cancellations.quote(second, alice);
        CancellationResult result = cancellations.cancelByPlayer(second, alice, 0L);

        assertThat(quote.outcome()).isEqualTo(RefundOutcome.NO_REFUND_LIMIT_REACHED);
        assertThat(quote.message()).isEqualTo("No refund: already used this month");
        assertThat(quote.refundsUsedThisMonth()).isEqualTo(1);
        assertThat(result.booking().status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(refunds(second)).isEmpty();
    }

    @Test
    void lateCancellationGetsNoRefundAndDoesNotUseTheMonthlyRefund() {
        long late = paidBooking(courtId, alice, 0);
        long later = paidBooking(courtId, alice, 1);
        setRefundHours(ALWAYS_LATE);

        CancellationResult lateResult = cancellations.cancelByPlayer(late, alice, null);
        setRefundHours(24);
        CancellationResult earlyResult = cancellations.cancelByPlayer(later, alice, null);

        assertThat(lateResult.terms().outcome()).isEqualTo(RefundOutcome.NO_REFUND_TOO_LATE);
        assertThat(lateResult.terms().message()).isEqualTo("No refund: cancelling less than 100000 hours before the slot");
        assertThat(refunds(late)).isEmpty();
        assertThat(earlyResult.terms().outcome()).isEqualTo(RefundOutcome.REFUND);
        assertThat(refunds(later)).hasSize(1);
    }

    @Test
    void lateWinsOverLimitInTheMessage() {
        long first = paidBooking(courtId, alice, 0);
        long second = paidBooking(courtId, alice, 1);
        cancellations.cancelByPlayer(first, alice, null);
        setRefundHours(ALWAYS_LATE);

        assertThat(cancellations.quote(second, alice).outcome()).isEqualTo(RefundOutcome.NO_REFUND_TOO_LATE);
    }

    @Test
    void allowanceIsPerTurf() {
        long otherTurf = createCourt();
        addStandardPriceRules(otherTurf);
        long here = paidBooking(courtId, alice, 0);
        long there = paidBooking(otherTurf, alice, 1);
        cancellations.cancelByPlayer(here, alice, null);

        assertThat(cancellations.cancelByPlayer(there, alice, null).terms().outcome()).isEqualTo(RefundOutcome.REFUND);
    }

    @Test
    void allowanceIsPerPlayer() {
        long bob = createPlayer(2);
        long alices = paidBooking(courtId, alice, 0);
        long bobs = paidBooking(courtId, bob, 1);
        cancellations.cancelByPlayer(alices, alice, null);

        assertThat(cancellations.cancelByPlayer(bobs, bob, null).terms().outcome()).isEqualTo(RefundOutcome.REFUND);
    }

    @Test
    void turfWithTwoRefundsPerMonth() {
        jdbc.sql("UPDATE venues SET refunds_per_month = 2 WHERE id = (SELECT venue_id FROM courts WHERE id = :id)").param("id", courtId).update();
        long b1 = paidBooking(courtId, alice, 0);
        long b2 = paidBooking(courtId, alice, 1);
        long b3 = paidBooking(courtId, alice, 2);

        CancellationTerms q1 = cancellations.cancelByPlayer(b1, alice, null).terms();
        CancellationTerms q2 = cancellations.cancelByPlayer(b2, alice, null).terms();
        CancellationTerms q3 = cancellations.cancelByPlayer(b3, alice, null).terms();

        assertThat(q1.message()).isEqualTo("₹200 back, uses 1 of your 2 refunds for " + month);
        assertThat(q2.outcome()).isEqualTo(RefundOutcome.REFUND);
        assertThat(q3.outcome()).isEqualTo(RefundOutcome.NO_REFUND_LIMIT_REACHED);
        assertThat(q3.message()).isEqualTo("No refund: all 2 refunds for " + month + " already used");
    }

    @Test
    void turfWithNoRefunds() {
        jdbc.sql("UPDATE venues SET refunds_per_month = 0 WHERE id = (SELECT venue_id FROM courts WHERE id = :id)").param("id", courtId).update();
        long id = paidBooking(courtId, alice, 0);

        CancellationTerms terms = cancellations.cancelByPlayer(id, alice, null).terms();

        assertThat(terms.outcome()).isEqualTo(RefundOutcome.NO_REFUND_LIMIT_REACHED);
        assertThat(terms.message()).isEqualTo("No refund: this venue does not refund cancellations");
    }

    @Test
    void refundFromLastMonthDoesNotCountButOneAtTheStartOfThisMonthDoes() {
        long first = paidBooking(courtId, alice, 0);
        long second = paidBooking(courtId, alice, 1);
        cancellations.cancelByPlayer(first, alice, null);

        moveRefundTo(first, "date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata' - interval '1 second'");
        assertThat(cancellations.quote(second, alice).outcome()).isEqualTo(RefundOutcome.REFUND);

        moveRefundTo(first, "date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'");
        assertThat(cancellations.quote(second, alice).outcome()).isEqualTo(RefundOutcome.NO_REFUND_LIMIT_REACHED);
    }

    @Test
    void rejectedPaymentRefundsDoNotCountTowardTheAllowance() {
        long expiredHold = bookingService.hold(courtId, alice, START.plusDays(10), START.plusDays(10).plusHours(1)).getId();
        var order = payments.startAdvancePayment(expiredHold, alice);
        jdbc.sql("""
                        UPDATE bookings SET payment_started_at = now() - interval '8 minutes',
                                            hold_expires_at = now() - interval '3 minutes' WHERE id = :id
                        """)
                .param("id", expiredHold).update();
        payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_late", 20_000); // refunded as PAYMENT_REJECTED
        long id = paidBooking(courtId, alice, 0);

        assertThat(cancellations.quote(id, alice).outcome()).isEqualTo(RefundOutcome.REFUND);
    }

    @Test
    void waivedBookingHasNothingToRefund() {
        customers.setAdvanceWaived(owner, venueOf(courtId), alice, true);
        long id = bookingService.hold(courtId, alice, START, START.plusHours(1)).getId();

        CancellationResult result = cancellations.cancelByPlayer(id, alice, null);

        assertThat(result.terms().outcome()).isEqualTo(RefundOutcome.NOTHING_PAID);
        assertThat(result.booking().status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancellations.quote(paidBookingAfterRevoking(), alice).outcome()).isEqualTo(RefundOutcome.REFUND);
    }

    @Test
    void changedTermsAbortTheCancellation() {
        long id = paidBooking(courtId, alice, 0);
        CancellationTerms shown = cancellations.quote(id, alice);
        setRefundHours(ALWAYS_LATE); // the deadline "passes" while the player reads the dialog

        assertThatThrownBy(() -> cancellations.cancelByPlayer(id, alice, shown.refundAmount()))
                .isInstanceOfSatisfying(CancellationTermsChangedException.class,
                        e -> assertThat(e.getTerms().outcome()).isEqualTo(RefundOutcome.NO_REFUND_TOO_LATE));
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        assertThat(refunds(id)).isEmpty();
    }

    @Test
    void playerCannotCancelHeldStartedOthersOrPaidAtTurfBookings() {
        long held = bookingService.hold(courtId, alice, START, START.plusHours(1)).getId();
        assertThatThrownBy(() -> cancellations.cancelByPlayer(held, alice, null))
                .isInstanceOf(InvalidBookingStateException.class)
                .hasMessageContaining("Release the hold");
        cancellations.releaseHold(held, alice);

        long started = paidBooking(courtId, alice, 1);
        jdbc.sql("UPDATE bookings SET start_at = now() - interval '10 minutes', end_at = now() + interval '50 minutes' WHERE id = :id")
                .param("id", started).update();
        assertThatThrownBy(() -> cancellations.cancelByPlayer(started, alice, null))
                .isInstanceOf(InvalidBookingStateException.class)
                .hasMessageContaining("already started");

        long bob = createPlayer(2);
        long bobs = paidBooking(courtId, bob, 2);
        assertThatThrownBy(() -> cancellations.cancelByPlayer(bobs, alice, null))
                .isInstanceOf(BookingNotFoundException.class);

        long paidAtTurf = paidBooking(courtId, alice, 3);
        payments.recordBalanceReceived(owner, paidAtTurf, PaymentMethod.CASH);
        assertThatThrownBy(() -> cancellations.cancelByPlayer(paidAtTurf, alice, null))
                .isInstanceOf(InvalidBookingStateException.class)
                .hasMessageContaining("Ask the venue");
    }

    @Test
    void twoConcurrentCancellationsGetAtMostOneRefund() throws Exception {
        int n = 8;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(paidBooking(courtId, alice, i));
        }

        CountDownLatch go = new CountDownLatch(1);
        List<Future<RefundOutcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (long id : ids) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return cancellations.cancelByPlayer(id, alice, null).terms().outcome();
                }));
            }
            go.countDown();
            List<RefundOutcome> outcomes = new ArrayList<>();
            for (Future<RefundOutcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(o -> o == RefundOutcome.REFUND).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == RefundOutcome.NO_REFUND_LIMIT_REACHED).hasSize(n - 1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE kind = 'REFUND'").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM bookings WHERE status = 'CANCELLED'").query(Long.class).single())
                .isEqualTo(n);
    }

    // --- release hold ---

    @Test
    void releasingAHoldIsNotACancellation() {
        long held = bookingService.hold(courtId, alice, START, START.plusHours(1)).getId();

        BookingView released = cancellations.releaseHold(held, alice);

        assertThat(released.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(refunds(held)).isEmpty();
        long next = paidBooking(courtId, alice, 0); // same slot is free again, and the refund is still unused
        assertThat(cancellations.quote(next, alice).outcome()).isEqualTo(RefundOutcome.REFUND);
        assertThatThrownBy(() -> cancellations.releaseHold(next, alice)).isInstanceOf(InvalidBookingStateException.class);
    }

    // --- owner cancellation ---

    @Test
    void ownerCancellationRefundsInFullEvenWhenLateAndNeverCounts() {
        setRefundHours(ALWAYS_LATE);
        long byOwner = paidBooking(courtId, alice, 0);
        long byPlayer = paidBooking(courtId, alice, 1);

        CancellationResult ownerResult = cancellations.cancelByOwner(owner, byOwner, CancelReason.RAIN, null);
        setRefundHours(24);

        assertThat(ownerResult.terms().outcome()).isEqualTo(RefundOutcome.REFUND);
        assertThat(ownerResult.terms().message()).isEqualTo("₹200 refunded to the player");
        assertThat(refunds(byOwner)).containsExactly("20000 SIMULATED SUCCEEDED OWNER_CANCELLATION");
        assertThat(cancellations.cancelByPlayer(byPlayer, alice, null).terms().outcome()).isEqualTo(RefundOutcome.REFUND);
    }

    @Test
    void ownerCancellationAlsoRefundsABalanceAlreadyReceived() {
        long id = paidBooking(courtId, alice, 0);
        payments.recordBalanceReceived(owner, id, PaymentMethod.UPI);

        CancellationResult result = cancellations.cancelByOwner(owner, id, CancelReason.MAINTENANCE, null);

        assertThat(result.terms().refundAmount()).isEqualTo(100_000);
        assertThat(result.terms().message()).isEqualTo("₹1,000 refunded to the player");
        assertThat(refunds(id)).containsExactlyInAnyOrder(
                "20000 SIMULATED SUCCEEDED OWNER_CANCELLATION", "80000 UPI SUCCEEDED OWNER_CANCELLATION");
    }

    @Test
    void ownerCancelsWalkInWithNothingPaid() {
        long id = bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(START, START.plusHours(1), BookingSource.WALKIN, null, "Sameer", null)).getId();

        CancellationResult result = cancellations.cancelByOwner(owner, id, CancelReason.OTHER, "Team cancelled by phone");

        assertThat(result.terms().outcome()).isEqualTo(RefundOutcome.NOTHING_PAID);
        assertThat(result.booking().status()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void ownerCannotCancelAnotherOwnersBooking() {
        long otherTurf = createCourt();
        addStandardPriceRules(otherTurf);
        long id = paidBooking(otherTurf, alice, 0);

        assertThatThrownBy(() -> cancellations.cancelByOwner(owner, id, CancelReason.RAIN, null))
                .isInstanceOf(BookingNotFoundException.class);
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
    }

    // --- HTTP ---

    @Test
    void quoteAndCancelOverHttp() throws Exception {
        long id = paidBooking(courtId, alice, 0);
        String auth = bearer(alice, Role.PLAYER);

        mvc.perform(get("/api/bookings/{id}/cancellation", id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUND"))
                .andExpect(jsonPath("$.refundAmount").value(20000))
                .andExpect(jsonPath("$.message").value("₹200 back, uses your 1 refund for " + month));

        setRefundHours(ALWAYS_LATE);
        mvc.perform(post("/api/bookings/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRefund\": 20000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_TERMS_CHANGED"))
                .andExpect(jsonPath("$.terms.outcome").value("NO_REFUND_TOO_LATE"));

        mvc.perform(post("/api/bookings/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRefund\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.outcome").value("NO_REFUND_TOO_LATE"))
                .andExpect(jsonPath("$.booking.status").value("CANCELLED"));
    }

    @Test
    void ownerCancelOverHttp() throws Exception {
        long id = paidBooking(courtId, alice, 0);

        String reasonJson = "{\"reason\": \"RAIN\"}";
        mvc.perform(post("/api/owner/bookings/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.refundAmount").value(20000))
                .andExpect(jsonPath("$.booking.cancelledBy").value("OWNER"))
                .andExpect(jsonPath("$.booking.cancelReason").value("RAIN"));
        mvc.perform(post("/api/owner/bookings/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonJson))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCancellationNeedsAReasonAndOtherNeedsANote() throws Exception {
        long id = paidBooking(courtId, alice, 0);
        String auth = bearer(owner, Role.OWNER);

        mvc.perform(post("/api/owner/bookings/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/owner/bookings/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"OTHER\", \"note\": \"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");

        mvc.perform(post("/api/owner/bookings/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"OTHER\", \"note\": \"Floodlights failed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.cancelReason").value("OTHER"))
                .andExpect(jsonPath("$.booking.cancelNote").value("Floodlights failed"));
    }

    @Test
    void playerCancellationRecordsWhoCancelled() {
        long id = paidBooking(courtId, alice, 0);

        CancellationResult result = cancellations.cancelByPlayer(id, alice, null);

        assertThat(result.booking().cancelledBy()).isEqualTo("PLAYER");
        assertThat(result.booking().cancelReason()).isNull();
    }

    @Test
    void databaseRejectsInconsistentCancellations() {
        long id = paidBooking(courtId, alice, 0);

        assertThatThrownBy(() -> jdbc.sql("UPDATE bookings SET status = 'CANCELLED' WHERE id = :id").param("id", id).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE bookings SET status = 'CANCELLED', cancelled_by = 'OWNER' WHERE id = :id")
                .param("id", id).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE bookings SET status = 'CANCELLED', cancelled_by = 'OWNER', cancel_reason = 'OTHER'
                        WHERE id = :id
                        """).param("id", id).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // --- helpers ---

    /** A CONFIRMED online booking with the ₹200 advance paid, {@code dayOffset} days after START. */
    private long paidBooking(long turf, long player, int dayOffset) {
        OffsetDateTime start = START.plusDays(dayOffset);
        long id = bookingService.hold(turf, player, start, start.plusHours(1)).getId();
        assertThat(checkout.payAdvance(id, player).booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        return id;
    }

    private long paidBookingAfterRevoking() {
        customers.setAdvanceWaived(owner, venueOf(courtId), alice, false);
        return paidBooking(courtId, alice, 5);
    }

    private void setRefundHours(int hours) {
        jdbc.sql("UPDATE courts SET refund_full_hours_before = :h").param("h", hours).update();
    }

    private void moveRefundTo(long bookingId, String createdAtSql) {
        jdbc.sql("UPDATE payments SET created_at = %s WHERE booking_id = :id AND kind = 'REFUND'".formatted(createdAtSql))
                .param("id", bookingId).update();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId).query(String.class).single();
    }

    private List<String> refunds(long bookingId) {
        return jdbc.sql("""
                        SELECT amount || ' ' || method || ' ' || status || ' ' || refund_reason FROM payments
                        WHERE booking_id = :id AND kind = 'REFUND' ORDER BY id
                        """)
                .param("id", bookingId)
                .query(String.class)
                .list();
    }
}
