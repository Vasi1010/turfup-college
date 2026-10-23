package com.turfup.booking;

import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.booking.BookingService.OwnerEntry;
import com.turfup.booking.MyBookingsService.MyBooking;
import com.turfup.booking.OwnerScheduleService.Schedule;
import com.turfup.payment.CheckoutService;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.venue.VenueCustomerService;
import com.turfup.venue.VenueCustomerService.Regular;
import com.turfup.venue.VenueNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Read models behind the screens: My bookings, the owner's day schedule, and the regulars list. */
class DashboardQueriesTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final LocalDate DAY = LocalDate.of(2030, 1, 15);
    private static final OffsetDateTime EIGHT_PM = DAY.atTime(20, 0).atOffset(IST);

    @Autowired
    BookingService bookingService;

    @Autowired
    CheckoutService checkout;

    @Autowired
    CancellationService cancellations;

    @Autowired
    PaymentService payments;

    @Autowired
    MyBookingsService myBookings;

    @Autowired
    OwnerScheduleService schedules;

    @Autowired
    VenueCustomerService customers;

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
        jdbc.sql("UPDATE users SET name = 'Alice' WHERE id = :id").param("id", alice).update();
    }

    // --- My bookings ---

    @Test
    void myBookingsShowTurfRefundAndOwnersCancellationReason() throws Exception {
        long cancelledByOwner = paid(EIGHT_PM);
        cancellations.cancelByOwner(owner, cancelledByOwner, CancelReason.RAIN, "Waterlogged pitch");
        long upcoming = paid(EIGHT_PM.plusDays(1));
        long abandoned = bookingService.hold(courtId, alice, EIGHT_PM.plusDays(2), EIGHT_PM.plusDays(2).plusHours(1)).getId();
        cancellations.releaseHold(abandoned, alice);

        List<MyBooking> list = myBookings.list(alice);

        assertThat(list).extracting(MyBooking::id).containsExactly(upcoming, cancelledByOwner); // latest first, no EXPIRED
        MyBooking cancelled = list.get(1);
        assertThat(cancelled.venueName()).isEqualTo("Test Venue");
        assertThat(cancelled.courtName()).isEqualTo("Court 1");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledBy()).isEqualTo("OWNER");
        assertThat(cancelled.cancelReason()).isEqualTo("RAIN");
        assertThat(cancelled.cancelNote()).isEqualTo("Waterlogged pitch");
        assertThat(cancelled.refunded()).isEqualTo(20_000);

        mvc.perform(get("/api/bookings/mine").header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].cancelReason").value("RAIN"));
        mvc.perform(get("/api/bookings/{id}", abandoned).header(HttpHeaders.AUTHORIZATION, bearer(alice, Role.PLAYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void playersSeeOnlyTheirOwnBookings() throws Exception {
        long id = paid(EIGHT_PM);
        long bob = createPlayer(2);

        assertThat(myBookings.list(bob)).isEmpty();
        mvc.perform(get("/api/bookings/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(bob, Role.PLAYER)))
                .andExpect(status().isNotFound());
    }

    // --- owner schedule ---

    @Test
    void scheduleShowsEverySlotAndWhoBookedWithBalances() throws Exception {
        long online = paid(EIGHT_PM);
        long walkIn = bookingService.enterForOwner(owner, courtId,
                new OwnerEntry(EIGHT_PM.plusHours(1), EIGHT_PM.plusHours(3), BookingSource.WALKIN, null, null, null)).getId();
        payments.recordBalanceReceived(owner, walkIn, PaymentMethod.UPI);
        long held = bookingService.hold(courtId, createPlayer(2), EIGHT_PM.minusHours(2), EIGHT_PM.minusHours(1)).getId();
        long expired = bookingService.hold(courtId, createPlayer(3), EIGHT_PM.minusHours(4), EIGHT_PM.minusHours(3)).getId();
        jdbc.sql("UPDATE bookings SET hold_expires_at = now() - interval '1 second' WHERE id = :id").param("id", expired).update();
        long cancelled = paid(EIGHT_PM.minusHours(6));
        cancellations.cancelByPlayer(cancelled, alice, null);

        Schedule s = schedules.schedule(owner, venueOf(courtId), DAY);

        assertThat(s.courts()).singleElement().satisfies(c -> assertThat(c.slots()).hasSize(20));
        var slots = s.courts().getFirst().slots();
        assertThat(s.bookings()).extracting(OwnerScheduleService.ScheduleBooking::id).containsExactly(held, online, walkIn);
        var aliceBooking = s.bookings().get(1);
        assertThat(aliceBooking.customerName()).isEqualTo("Alice");
        assertThat(aliceBooking.customerPhone()).isEqualTo("9000000001");
        assertThat(aliceBooking.balanceDue()).isEqualTo(80_000);
        var walkInBooking = s.bookings().get(2);
        assertThat(walkInBooking.customerName()).isEqualTo("Walk-in");
        assertThat(walkInBooking.balanceDue()).isZero();
        assertThat(walkInBooking.balanceMethod()).isEqualTo("UPI");
        // The two-hour walk-in covers the 9 PM and 10 PM slots.
        assertThat(slots.stream().filter(slot -> Long.valueOf(walkIn).equals(slot.bookingId()))).hasSize(2);
        assertThat(slots.get(0).price()).isEqualTo(80_000);

        mvc.perform(get("/api/owner/venues/{id}/schedule", venueOf(courtId)).param("date", "2030-01-15")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueName").value("Test Venue"))
                .andExpect(jsonPath("$.courts[0].name").value("Court 1"))
                .andExpect(jsonPath("$.bookings.length()").value(3))
                .andExpect(jsonPath("$.serverNow").isNotEmpty());
    }

    @Test
    void scheduleIncludesTodaysPastSlots() {
        long allDay = createCourt(0, 1440, 60);
        addPriceRule(allDay, null, 0, 2880, 100_000);
        LocalDate today = jdbc.sql("SELECT (now() AT TIME ZONE 'Asia/Kolkata')::date").query(LocalDate.class).single();

        Schedule s = schedules.schedule(ownerOf(allDay), venueOf(allDay), today);

        assertThat(s.courts().getFirst().slots()).hasSize(24);
        assertThat(s.currentOperatingDate()).isEqualTo(today);
    }

    @Test
    void scheduleHasAColumnPerCourtWithItsOwnSlotsAndBookings() {
        long court2 = addCourt(venueOf(courtId), "Badminton 1", 30, "badminton");
        addStandardPriceRules(court2);
        long onCourt1 = paid(EIGHT_PM);
        long onCourt2 = bookingService.enterForOwner(owner, court2,
                new OwnerEntry(EIGHT_PM, EIGHT_PM.plusMinutes(30), BookingSource.WALKIN, null, null, null)).getId();

        Schedule s = schedules.schedule(owner, venueOf(courtId), DAY);

        assertThat(s.courts()).extracting(OwnerScheduleService.CourtColumn::name).containsExactly("Court 1", "Badminton 1");
        assertThat(s.courts().get(1).sports()).containsExactly("badminton");
        assertThat(s.courts().get(0).slots()).hasSize(20);
        assertThat(s.courts().get(1).slots()).hasSize(40);
        assertThat(s.bookings()).extracting(OwnerScheduleService.ScheduleBooking::courtId).containsExactly(courtId, court2);
        assertThat(s.courts().get(0).slots().stream().filter(x -> Long.valueOf(onCourt1).equals(x.bookingId()))).hasSize(1);
        assertThat(s.courts().get(1).slots().stream().filter(x -> Long.valueOf(onCourt2).equals(x.bookingId()))).hasSize(1);
        assertThat(s.courts().get(1).slots().stream().filter(x -> Long.valueOf(onCourt1).equals(x.bookingId()))).isEmpty();
    }

    @Test
    void anotherOwnersScheduleAndCustomersAreHidden() {
        long otherTurf = createCourt();

        assertThatThrownBy(() -> schedules.schedule(owner, venueOf(otherTurf), DAY)).isInstanceOf(VenueNotFoundException.class);
        assertThatThrownBy(() -> customers.list(owner, venueOf(otherTurf))).isInstanceOf(VenueNotFoundException.class);
    }

    // --- regulars ---

    @Test
    void regularsAreSuggestedFromCompletedBookingsInTheLast30Days() throws Exception {
        long bob = createPlayer(2);
        long carol = createPlayer(3);
        playedDaysAgo(alice, 8, 1);      // 8 recent: suggested
        playedDaysAgo(bob, 7, 1);        // 7 recent: not yet
        playedDaysAgo(bob, 5, 40);       // too old to count as recent
        playedDaysAgo(carol, 9, 1);      // already waived: not suggested
        customers.setAdvanceWaived(owner, venueOf(courtId), carol, true);
        long upcoming = paidFor(bob, EIGHT_PM); // future booking: known, not "played"

        var result = customers.list(owner, venueOf(courtId));

        assertThat(result.suggestionMinBookings()).isEqualTo(8);
        assertThat(result.customers()).extracting(Regular::playerId).containsExactly(alice, carol, bob);
        Regular a = result.customers().get(0);
        assertThat(a.suggested()).isTrue();
        assertThat(a.recentPlayed()).isEqualTo(8);
        Regular c = result.customers().get(1);
        assertThat(c.advanceWaived()).isTrue();
        assertThat(c.suggested()).isFalse();
        Regular b = result.customers().get(2);
        assertThat(b.recentPlayed()).isEqualTo(7);
        assertThat(b.totalPlayed()).isEqualTo(12);
        assertThat(b.suggested()).isFalse();
        assertThat(upcoming).isPositive();

        mvc.perform(get("/api/owner/venues/{id}/customers", venueOf(courtId)).header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[0].name").value("Alice"))
                .andExpect(jsonPath("$.customers[0].suggested").value(true));
    }

    @Test
    void noShowsAndCancellationsDoNotCountAsPlayed() {
        playedDaysAgo(alice, 8, 1);
        jdbc.sql("UPDATE bookings SET status = 'NO_SHOW' WHERE id = (SELECT min(id) FROM bookings)").update();

        Regular a = customers.list(owner, venueOf(courtId)).customers().getFirst();

        assertThat(a.recentPlayed()).isEqualTo(7);
        assertThat(a.noShowCount()).isEqualTo(1);
        assertThat(a.suggested()).isFalse();
    }

    // --- helpers ---

    private long paid(OffsetDateTime start) {
        return paidFor(alice, start);
    }

    private long paidFor(long player, OffsetDateTime start) {
        long id = bookingService.hold(courtId, player, start, start.plusHours(1)).getId();
        checkout.payAdvance(id, player);
        return id;
    }

    /**
     * {@code count} COMPLETED online bookings, one per day, starting {@code daysAgo} days back.
     * Each player gets a different hour so their rows never overlap.
     */
    private void playedDaysAgo(long player, int count, int daysAgo) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            INSERT INTO bookings (court_id, player_id, start_at, end_at, status, source, total_price,
                                                  advance_required)
                            VALUES (:courtId, :player, now() - make_interval(days => :d, hours => :h + 1),
                                    now() - make_interval(days => :d, hours => :h), 'COMPLETED', 'ONLINE', 100000, 20000)
                            """)
                    .param("courtId", courtId)
                    .param("player", player)
                    .param("d", daysAgo + i)
                    .param("h", (int) player * 2)
                    .update();
        }
    }
}
