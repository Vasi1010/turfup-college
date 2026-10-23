package com.turfup.venue;

import com.turfup.venue.InvalidSlotException.Reason;
import com.turfup.venue.CourtHours.Booker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Time rules with a fixed clock. Venue open 6 AM to 2 AM, court with 60-minute slots. */
class CourtHoursTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final CourtHours HOURS = new CourtHours(360, 1560, 60);
    private static final LocalDate DAY = LocalDate.of(2030, 1, 15);

    @Test
    void currentOperatingDayIsTheOneThatMostRecentlyOpened() {
        assertThat(HOURS.currentOperatingDate(at(DAY, 6, 0))).isEqualTo(DAY);
        assertThat(HOURS.currentOperatingDate(at(DAY, 23, 0))).isEqualTo(DAY);
        // After midnight, before closing: still the 15th.
        assertThat(HOURS.currentOperatingDate(at(DAY.plusDays(1), 1, 30))).isEqualTo(DAY);
        // After closing at 2 AM but before opening at 6 AM: still the 15th, so its slots can be logged.
        assertThat(HOURS.currentOperatingDate(at(DAY.plusDays(1), 3, 0))).isEqualTo(DAY);
        assertThat(HOURS.currentOperatingDate(at(DAY.plusDays(1), 6, 0))).isEqualTo(DAY.plusDays(1));
    }

    @Test
    void ownerMayEnterStartedAndFinishedSlotsOfTheCurrentOperatingDay() {
        OffsetDateTime now = at(DAY, 20, 30);

        assertThat(HOURS.validateSlot(at(DAY, 20, 0), at(DAY, 21, 0), now, Booker.OWNER).startMinute())
                .isEqualTo(1200); // in progress
        assertThat(HOURS.validateSlot(at(DAY, 6, 0), at(DAY, 7, 0), now, Booker.OWNER).startMinute())
                .isEqualTo(360);  // first slot of the day, long finished
        assertThat(HOURS.validateSlot(at(DAY.plusDays(3), 20, 0), at(DAY.plusDays(3), 21, 0), now, Booker.OWNER)
                .operatingDate()).isEqualTo(DAY.plusDays(3)); // future days still fine
    }

    @Test
    void ownerCanLogLastNightsLateSlotAfterClosing() {
        OffsetDateTime threeAm = at(DAY.plusDays(1), 3, 0);

        OperatingSlot slot = HOURS.validateSlot(at(DAY.plusDays(1), 1, 0), at(DAY.plusDays(1), 2, 0), threeAm, Booker.OWNER);

        assertThat(slot.operatingDate()).isEqualTo(DAY);
        assertThat(slot.startMinute()).isEqualTo(1500);
    }

    @Test
    void finishedSlotOfAVenueThatOpenedNinetyMinutesAgoIsLoggableAtEveryMinuteOfTheDay() {
        // The construction OwnerEntryTest.ownerCanLogAFinishedSlotFromEarlierToday uses against the real clock,
        // checked here for all 1440 minutes, including just after midnight.
        for (int minute = 0; minute < 1440; minute++) {
            OffsetDateTime now = DAY.atStartOfDay().atOffset(IST).plusMinutes(minute);
            OffsetDateTime openedAt = now.minusMinutes(90).truncatedTo(ChronoUnit.HOURS);
            int open = openedAt.getHour() * 60;
            CourtHours allDay = new CourtHours(open, open + 1440, 60);

            OperatingSlot slot = allDay.validateSlot(openedAt, openedAt.plusHours(1), now, Booker.OWNER);

            assertThat(slot.operatingDate()).as("at minute %d", minute).isEqualTo(allDay.currentOperatingDate(now));
            assertThat(openedAt.plusHours(1)).as("at minute %d", minute).isBefore(now);
        }
    }

    @Test
    void ownerCannotEnterEarlierOperatingDays() {
        OffsetDateTime now = at(DAY, 20, 30);

        assertThatThrownBy(() -> HOURS.validateSlot(at(DAY.minusDays(1), 20, 0), at(DAY.minusDays(1), 21, 0), now, Booker.OWNER))
                .isInstanceOfSatisfying(InvalidSlotException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.EARLIER_DAY));
        // 1 AM on the 15th belongs to the 14th's operating day.
        assertThatThrownBy(() -> HOURS.validateSlot(at(DAY, 1, 0), at(DAY, 2, 0), now, Booker.OWNER))
                .isInstanceOfSatisfying(InvalidSlotException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.EARLIER_DAY));
    }

    @Test
    void playerMayOnlyHoldSlotsThatHaveNotStarted() {
        OffsetDateTime now = at(DAY, 20, 30);

        assertThatThrownBy(() -> HOURS.validateSlot(at(DAY, 20, 0), at(DAY, 21, 0), now, Booker.PLAYER))
                .isInstanceOfSatisfying(InvalidSlotException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.PAST));
        assertThat(HOURS.validateSlot(at(DAY, 21, 0), at(DAY, 22, 0), now, Booker.PLAYER).startMinute()).isEqualTo(1260);
    }

    @Test
    void ownerEntriesStillNeedHoursAndAlignment() {
        OffsetDateTime now = at(DAY, 20, 30);

        assertThatThrownBy(() -> HOURS.validateSlot(at(DAY, 19, 30), at(DAY, 20, 30), now, Booker.OWNER))
                .isInstanceOfSatisfying(InvalidSlotException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.MISALIGNED));
        assertThatThrownBy(() -> HOURS.validateSlot(at(DAY.plusDays(1), 3, 0), at(DAY.plusDays(1), 4, 0), now, Booker.OWNER))
                .isInstanceOfSatisfying(InvalidSlotException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.OUTSIDE_HOURS));
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(IST);
    }
}
