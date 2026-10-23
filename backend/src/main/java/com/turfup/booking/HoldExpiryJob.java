package com.turfup.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute, releases holds that have run out by calling the {@code release_expired_holds()} stored
 * procedure (migration V6, per court since V12). Booking paths also expire stale holds they collide with, so this job is what frees
 * slots nobody is trying to book, and keeps availability accurate.
 *
 * <p>Not transactional on purpose: the procedure commits once per court, which is only allowed when it is called
 * outside an open transaction.
 */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final JdbcClient jdbc;

    public HoldExpiryJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return how many holds were released */
    @Scheduled(fixedDelayString = "${turfup.jobs.hold-expiry-ms:60000}", initialDelayString = "${turfup.jobs.hold-expiry-ms:60000}")
    public int releaseExpiredHolds() {
        int released = jdbc.sql("CALL release_expired_holds()").query(Integer.class).single();
        if (released > 0) {
            log.info("Released {} expired hold(s)", released);
        }
        return released;
    }
}
