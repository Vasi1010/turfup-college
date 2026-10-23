package com.turfup.booking;

import com.turfup.PostgresTestBase;
import com.turfup.pricing.PriceNotConfiguredException;
import com.turfup.venue.InvalidSlotException;
import com.turfup.venue.InvalidSlotException.Reason;
import com.turfup.venue.CourtNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * hold() validates and prices the slot. Turf: 6 AM to 2 AM, 60-minute slots, ₹800/hr until 5 PM and
 * ₹1000/hr after, Saturday evenings ₹1200/hr, Sunday ₹1500/hr all day.
 */
class BookingSlotValidationTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime TUESDAY = OffsetDateTime.of(2030, 1, 15, 0, 0, 0, 0, IST);
    private static final OffsetDateTime SATURDAY = OffsetDateTime.of(2030, 1, 19, 0, 0, 0, 0, IST);
    private static final OffsetDateTime SUNDAY = SATURDAY.plusDays(1);

    @Autowired
    BookingService bookingService;

    long courtId;
    long alice;

    @BeforeEach
    void setUp() {
        assertThat(TUESDAY.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(SATURDAY.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        courtId = createCourt();
        addStandardPriceRules(courtId);
        addPriceRule(courtId, DayOfWeek.SATURDAY.getValue(), 1020, 1560, 120_000);
        addPriceRule(courtId, DayOfWeek.SUNDAY.getValue(), 360, 1560, 150_000);
        alice = createPlayer(1);
    }

    // --- pricing stored on the booking ---

    @Test
    void slotEndingAtFivePmIsChargedDayRate() {
        Booking b = hold(TUESDAY.withHour(16), 1);

        assertThat(b.getTotalPrice()).isEqualTo(80_000);
    }

    @Test
    void slotStartingAtFivePmIsChargedEveningRate() {
        Booking b = hold(TUESDAY.withHour(17), 1);

        assertThat(b.getTotalPrice()).isEqualTo(100_000);
    }

    @Test
    void halfHourSlotsAcrossFivePmAreSplitProportionally() {
        long halfHourTurf = createCourt(360, 1560, 30);
        addStandardPriceRules(halfHourTurf);

        Booking b = bookingService.hold(halfHourTurf, alice,
                TUESDAY.withHour(16).withMinute(30), TUESDAY.withHour(17).withMinute(30));

        assertThat(b.getTotalPrice()).isEqualTo(40_000 + 50_000);
    }

    @Test
    void saturdayEveningUsesWeekendRule() {
        assertThat(hold(SATURDAY.withHour(20), 1).getTotalPrice()).isEqualTo(120_000);
    }

    @Test
    void saturdayAfternoonFallsBackToGeneralRule() {
        assertThat(hold(SATURDAY.withHour(15), 1).getTotalPrice()).isEqualTo(80_000);
    }

    @Test
    void slotAfterMidnightIsPricedOnThePreviousOperatingDay() {
        // Sunday 1-2 AM is Saturday night: Saturday's ₹1200, not Sunday's ₹1500.
        assertThat(hold(SUNDAY.withHour(1), 1).getTotalPrice()).isEqualTo(120_000);
    }

    @Test
    void slotCrossingMidnightIsPricedOnItsOperatingDay() {
        // Saturday 11 PM - Sunday 1 AM: two hours at Saturday's ₹1200.
        assertThat(hold(SATURDAY.withHour(23), 2).getTotalPrice()).isEqualTo(240_000);
    }

    @Test
    void slotEndingExactlyAtClosingIsAllowed() {
        assertThat(hold(SUNDAY.withHour(1), 1).getEndAt().toInstant())
                .isEqualTo(SUNDAY.withHour(2).toInstant());
    }

    // --- rejected slots ---

    @Test
    void slotInThePastIsRejected() {
        OffsetDateTime past = OffsetDateTime.of(2020, 1, 14, 20, 0, 0, 0, IST);

        assertRejected(past, past.plusHours(1), Reason.PAST);
    }

    @Test
    void slotStartingOffTheGridIsRejected() {
        assertRejected(TUESDAY.withHour(20).withMinute(30), TUESDAY.withHour(21).withMinute(30), Reason.MISALIGNED);
    }

    @Test
    void slotWithPartialDurationIsRejected() {
        assertRejected(TUESDAY.withHour(20), TUESDAY.withHour(20).withMinute(30), Reason.MISALIGNED);
    }

    @Test
    void slotWithSecondsIsRejected() {
        assertRejected(TUESDAY.withHour(20).withSecond(1), TUESDAY.withHour(21).withSecond(1), Reason.MISALIGNED);
    }

    @Test
    void slotBeforeOpeningIsRejected() {
        assertRejected(TUESDAY.withHour(5), TUESDAY.withHour(6), Reason.OUTSIDE_HOURS);
    }

    @Test
    void slotInTheClosedHoursAfterMidnightIsRejected() {
        assertRejected(TUESDAY.withHour(3), TUESDAY.withHour(4), Reason.OUTSIDE_HOURS);
    }

    @Test
    void slotRunningPastClosingIsRejected() {
        assertRejected(TUESDAY.withHour(1), TUESDAY.withHour(3), Reason.OUTSIDE_HOURS);
    }

    @Test
    void unpricedSlotIsRejected() {
        long bareTurf = createCourt();
        addPriceRule(bareTurf, null, 360, 1020, 80_000);

        assertThatThrownBy(() -> bookingService.hold(bareTurf, alice, TUESDAY.withHour(17), TUESDAY.withHour(18)))
                .isInstanceOf(PriceNotConfiguredException.class);
        assertThat(bookingCount()).isZero();
    }

    @Test
    void unknownTurfIsRejected() {
        assertThatThrownBy(() -> bookingService.hold(999_999, alice, TUESDAY.withHour(17), TUESDAY.withHour(18)))
                .isInstanceOf(CourtNotFoundException.class);
    }

    private Booking hold(OffsetDateTime start, int hours) {
        return bookingService.hold(courtId, alice, start, start.plusHours(hours));
    }

    private void assertRejected(OffsetDateTime start, OffsetDateTime end, Reason reason) {
        assertThatThrownBy(() -> bookingService.hold(courtId, alice, start, end))
                .isInstanceOfSatisfying(InvalidSlotException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
        assertThat(bookingCount()).isZero();
    }

    private long bookingCount() {
        return jdbc.sql("SELECT count(*) FROM bookings").query(Long.class).single();
    }
}
