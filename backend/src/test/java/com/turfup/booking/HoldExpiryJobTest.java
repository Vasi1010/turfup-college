package com.turfup.booking;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The every-minute job and the release_expired_holds() procedure behind it. Expiry is judged by the database
 * clock, so tests move holds by rewriting timestamps relative to now(). Grace window: 2 minutes.
 */
class HoldExpiryJobTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);

    @Autowired
    HoldExpiryJob job;

    @Autowired
    BookingService bookingService;

    @Autowired
    PlatformTransactionManager transactions;

    long courtId;

    @BeforeEach
    void setUp() {
        courtId = createCourt();
        addStandardPriceRules(courtId);
    }

    @Test
    void releasesOnlyHoldsThatHaveExpiredByTheSharedRule() {
        long running = hold(1, 0);            // still inside its 5 minutes
        long lapsed = hold(2, 1);             // ran out, no payment started
        long inGrace = hold(3, 2);            // ran out 1 min ago, payment started in time: grace keeps it
        long graceOver = hold(4, 3);          // ran out 2 min 1 s ago with payment started: grace over
        move(lapsed, "now() - interval '1 second'", null);
        move(inGrace, "now() - interval '1 minute'", "now() - interval '3 minutes'");
        move(graceOver, "now() - interval '2 minutes 1 second'", "now() - interval '4 minutes'");

        int released = job.releaseExpiredHolds();

        assertThat(released).isEqualTo(2);
        assertThat(statusOf(running)).isEqualTo("HELD");
        assertThat(statusOf(lapsed)).isEqualTo("EXPIRED");
        assertThat(statusOf(inGrace)).isEqualTo("HELD");
        assertThat(statusOf(graceOver)).isEqualTo("EXPIRED");
        assertThat(job.releaseExpiredHolds()).isZero(); // idempotent
    }

    @Test
    void javaQueriesAndTheProcedureUseTheSameRule() {
        long inGrace = hold(1, 0);
        move(inGrace, "now() - interval '1 minute'", "now() - interval '3 minutes'");

        boolean expiredPerJava = jdbc.sql("SELECT %s FROM bookings WHERE id = :id".formatted(HoldExpirySql.IS_EXPIRED))
                .param("graceMinutes", 2)
                .param("id", inGrace)
                .query(Boolean.class)
                .single();
        job.releaseExpiredHolds();

        assertThat(expiredPerJava).isFalse();
        assertThat(statusOf(inGrace)).isEqualTo("HELD");
    }

    @Test
    void releasedSlotCanBeBookedAgain() {
        long lapsed = hold(1, 0);
        move(lapsed, "now() - interval '1 second'", null);
        job.releaseExpiredHolds();

        Booking again = bookingService.hold(courtId, createPlayer(2), START, START.plusHours(1));

        assertThat(again.getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void worksAcrossTurfs() {
        long otherTurf = createCourt();
        addStandardPriceRules(otherTurf);
        long here = hold(1, 0);
        long there = bookingService.hold(otherTurf, createPlayer(2), START, START.plusHours(1)).getId();
        move(here, "now() - interval '1 second'", null);
        move(there, "now() - interval '1 second'", null);

        assertThat(job.releaseExpiredHolds()).isEqualTo(2);
        assertThat(statusOf(here)).isEqualTo("EXPIRED");
        assertThat(statusOf(there)).isEqualTo("EXPIRED");
    }

    @Test
    void waitsForTheCourtLockLikeEveryBookingPath() throws Exception {
        long lapsed = hold(1, 0);
        move(lapsed, "now() - interval '1 second'", null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Another transaction holds this turf's lock, as an in-flight booking would.
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.sql("SELECT 1 FROM courts WHERE id = :id FOR NO KEY UPDATE").param("id", courtId).query(Integer.class).single();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> run = pool.submit(job::releaseExpiredHolds);
            assertThatThrownBy(() -> run.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(statusOf(lapsed)).isEqualTo("HELD"); // it is queued behind the lock, not skipping ahead

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(run.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(statusOf(lapsed)).isEqualTo("EXPIRED");
    }

    @Test
    void runsAlongsideConcurrentHoldsWithoutDeadlocksOrDoubleBooking() throws Exception {
        int turfs = 6;
        int contenders = 4;
        List<Long> turfIds = new ArrayList<>();
        int player = 100;
        for (int i = 0; i < turfs; i++) {
            long t = i == 0 ? courtId : createCourt();
            if (i > 0) {
                addStandardPriceRules(t);
            }
            turfIds.add(t);
            long stale = bookingService.hold(t, createPlayer(player++), START, START.plusHours(1)).getId();
            move(stale, "now() - interval '1 second'", null);
        }
        List<Long> players = new ArrayList<>();
        for (int i = 0; i < turfs * contenders; i++) {
            players.add(createPlayer(player++));
        }

        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(turfs * contenders + 1)) {
            futures.add(pool.submit(() -> {
                await(go);
                for (int i = 0; i < 5; i++) {
                    job.releaseExpiredHolds();
                }
                return null;
            }));
            for (int i = 0; i < turfs * contenders; i++) {
                long t = turfIds.get(i % turfs);
                long p = players.get(i);
                futures.add(pool.submit(() -> {
                    await(go);
                    try {
                        bookingService.hold(t, p, START, START.plusHours(1));
                    } catch (SlotUnavailableException expected) {
                        // lost the race for this slot
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS); // any deadlock or other error fails here
            }
        }

        for (long t : turfIds) {
            long active = jdbc.sql("SELECT count(*) FROM bookings WHERE court_id = :t AND status IN ('HELD', 'CONFIRMED')")
                    .param("t", t).query(Long.class).single();
            assertThat(active).isEqualTo(1);
        }
    }

    private long hold(int player, int hourOffset) {
        OffsetDateTime start = START.plusHours(hourOffset);
        return bookingService.hold(courtId, createPlayer(player), start, start.plusHours(1)).getId();
    }

    private void move(long bookingId, String holdExpiresAtSql, String paymentStartedAtSql) {
        jdbc.sql("UPDATE bookings SET hold_expires_at = %s, payment_started_at = %s WHERE id = :id"
                        .formatted(holdExpiresAtSql, paymentStartedAtSql == null ? "NULL" : paymentStartedAtSql))
                .param("id", bookingId)
                .update();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId).query(String.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
