package com.turfup.booking;

import com.turfup.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BookingHoldConcurrencyTest extends PostgresTestBase {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final int REQUESTS = 50;

    @Autowired
    BookingService bookingService;

    enum Outcome { HELD, SLOT_UNAVAILABLE }

    @Test
    void fiftyConcurrentHoldsForTheSameSlot_exactlyOneSucceeds() throws Exception {
        long courtId = createCourt();
        addStandardPriceRules(courtId);
        List<Long> players = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            players.add(createPlayer(i));
        }
        OffsetDateTime start = OffsetDateTime.of(2030, 1, 15, 20, 0, 0, 0, IST);
        OffsetDateTime end = start.plusHours(1);

        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
            for (long playerId : players) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        bookingService.hold(courtId, playerId, start, end);
                        return Outcome.HELD;
                    } catch (SlotUnavailableException e) {
                        return Outcome.SLOT_UNAVAILABLE;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            // Any other exception surfaces here as an ExecutionException and fails the test.
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(o -> o == Outcome.HELD).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == Outcome.SLOT_UNAVAILABLE).hasSize(REQUESTS - 1);
        }

        long activeRows = jdbc.sql("""
                        SELECT count(*) FROM bookings
                        WHERE court_id = :courtId AND status IN ('HELD', 'CONFIRMED')
                        """)
                .param("courtId", courtId)
                .query(Long.class)
                .single();
        assertThat(activeRows).isEqualTo(1);
    }
}
