package com.turfup.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12-V13 (turfs -> venues with courts) against a database that already has data, as production will. Each test
 * migrates its own fresh database to V11, seeds turfs, bookings, rules, standing slots, payments and regulars the
 * way the app wrote them, then migrates to the latest version and checks nothing was lost or changed meaning.
 * No Spring context: Flyway and JDBC only.
 */
class VenueMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private String url;
    private JdbcClient jdbc;

    /** A new empty database migrated to {@code version}. */
    private void freshDatabaseAt(String version) {
        String name = "migration_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSource(POSTGRES.getJdbcUrl())).sql("CREATE DATABASE " + name).update();
        url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        jdbc = JdbcClient.create(dataSource(url));
        flyway(version).migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target(target).load();
    }

    private static DriverManagerDataSource dataSource(String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The V11 schema with two turfs and everything that hangs off them. */
    private void seedV11() {
        sql("""
                INSERT INTO users (id, name, phone, password_hash, role) VALUES
                    (1, 'Owner A', '8000000001', 'x', 'OWNER'),
                    (2, 'Owner B', '8000000002', 'x', 'OWNER'),
                    (3, 'Rahul', '9000000001', 'x', 'PLAYER'),
                    (4, 'Sana', '9000000002', 'x', 'PLAYER');
                ALTER SEQUENCE users_id_seq RESTART WITH 5;

                INSERT INTO turfs (id, owner_id, name, area, address, sports, open_minute, close_minute, slot_minutes,
                                   advance_amount, refund_policy, approved, refund_full_hours_before, refunds_per_month,
                                   razorpay_account_id, razorpay_account_status)
                VALUES (1, 1, 'Green Arena', 'Cidco', 'N-1', '{Football, cricket}', 360, 1560, 60, 20000,
                        'Full refund 12 h ahead', true, 12, 2, 'acc_GreenArena0001', 'activated'),
                       (2, 2, 'Night Box', 'Garkheda', 'G-4', '{}', 1080, 1680, 30, 0, NULL, false, 24, 1, NULL, NULL);
                ALTER SEQUENCE turfs_id_seq RESTART WITH 3;

                INSERT INTO price_rules (turf_id, day_of_week, start_minute, end_minute, rate) VALUES
                    (1, NULL, 360, 1020, 80000), (1, NULL, 1020, 1560, 100000), (1, 6, 1020, 1560, 120000),
                    (2, NULL, 1080, 1680, 60000);

                INSERT INTO recurring_bookings (id, turf_id, player_id, day_of_week, start_minute, end_minute, valid_from)
                VALUES (1, 1, 3, 2, 1200, 1260, '2030-01-01');
                INSERT INTO recurring_skips (recurring_id, start_at, end_at, reason)
                VALUES (1, '2030-01-08 20:00+05:30', '2030-01-08 21:00+05:30', 'SLOT_TAKEN');

                -- Turf 1: a confirmed slot crossing midnight, a live hold, a standing-slot occurrence.
                INSERT INTO bookings (id, turf_id, player_id, start_at, end_at, status, source, total_price,
                                      advance_required, hold_expires_at, recurring_id)
                VALUES (1, 1, 3, '2030-01-04 23:00+05:30', '2030-01-05 01:00+05:30', 'CONFIRMED', 'ONLINE', 200000,
                        20000, NULL, NULL),
                       (2, 1, 4, '2030-01-05 18:00+05:30', '2030-01-05 19:00+05:30', 'HELD', 'ONLINE', 100000,
                        20000, now() + interval '5 minutes', NULL),
                       (3, 1, 3, '2030-01-15 20:00+05:30', '2030-01-15 21:00+05:30', 'CONFIRMED', 'PHONE', 100000,
                        0, NULL, 1);
                -- Turf 2: the same time as booking 1, which was always allowed (another turf).
                INSERT INTO bookings (id, turf_id, walkin_name, start_at, end_at, status, source, total_price,
                                      advance_required)
                VALUES (4, 2, 'Walk-in', '2030-01-04 23:00+05:30', '2030-01-05 01:00+05:30', 'CONFIRMED', 'WALKIN',
                        120000, 0);
                ALTER SEQUENCE bookings_id_seq RESTART WITH 5;

                INSERT INTO payments (booking_id, kind, amount, method, status, gateway_order_id, gateway_payment_id)
                VALUES (1, 'ADVANCE', 20000, 'SIMULATED', 'SUCCEEDED', 'sim_order_1', 'sim_pay_1');

                INSERT INTO turf_customers (turf_id, player_id, advance_waived, no_show_count, notes)
                VALUES (1, 3, true, 1, 'Tuesday regular');
                INSERT INTO subscriptions (turf_id, plan, status) VALUES (2, 'monthly', 'TRIAL');
                """);
    }

    @Test
    void everyTurfBecomesAVenueWithOneCourtKeepingItsIds() {
        freshDatabaseAt("11");
        seedV11();

        flyway("latest").migrate();

        assertThat(rows("SELECT id || ' ' || name || ' ' || area || ' ' || open_minute || '-' || close_minute"
                        + " || ' ' || approved || ' ' || refunds_per_month || ' ' || coalesce(razorpay_account_status, '-')"
                        + " FROM venues ORDER BY id"))
                .containsExactly("1 Green Arena Cidco 360-1560 true 2 activated", "2 Night Box Garkheda 1080-1680 false 1 -");
        assertThat(rows("SELECT id || ' ' || venue_id || ' ' || name || ' ' || slot_minutes || ' ' || advance_amount"
                        + " || ' ' || refund_full_hours_before || ' ' || coalesce(refund_policy, '-') || ' ' || active"
                        + " FROM courts ORDER BY id"))
                .containsExactly("1 1 Main 60 20000 12 Full refund 12 h ahead true", "2 2 Main 30 0 24 - true");
        // Mixed-case sports are normalized; a turf with none gets football.
        assertThat(rows("SELECT court_id || ' ' || sport FROM court_sports ORDER BY court_id, sport"))
                .containsExactly("1 cricket", "1 football", "2 football");
        // The moved columns are gone from venues.
        assertThat(rows("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'venues' AND column_name IN
                      ('sports', 'slot_minutes', 'advance_amount', 'refund_full_hours_before', 'refund_policy')
                """)).isEmpty();
        assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_name IN ('turfs', 'turf_customers')"))
                .isEmpty();
    }

    @Test
    void bookingsRulesAndStandingSlotsPointAtTheCourt() {
        freshDatabaseAt("11");
        seedV11();

        flyway("latest").migrate();

        assertThat(rows("SELECT id || ' ' || court_id || ' ' || status || ' ' || total_price FROM bookings ORDER BY id"))
                .containsExactly("1 1 CONFIRMED 200000", "2 1 HELD 100000", "3 1 CONFIRMED 100000", "4 2 CONFIRMED 120000");
        assertThat(rows("SELECT court_id || ' ' || coalesce(day_of_week, 0) || ' ' || start_minute || ' ' || rate"
                        + " FROM price_rules ORDER BY court_id, coalesce(day_of_week, 0), start_minute"))
                .containsExactly("1 0 360 80000", "1 0 1020 100000", "1 6 1020 120000", "2 0 1080 60000");
        assertThat(rows("SELECT id || ' ' || court_id FROM recurring_bookings")).containsExactly("1 1");
        assertThat(rows("SELECT recurring_id || ' ' || reason FROM recurring_skips")).containsExactly("1 SLOT_TAKEN");
        assertThat(rows("SELECT booking_id || ' ' || kind || ' ' || amount FROM payments")).containsExactly("1 ADVANCE 20000");
        assertThat(rows("SELECT venue_id || ' ' || player_id || ' ' || advance_waived || ' ' || no_show_count FROM venue_customers"))
                .containsExactly("1 3 true 1");
        assertThat(rows("SELECT venue_id || ' ' || status FROM subscriptions")).containsExactly("2 TRIAL");
    }

    @Test
    void doubleBookingIsStillRejectedPerCourtAndOtherCourtsAreIndependent() {
        freshDatabaseAt("11");
        seedV11();
        flyway("latest").migrate();

        // Same court, overlapping booking 1 (crosses midnight): rejected by the exclusion constraint.
        assertThatThrownBy(() -> sql("""
                INSERT INTO bookings (court_id, player_id, start_at, end_at, status, source, total_price, advance_required)
                VALUES (1, 4, '2030-01-05 00:00+05:30', '2030-01-05 01:00+05:30', 'CONFIRMED', 'ONLINE', 100000, 20000)
                """)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("bookings_no_overlap");

        // A new court at the same venue gets the next id and can be booked at that same time.
        long court = jdbc.sql("INSERT INTO courts (venue_id, name) VALUES (1, 'Court 2') RETURNING id")
                .query(Long.class).single();
        assertThat(court).isEqualTo(3);
        sql("""
                INSERT INTO bookings (court_id, player_id, start_at, end_at, status, source, total_price, advance_required)
                VALUES (3, 4, '2030-01-05 00:00+05:30', '2030-01-05 01:00+05:30', 'CONFIRMED', 'ONLINE', 100000, 20000)
                """);

        // Price rules may not overlap within a court, but each court has its own.
        assertThatThrownBy(() -> sql("INSERT INTO price_rules (court_id, start_minute, end_minute, rate) VALUES (1, 900, 1100, 1)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("price_rules_no_overlap");
        sql("INSERT INTO price_rules (court_id, start_minute, end_minute, rate) VALUES (3, 900, 1100, 1)");

        // A court belongs to a known sport only.
        assertThatThrownBy(() -> sql("INSERT INTO court_sports (court_id, sport) VALUES (3, 'kabaddi')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theNoShowTriggerAndTheHoldExpiryProcedureWorkOnCourts() {
        freshDatabaseAt("11");
        seedV11();
        flyway("latest").migrate();

        // A no-show on a second court of the venue counts at the venue.
        sql("INSERT INTO courts (id, venue_id, name) VALUES (10, 1, 'Court 2')");
        sql("""
                INSERT INTO bookings (id, court_id, player_id, start_at, end_at, status, source, total_price, advance_required)
                VALUES (10, 10, 3, '2030-01-06 18:00+05:30', '2030-01-06 19:00+05:30', 'CONFIRMED', 'ONLINE', 100000, 20000)
                """);
        sql("UPDATE bookings SET status = 'NO_SHOW' WHERE id = 10");
        assertThat(rows("SELECT venue_id || ' ' || player_id || ' ' || no_show_count FROM venue_customers"))
                .containsExactly("1 3 2");

        // The live hold (booking 2) expires through the procedure, which now locks courts.
        sql("UPDATE bookings SET hold_expires_at = now() - interval '1 minute' WHERE id = 2");
        Integer released = jdbc.sql("CALL release_expired_holds()").query(Integer.class).single();
        assertThat(released).isEqualTo(1);
        assertThat(rows("SELECT status FROM bookings WHERE id = 2")).containsExactly("EXPIRED");
    }

    @Test
    void anUnknownSportStopsTheMigrationAndChangesNothing() {
        freshDatabaseAt("11");
        seedV11();
        sql("UPDATE turfs SET sports = '{football, kabaddi}' WHERE id = 1");

        assertThatThrownBy(() -> flyway("latest").migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("Unknown sports on turfs: kabaddi (venue 1)");

        // V12 ran in one transaction: still the V11 schema, data untouched.
        assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_name IN ('turfs', 'venues', 'courts')"))
                .containsExactly("turfs");
        assertThat(rows("SELECT count(*)::text FROM bookings WHERE turf_id = 1")).containsExactly("3");
    }

    private void sql(String sql) {
        jdbc.sql(sql).update();
    }

    private List<String> rows(String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }
}
