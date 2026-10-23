package com.turfup.booking;

import com.turfup.PostgresTestBase;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.venue.VenueCustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CONFIRMED -> COMPLETED a day after the slot ends; owners keep that day to mark no-shows. */
class CompletionJobTest extends PostgresTestBase {

    @Autowired
    CompletionJob job;

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService payments;

    @Autowired
    VenueCustomerService customers;

    long courtId;
    long owner;
    long player;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
        owner = ownerOf(courtId);
        player = createPlayer(1);
    }

    @Test
    void completesConfirmedBookingsOnlyOnceADayHasPassedSinceTheSlotEnded() {
        long dayOld = booking("CONFIRMED", 26);      // ended 25 h ago
        long fresh = booking("CONFIRMED", 24);       // ended 23 h ago: the owner can still mark a no-show
        long upcoming = booking("CONFIRMED", -48);   // in two days
        long noShow = booking("NO_SHOW", 50);
        long expired = booking("EXPIRED", 50);

        assertThat(job.completeFinishedBookings()).isEqualTo(1);

        assertThat(statusOf(dayOld)).isEqualTo("COMPLETED");
        assertThat(statusOf(fresh)).isEqualTo("CONFIRMED");
        assertThat(statusOf(upcoming)).isEqualTo("CONFIRMED");
        assertThat(statusOf(noShow)).isEqualTo("NO_SHOW");
        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        assertThat(job.completeFinishedBookings()).isZero();
    }

    @Test
    void noShowCanBeMarkedDuringTheDayButNotAfterCompletion() {
        long fresh = booking("CONFIRMED", 24);
        long old = booking("CONFIRMED", 30);
        job.completeFinishedBookings();

        assertThat(bookingService.markNoShow(owner, fresh).getStatus()).isEqualTo(BookingStatus.NO_SHOW);
        assertThatThrownBy(() -> bookingService.markNoShow(owner, old))
                .isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void balanceCanStillBeReceivedAfterCompletion() {
        long old = booking("CONFIRMED", 30);
        job.completeFinishedBookings();

        var receipt = payments.recordBalanceReceived(owner, old, PaymentMethod.CASH);

        assertThat(receipt.amount()).isEqualTo(80_000);
    }

    @Test
    void delayComesFromSettings() {
        long twoHoursAgo = booking("CONFIRMED", 3);
        jdbc.sql("UPDATE app_settings SET value = '1' WHERE key = 'complete_after_hours'").update();
        try {
            assertThat(job.completeFinishedBookings()).isEqualTo(1);
            assertThat(statusOf(twoHoursAgo)).isEqualTo("COMPLETED");
        } finally {
            jdbc.sql("UPDATE app_settings SET value = '24' WHERE key = 'complete_after_hours'").update();
        }
    }

    @Test
    void regularCountsUseCompletedBookings() {
        for (int i = 0; i < 8; i++) {
            booking("CONFIRMED", 30 + i * 24); // eight games, all over a day ago, not yet completed
        }
        assertThat(customers.list(owner, venueOf(courtId)).customers().getFirst().recentPlayed()).isZero();

        job.completeFinishedBookings();

        var regular = customers.list(owner, venueOf(courtId)).customers().getFirst();
        assertThat(regular.recentPlayed()).isEqualTo(8);
        assertThat(regular.suggested()).isTrue();
    }

    /** An online booking for {@code player} whose one-hour slot started {@code hoursAgo} hours ago. */
    private long booking(String status, int hoursAgo) {
        return jdbc.sql("""
                        INSERT INTO bookings (court_id, player_id, start_at, end_at, status, source, total_price,
                                              advance_required, hold_expires_at)
                        VALUES (:courtId, :player, now() - make_interval(hours => :h),
                                now() - make_interval(hours => :h) + interval '1 hour', :status, 'ONLINE', 100000, 20000,
                                CASE WHEN :status = 'HELD' THEN now() END)
                        RETURNING id
                        """)
                .param("courtId", courtId)
                .param("player", player)
                .param("h", hoursAgo)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId).query(String.class).single();
    }
}
