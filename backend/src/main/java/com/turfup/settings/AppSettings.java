package com.turfup.settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads runtime settings from the {@code app_settings} table. Values are read on every call so an
 * admin change takes effect without a restart.
 */
@Component
public class AppSettings {

    public static final String HOLD_MINUTES = "hold_minutes";
    public static final String GRACE_MINUTES = "grace_minutes";
    public static final String MIN_PAY_SECONDS_REMAINING = "min_pay_seconds_remaining";

    private final JdbcClient jdbc;

    public AppSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int holdMinutes() {
        return getInt(HOLD_MINUTES);
    }

    public int graceMinutes() {
        return getInt(GRACE_MINUTES);
    }

    /** A payment may not start once fewer seconds than this are left on a hold. */
    public int minPaySecondsRemaining() {
        return getInt(MIN_PAY_SECONDS_REMAINING);
    }

    public int getInt(String key) {
        String value = jdbc.sql("SELECT value FROM app_settings WHERE key = ?")
                .param(key)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Missing app setting: " + key));
        return Integer.parseInt(value.trim());
    }
}
