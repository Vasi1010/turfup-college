package com.turfup.pricing;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prices in operating-day minutes. Standard tariff: ₹800/hr until 5 PM (1020), ₹1000/hr after. */
class PricingServiceTest extends PostgresTestBase {

    @Autowired
    PricingService pricing;

    long courtId;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
    }

    @Test
    void hourEndingAtFivePmIsDayRateAndHourStartingAtFivePmIsEveningRate() {
        assertThat(pricing.price(courtId, TUESDAY, 960, 1020)).isEqualTo(80_000);
        assertThat(pricing.price(courtId, TUESDAY, 1020, 1080)).isEqualTo(100_000);
    }

    @Test
    void minuteAt459IsDayRateAndMinuteAt500IsEveningRate() {
        // 4:59-5:01 PM: one minute at ₹800/hr + one at ₹1000/hr = (80000 + 100000) / 60 paise.
        assertThat(pricing.price(courtId, TUESDAY, 1019, 1021)).isEqualTo(3_000);
    }

    @Test
    void bookingSpanningTwoRatesIsSplitProportionally() {
        // 4:30-5:30 PM = half an hour at ₹800 + half an hour at ₹1000.
        assertThat(pricing.price(courtId, TUESDAY, 990, 1050)).isEqualTo(40_000 + 50_000);
    }

    @Test
    void dayRuleOverridesGeneralRuleOnlyWhereItApplies() {
        addPriceRule(courtId, SATURDAY.getValue(), 1020, 1560, 120_000);

        assertThat(pricing.price(courtId, SATURDAY, 1200, 1260)).isEqualTo(120_000); // Sat 8 PM
        assertThat(pricing.price(courtId, SATURDAY, 960, 1020)).isEqualTo(80_000);   // Sat 4 PM: general
        assertThat(pricing.price(courtId, SATURDAY, 990, 1050)).isEqualTo(40_000 + 60_000);
        assertThat(pricing.price(courtId, TUESDAY, 1200, 1260)).isEqualTo(100_000);  // weekday unaffected
        assertThat(pricing.price(courtId, SUNDAY, 1200, 1260)).isEqualTo(100_000);
    }

    @Test
    void afterMidnightUsesTheOperatingDaysRule() {
        addPriceRule(courtId, SATURDAY.getValue(), 1020, 1560, 120_000);
        addPriceRule(courtId, SUNDAY.getValue(), 0, 1560, 150_000);

        // Sunday 1-2 AM belongs to Saturday's operating day (minutes 1500..1560).
        assertThat(pricing.price(courtId, SATURDAY, 1500, 1560)).isEqualTo(120_000);
    }

    @Test
    void roundsHalfUpToWholePaise() {
        long oddTurf = createCourt(360, 1560, 1);
        addPriceRule(oddTurf, null, 0, 2880, 100);

        assertThat(pricing.price(oddTurf, TUESDAY, 600, 601)).isEqualTo(2);  // 1.67 paise
        assertThat(pricing.price(oddTurf, TUESDAY, 600, 618)).isEqualTo(30); // exactly 30
    }

    @Test
    void timeWithoutAnyRuleCannotBePriced() {
        long bareTurf = createCourt(360, 1560, 60);
        addPriceRule(bareTurf, null, 360, 1020, 80_000);

        assertThatThrownBy(() -> pricing.price(bareTurf, TUESDAY, 960, 1080))
                .isInstanceOf(PriceNotConfiguredException.class);
    }
}
