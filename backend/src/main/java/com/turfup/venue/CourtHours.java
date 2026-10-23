package com.turfup.venue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * When a court can be booked: its venue's opening hours and the court's own slot size, in operating-day
 * minutes (see CLAUDE.md "Operating day"). Hours span at most 24 hours, so any instant belongs to at most one
 * operating day of a venue.
 */
public record CourtHours(int openMinute, int closeMinute, int slotMinutes) {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int MINUTES_PER_DAY = 1440;

    /** Who is booking, which decides how far back in time a slot may be. */
    public enum Booker {
        /** Online players: the slot must start in the future. */
        PLAYER,
        /**
         * Owner walk-in/phone entry: any slot on the current operating day (including slots already
         * started or finished, for late arrivals and after-the-fact logging) or later.
         */
        OWNER
    }

    /**
     * Checks that {@code [startAt, endAt)} is a bookable slot for {@code booker} and returns it in
     * operating-day terms: it lies within opening hours on one operating day, its start offset from
     * opening and its duration are multiples of {@code slotMinutes}, and it is not too far in the past
     * (see {@link Booker}). Pass the database clock as {@code now}.
     *
     * @throws InvalidSlotException      if any rule is broken
     * @throws IllegalArgumentException  if the range is empty
     */
    public OperatingSlot validateSlot(OffsetDateTime startAt, OffsetDateTime endAt, OffsetDateTime now, Booker booker) {
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
        if (booker == Booker.PLAYER && !startAt.isAfter(now)) {
            throw new InvalidSlotException(InvalidSlotException.Reason.PAST);
        }
        ZonedDateTime start = startAt.atZoneSameInstant(IST);
        Duration duration = Duration.between(startAt, endAt);
        if (start.getSecond() != 0 || start.getNano() != 0
                || duration.toSecondsPart() != 0 || duration.toNanosPart() != 0) {
            throw new InvalidSlotException(InvalidSlotException.Reason.MISALIGNED);
        }
        if (duration.toMinutes() > MINUTES_PER_DAY) {
            throw new InvalidSlotException(InvalidSlotException.Reason.OUTSIDE_HOURS);
        }
        int minutes = (int) duration.toMinutes();
        int minuteOfDay = start.getHour() * 60 + start.getMinute();

        OperatingSlot slot;
        if (fits(minuteOfDay, minutes)) {
            slot = new OperatingSlot(start.toLocalDate(), minuteOfDay, minuteOfDay + minutes);
        } else if (fits(minuteOfDay + MINUTES_PER_DAY, minutes)) {
            // After midnight, still inside the previous operating day's hours.
            int carried = minuteOfDay + MINUTES_PER_DAY;
            slot = new OperatingSlot(start.toLocalDate().minusDays(1), carried, carried + minutes);
        } else {
            throw new InvalidSlotException(InvalidSlotException.Reason.OUTSIDE_HOURS);
        }

        if ((slot.startMinute() - openMinute) % slotMinutes != 0 || minutes % slotMinutes != 0) {
            throw new InvalidSlotException(InvalidSlotException.Reason.MISALIGNED);
        }
        if (booker == Booker.OWNER && slot.operatingDate().isBefore(currentOperatingDate(now))) {
            throw new InvalidSlotException(InvalidSlotException.Reason.EARLIER_DAY);
        }
        return slot;
    }

    /**
     * The operating day that most recently opened at {@code now}. Before today's opening time this is
     * still yesterday's operating day, even after it has closed, so the owner can log its last slots.
     */
    public LocalDate currentOperatingDate(OffsetDateTime now) {
        ZonedDateTime t = now.atZoneSameInstant(IST);
        int minuteOfDay = t.getHour() * 60 + t.getMinute();
        return minuteOfDay >= openMinute ? t.toLocalDate() : t.toLocalDate().minusDays(1);
    }

    /** Every slot of {@code slotMinutes} from opening to closing on the given operating day. */
    public List<OperatingSlot> slotsOn(LocalDate operatingDate) {
        List<OperatingSlot> slots = new ArrayList<>();
        for (int m = openMinute; m + slotMinutes <= closeMinute; m += slotMinutes) {
            slots.add(new OperatingSlot(operatingDate, m, m + slotMinutes));
        }
        return slots;
    }

    private boolean fits(int startMinute, int minutes) {
        return startMinute >= openMinute && startMinute + minutes <= closeMinute;
    }
}
