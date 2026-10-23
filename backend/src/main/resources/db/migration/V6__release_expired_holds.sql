-- The hold expiry rule, defined once in the database and used everywhere:
--   * HoldExpirySql (Java) calls hold_expired() inside booking, availability and schedule queries;
--   * release_expired_holds() below calls it from the every-minute job.
--
-- A HELD row has expired once hold_expires_at has passed, unless a payment was started during the hold; then
-- it lasts until the end of the grace window. Judged by the database clock (now()), never the app's clock.
CREATE FUNCTION hold_expired(hold_expires_at timestamptz, payment_started_at timestamptz, grace_minutes integer)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS
$$
SELECT now() > hold_expires_at
           AND (payment_started_at IS NULL OR now() > hold_expires_at + make_interval(mins => grace_minutes))
$$;

-- Marks expired HELD bookings as EXPIRED, one turf at a time, and returns how many it released.
--
-- Locking: for each turf it first takes the turf row lock (FOR NO KEY UPDATE), exactly like every booking
-- insert and cancellation, then updates that turf's bookings, then COMMITs before moving on. So it never holds
-- more than one turf lock, never waits on bookings while holding a lock other paths take later, and blocks
-- bookings on a turf only for the moment it takes to expire that turf's holds.
--
-- Because it commits per turf, call it on its own (autocommit), not inside an open transaction:
--   CALL release_expired_holds();
CREATE PROCEDURE release_expired_holds(INOUT released integer DEFAULT 0)
    LANGUAGE plpgsql
AS
$$
DECLARE
    grace   integer := (SELECT value::integer FROM app_settings WHERE key = 'grace_minutes');
    turf    bigint;
    expired integer;
BEGIN
    released := 0;
    FOR turf IN
        SELECT DISTINCT turf_id FROM bookings
        WHERE status = 'HELD' AND hold_expires_at < now() -- necessary for expiry; uses bookings_held_expiry_idx
        ORDER BY turf_id
    LOOP
        PERFORM 1 FROM turfs WHERE id = turf FOR NO KEY UPDATE;

        UPDATE bookings SET status = 'EXPIRED'
        WHERE turf_id = turf
          AND status = 'HELD'
          AND hold_expired(hold_expires_at, payment_started_at, grace);
        GET DIAGNOSTICS expired = ROW_COUNT;
        released := released + expired;

        COMMIT;
    END LOOP;
END;
$$;
