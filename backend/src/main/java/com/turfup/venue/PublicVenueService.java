package com.turfup.venue;

import com.turfup.payment.LinkedAccountService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What players can see: active, approved venues and their active courts only. Money is paise.
 *
 * <p>"Any free court" is offered for a sport when at least two of the venue's courts offer it and they share a
 * slot length, so their slots line up.
 */
@Service
public class PublicVenueService {

    /** How many of a venue's courts offer a sport. */
    public record SportCourts(String sport, int courts) {
    }

    /**
     * A venue card in the browse list. With a sport filter, {@code advanceFrom} and {@code fromRate} (lowest hourly
     * rate of any rule, paise/hr) cover only that sport's courts.
     */
    public record VenueCard(long id, String name, String area, String address, List<SportCourts> sports,
                            long advanceFrom, Long fromRate) {
    }

    /** A court as players see it on the venue page. */
    public record PublicCourt(long id, String name, List<String> sports, int slotMinutes, long advanceAmount,
                              int refundFullHoursBefore, String refundPolicy, Long fromRate) {
    }

    /** A sport at this venue: which courts offer it (display order), and whether "any free court" is offered. */
    public record SportOption(String sport, List<Long> courtIds, boolean anyCourt) {
    }

    /**
     * The venue page: everything a player needs before holding a slot, including the refund rules.
     * {@code onlinePayments} false means online booking is paused for now (only waived regulars can book).
     */
    public record VenueDetail(long id, String name, String area, String address, BigDecimal lat, BigDecimal lng,
                              int openMinute, int closeMinute, int refundsPerMonth, boolean onlinePayments,
                              List<PublicCourt> courts, List<SportOption> sports) {
    }

    public record AreaCount(String area, long venues) {
    }

    public record SportCount(String sport, long venues) {
    }

    /** Venue alias v, court alias c. */
    static final String PUBLIC = "v.active AND v.approved AND c.active";

    private final JdbcClient jdbc;
    private final LinkedAccountService linkedAccounts;

    public PublicVenueService(JdbcClient jdbc, LinkedAccountService linkedAccounts) {
        this.jdbc = jdbc;
        this.linkedAccounts = linkedAccounts;
    }

    @Transactional(readOnly = true)
    public List<AreaCount> areas() {
        return jdbc.sql("""
                        SELECT v.area, count(DISTINCT v.id) AS venues
                        FROM venues v JOIN courts c ON c.venue_id = v.id
                        WHERE %s
                        GROUP BY v.area ORDER BY v.area
                        """.formatted(PUBLIC))
                .query(AreaCount.class)
                .list();
    }

    /** Every sport, in display order, with how many public venues offer it (0 included). */
    @Transactional(readOnly = true)
    public List<SportCount> sports() {
        return jdbc.sql("""
                        SELECT s.code AS sport,
                               (SELECT count(DISTINCT v.id)
                                FROM court_sports cs JOIN courts c ON c.id = cs.court_id JOIN venues v ON v.id = c.venue_id
                                WHERE cs.sport = s.code AND %s) AS venues
                        FROM sports s ORDER BY s.sort_order
                        """.formatted(PUBLIC))
                .query(SportCount.class)
                .list();
    }

    /**
     * @param area  exact area name (case-insensitive), or null for every area
     * @param sport a sport code, or null for every sport
     */
    @Transactional(readOnly = true)
    public List<VenueCard> list(String area, String sport) {
        boolean byArea = area != null && !area.isBlank();
        boolean bySport = sport != null && !sport.isBlank();
        String sportCourt = bySport
                ? " AND EXISTS (SELECT 1 FROM court_sports cs WHERE cs.court_id = c.id AND cs.sport = :sport)" : "";
        record Row(long id, String name, String area, String address, long advanceFrom, Long fromRate) {
        }
        var spec = jdbc.sql("""
                        SELECT v.id, v.name, v.area, v.address, min(c.advance_amount) AS advance_from,
                               min((SELECT min(r.rate) FROM price_rules r WHERE r.court_id = c.id)) AS from_rate
                        FROM venues v JOIN courts c ON c.venue_id = v.id
                        WHERE %s%s%s
                        GROUP BY v.id
                        ORDER BY v.area, v.name
                        """.formatted(PUBLIC, byArea ? " AND lower(v.area) = lower(:area)" : "", sportCourt));
        if (byArea) {
            spec = spec.param("area", area.strip());
        }
        if (bySport) {
            spec = spec.param("sport", sport.strip().toLowerCase(Locale.ROOT));
        }
        List<Row> rows = spec.query(Row.class).list();
        Map<Long, List<SportCourts>> sports = sportCounts(rows.stream().map(Row::id).toList());
        return rows.stream()
                .map(r -> new VenueCard(r.id(), r.name(), r.area(), r.address(),
                        sports.getOrDefault(r.id(), List.of()), r.advanceFrom(), r.fromRate()))
                .toList();
    }

