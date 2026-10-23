package com.turfup.pricing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.util.List;

/**
 * Computes booking prices from a court's {@code price_rules}. Prices are computed once at booking time and stored
 * on the booking; old bookings are never re-priced.
 */
@Service
public class PricingService {

    private final JdbcClient jdbc;

    public PricingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The rules for one operating weekday: that day's rules plus the general ones. */
    public DayPricing forDay(long courtId, DayOfWeek operatingDay) {
        List<PriceRule> rules = jdbc.sql("""
                        SELECT day_of_week, start_minute, end_minute, rate FROM price_rules
                        WHERE court_id = :courtId AND (day_of_week IS NULL OR day_of_week = :day)
                        """)
                .param("courtId", courtId)
                .param("day", operatingDay.getValue())
                .query(PriceRule.class)
                .list();
        return new DayPricing(
                rules.stream().filter(r -> r.dayOfWeek() != null).toList(),
                rules.stream().filter(r -> r.dayOfWeek() == null).toList());
    }

    /**
     * Price in paise for operating-day minutes {@code [startMinute, endMinute)} on {@code operatingDay}.
     *
     * @throws PriceNotConfiguredException if some minute has no rule
     */
    public long price(long courtId, DayOfWeek operatingDay, int startMinute, int endMinute) {
        return forDay(courtId, operatingDay).price(startMinute, endMinute);
    }
}
