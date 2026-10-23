package com.turfup.schema;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Database-level rules for price rules, opening hours and recurring bookings (operating-day minutes). */
class OperatingDaySchemaTest extends PostgresTestBase {

    private static final int SATURDAY = 6;
    private static final int SUNDAY = 7;

    long courtId;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
    }

    // --- price_rules exclusion constraint ---

    @Test
    void overlappingGeneralRulesAreRejected() {
        insertPriceRule(null, 360, 1020, 80_000);

        assertViolates("price_rules_no_overlap", () -> insertPriceRule(null, 960, 1560, 100_000));
    }

    @Test
    void dayRuleMayOverlapGeneralRule() {
        insertPriceRule(null, 1020, 1560, 100_000);

        assertThatCode(() -> insertPriceRule(SATURDAY, 1020, 1560, 120_000)).doesNotThrowAnyException();
    }

    @Test
    void overlappingRulesForTheSameDayAreRejected() {
        insertPriceRule(SATURDAY, 1020, 1560, 120_000);

        assertViolates("price_rules_no_overlap", () -> insertPriceRule(SATURDAY, 1380, 1500, 150_000));
    }

    @Test
    void overlappingRulesForDifferentDaysAreAllowed() {
        insertPriceRule(SATURDAY, 1020, 1560, 120_000);

        assertThatCode(() -> insertPriceRule(SUNDAY, 1020, 1560, 120_000)).doesNotThrowAnyException();
    }

    @Test
    void adjacentGeneralRulesAtFivePmAreAllowed() {
        insertPriceRule(null, 360, 1020, 80_000);

        assertThatCode(() -> insertPriceRule(null, 1020, 1560, 100_000)).doesNotThrowAnyException();
    }

    @Test
    void priceRuleMayRunPastMidnightButNotPastSecondMidnight() {
        assertThatCode(() -> insertPriceRule(SATURDAY, 1380, 2880, 120_000)).doesNotThrowAnyException();

        assertViolates("price_rules_end_minute_check", () -> insertPriceRule(SUNDAY, 1380, 2881, 120_000));
        assertViolates("price_rules_range_check", () -> insertPriceRule(null, 600, 600, 80_000));
    }

    // --- turfs opening hours ---

    @Test
    void turfMayCloseAfterMidnightForAtMostTwentyFourHours() {
        assertThatCode(() -> updateHours(360, 1560)).doesNotThrowAnyException(); // 6 AM - 2 AM
        assertThatCode(() -> updateHours(0, 1440)).doesNotThrowAnyException();   // 24 hours

        assertViolates("venues_hours_check", () -> updateHours(360, 1801));      // over 24 hours
        assertViolates("venues_hours_check", () -> updateHours(1200, 360));      // closes before it opens
        assertViolates("venues_open_minute_check", () -> updateHours(1440, 1500)); // opens on the next day
    }

    // --- recurring_bookings ---

    @Test
    void recurringBookingMayCrossMidnightOnItsOperatingDay() {
        long player = createPlayer(1);

        assertThatCode(() -> insertRecurring(player, 1380, 1500)).doesNotThrowAnyException();
        assertViolates("recurring_bookings_range_check", () -> insertRecurring(player, 1500, 1380));
    }

    private void insertPriceRule(Integer dayOfWeek, int startMinute, int endMinute, long rate) {
        jdbc.sql("""
                        INSERT INTO price_rules (court_id, day_of_week, start_minute, end_minute, rate)
                        VALUES (:courtId, :day, :start, :end, :rate)
                        """)
                .param("courtId", courtId)
                .param("day", dayOfWeek, Types.SMALLINT)
                .param("start", startMinute)
                .param("end", endMinute)
                .param("rate", rate)
                .update();
    }

    private void updateHours(int openMinute, int closeMinute) {
        jdbc.sql("UPDATE venues SET open_minute = :open, close_minute = :close WHERE id = (SELECT venue_id FROM courts WHERE id = :id)")
                .param("open", openMinute)
                .param("close", closeMinute)
                .param("id", courtId)
                .update();
    }

    private void insertRecurring(long playerId, int startMinute, int endMinute) {
        jdbc.sql("""
                        INSERT INTO recurring_bookings (court_id, player_id, day_of_week, start_minute, end_minute, valid_from)
                        VALUES (:courtId, :playerId, :day, :start, :end, DATE '2030-01-01')
                        """)
                .param("courtId", courtId)
                .param("playerId", playerId)
                .param("day", SATURDAY)
                .param("start", startMinute)
                .param("end", endMinute)
                .update();
    }

    private static void assertViolates(String constraint, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    PSQLException pg = (PSQLException) e.getCause();
                    assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                });
    }
}
