package com.turfup.demo;

import com.turfup.PostgresTestBase;
import com.turfup.booking.BookingService;
import com.turfup.booking.RecurringBookingService;
import com.turfup.payment.CheckoutService;
import com.turfup.payment.PaymentService;
import com.turfup.payment.PaymentsProperties;
import com.turfup.pricing.PricingService;
import com.turfup.settings.AppSettings;
import com.turfup.venue.VenueApprovalService;
import com.turfup.venue.VenueCustomerService;
import com.turfup.venue.VenueCustomerService.Regular;
import com.turfup.venue.VenueSetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The showcase seed for owner demos. It is placed relative to the clock, so these checks hold at any hour.
 */
class DemoShowcaseTest extends PostgresTestBase {

    @Autowired PasswordEncoder passwords;
    @Autowired VenueSetupService venueSetup;
    @Autowired VenueApprovalService approvals;
    @Autowired ApplicationContext context;
    @Autowired BookingService bookings;
    @Autowired CheckoutService checkout;
    @Autowired PaymentService payments;
    @Autowired RecurringBookingService recurring;
    @Autowired VenueCustomerService customers;
    @Autowired PricingService pricing;
    @Autowired PaymentsProperties paymentsProps;
    @Autowired AppSettings settings;

    DemoShowcase showcase;
    long venueId;
    long ownerId;

    @BeforeEach
    void seed() {
        new DemoDataSeeder(jdbc, passwords, venueSetup, approvals, context).seed();
        showcase = new DemoShowcase(jdbc, passwords, bookings, checkout, payments, recurring, customers, pricing,
                paymentsProps, settings);
        showcase.seed();
        ownerId = jdbc.sql("SELECT id FROM users WHERE phone = :phone")
                .param("phone", DemoDataSeeder.OWNER_PHONE).query(Long.class).single();
        venueId = jdbc.sql("SELECT id FROM venues WHERE owner_id = :ownerId")
                .param("ownerId", ownerId).query(Long.class).single();
    }

    @Test
    void runsOnceOnAFreshVenue() {
        long before = count("SELECT count(*) FROM bookings");
        showcase.seed();
        assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(before);
    }

