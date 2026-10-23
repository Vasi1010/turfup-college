package com.turfup.booking;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expiry rules for HELD rows. Time is moved by rewriting timestamps relative to the database clock,
 * since expiry is always judged by Postgres now(). Grace window is the seeded 2 minutes.
 */
class BookingHoldExpiryTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
    private static final OffsetDateTime END = START.plusHours(1);

    @Autowired
    BookingService bookingService;

    long courtId;
    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        alice = createPlayer(1);
        bob = createPlayer(2);
    }

    @Test
    void holdExpiresAfterConfiguredHoldMinutesByDatabaseClock() {
        Booking held = bookingService.hold(courtId, alice, START, END);

        assertThat(held.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(held.getAdvanceRequired()).isEqualTo(20_000);
        // created_at defaults to now() in the same transaction, so this checks now() + hold_minutes.
        boolean fiveMinutesFromDbNow = jdbc.sql("""
                        SELECT hold_expires_at = created_at + interval '5 minutes' FROM bookings WHERE id = :id
                        """)
                .param("id", held.getId())
                .query(Boolean.class)
                .single();
        assertThat(fiveMinutesFromDbNow).isTrue();
    }

    @Test
    void expiredHoldDoesNotBlockNewHold() {
        Booking aliceHold = bookingService.hold(courtId, alice, START, END);
        moveHold(aliceHold.getId(), "now() - interval '1 second'", null);

        Booking bobHold = bookingService.hold(courtId, bob, START, END);

        assertThat(bobHold.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(statusOf(aliceHold.getId())).isEqualTo("EXPIRED");
    }

    @Test
    void holdWithPaymentStartedInsideGraceWindowStillBlocksSlot() {
        Booking aliceHold = bookingService.hold(courtId, alice, START, END);
        // Hold ran out 1 minute ago, but payment started during the hold: still inside the 2-minute grace.
        moveHold(aliceHold.getId(), "now() - interval '1 minute'", "now() - interval '3 minutes'");

        assertThatThrownBy(() -> bookingService.hold(courtId, bob, START, END))
                .isInstanceOf(SlotUnavailableException.class);
        assertThat(statusOf(aliceHold.getId())).isEqualTo("HELD");
    }

    @Test
    void holdWithPaymentStartedButGraceWindowOverDoesNotBlockSlot() {
        Booking aliceHold = bookingService.hold(courtId, alice, START, END);
        moveHold(aliceHold.getId(), "now() - interval '2 minutes 1 second'", "now() - interval '4 minutes'");

        Booking bobHold = bookingService.hold(courtId, bob, START, END);

        assertThat(bobHold.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(statusOf(aliceHold.getId())).isEqualTo("EXPIRED");
    }

    @Test
    void adjacentSlotsDoNotConflict() {
        bookingService.hold(courtId, alice, START, END);

        Booking next = bookingService.hold(courtId, bob, END, END.plusHours(1));

        assertThat(next.getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void playerWithActiveHoldCannotHoldAnotherSlot() {
        bookingService.hold(courtId, alice, START, END);

        assertThatThrownBy(() -> bookingService.hold(courtId, alice, END, END.plusHours(1)))
                .isInstanceOf(ActiveHoldExistsException.class)
                .hasMessageContaining("already has a slot on hold");
    }

    @Test
    void playersOwnExpiredHoldDoesNotBlockTheirNextHold() {
        Booking first = bookingService.hold(courtId, alice, START, END);
        moveHold(first.getId(), "now() - interval '1 second'", null);

        Booking second = bookingService.hold(courtId, alice, END, END.plusHours(1));

        assertThat(second.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(statusOf(first.getId())).isEqualTo("EXPIRED");
    }

    private void moveHold(long bookingId, String holdExpiresAtSql, String paymentStartedAtSql) {
        jdbc.sql("UPDATE bookings SET hold_expires_at = %s, payment_started_at = %s WHERE id = :id"
                        .formatted(holdExpiresAtSql, paymentStartedAtSql == null ? "NULL" : paymentStartedAtSql))
                .param("id", bookingId)
                .update();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id")
                .param("id", bookingId)
                .query(String.class)
                .single();
    }
}
