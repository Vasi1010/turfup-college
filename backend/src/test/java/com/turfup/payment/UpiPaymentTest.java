package com.turfup.payment;

import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.booking.ActiveHoldExistsException;
import com.turfup.booking.Booking;
import com.turfup.booking.BookingService;
import com.turfup.booking.BookingStatus;
import com.turfup.booking.BookingView;
import com.turfup.booking.CancellationService;
import com.turfup.booking.InvalidBookingStateException;
import com.turfup.booking.MyBookingsService;
import com.turfup.booking.SlotUnavailableException;
import com.turfup.payment.CheckoutService.PayOutcome;
import com.turfup.payment.CheckoutService.PayResult;
import com.turfup.venue.InvalidVenueSetupException;
import com.turfup.venue.VenueApprovalBlockedException;
import com.turfup.venue.VenueApprovalService;
import com.turfup.venue.VenueSetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * upi mode: the advance is paid straight to the venue's UPI ID, the player says so, and the owner confirms or
 * rejects it. A claimed hold keeps its slot until the owner decides; refunds wait for the owner to send them.
 */
@TestPropertySource(properties = "turfup.payments.mode=upi")
class UpiPaymentTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);

    @Autowired BookingService bookings;
    @Autowired CheckoutService checkout;
    @Autowired UpiPaymentService upi;
    @Autowired CancellationService cancellations;
    @Autowired MyBookingsService mine;
    @Autowired VenueSetupService venueSetup;
    @Autowired VenueApprovalService approvals;
    @Autowired MockMvc mvc;

    long courtId;
    long venueId;
    long owner;
    long player;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        venueId = venueOf(courtId);
        owner = ownerOf(courtId);
        player = createPlayer(1);
        venueSetup.setUpi(owner, venueId, " kickarena@ybl ", "Kick Arena");
    }

    /** Hold, open the UPI app (pay), and say "I've paid". */
    private long claimedBooking(long playerId, String ref) {
        Booking held = bookings.hold(courtId, playerId, START, END);
        checkout.payAdvance(held.getId(), playerId);
        upi.claim(held.getId(), playerId, ref);
        return held.getId();
    }

    private String bookingStatus(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId).query(String.class).single();
    }

    private long count(String sql, long bookingId) {
        return jdbc.sql(sql).param("id", bookingId).query(Long.class).single();
    }

    @Test
    void payGivesTheVenuesUpiDetailsAndRecordsNoPayment() {
        Booking held = bookings.hold(courtId, player, START, END);

        PayResult result = checkout.payAdvance(held.getId(), player);

        assertThat(result.outcome()).isEqualTo(PayOutcome.PAY_BY_UPI);
        assertThat(result.checkout()).isNull();
        assertThat(result.upi()).isEqualTo(new CheckoutService.UpiPayment(
                "kickarena@ybl", "Kick Arena", 20_000, "TurfUp booking " + held.getId()));
        assertThat(count("SELECT count(*) FROM payments WHERE booking_id = :id", held.getId())).isZero();
        assertThat(count("SELECT count(*) FROM bookings WHERE id = :id AND payment_started_at IS NOT NULL",
                held.getId())).isEqualTo(1);
    }

    @Test
    void aClaimedHoldKeepsItsSlotPastTheHoldUntilTheOwnerDecides() {
        long id = claimedBooking(player, " 4123 4567 8901 ");
        jdbc.sql("UPDATE bookings SET hold_expires_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", id).update();

        jdbc.sql("CALL release_expired_holds()").query(Integer.class).single();

        assertThat(bookingStatus(id)).isEqualTo("HELD");
        assertThatThrownBy(() -> bookings.hold(courtId, createPlayer(2), START, END))
                .isInstanceOf(SlotUnavailableException.class);
        // One unconfirmed booking per player: a second claim cannot block another slot.
        assertThatThrownBy(() -> bookings.hold(courtId, player, START.plusHours(2), END.plusHours(2)))
                .isInstanceOf(ActiveHoldExistsException.class);
        var check = upi.inbox(owner, venueId).paymentsToCheck();
        assertThat(check).hasSize(1);
        assertThat(check.getFirst().paymentRef()).isEqualTo("412345678901");
        assertThat(check.getFirst().amount()).isEqualTo(20_000);
        assertThat(mine.get(player, id).paymentClaimedAt()).isNotNull();
    }

    @Test
    void receivedConfirmsTheBookingAndRecordsTheAdvanceOnce() {
        long id = claimedBooking(player, null);

        BookingView confirmed = upi.markReceived(owner, id);
        upi.markReceived(owner, id);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(count("""
                SELECT count(*) FROM payments
                WHERE booking_id = :id AND kind = 'ADVANCE' AND method = 'UPI' AND status = 'SUCCEEDED'
                  AND amount = 20000
                """, id)).isEqualTo(1);
        assertThat(upi.inbox(owner, venueId).paymentsToCheck()).isEmpty();
    }

    @Test
    void notReceivedCancelsForTheVenueAndFreesTheSlot() {
        long id = claimedBooking(player, null);

        upi.markNotReceived(owner, id);

        assertThat(jdbc.sql("SELECT status || ' ' || cancelled_by || ' ' || cancel_reason FROM bookings WHERE id = :id")
                .param("id", id).query(String.class).single())
                .isEqualTo("CANCELLED OWNER PAYMENT_NOT_RECEIVED");
        assertThat(count("SELECT count(*) FROM payments WHERE booking_id = :id", id)).isZero();
        assertThat(bookings.hold(courtId, createPlayer(2), START, END).getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(mine.get(player, id).cancelReason()).isEqualTo("PAYMENT_NOT_RECEIVED");
    }

    @Test
    void aClaimMustComeWhileTheHoldLasts() {
        Booking quiet = bookings.hold(courtId, player, START, END);
        jdbc.sql("UPDATE bookings SET hold_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", quiet.getId()).update();
        // Hold over, payment never started: too late.
        assertThatThrownBy(() -> upi.claim(quiet.getId(), player, null))
                .isInstanceOf(InvalidBookingStateException.class);
        jdbc.sql("CALL release_expired_holds()").query(Integer.class).single();

        // Payment started in time: the grace window still allows the claim.
        Booking paying = bookings.hold(courtId, player, START, END);
        checkout.payAdvance(paying.getId(), player);
        jdbc.sql("UPDATE bookings SET hold_expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", paying.getId()).update();
        assertThat(upi.claim(paying.getId(), player, "412345678901").status()).isEqualTo(BookingStatus.HELD);

        assertThatThrownBy(() -> upi.claim(paying.getId(), player, "12-34"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClaimedHoldCannotBeReleasedByThePlayer() {
        long id = claimedBooking(player, null);

        assertThatThrownBy(() -> cancellations.releaseHold(id, player))
                .isInstanceOf(InvalidBookingStateException.class);
        assertThat(bookingStatus(id)).isEqualTo("HELD");
    }

    @Test
    void aCancellationRefundWaitsForTheOwnerToSendIt() {
        long id = claimedBooking(player, "412345678901");
        upi.markReceived(owner, id);

        cancellations.cancelByPlayer(id, player, null);

        assertThat(mine.get(player, id).refundPending()).isEqualTo(20_000);
        var refunds = upi.inbox(owner, venueId).refundsToSend();
        assertThat(refunds).hasSize(1);
        assertThat(refunds.getFirst().amount()).isEqualTo(20_000);
        assertThat(refunds.getFirst().paymentRef()).isEqualTo("412345678901");

        upi.markRefundSent(owner, refunds.getFirst().refundId());

        assertThat(mine.get(player, id).refundPending()).isZero();
        assertThat(mine.get(player, id).refunded()).isEqualTo(20_000);
        assertThat(upi.inbox(owner, venueId).refundsToSend()).isEmpty();
        assertThatThrownBy(() -> upi.markRefundSent(owner, refunds.getFirst().refundId()))
                .isInstanceOf(RefundNotOpenException.class);
    }

    @Test
    void anotherOwnerCannotSettleThisVenuesPayments() {
        long id = claimedBooking(player, null);
        long otherOwner = ownerOf(createCourt());

        assertThatThrownBy(() -> upi.markReceived(otherOwner, id))
                .isInstanceOf(com.turfup.booking.BookingNotFoundException.class);
        assertThatThrownBy(() -> upi.markNotReceived(otherOwner, id))
                .isInstanceOf(com.turfup.booking.BookingNotFoundException.class);
        assertThat(bookingStatus(id)).isEqualTo("HELD");
    }

    @Test
    void aVenueWithoutAUpiIdTakesNoAdvancesAndCannotBeApproved() {
        venueSetup.setUpi(owner, venueId, "", null);

        assertThatThrownBy(() -> bookings.hold(courtId, player, START, END))
                .isInstanceOf(OnlinePaymentUnavailableException.class);
        assertThatThrownBy(() -> approvals.setApproved(venueId, true))
                .isInstanceOf(VenueApprovalBlockedException.class);
        assertThatThrownBy(() -> venueSetup.setUpi(owner, venueId, "not a upi id", null))
                .isInstanceOf(InvalidVenueSetupException.class);

        venueSetup.setUpi(owner, venueId, "9876543210@paytm", "");
        assertThat(approvals.setApproved(venueId, true).approved()).isTrue();
        assertThat(venueSetup.find(venueId).upiName()).isNull();
        assertThat(venueSetup.find(venueId).onlinePayments()).isTrue();
    }

    @Test
    void endpointsAreSplitBetweenPlayerAndOwner() throws Exception {
        long id = bookings.hold(courtId, player, START, END).getId();
        checkout.payAdvance(id, player);

        mvc.perform(post("/api/bookings/{id}/upi-paid", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/{id}/upi-paid", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(player, Role.PLAYER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ref\": \"412345678901\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD"));
        mvc.perform(get("/api/owner/venues/{id}/upi-inbox", venueId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsToCheck[0].bookingId").value(id));
        mvc.perform(post("/api/owner/bookings/{id}/upi-received", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void productionRefusesSimulatedPayments() {
        assertThatThrownBy(() -> new ProdPaymentsGuard(new PaymentsProperties(PaymentMode.SIMULATED, null)))
                .isInstanceOf(IllegalStateException.class);
        new ProdPaymentsGuard(new PaymentsProperties(PaymentMode.UPI, null));
    }
}
