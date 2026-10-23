-- A CONFIRMED booking becomes COMPLETED this many hours after its slot ends. Until then the owner can still mark
-- it NO_SHOW; regular-customer counts use COMPLETED bookings only.
INSERT INTO app_settings (key, value) VALUES ('complete_after_hours', '24');
