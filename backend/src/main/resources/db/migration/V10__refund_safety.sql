-- Keeping advances refundable (razorpay mode).
--
-- Each advance's Route transfer is held (on_hold_until) until the booking's refund deadline: start_at minus the
-- turf's refund_full_hours_before. Until then the money sits unsettled in the owner's linked account, so a
-- player refund can always reverse it. The deadline sent to Razorpay is kept on the advance payment.
ALTER TABLE payments
    ADD COLUMN transfer_hold_until timestamptz,
    ADD CONSTRAINT payments_transfer_hold_check
        CHECK (transfer_hold_until IS NULL OR (kind = 'ADVANCE' AND method = 'RAZORPAY'));

COMMENT ON COLUMN payments.transfer_hold_until IS 'Route transfer held until then (the refund deadline); settles to the owner after';

-- A refund Razorpay rejected (typically: the owner's linked account could not cover the reversal) stays
-- PENDING and is retried by reconciliation; gateway_error says why, for the admin. FAILED refunds and rejected
-- ones are listed for the admin, who can settle them from the Razorpay dashboard and record that here.
ALTER TABLE payments
    ADD COLUMN gateway_error text,
    ADD COLUMN gateway_error_at timestamptz,
    ADD COLUMN settled_manually_at timestamptz,
    ADD CONSTRAINT payments_gateway_error_check
        CHECK ((gateway_error IS NULL) = (gateway_error_at IS NULL) AND (gateway_error IS NULL OR kind = 'REFUND')),
    ADD CONSTRAINT payments_settled_manually_check
        CHECK (settled_manually_at IS NULL OR (kind = 'REFUND' AND method = 'RAZORPAY' AND status = 'SUCCEEDED'));

COMMENT ON COLUMN payments.gateway_error IS 'Why Razorpay last rejected this refund; cleared when it goes through';
COMMENT ON COLUMN payments.settled_manually_at IS 'An admin refunded this from the Razorpay dashboard instead';

-- The admin's "refunds needing attention" list.
CREATE INDEX payments_refund_attention_idx ON payments (created_at)
    WHERE kind = 'REFUND' AND method = 'RAZORPAY' AND (status = 'FAILED' OR (status = 'PENDING' AND gateway_error IS NOT NULL));
