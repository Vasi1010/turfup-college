-- Turfs must be approved by an admin before players can see or book them.
-- Existing turfs start unapproved; owners can still set them up and enter walk-ins.
ALTER TABLE turfs ADD COLUMN approved boolean NOT NULL DEFAULT false;
