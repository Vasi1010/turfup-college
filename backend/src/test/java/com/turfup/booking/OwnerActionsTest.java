package com.turfup.booking;

import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.payment.CheckoutService;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner actions after booking: balance received, no-show (counted by a database trigger), advance waiver,
 * and isolation between owners. Evening slots cost ₹1000, advance ₹200.
 */
class OwnerActionsTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);

    @Autowired
    BookingService bookingService;

    @Autowired
    CheckoutService checkout;

    @Autowired
    PaymentService payments;

    @Autowired
    MockMvc mvc;

    long courtId;
    long owner;
    long alice;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        owner = ownerOf(courtId);
        alice = createPlayer(1);
    }

    // --- balance ---

    @Test
    void balanceIsTotalMinusAdvanceAndIsReceivedOnce() throws Exception {
        long id = paidOnlineBooking(alice, START);

        balance(owner, id, "UPI")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(80000))
                .andExpect(jsonPath("$.method").value("UPI"));
        balance(owner, id, "CASH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_STATE"));

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM payments WHERE booking_id = :id AND kind = 'BALANCE'
                        """).param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void walkInBalanceIsTheFullPrice() {
        Booking walkIn = walkIn(START);

        var receipt = payments.recordBalanceReceived(owner, walkIn.getId(), PaymentMethod.CASH);

        assertThat(receipt.amount()).isEqualTo(100_000);
    }

    @Test
    void balanceOnlyForConfirmedBookings() {
        Booking held = bookingService.hold(courtId, alice, START, END);

        assertThatThrownBy(() -> payments.recordBalanceReceived(owner, held.getId(), PaymentMethod.CASH))
                .isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void balanceMethodMustBeCashOrUpi() throws Exception {
        long id = walkIn(START).getId();

        balance(owner, id, "SIMULATED").andExpect(status().isBadRequest());
    }

    // --- no-show and the trigger ---

    @Test
    void noShowIncrementsCountThroughTrigger() throws Exception {
        long first = paidOnlineBooking(alice, START);
        long second = paidOnlineBooking(alice, START.plusDays(1));
        moveToPast(first);
        moveToPast(second);

        noShow(owner, first).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_SHOW"));
        assertThat(noShowCount(alice)).isEqualTo(1);

        noShow(owner, second).andExpect(status().isOk());
        assertThat(noShowCount(alice)).isEqualTo(2);
    }

    @Test
    void triggerFiresForAnyStatusChangeToNoShowButCountsOnlyOnce() {
        long id = paidOnlineBooking(alice, START);

        jdbc.sql("UPDATE bookings SET status = 'NO_SHOW' WHERE id = :id").param("id", id).update();
        jdbc.sql("UPDATE bookings SET status = 'NO_SHOW' WHERE id = :id").param("id", id).update();

        assertThat(noShowCount(alice)).isEqualTo(1);
    }

    @Test
    void noShowOnlyAfterTheSlotStarts() throws Exception {
        long id = paidOnlineBooking(alice, START);

        noShow(owner, id).andExpect(status().isConflict());
        assertThat(noShowCount(alice)).isNull();
    }

    @Test
    void noShowOnlyForConfirmedBookings() {
        Booking held = bookingService.hold(courtId, alice, START, END);
        moveToPast(held.getId());

        assertThatThrownBy(() -> bookingService.markNoShow(owner, held.getId()))
                .isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void walkInNoShowWithoutPlayerCreatesNoCustomerRow() {
        Booking walkIn = walkIn(START);
        moveToPast(walkIn.getId());

        bookingService.markNoShow(owner, walkIn.getId());

        assertThat(jdbc.sql("SELECT count(*) FROM venue_customers").query(Long.class).single()).isZero();
    }

    // --- waiver ---

    @Test
    void ownerRevokesWaiverAfterNoShowInOneCall() throws Exception {
        waiver(owner, courtId, alice, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advanceWaived").value(true));
        Booking waived = bookingService.hold(courtId, alice, START, END);
        assertThat(waived.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        moveToPast(waived.getId());
        bookingService.markNoShow(owner, waived.getId());

        waiver(owner, courtId, alice, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advanceWaived").value(false))
                .andExpect(jsonPath("$.noShowCount").value(1));

        Booking next = bookingService.hold(courtId, alice, START.plusDays(1), END.plusDays(1));
        assertThat(next.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(next.getAdvanceRequired()).isEqualTo(20_000);
    }

    @Test
    void waiverNeedsAnExistingPlayer() throws Exception {
        waiver(owner, courtId, 999_999, true).andExpect(status().isNotFound());
        waiver(owner, courtId, owner, true).andExpect(status().isNotFound()); // an owner is not a player
    }

    // --- isolation between owners ---

    @Test
    void ownerCannotTouchAnotherOwnersVenueOrBookings() throws Exception {
        long otherTurf = createCourt();
        addStandardPriceRules(otherTurf);
        long otherOwner = ownerOf(otherTurf);
        long othersBooking = paidOnlineBookingOn(otherTurf, alice, START);
        moveToPast(othersBooking);
        String asOwner = bearer(owner, Role.OWNER);

        mvc.perform(put("/api/owner/venues/{id}", venueOf(otherTurf))
                        .header(HttpHeaders.AUTHORIZATION, asOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hijacked", "area": "x", "address": "x", "openMinute": 360, "closeMinute": 1560}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/owner/courts/{id}", otherTurf)
                        .header(HttpHeaders.AUTHORIZATION, asOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hijacked", "sports": ["football"], "slotMinutes": 60, "advanceAmount": 0,
                                 "priceRules": [{"startMinute": 360, "endMinute": 1560, "rate": 1}]}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/owner/courts/{id}/bookings", otherTurf)
                        .header(HttpHeaders.AUTHORIZATION, asOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startAt": "2030-02-15T20:00:00+05:30", "endAt": "2030-02-15T21:00:00+05:30",
                                 "source": "WALKIN", "walkinName": "Sameer"}
                                """))
                .andExpect(status().isNotFound());
        waiver(owner, otherTurf, alice, true).andExpect(status().isNotFound());
        noShow(owner, othersBooking).andExpect(status().isNotFound());
        balance(owner, othersBooking, "CASH").andExpect(status().isNotFound());
        mvc.perform(get("/api/owner/venues").header(HttpHeaders.AUTHORIZATION, asOwner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(venueOf(courtId)));

        // The other owner's data is untouched.
        assertThat(jdbc.sql("SELECT name FROM venues WHERE id = :id").param("id", venueOf(otherTurf))
                .query(String.class).single()).isEqualTo("Test Venue");
        assertThat(jdbc.sql("SELECT name FROM courts WHERE id = :id").param("id", otherTurf)
                .query(String.class).single()).isEqualTo("Court 1");
        assertThat(jdbc.sql("SELECT count(*) FROM bookings WHERE court_id = :id").param("id", otherTurf)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", othersBooking)
                .query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE kind = 'BALANCE'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM venue_customers").query(Long.class).single()).isZero();
        // ...and its owner can still act on it.
        noShow(otherOwner, othersBooking).andExpect(status().isOk());
    }

    // --- helpers ---

    private long paidOnlineBooking(long player, OffsetDateTime start) {
        return paidOnlineBookingOn(courtId, player, start);
    }

    private long paidOnlineBookingOn(long turf, long player, OffsetDateTime start) {
        Booking held = bookingService.hold(turf, player, start, start.plusHours(1));
        assertThat(checkout.payAdvance(held.getId(), player).booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        return held.getId();
    }

    private Booking walkIn(OffsetDateTime start) {
        return bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(start, start.plusHours(1), BookingSource.WALKIN, null, "Sameer", null));
    }

    /** Shifts a booking so its slot started an hour ago (the slot checks only apply when booking). */
    private void moveToPast(long bookingId) {
        jdbc.sql("""
                        UPDATE bookings
                        SET start_at = now() - interval '1 hour' - make_interval(days => id::int),
                            end_at = now() - make_interval(days => id::int)
                        WHERE id = :id
                        """)
                .param("id", bookingId).update();
    }

    private Integer noShowCount(long player) {
        return jdbc.sql("SELECT no_show_count FROM venue_customers WHERE venue_id = :venueId AND player_id = :player")
                .param("venueId", venueOf(courtId)).param("player", player)
                .query(Integer.class).optional().orElse(null);
    }

    private ResultActions balance(long ownerId, long bookingId, String method) throws Exception {
        return mvc.perform(post("/api/owner/bookings/{id}/balance", bookingId)
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId, Role.OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"%s\"}".formatted(method)));
    }

    private ResultActions noShow(long ownerId, long bookingId) throws Exception {
        return mvc.perform(post("/api/owner/bookings/{id}/no-show", bookingId)
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId, Role.OWNER)));
    }

    /** {@code court}: any court of the venue whose waiver is set. */
    private ResultActions waiver(long ownerId, long court, long player, boolean waived) throws Exception {
        return mvc.perform(put("/api/owner/venues/{venueId}/customers/{playerId}/waiver", venueOf(court), player)
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId, Role.OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"advanceWaived\": %s}".formatted(waived)));
    }
}
