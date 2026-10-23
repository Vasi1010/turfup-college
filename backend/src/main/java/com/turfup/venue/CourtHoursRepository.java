package com.turfup.venue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CourtHoursRepository {

    /** A publicly bookable court with its hours. */
    public record PublicCourtHours(long courtId, CourtHours hours) {
    }

    private final JdbcClient jdbc;

    public CourtHoursRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Hours of a publicly bookable court; empty if it, or its venue, is missing, inactive or unapproved. */
    public Optional<CourtHours> findPublic(long courtId) {
        return jdbc.sql("""
                        SELECT v.open_minute, v.close_minute, c.slot_minutes
                        FROM courts c JOIN venues v ON v.id = c.venue_id
                        WHERE c.id = :courtId AND %s
                        """.formatted(PublicVenueService.PUBLIC))
                .param("courtId", courtId)
                .query(CourtHours.class)
                .optional();
    }

    /** The venue's publicly bookable courts offering {@code sport}, in display order. */
    public List<PublicCourtHours> findPublicForSport(long venueId, String sport) {
        return jdbc.sql("""
                        SELECT c.id, v.open_minute, v.close_minute, c.slot_minutes
                        FROM courts c JOIN venues v ON v.id = c.venue_id
                        WHERE v.id = :venueId AND %s
                          AND EXISTS (SELECT 1 FROM court_sports cs WHERE cs.court_id = c.id AND cs.sport = :sport)
                        ORDER BY c.sort_order, c.id
                        """.formatted(PublicVenueService.PUBLIC))
                .param("venueId", venueId)
                .param("sport", sport)
                .query((rs, n) -> new PublicCourtHours(rs.getLong("id"),
                        new CourtHours(rs.getInt("open_minute"), rs.getInt("close_minute"), rs.getInt("slot_minutes"))))
                .list();
    }
}
