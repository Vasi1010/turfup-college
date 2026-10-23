-- Advances paid by UPI straight to the venue owner (turfup.payments.mode = upi): no gateway, no fees.
--
-- The player pays the venue's UPI ID from their own UPI app, then claims the payment ("I've paid", with the UPI
-- reference if they have it). The owner checks their bank or UPI app and marks it received (the booking is
-- CONFIRMED and an ADVANCE payment recorded) or not received (the booking is CANCELLED by the owner, reason
-- PAYMENT_NOT_RECEIVED). A claimed hold stays HELD and keeps its slot until the owner decides, so every slot check,
-- the no-double-booking constraint and the one-hold-per-player index keep working unchanged.

-- The venue's UPI ID (VPA) and the name players see on the payment. Per venue, like the Razorpay account.
ALTER TABLE venues
    ADD COLUMN upi_id   varchar(100),
    ADD COLUMN upi_name varchar(60),
    ADD CONSTRAINT venues_upi_id_check CHECK (upi_id ~ '^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,63}$');

COMMENT ON COLUMN venues.upi_id IS 'UPI ID (VPA) players pay the advance to in upi mode, e.g. kickarena@ybl';

-- The player's claim: when they said they paid, and the UPI reference (UTR) they entered, if any.
ALTER TABLE bookings
    ADD COLUMN payment_claimed_at timestamptz,
    ADD COLUMN payment_ref        varchar(35),
    ADD CONSTRAINT bookings_payment_ref_check CHECK (payment_ref IS NULL OR payment_claimed_at IS NOT NULL);

COMMENT ON COLUMN bookings.payment_claimed_at IS 'upi mode: the player said they paid the advance; the hold waits for the owner';

-- The owner found no payment for a claim.
ALTER TABLE bookings DROP CONSTRAINT bookings_cancel_reason_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_cancel_reason_check
    CHECK (cancel_reason IN ('RAIN', 'MAINTENANCE', 'OTHER', 'RECURRING_CHANGED', 'PAYMENT_NOT_RECEIVED'));

-- Refunds of a UPI advance are sent by the owner from their own UPI app: recorded PENDING until the owner marks the
-- refund sent. (Balances paid in cash or UPI at the venue are handed back there and recorded SUCCEEDED, as before.)
ALTER TABLE payments ADD COLUMN refund_sent_at timestamptz;
ALTER TABLE payments ADD CONSTRAINT payments_refund_sent_check
    CHECK (refund_sent_at IS NULL OR (kind = 'REFUND' AND method = 'UPI' AND status = 'SUCCEEDED'));

CREATE INDEX payments_upi_refunds_pending_idx ON payments (booking_id)
    WHERE kind = 'REFUND' AND method = 'UPI' AND status = 'PENDING';

-- The expiry rule, now aware of claims: a claimed hold never expires on its own. The three-argument rule (V6) stays
-- as it was; this four-argument one wraps it and is what the Java queries and the job use from now on.
CREATE FUNCTION hold_expired(hold_expires_at timestamptz, payment_started_at timestamptz,
                             payment_claimed_at timestamptz, grace_minutes integer)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS
$$
SELECT payment_claimed_at IS NULL AND hold_expired(hold_expires_at, payment_started_at, grace_minutes)
$$;

-- Same procedure as V12, using the claim-aware rule.
CREATE OR REPLACE PROCEDURE release_expired_holds(INOUT released integer DEFAULT 0)
    LANGUAGE plpgsql
AS
$$
DECLARE
    grace   integer := (SELECT value::integer FROM app_settings WHERE key = 'grace_minutes');
    court   bigint;
    expired integer;
BEGIN
    released := 0;
    FOR court IN
        SELECT DISTINCT court_id FROM bookings
        WHERE status = 'HELD' AND hold_expires_at < now() -- necessary for expiry; uses bookings_held_expiry_idx
        ORDER BY court_id
    LOOP
        PERFORM 1 FROM courts WHERE id = court FOR NO KEY UPDATE;

        UPDATE bookings SET status = 'EXPIRED'
        WHERE court_id = court
          AND status = 'HELD'
          AND hold_expired(hold_expires_at, payment_started_at, payment_claimed_at, grace);
        GET DIAGNOSTICS expired = ROW_COUNT;
        released := released + expired;

        COMMIT;
    END LOOP;
END;
$$;
