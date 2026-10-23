-- Recurring bookings: an owner sets up a standing slot once ("every Tuesday 8-9 PM for Rahul"), and a daily job
-- generates its CONFIRMED bookings four weeks ahead.

ALTER TABLE recurring_bookings
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    -- Set when the owner ends (or edits, which ends and replaces) the standing slot. Ended rows are kept
    -- because their past bookings still point at them.
    ADD COLUMN ended_at timestamptz;

CREATE INDEX recurring_bookings_active_idx ON recurring_bookings (turf_id) WHERE ended_at IS NULL;

-- Each occurrence is generated at most once, whatever happens to it later. An occurrence the owner cancelled
-- for rain, or the player cancelled, is therefore never recreated by the next run.
CREATE UNIQUE INDEX bookings_recurring_occurrence ON bookings (recurring_id, start_at) WHERE recurring_id IS NOT NULL;

-- Occurrences the job could not book, shown on the owner's Today screen. The job retries them on every run
-- and deletes the row once the occurrence is booked (for example after the clashing booking is cancelled).
CREATE TABLE recurring_skips (
    recurring_id bigint      NOT NULL REFERENCES recurring_bookings (id),
    start_at     timestamptz NOT NULL,
    end_at       timestamptz NOT NULL,
    reason       varchar(16) NOT NULL,
    detected_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (recurring_id, start_at),
    CONSTRAINT recurring_skips_reason_check CHECK (reason IN ('SLOT_TAKEN', 'OUTSIDE_HOURS', 'NO_PRICE')),
    CONSTRAINT recurring_skips_range_check CHECK (end_at > start_at)
);

-- Future occurrences cancelled because their standing slot was ended or changed.
ALTER TABLE bookings DROP CONSTRAINT bookings_cancel_reason_check;
ALTER TABLE bookings ALTER COLUMN cancel_reason TYPE varchar(20);
ALTER TABLE bookings ADD CONSTRAINT bookings_cancel_reason_check
    CHECK (cancel_reason IN ('RAIN', 'MAINTENANCE', 'OTHER', 'RECURRING_CHANGED'));
