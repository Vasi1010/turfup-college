-- V12 copied these to each venue's court; the court's values are the ones that apply now. Their CHECK
-- constraints go with them. Hours, the Razorpay account, approval and refunds_per_month stay on the venue.
ALTER TABLE venues
    DROP COLUMN sports,
    DROP COLUMN slot_minutes,
    DROP COLUMN advance_amount,
    DROP COLUMN refund_full_hours_before,
    DROP COLUMN refund_policy;
