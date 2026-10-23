-- Who cancelled a booking, and why (owner cancellations must give a reason; OTHER needs a note).
ALTER TABLE bookings
    ADD COLUMN cancelled_by varchar(6),
    ADD COLUMN cancel_reason varchar(12),
    ADD COLUMN cancel_note text;

-- Backfill cancellations made before this migration.
UPDATE bookings b SET cancelled_by = 'OWNER', cancel_reason = 'OTHER',
                      cancel_note = 'Cancelled before reasons were recorded'
WHERE b.status = 'CANCELLED'
  AND EXISTS (SELECT 1 FROM payments p WHERE p.booking_id = b.id AND p.refund_reason = 'OWNER_CANCELLATION');
UPDATE bookings SET cancelled_by = 'PLAYER' WHERE status = 'CANCELLED' AND cancelled_by IS NULL;

ALTER TABLE bookings
    ADD CONSTRAINT bookings_cancelled_by_check CHECK (cancelled_by IN ('PLAYER', 'OWNER')),
    ADD CONSTRAINT bookings_cancel_reason_check CHECK (cancel_reason IN ('RAIN', 'MAINTENANCE', 'OTHER')),
    ADD CONSTRAINT bookings_cancellation_check CHECK (
        CASE
            WHEN status <> 'CANCELLED' THEN cancelled_by IS NULL AND cancel_reason IS NULL AND cancel_note IS NULL
            WHEN cancelled_by = 'PLAYER' THEN cancel_reason IS NULL
            WHEN cancelled_by = 'OWNER' THEN cancel_reason IS NOT NULL
                AND (cancel_reason <> 'OTHER' OR btrim(coalesce(cancel_note, '')) <> '')
            ELSE false
        END);

-- Regular-customer suggestions on the owner dashboard: N+ played bookings in the last D days.
INSERT INTO app_settings (key, value) VALUES
    ('regular_suggestion_min_bookings', '8'),
    ('regular_suggestion_days', '30');
