-- Razorpay (business version, payments.mode = razorpay).
--
-- Route: each turf's advances are transferred to its owner's Razorpay linked account, so TurfUp never
-- holds the money. The admin enters the account id; its status is fetched from Razorpay and refreshed by
-- a job. In razorpay mode a turf can be approved only while the account is 'activated', and players can
-- pay advances online only while it stays 'activated' (a suspended account pauses online advances).
ALTER TABLE turfs
    ADD COLUMN razorpay_account_id varchar(32),
    ADD COLUMN razorpay_account_status varchar(32),
    ADD COLUMN razorpay_account_checked_at timestamptz,
    ADD CONSTRAINT turfs_razorpay_account_id_check CHECK (razorpay_account_id ~ '^acc_[A-Za-z0-9]{8,24}$'),
    ADD CONSTRAINT turfs_razorpay_account_status_check
        CHECK (razorpay_account_status IS NULL OR razorpay_account_id IS NOT NULL);

COMMENT ON COLUMN turfs.razorpay_account_id IS 'Razorpay Route linked account (acc_...) that receives this turf''s advances';
COMMENT ON COLUMN turfs.razorpay_account_status IS 'Last status fetched from Razorpay (created, activated, suspended, ...)';

-- Gateway refunds. A refund row is recorded PENDING inside the booking transaction; after commit it is sent
-- to Razorpay as a refund of the captured payment named by refund_of (reversing the Route transfer), and
-- gateway_refund_id is stored. The refund webhook (or the reconciliation job) marks it SUCCEEDED or FAILED.
ALTER TABLE payments
    ADD COLUMN refund_of bigint REFERENCES payments (id),
    ADD COLUMN gateway_refund_id varchar(64),
    ADD CONSTRAINT payments_gateway_refund_id_key UNIQUE (gateway_refund_id),
    ADD CONSTRAINT payments_refund_of_check CHECK (refund_of IS NULL OR kind = 'REFUND'),
    ADD CONSTRAINT payments_gateway_refund_source_check
        CHECK (kind <> 'REFUND' OR method <> 'RAZORPAY' OR refund_of IS NOT NULL);

-- The reconciliation job scans open Razorpay orders and refunds.
CREATE INDEX payments_razorpay_pending_idx ON payments (created_at)
    WHERE method = 'RAZORPAY' AND status = 'PENDING';
