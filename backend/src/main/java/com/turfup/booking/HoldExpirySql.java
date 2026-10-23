package com.turfup.booking;

/**
 * "This HELD row has expired", as a SQL condition. The rule itself is the database function
 * {@code hold_expired()} (migrations V6, V14), which the {@code release_expired_holds()} procedure also uses, so the
 * booking paths, availability, the owner schedule and the expiry job can never disagree.
 *
 * <p>A hold expires once {@code hold_expires_at} has passed, unless a payment was started during the hold; then
 * it lasts until the end of the grace window. A hold whose UPI payment the player has claimed (upi mode) never expires
 * on its own: it waits for the owner to mark the payment received or not (V14). Uses the database clock ({@code now()}), never the JVM clock.
 * Requires the named parameter {@code :graceMinutes} (from {@code app_settings}).
 */
public final class HoldExpirySql {

    public static final String IS_EXPIRED =
            "hold_expired(hold_expires_at, payment_started_at, payment_claimed_at, :graceMinutes)";

    private HoldExpirySql() {
    }
}
