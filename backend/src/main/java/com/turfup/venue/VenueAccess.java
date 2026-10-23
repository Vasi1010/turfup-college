package com.turfup.venue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Ownership checks and the setup locks. A venue or court that belongs to someone else is reported as not found,
 * so owners cannot probe which ids exist.
 *
 * <p>Lock order, everywhere: venue, then court(s) in id order, then booking and payment rows. Booking inserts
 * lock only their court; setup changes lock the venue first and then the courts they affect, so a booking is
 * never checked against hours or prices that are changing under it.
 */
@Component
public class VenueAccess {

    private final JdbcClient jdbc;

    public VenueAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws VenueNotFoundException if the venue does not exist or belongs to another owner */
    public void requireOwnedVenue(long ownerId, long venueId) {
        jdbc.sql("SELECT 1 FROM venues WHERE id = :venueId AND owner_id = :ownerId")
                .param("venueId", venueId)
                .param("ownerId", ownerId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new VenueNotFoundException(venueId));
    }

    /**
     * The venue of one of the owner's courts.
     *
     * @throws CourtNotFoundException if the court does not exist or is at another owner's venue
     */
    public long venueOfOwnedCourt(long ownerId, long courtId) {
        return jdbc.sql("""
                        SELECT c.venue_id FROM courts c JOIN venues v ON v.id = c.venue_id
                        WHERE c.id = :courtId AND v.owner_id = :ownerId
                        """)
                .param("courtId", courtId)
                .param("ownerId", ownerId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new CourtNotFoundException(courtId));
    }

    /**
     * Locks the owner's venue and then all of its courts in id order, for a change that affects every court
     * (opening hours). Waits for bookings in progress on those courts.
     *
     * @throws VenueNotFoundException if the venue does not exist or belongs to another owner
     */
    public void lockOwnedVenueAndCourts(long ownerId, long venueId) {
        lockOwnedVenue(ownerId, venueId);
        jdbc.sql("SELECT id FROM courts WHERE venue_id = :venueId ORDER BY id FOR NO KEY UPDATE")
                .param("venueId", venueId)
                .query(Long.class)
                .list();
    }

    /**
     * Locks the owner's venue, then one of its courts, for a change to that court (slot length, price rules).
     *
     * @return the court's venue id
     * @throws CourtNotFoundException if the court does not exist or is at another owner's venue
     */
    public long lockOwnedCourt(long ownerId, long courtId) {
        long venueId = venueOfOwnedCourt(ownerId, courtId);
        lockOwnedVenue(ownerId, venueId);
        jdbc.sql("SELECT 1 FROM courts WHERE id = :courtId FOR NO KEY UPDATE")
                .param("courtId", courtId)
                .query(Integer.class)
                .single();
        return venueId;
    }

    /** NO KEY UPDATE: does not block the KEY SHARE locks foreign keys take (inserting a court or regular). */
    public void lockOwnedVenue(long ownerId, long venueId) {
        jdbc.sql("SELECT 1 FROM venues WHERE id = :venueId AND owner_id = :ownerId FOR NO KEY UPDATE")
                .param("venueId", venueId)
                .param("ownerId", ownerId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new VenueNotFoundException(venueId));
    }
}
