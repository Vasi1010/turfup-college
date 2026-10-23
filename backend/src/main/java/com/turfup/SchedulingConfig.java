package com.turfup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background jobs ({@code @Scheduled} methods). Tests set {@code turfup.jobs.enabled=false} and
 * call the jobs directly, so a job never runs in the middle of a test.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "turfup.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
