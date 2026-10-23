package com.turfup.availability;

import com.turfup.PostgresTestBase;
import com.turfup.booking.BookingService;
import com.turfup.venue.CourtHours;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Turf: 6 AM to 2 AM, 60-minute slots, standard tariff. 2030-01-15 is a Tuesday. */
class AvailabilityTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final LocalDate DAY = LocalDate.of(2030, 1, 15);

    @Autowired
    AvailabilityService availability;

    @Autowired
    BookingService bookingService;

    @Autowired
    MockMvc mvc;

    long courtId;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
    }

    @Test
    void listsEverySlotOfTheOperatingDayWithItsPrice() {
        List<SlotView> slots = availability.slotsFor(courtId, DAY).slots();

        assertThat(slots).hasSize(20); // 6 AM to 2 AM
        assertThat(slots).allMatch(s -> s.status() == SlotStatus.AVAILABLE);
        assertThat(slots.getFirst().start()).isEqualTo(at(DAY, 6));
        assertThat(slotAt(slots, at(DAY, 16)).price()).isEqualTo(80_000);
        assertThat(slotAt(slots, at(DAY, 17)).price()).isEqualTo(100_000);
        // The last slot is 1-2 AM on the next calendar date.
        assertThat(slots.getLast().start()).isEqualTo(at(DAY.plusDays(1), 1));
        assertThat(slots.getLast().end()).isEqualTo(at(DAY.plusDays(1), 2));
    }

    @Test
    void heldConfirmedAndExpiredBookingsAreReflected() {
        long alice = createPlayer(1);
        long bob = createPlayer(2);
        long carol = createPlayer(3);
        bookingService.hold(courtId, alice, at(DAY, 18), at(DAY, 19));
        long confirmed = bookingService.hold(courtId, bob, at(DAY, 20), at(DAY, 22)).getId();
        jdbc.sql("UPDATE bookings SET status = 'CONFIRMED', hold_expires_at = NULL WHERE id = :id")
                .param("id", confirmed).update();
        long expired = bookingService.hold(courtId, carol, at(DAY, 7), at(DAY, 8)).getId();
        jdbc.sql("UPDATE bookings SET hold_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", expired).update();

        List<SlotView> slots = availability.slotsFor(courtId, DAY).slots();

        assertThat(slotAt(slots, at(DAY, 18)).status()).isEqualTo(SlotStatus.HELD);
        assertThat(slotAt(slots, at(DAY, 20)).status()).isEqualTo(SlotStatus.BOOKED);
        assertThat(slotAt(slots, at(DAY, 21)).status()).isEqualTo(SlotStatus.BOOKED);
        assertThat(slotAt(slots, at(DAY, 7)).status()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(slotAt(slots, at(DAY, 19)).status()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void holdInsidePaymentGraceWindowStillShowsHeld() {
        long alice = createPlayer(1);
        long id = bookingService.hold(courtId, alice, at(DAY, 18), at(DAY, 19)).getId();
        jdbc.sql("""
                        UPDATE bookings SET hold_expires_at = now() - interval '1 minute',
                                            payment_started_at = now() - interval '3 minutes'
                        WHERE id = :id
                        """)
                .param("id", id).update();

        assertThat(slotAt(availability.slotsFor(courtId, DAY).slots(), at(DAY, 18)).status())
                .isEqualTo(SlotStatus.HELD);
    }

    @Test
    void bookingOnTheNextOperatingDayDoesNotAffectThisDay() {
        // 6-7 AM on the 16th is not part of the 15th's operating day (which ends at 2 AM on the 16th).
        bookingService.hold(courtId, createPlayer(1), at(DAY.plusDays(1), 6), at(DAY.plusDays(1), 7));

        assertThat(availability.slotsFor(courtId, DAY).slots())
                .allMatch(s -> s.status() == SlotStatus.AVAILABLE);
    }

    @Test
    void pastDayHasNoBookableSlots() {
        LocalDate past = LocalDate.now(CourtHours.IST).minusDays(3);

        assertThat(availability.slotsFor(courtId, past).slots()).isEmpty();
    }

    @Test
    void unpricedSlotsAreLeftOut() {
        long bareTurf = createCourt();
        addPriceRule(bareTurf, null, 360, 1020, 80_000);

        assertThat(availability.slotsFor(bareTurf, DAY).slots()).hasSize(11); // 6 AM to 5 PM
    }

    @Test
    void endpointReturnsSlotsAsJson() throws Exception {
        mvc.perform(get("/api/courts/{id}/slots", courtId).param("date", "2030-01-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courtId").value(courtId))
                .andExpect(jsonPath("$.date").value("2030-01-15"))
                .andExpect(jsonPath("$.slotMinutes").value(60))
                .andExpect(jsonPath("$.slots.length()").value(20))
                .andExpect(jsonPath("$.slots[11].start").value("2030-01-15T17:00:00+05:30"))
                .andExpect(jsonPath("$.slots[11].end").value("2030-01-15T18:00:00+05:30"))
                .andExpect(jsonPath("$.slots[11].price").value(100_000))
                .andExpect(jsonPath("$.slots[11].status").value("available"));
    }

    @Test
    void endpointReturns404ForUnknownTurf() throws Exception {
        mvc.perform(get("/api/courts/{id}/slots", 999_999).param("date", "2030-01-15"))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpointReturns400ForBadDate() throws Exception {
        mvc.perform(get("/api/courts/{id}/slots", courtId).param("date", "15-01-2030"))
                .andExpect(status().isBadRequest());
    }

    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atOffset(IST);
    }

    private static SlotView slotAt(List<SlotView> slots, OffsetDateTime start) {
        return slots.stream()
                .filter(s -> s.start().isEqual(start))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No slot starting at " + start));
    }
}