    /** @throws VenueNotFoundException if the venue does not exist, is not public or has no active court */
    @Transactional(readOnly = true)
    public VenueDetail detail(long venueId) {
        record Venue(long id, String name, String area, String address, BigDecimal lat, BigDecimal lng, int openMinute,
                     int closeMinute, int refundsPerMonth, String razorpayAccountStatus, String upiId) {
        }
        Venue v = jdbc.sql("SELECT * FROM venues v WHERE v.id = :id AND v.active AND v.approved")
                .param("id", venueId)
                .query(Venue.class)
                .optional()
                .orElseThrow(() -> new VenueNotFoundException(venueId));
        List<PublicCourt> courts = publicCourts(venueId);
        if (courts.isEmpty()) {
            throw new VenueNotFoundException(venueId);
        }

        Map<String, List<PublicCourt>> bySport = new LinkedHashMap<>();
        for (String sport : jdbc.sql("SELECT code FROM sports ORDER BY sort_order").query(String.class).list()) {
            List<PublicCourt> offering = courts.stream().filter(c -> c.sports().contains(sport)).toList();
            if (!offering.isEmpty()) {
                bySport.put(sport, offering);
            }
        }
        List<SportOption> sports = new ArrayList<>();
        bySport.forEach((sport, offering) -> sports.add(new SportOption(sport,
                offering.stream().map(PublicCourt::id).toList(), anyCourt(offering))));

        return new VenueDetail(v.id(), v.name(), v.area(), v.address(), v.lat(), v.lng(), v.openMinute(),
                v.closeMinute(), v.refundsPerMonth(), linkedAccounts.acceptsOnlineAdvance(v.razorpayAccountStatus(), v.upiId()),
                courts, sports);
    }

    /** At least two courts whose slots line up. */
    static boolean anyCourt(List<PublicCourt> courts) {
        return courts.size() >= 2 && courts.stream().map(PublicCourt::slotMinutes).distinct().count() == 1;
    }

    /** The venue's active courts in display order. */
    List<PublicCourt> publicCourts(long venueId) {
        record Row(long id, String name, int slotMinutes, long advanceAmount, int refundFullHoursBefore,
                   String refundPolicy, Long fromRate) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT c.id, c.name, c.slot_minutes, c.advance_amount, c.refund_full_hours_before,
                               c.refund_policy, (SELECT min(r.rate) FROM price_rules r WHERE r.court_id = c.id) AS from_rate
                        FROM courts c WHERE c.venue_id = :venueId AND c.active
                        ORDER BY c.sort_order, c.id
                        """)
                .param("venueId", venueId)
                .query(Row.class)
                .list();
        record CourtSport(long courtId, String sport) {
        }
        Map<Long, List<String>> sports = jdbc.sql("""
                        SELECT cs.court_id, cs.sport FROM court_sports cs
                        JOIN courts c ON c.id = cs.court_id JOIN sports s ON s.code = cs.sport
                        WHERE c.venue_id = :venueId ORDER BY s.sort_order
                        """)
                .param("venueId", venueId)
                .query(CourtSport.class)
                .list()
                .stream()
                .collect(Collectors.groupingBy(CourtSport::courtId, LinkedHashMap::new,
                        Collectors.mapping(CourtSport::sport, Collectors.toList())));
        return rows.stream().map(r -> new PublicCourt(r.id(), r.name(), sports.getOrDefault(r.id(), List.of()),
                r.slotMinutes(), r.advanceAmount(), r.refundFullHoursBefore(), r.refundPolicy(), r.fromRate())).toList();
    }

    private Map<Long, List<SportCourts>> sportCounts(List<Long> venueIds) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        record Row(long venueId, String sport, int courts) {
        }
        return jdbc.sql("""
                        SELECT c.venue_id, cs.sport, count(*) AS courts
                        FROM court_sports cs JOIN courts c ON c.id = cs.court_id JOIN sports s ON s.code = cs.sport
                        WHERE c.venue_id IN (:ids) AND c.active
                        GROUP BY c.venue_id, cs.sport, s.sort_order
                        ORDER BY s.sort_order
                        """)
                .param("ids", venueIds)
                .query(Row.class)
                .list()
                .stream()
                .collect(Collectors.groupingBy(Row::venueId,
                        Collectors.mapping(r -> new SportCourts(r.sport(), r.courts()), Collectors.toList())));
    }
}
