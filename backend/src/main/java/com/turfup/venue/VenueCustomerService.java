package com.turfup.venue;

import com.turfup.auth.PlayerNotFoundException;
import com.turfup.settings.AppSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * A venue's regular customers ({@code venue_customers}). Waivers are per venue (they apply on every court there),
 * never global.
 */
@Service
public class VenueCustomerService {

    public record VenueCustomerView(long venueId, long playerId, boolean advanceWaived, int noShowCount, String notes) {
    }

    /**
     * A player who has booked a court here or has a {@code venue_customers} row. {@code recentPlayed} counts
     * COMPLETED bookings (marked a day after the slot, see CompletionJob) that ended in the last
     * {@code suggestionDays} days;
     * {@code suggested} means the dashboard proposes waiving their advance (the owner decides).
     */
    public record Regular(long playerId, String name, String phone, boolean advanceWaived, int noShowCount,
                          String notes, long recentPlayed, long totalPlayed, OffsetDateTime lastSlotAt,
                          boolean suggested) {
    }

    public record Regulars(int suggestionMinBookings, int suggestionDays, List<Regular> customers) {
    }

    record RegularRow(long playerId, String name, String phone, boolean advanceWaived, int noShowCount,
                      String notes, long recentPlayed, long totalPlayed, OffsetDateTime lastSlotAt) {
    }

    static final String SUGGESTION_MIN_BOOKINGS = "regular_suggestion_min_bookings";
    static final String SUGGESTION_DAYS = "regular_suggestion_days";

    private final JdbcClient jdbc;
    private final VenueAccess access;
    private final AppSettings settings;

    public VenueCustomerService(JdbcClient jdbc, VenueAccess access, AppSettings settings) {
        this.jdbc = jdbc;
        this.access = access;
        this.settings = settings;
    }

    /**
     * The venue's customers, counting bookings on all its courts: suggested regulars first, then waived ones,
     * then by recent bookings.
     *
     * @throws VenueNotFoundException if the venue does not exist or belongs to another owner
     */
    @Transactional(readOnly = true)
    public Regulars list(long ownerId, long venueId) {
        access.requireOwnedVenue(ownerId, venueId);
        int minBookings = settings.getInt(SUGGESTION_MIN_BOOKINGS);
        int days = settings.getInt(SUGGESTION_DAYS);
        List<RegularRow> rows = jdbc.sql("""
                        WITH played AS (
                            SELECT b.player_id,
                                   count(*) FILTER (WHERE b.end_at > now() - make_interval(days => :days))
                                       AS recent_played,
                                   count(*) AS total_played,
                                   max(b.start_at) AS last_slot_at
                            FROM bookings b JOIN courts c ON c.id = b.court_id
                            WHERE c.venue_id = :venueId AND b.player_id IS NOT NULL
                              AND b.status = 'COMPLETED'
                            GROUP BY b.player_id
                        ),
                        known AS (
                            SELECT b.player_id FROM bookings b JOIN courts c ON c.id = b.court_id
                            WHERE c.venue_id = :venueId AND b.player_id IS NOT NULL
                            UNION
                            SELECT player_id FROM venue_customers WHERE venue_id = :venueId
                        )
                        SELECT u.id AS player_id, u.name, u.phone,
                               COALESCE(tc.advance_waived, false) AS advance_waived,
                               COALESCE(tc.no_show_count, 0) AS no_show_count,
                               tc.notes,
                               COALESCE(p.recent_played, 0) AS recent_played,
                               COALESCE(p.total_played, 0) AS total_played,
                               p.last_slot_at
                        FROM known k
                        JOIN users u ON u.id = k.player_id AND u.role = 'PLAYER'
                        LEFT JOIN played p ON p.player_id = u.id
                        LEFT JOIN venue_customers tc ON tc.venue_id = :venueId AND tc.player_id = u.id
                        """)
                .param("venueId", venueId)
                .param("days", days)
                .query(RegularRow.class)
                .list();
        List<Regular> customers = rows.stream()
                .map(r -> new Regular(r.playerId(), r.name(), r.phone(), r.advanceWaived(), r.noShowCount(),
                        r.notes(), r.recentPlayed(), r.totalPlayed(), r.lastSlotAt(),
                        !r.advanceWaived() && r.recentPlayed() >= minBookings))
                .sorted(Comparator.comparing(Regular::suggested).reversed()
                        .thenComparing(Comparator.comparing(Regular::advanceWaived).reversed())
                        .thenComparing(Comparator.comparingLong(Regular::recentPlayed).reversed())
                        .thenComparing(Regular::name))
                .toList();
        return new Regulars(minBookings, days, customers);
    }

    /**
     * Grants or revokes the advance waiver for one player at one of the owner's venues (every court there).
     * Takes effect for the player's next booking.
     *
     * @throws VenueNotFoundException   if the venue does not exist or belongs to another owner
     * @throws PlayerNotFoundException  if there is no player with this id
     */
    @Transactional
    public VenueCustomerView setAdvanceWaived(long ownerId, long venueId, long playerId, boolean waived) {
        access.requireOwnedVenue(ownerId, venueId);
        jdbc.sql("SELECT 1 FROM users WHERE id = :playerId AND role = 'PLAYER'")
                .param("playerId", playerId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new PlayerNotFoundException(playerId));
        return jdbc.sql("""
                        INSERT INTO venue_customers (venue_id, player_id, advance_waived)
                        VALUES (:venueId, :playerId, :waived)
                        ON CONFLICT (venue_id, player_id) DO UPDATE SET advance_waived = EXCLUDED.advance_waived
                        RETURNING venue_id, player_id, advance_waived, no_show_count, notes
                        """)
                .param("venueId", venueId)
                .param("playerId", playerId)
                .param("waived", waived)
                .query(VenueCustomerView.class)
                .single();
    }
}
