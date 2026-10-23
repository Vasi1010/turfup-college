-- Advance transfers are now held until the booking's slot ends (was: its refund deadline), so every refund
-- before the slot ends, including owner cancellations, reverses money that is still unsettled in the owner's
-- linked account. Only the documentation of the column changes.
COMMENT ON COLUMN payments.transfer_hold_until IS 'Route transfer held until then (the slot end); settles to the owner after';
