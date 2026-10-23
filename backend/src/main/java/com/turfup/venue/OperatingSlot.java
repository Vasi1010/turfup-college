package com.turfup.venue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A time range on a venue's operating day: minutes from IST midnight of {@code operatingDate}, possibly
 * past 1440. Pricing and recurring rules use {@link #dayOfWeek()}, the operating day's weekday.
 */
public record OperatingSlot(LocalDate operatingDate, int startMinute, int endMinute) {

    public DayOfWeek dayOfWeek() {
        return operatingDate.getDayOfWeek();
    }

    public OffsetDateTime startAt() {
        return at(startMinute);
    }

    public OffsetDateTime endAt() {
        return at(endMinute);
    }

    private OffsetDateTime at(int minute) {
        return operatingDate.atStartOfDay(CourtHours.IST).plusMinutes(minute).toOffsetDateTime();
    }
}
