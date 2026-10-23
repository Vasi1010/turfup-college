package com.turfup.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily (and at startup): tops up every standing slot's bookings to four weeks ahead. Each standing slot is
 * generated in its own transaction, so one problem never stops the others; courts are visited in id order.
 */
@Component
public class RecurringBookingJob {

    private static final Logger log = LoggerFactory.getLogger(RecurringBookingJob.class);

    private final RecurringBookingService recurring;
    private final boolean enabled;

    public RecurringBookingJob(RecurringBookingService recurring, @Value("${turfup.jobs.enabled:true}") boolean enabled) {
        this.recurring = recurring;
        this.enabled = enabled;
    }

    /** @return bookings created */
    @Scheduled(cron = "${turfup.jobs.recurring-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public int generateAll() {
        int created = 0;
        int skipped = 0;
        for (long id : recurring.activeIds()) {
            try {
                RecurringBookingService.Generated g = recurring.generate(id);
                created += g.created();
                skipped += g.skipped();
            } catch (RuntimeException e) {
                log.warn("Could not generate bookings for recurring booking {}", id, e);
            }
        }
        if (created > 0 || skipped > 0) {
            log.info("Recurring bookings: {} created, {} skipped", created, skipped);
        }
        return created;
    }

    /** Catch up after downtime instead of waiting for tomorrow's run. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (enabled) {
            generateAll();
        }
    }
}
