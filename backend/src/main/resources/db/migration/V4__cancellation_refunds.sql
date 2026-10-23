-- Player cancellation refunds: full advance back if cancelled at least refund_full_hours_before ahead,
-- at most refunds_per_month times per player per turf per calendar month (IST).
ALTER TABLE turfs
    ADD COLUMN refund_full_hours_before integer NOT NULL DEFAULT 24,
    ADD COLUMN refunds_per_month integer NOT NULL DEFAULT 1,
    ADD CONSTRAINT turfs_refund_full_hours_before_check CHECK (refund_full_hours_before >= 0),
    ADD CONSTRAINT turfs_refunds_per_month_check CHECK (refunds_per_month >= 0);

-- Why money went back. Only PLAYER_CANCELLATION refunds count toward refunds_per_month.
--   PAYMENT_REJECTED     a captured payment that could not confirm (late, slot gone, duplicate, wrong amount)
--   PLAYER_CANCELLATION  the player cancelled in time and within the monthly allowance
--   OWNER_CANCELLATION   the owner cancelled; always a full refund
ALTER TABLE payments ADD COLUMN refund_reason varchar(24);
UPDATE payments SET refund_reason = 'PAYMENT_REJECTED' WHERE kind = 'REFUND';
ALTER TABLE payments
    ADD CONSTRAINT payments_refund_reason_check CHECK (
        (kind = 'REFUND') = (refund_reason IS NOT NULL)
        AND (refund_reason IS NULL
             OR refund_reason IN ('PAYMENT_REJECTED', 'PLAYER_CANCELLATION', 'OWNER_CANCELLATION')));