    @Test
    void todayAndTomorrowMixOnlineWalkInAndPhoneBookings() {
        Map<String, Long> bySource = jdbc.sql("""
                        WITH today AS (
                            SELECT (now() AT TIME ZONE 'Asia/Kolkata' - interval '6 hours')::date AS d
                        )
                        SELECT source, count(*) AS n FROM bookings, today
                        WHERE start_at >= today.d::timestamp AT TIME ZONE 'Asia/Kolkata'
                          AND start_at < (today.d + 2)::timestamp AT TIME ZONE 'Asia/Kolkata'
                        GROUP BY source
                        """)
                .query((rs, i) -> Map.entry(rs.getString("source"), rs.getLong("n")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(bySource.get("ONLINE")).isGreaterThanOrEqualTo(10);
        assertThat(bySource.get("WALKIN")).isGreaterThanOrEqualTo(3);
        assertThat(bySource.get("PHONE")).isGreaterThanOrEqualTo(8);
        assertThat(count("SELECT count(*) FROM bookings WHERE walkin_name = 'Walk-in'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bookings WHERE status NOT IN ('CONFIRMED', 'COMPLETED')")).isZero();
    }

    @Test
    void onlineBookingsPaidTheirAdvanceUnlessWaived() {
        assertThat(count("""
                SELECT count(*) FROM bookings b
                WHERE b.source = 'ONLINE' AND b.advance_required > 0
                  AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.booking_id = b.id AND p.kind = 'ADVANCE'
                                    AND p.status = 'SUCCEEDED' AND p.amount = b.advance_required)
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM bookings b JOIN users u ON u.id = b.player_id
                WHERE b.source = 'ONLINE' AND u.phone = '7000000001' AND b.advance_required > 0
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM bookings b JOIN users u ON u.id = b.player_id
                WHERE b.source = 'ONLINE' AND u.phone = '7000000001'
                """)).isEqualTo(2);
    }

    @Test
    void pricesFollowTheRates() {
        assertThat(count("""
                SELECT count(*) FROM bookings b JOIN courts c ON c.id = b.court_id
                WHERE b.total_price <> CASE
                    WHEN extract(hour FROM b.start_at AT TIME ZONE 'Asia/Kolkata') BETWEEN 6 AND 16
                        THEN CASE WHEN c.name = 'Main turf' THEN 80000 ELSE 30000 END
                    ELSE CASE WHEN c.name = 'Main turf' THEN 100000 ELSE 40000 END
                END
                """)).isZero();
    }

    @Test
    void noTwoBookingsShareACourtHour() {
        assertThat(count("""
                SELECT count(*) FROM bookings a JOIN bookings b
                  ON a.court_id = b.court_id AND a.id < b.id
                 AND tstzrange(a.start_at, a.end_at) && tstzrange(b.start_at, b.end_at)
                """)).isZero();
    }

    @Test
    void twoRegularsOneWaivedOneSuggested() {
        Map<String, Regular> regulars = customers.list(ownerId, venueId).customers().stream()
                .collect(Collectors.toMap(Regular::name, r -> r));
        assertThat(regulars.get("Rahul Patil").advanceWaived()).isTrue();
        assertThat(regulars.get("Sameer Khan").advanceWaived()).isFalse();
        assertThat(regulars.get("Sameer Khan").suggested()).isTrue();
        assertThat(regulars.values().stream().filter(r -> r.advanceWaived() || r.suggested())).hasSize(2);
        assertThat(passwords.matches(DemoShowcase.PLAYER_PASSWORD, jdbc.sql(
                "SELECT password_hash FROM users WHERE phone = '7000000002'").query(String.class).single())).isTrue();
    }

    @Test
    void standingTuesdayAndFriday8pmSlotRunsAheadAndBehind() {
        List<RecurringBookingService.RecurringView> slots = recurring.list(ownerId, venueId);
        assertThat(slots).extracting(RecurringBookingService.RecurringView::dayOfWeek).containsExactlyInAnyOrder(2, 5);
        assertThat(slots).allSatisfy(s -> {
            assertThat(s.playerName()).isEqualTo("Rahul Patil");
            assertThat(s.courtName()).isEqualTo("Main turf");
            assertThat(s.startMinute()).isEqualTo(1200);
            assertThat(s.upcoming()).isGreaterThanOrEqualTo(3);
        });
        assertThat(count("""
                SELECT count(*) FROM bookings WHERE recurring_id IS NOT NULL AND start_at < now()
                """)).isGreaterThanOrEqualTo(7);
    }

    @Test
    void onePlayedBookingStillHasItsBalanceDue() {
        List<String> due = jdbc.sql("""
                        SELECT coalesce(b.walkin_name, u.name) FROM bookings b LEFT JOIN users u ON u.id = b.player_id
                        WHERE b.end_at <= now() AND b.total_price > b.advance_required
                          AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.booking_id = b.id AND p.kind = 'BALANCE')
                        """)
                .query(String.class).list();
        boolean ganeshPlayed = count("""
                SELECT count(*) FROM bookings WHERE walkin_name = 'Ganesh Wagh' AND end_at <= now()
                """) > 0;
        assertThat(due).isEqualTo(ganeshPlayed ? List.of("Ganesh Wagh") : List.of());
    }

    @Test
    void leavesFreeEveningSlotsTomorrow() {
        // Tomorrow's operating day: 7 PM, 10 PM, midnight and 1 AM on the main turf stay open.
        long taken = count("""
                WITH today AS (
                    SELECT (now() AT TIME ZONE 'Asia/Kolkata' - interval '6 hours')::date AS d
                )
                SELECT count(*) FROM bookings b JOIN courts c ON c.id = b.court_id, today
                WHERE c.name = 'Main turf' AND b.status IN ('HELD', 'CONFIRMED')
                  AND b.start_at IN (SELECT ((today.d + 1) + make_interval(hours => h)) AT TIME ZONE 'Asia/Kolkata'
                                     FROM unnest(ARRAY[19, 22, 24, 25]) AS h)
                """);
        assertThat(taken).isZero();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
