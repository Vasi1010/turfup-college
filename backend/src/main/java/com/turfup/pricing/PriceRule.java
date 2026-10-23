package com.turfup.pricing;

/** A row of {@code price_rules}: operating-day minutes {@code [startMinute, endMinute)}, rate in paise per hour. */
public record PriceRule(Integer dayOfWeek, int startMinute, int endMinute, long rate) {

    boolean covers(int minute) {
        return startMinute <= minute && minute < endMinute;
    }
}
