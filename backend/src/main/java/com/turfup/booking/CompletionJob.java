package com.turfup.booking;

import com.turfup.settings.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every 15 minutes: CONFIRMED bookings whose slot ended at least {@code complete_after_hours} (24) hours ago
 * become COMPLETED. The delay gives the owner a day to mark a no-show, which is only possible while a booking is
 * CONFIRMED. Regular-customer counts use COMPLETED bookings.
 *
 * <p>A single UPDATE: it takes only the booking row locks, like marking a no-show or receiving the balance, and
 * never a venue or court lock, so it cannot form a lock cycle with booking paths (which take the court lock first).
 */
@Component
public class CompletionJob {

    static final String COMPLETE_AFTER_HOURS = "complete_after_hours";

    private static final Logger log = LoggerFactory.getLogger(CompletionJob.class);

    private final JdbcClient jdbc;
    private final AppSettings settings;

    public CompletionJob(JdbcClient jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** @return bookings marked COMPLETED */
    @Scheduled(fixedDelayString = "${turfup.jobs.completion-ms:900000}", initialDelayString = "${turfup.jobs.completion-ms:900000}")
    @Transactional
    public int completeFinishedBookings() {
        int completed = jdbc.sql("""
                        UPDATE bookings SET status = 'COMPLETED'
                        WHERE status = 'CONFIRMED'
                          AND end_at <= now() - make_interval(hours => :hours)
                        """)
                .param("hours", settings.getInt(COMPLETE_AFTER_HOURS))
                .update();
        if (completed > 0) {
            log.info("Marked {} booking(s) completed", completed);
        }
        return completed;
    }
}
