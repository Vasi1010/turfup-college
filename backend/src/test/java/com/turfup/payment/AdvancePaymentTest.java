package com.turfup.payment;

import com.jayway.jsonpath.JsonPath;
import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.booking.Booking;
import com.turfup.booking.BookingService;
import com.turfup.booking.BookingStatus;
import com.turfup.booking.InvalidBookingStateException;
import com.turfup.booking.PaymentWindowClosedException;
import com.turfup.payment.PaymentService.AdvanceOrder;
import com.turfup.payment.PaymentService.CaptureOutcome;
import com.turfup.venue.VenueCustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
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
 * Hold -> pay -> confirm, through the same service methods a gateway webhook will call. Hold 5 min,
 * grace 2 min, payments may start only with 30 s or more left (seeded settings). Advance ₹200.
 */
class AdvancePaymentTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService payments;

    @Autowired
    VenueCustomerService customers;

    @Autowired
    CheckoutService checkout;

    @Autowired
    MockMvc mvc;

    long courtId;
    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        alice = createPlayer(1);
        bob = createPlayer(2);
    }

    // --- simulated "Pay advance" end to end ---

    @Test
    void payAdvanceConfirmsTheHoldAndRecordsThePayment() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);

        mvc.perform(post("/api/bookings/{id}/pay", held.getId()).header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                .andExpect(jsonPath("$.booking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.booking.paymentStartedAt").isNotEmpty());

        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        assertThat(payments(held.getId())).containsExactly("ADVANCE SUCCEEDED 20000 SIMULATED");
    }

    @Test
    void playerCannotPaySomeoneElsesBooking() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);

        mvc.perform(post("/api/bookings/{id}/pay", held.getId()).header(HttpHeaders.AUTHORIZATION, bearer(bob, Role.PLAYER)))
                .andExpect(status().isNotFound());
        assertThat(statusOf(held.getId())).isEqualTo("HELD");
    }

    @Test
    void confirmedBookingCannotBePaidAgain() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        String auth = bearer(alice, Role.PLAYER);
        mvc.perform(post("/api/bookings/{id}/pay", held.getId()).header(HttpHeaders.AUTHORIZATION, auth));

        mvc.perform(post("/api/bookings/{id}/pay", held.getId()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_STATE"));
        assertThat(payments(held.getId())).hasSize(1);
    }

    // --- countdown times come from the server ---

    @Test
    void holdResponseCarriesHoldEndAndPayCutoffFromSettings() throws Exception {
        String body = mvc.perform(post("/api/bookings/holds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courtId": %d, "startAt": "2030-01-15T20:00:00+05:30", "endAt": "2030-01-15T21:00:00+05:30"}
                                """.formatted(courtId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        OffsetDateTime created = OffsetDateTime.parse(JsonPath.read(body, "$.createdAt"));
        OffsetDateTime expires = OffsetDateTime.parse(JsonPath.read(body, "$.holdExpiresAt"));
        OffsetDateTime cutoff = OffsetDateTime.parse(JsonPath.read(body, "$.payCutoffAt"));
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Duration.between(cutoff, expires)).isEqualTo(Duration.ofSeconds(30));

        // Settings drive the cutoff: no client constant to keep in sync.
        jdbc.sql("UPDATE app_settings SET value = '45' WHERE key = 'min_pay_seconds_remaining'").update();
        try {
            String again = mvc.perform(get("/api/bookings/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            OffsetDateTime cutoff45 = OffsetDateTime.parse(JsonPath.read(again, "$.payCutoffAt"));
            assertThat(Duration.between(cutoff45.toInstant(), expires.toInstant()))
                    .isEqualTo(Duration.ofSeconds(45));
        } finally {
            jdbc.sql("UPDATE app_settings SET value = '30' WHERE key = 'min_pay_seconds_remaining'").update();
        }
    }

    @Test
    void confirmedBookingHasNoPayCutoff() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        checkout.payAdvance(held.getId(), alice);

        mvc.perform(get("/api/bookings/{id}", held.getId()).header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(jsonPath("$.payCutoffAt").isEmpty());
    }

    // --- payment window and grace ---

    @Test
    void paymentCannotStartWithLessThanThirtySecondsLeft() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        sql("UPDATE bookings SET hold_expires_at = now() + interval '29 seconds' WHERE id = :id", held.getId());

        assertThatThrownBy(() -> payments.startAdvancePayment(held.getId(), alice))
                .isInstanceOf(PaymentWindowClosedException.class);
        assertThat(paymentStartedAt(held.getId())).isNull();
        assertThat(payments(held.getId())).isEmpty();
    }

    @Test
    void paymentMayStartWithThirtySecondsLeft() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        sql("UPDATE bookings SET hold_expires_at = now() + interval '31 seconds' WHERE id = :id", held.getId());

        AdvanceOrder order = payments.startAdvancePayment(held.getId(), alice);

        assertThat(order.amount()).isEqualTo(20_000);
        assertThat(paymentStartedAt(held.getId())).isNotNull();
    }

    @Test
    void captureInsideGraceWindowConfirms() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        AdvanceOrder order = payments.startAdvancePayment(held.getId(), alice);
        // Payment started 3 minutes ago; the hold ran out 1 minute ago: inside the 2-minute grace.
        sql("""
                UPDATE bookings SET payment_started_at = now() - interval '3 minutes',
                                    hold_expires_at = now() - interval '1 minute' WHERE id = :id
                """, held.getId());

        var result = payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 20_000);

        assertThat(result.outcome()).isEqualTo(CaptureOutcome.CONFIRMED);
        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
    }

    @Test
    void captureAfterGraceWindowIsRefunded() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        AdvanceOrder order = payments.startAdvancePayment(held.getId(), alice);
        sql("""
                UPDATE bookings SET payment_started_at = now() - interval '4 minutes',
                                    hold_expires_at = now() - interval '2 minutes 1 second' WHERE id = :id
                """, held.getId());

        var result = payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 20_000);

        assertThat(result.outcome()).isEqualTo(CaptureOutcome.REFUNDED);
        assertThat(statusOf(held.getId())).isEqualTo("EXPIRED");
        assertThat(payments(held.getId()))
                .containsExactlyInAnyOrder("ADVANCE SUCCEEDED 20000 SIMULATED", "REFUND SUCCEEDED 20000 SIMULATED");
    }

    @Test
    void captureForASlotTakenInTheMeantimeIsRefunded() {
        Booking aliceHold = bookingService.hold(courtId, alice, START, END);
        AdvanceOrder order = payments.startAdvancePayment(aliceHold.getId(), alice);
        sql("""
                UPDATE bookings SET payment_started_at = now() - interval '8 minutes',
                                    hold_expires_at = now() - interval '3 minutes' WHERE id = :id
                """, aliceHold.getId());
        Booking bobHold = bookingService.hold(courtId, bob, START, END);

        var result = payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 20_000);

        assertThat(result.outcome()).isEqualTo(CaptureOutcome.REFUNDED);
        assertThat(statusOf(aliceHold.getId())).isEqualTo("EXPIRED");
        assertThat(statusOf(bobHold.getId())).isEqualTo("HELD");
    }

    // --- webhook idempotency ---

    @Test
    void duplicateWebhookDeliveryConfirmsOnce() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        AdvanceOrder order = payments.startAdvancePayment(held.getId(), alice);

        var first = payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 20_000);
        var second = payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 20_000);

        assertThat(first.outcome()).isEqualTo(CaptureOutcome.CONFIRMED);
        assertThat(second.outcome()).isEqualTo(CaptureOutcome.DUPLICATE);
        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        assertThat(payments(held.getId())).containsExactly("ADVANCE SUCCEEDED 20000 SIMULATED");
    }

    @Test
    void concurrentDuplicateDeliveriesConfirmOnce() throws Exception {
        Booking held = bookingService.hold(courtId, alice, START, END);
        AdvanceOrder order = payments.startAdvancePayment(held.getId(), alice);
        int deliveries = 10;

        CountDownLatch go = new CountDownLatch(1);
        List<Future<CaptureOutcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(deliveries)) {
            for (int i = 0; i < deliveries; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 20_000).outcome();
                }));
            }
            go.countDown();
            List<CaptureOutcome> outcomes = new ArrayList<>();
            for (Future<CaptureOutcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(o -> o == CaptureOutcome.CONFIRMED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == CaptureOutcome.DUPLICATE).hasSize(deliveries - 1);
        }
        assertThat(payments(held.getId())).containsExactly("ADVANCE SUCCEEDED 20000 SIMULATED");
    }

    @Test
    void secondDistinctPaymentForAConfirmedBookingIsRefunded() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        AdvanceOrder order = payments.startAdvancePayment(held.getId(), alice);
        payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 20_000);

        var second = payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_2", 20_000);

        assertThat(second.outcome()).isEqualTo(CaptureOutcome.REFUNDED);
        assertThat(statusOf(held.getId())).isEqualTo("CONFIRMED");
        assertThat(payments(held.getId())).containsExactlyInAnyOrder(
                "ADVANCE SUCCEEDED 20000 SIMULATED", "ADVANCE SUCCEEDED 20000 SIMULATED",
                "REFUND SUCCEEDED 20000 SIMULATED");
    }

    @Test
    void wrongAmountIsRefundedAndHoldStays() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        AdvanceOrder order = payments.startAdvancePayment(held.getId(), alice);

        var result = payments.recordCapturedAdvance(order.gatewayOrderId(), "pay_1", 100);

        assertThat(result.outcome()).isEqualTo(CaptureOutcome.REFUNDED);
        assertThat(statusOf(held.getId())).isEqualTo("HELD");
    }

    @Test
    void captureForUnknownOrderIsRejected() {
        assertThatThrownBy(() -> payments.recordCapturedAdvance("order_nobody_made", "pay_1", 20_000))
                .isInstanceOf(UnknownPaymentOrderException.class);
    }

    // --- advance waiver ---

    @Test
    void waivedRegularIsConfirmedWithoutPayment() {
        customers.setAdvanceWaived(ownerOf(courtId), venueOf(courtId), alice, true);

        Booking booking = bookingService.hold(courtId, alice, START, END);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getAdvanceRequired()).isZero();
        assertThat(booking.getTotalPrice()).isEqualTo(100_000);
        assertThat(booking.getHoldExpiresAt()).isNull();
        assertThat(payments(booking.getId())).isEmpty();
        assertThatThrownBy(() -> payments.startAdvancePayment(booking.getId(), alice))
                .isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void nonRegularMustPayAndIsNotConfirmedUntilThen() {
        Booking booking = bookingService.hold(courtId, bob, START, END);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(booking.getAdvanceRequired()).isEqualTo(20_000);
        assertThat(booking.getHoldExpiresAt()).isNotNull();
    }

    @Test
    void waiverAppliesOnlyToTheTurfThatGrantedIt() {
        long otherTurf = createCourt();
        addStandardPriceRules(otherTurf);
        customers.setAdvanceWaived(ownerOf(courtId), venueOf(courtId), alice, true);

        Booking elsewhere = bookingService.hold(otherTurf, alice, START, END);

        assertThat(elsewhere.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(elsewhere.getAdvanceRequired()).isEqualTo(20_000);
    }

    @Test
    void turfWithZeroAdvanceConfirmsImmediately() {
        sql("UPDATE courts SET advance_amount = 0 WHERE id = :id", courtId);

        Booking booking = bookingService.hold(courtId, bob, START, END);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getAdvanceRequired()).isZero();
    }

    // --- helpers ---

    private void sql(String sql, long id) {
        jdbc.sql(sql).param("id", id).update();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId).query(String.class).single();
    }

    private OffsetDateTime paymentStartedAt(long bookingId) {
        return jdbc.sql("SELECT payment_started_at FROM bookings WHERE id = :id")
                .param("id", bookingId).query(OffsetDateTime.class).list().getFirst();
    }

    private List<String> payments(long bookingId) {
        return jdbc.sql("""
                        SELECT kind || ' ' || status || ' ' || amount || ' ' || method FROM payments
                        WHERE booking_id = :id ORDER BY id
                        """)
                .param("id", bookingId)
                .query(String.class)
                .list();
    }
}
