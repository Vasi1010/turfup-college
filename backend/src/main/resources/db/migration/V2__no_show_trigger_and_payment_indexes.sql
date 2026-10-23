-- Phase 3: no-show counting, balance payments, webhook lookups.

-- ---------------------------------------------------------------------------
-- no_show_count is maintained by the database: whenever a booking becomes
-- NO_SHOW, the player's turf_customers row (created if missing) is incremented.
-- Works for any path that changes the status, including manual SQL.
-- ---------------------------------------------------------------------------
CREATE FUNCTION count_no_show() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO turf_customers (turf_id, player_id, no_show_count)
    VALUES (NEW.turf_id, NEW.player_id, 1)
    ON CONFLICT (turf_id, player_id)
        DO UPDATE SET no_show_count = turf_customers.no_show_count + 1;
    RETURN NULL;
END;
$$;

CREATE TRIGGER bookings_count_no_show
    AFTER UPDATE OF status ON bookings
    FOR EACH ROW
    WHEN (NEW.status = 'NO_SHOW' AND OLD.status <> 'NO_SHOW' AND NEW.player_id IS NOT NULL)
EXECUTE FUNCTION count_no_show();

-- The balance is received at most once per booking.
CREATE UNIQUE INDEX payments_one_balance_per_booking
    ON payments (booking_id)
    WHERE kind = 'BALANCE' AND status = 'SUCCEEDED';

-- Payment webhooks find the payment by the gateway order id.
CREATE INDEX payments_gateway_order_id_idx ON payments (gateway_order_id);
