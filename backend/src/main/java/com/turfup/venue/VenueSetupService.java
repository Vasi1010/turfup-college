package com.turfup.venue;

import com.turfup.payment.LinkedAccountService;
import com.turfup.pricing.PriceRule;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owners create and edit venues and their courts. Each save is all or nothing: the fields and the full set of
 * price rules are validated together, then written in one transaction.
 *
 * <p>A court must fit its venue: its slot length within the opening hours, and its general price rules (no day)
 * covering every minute the venue is open, so any slot can be priced; day-specific rules may cover only part of
 * the day. Rules of the same specificity may not overlap (also enforced by {@code price_rules_no_overlap}).
 * Changing the venue's hours re-checks every court and is refused if one would no longer fit.
 *
 * <p>Locks follow {@link VenueAccess}: venue, then courts in id order.
 */
@Service
public class VenueSetupService {

    private static final int MAX_MINUTE = 2880;

    private final JdbcClient jdbc;
    private final VenueAccess access;
    private final LinkedAccountService linkedAccounts;

    public VenueSetupService(JdbcClient jdbc, VenueAccess access, LinkedAccountService linkedAccounts) {
        this.jdbc = jdbc;
        this.access = access;
        this.linkedAccounts = linkedAccounts;
    }

    /**
     * A new venue with its first court(s). New venues start unapproved.
     *
     * @throws InvalidVenueSetupException if the venue or a court is inconsistent, or no court is given
     */
    @Transactional
    public VenueView create(long ownerId, VenueSetup venue, List<CourtSetup> courts) {
        validateVenue(venue);
        if (courts == null || courts.isEmpty()) {
            throw new InvalidVenueSetupException("Add at least one court.");
        }
        for (CourtSetup c : courts) {
            validateCourt(c, venue.openMinute(), venue.closeMinute());
        }
        long venueId = jdbc.sql("""
                        INSERT INTO venues (owner_id, name, area, address, lat, lng, open_minute, close_minute,
                                            refunds_per_month)
                        VALUES (:ownerId, :name, :area, :address, :lat, :lng, :open, :close, :refundsPerMonth)
                        RETURNING id
                        """)
                .param("ownerId", ownerId)
                .params(venueFields(venue))
                .query(Long.class)
                .single();
        for (CourtSetup c : courts) {
            insertCourt(venueId, c);
        }
        return find(venueId);
    }

    /** A UPI ID (VPA), e.g. kickarena@ybl or 9876543210@paytm. */
    static final java.util.regex.Pattern UPI_ID = java.util.regex.Pattern.compile(
            "^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,63}$");

    /**
     * Sets (or, with a blank id, clears) the UPI ID players pay this venue's advances to in upi mode, and the name
     * they see on the payment (the venue's name if blank). Clearing it pauses online advances there.
     *
     * @throws VenueNotFoundException      if the venue does not exist or belongs to another owner
     * @throws InvalidVenueSetupException if the UPI ID is malformed or the name too long
     */
    @Transactional
    public VenueView setUpi(long ownerId, long venueId, String upiId, String upiName) {
        access.requireOwnedVenue(ownerId, venueId);
        String id = upiId == null ? "" : upiId.strip();
        String name = upiName == null ? "" : upiName.strip();
        if (!id.isEmpty() && !UPI_ID.matcher(id).matches()) {
            throw new InvalidVenueSetupException("Enter a UPI ID like kickarena@ybl or 9876543210@paytm.");
        }
        if (name.length() > 60) {
            throw new InvalidVenueSetupException("The name on the payment can be at most 60 characters.");
        }
        jdbc.sql("UPDATE venues SET upi_id = :upiId, upi_name = :upiName WHERE id = :venueId")
                .param("upiId", id.isEmpty() ? null : id)
                .param("upiName", id.isEmpty() || name.isEmpty() ? null : name)
                .param("venueId", venueId)
                .update();
        return find(venueId);
    }

    /**
     * Replaces the venue's own settings. Locks the venue and all its courts, and refuses new hours that a court's
     * slots or general price rules would not fit. Existing bookings keep their times and prices.
     *
     * @throws VenueNotFoundException     if the venue does not exist or belongs to another owner
     * @throws InvalidVenueSetupException if the venue or one of its courts would be inconsistent
     */
    @Transactional
    public VenueView update(long ownerId, long venueId, VenueSetup venue) {
        access.lockOwnedVenueAndCourts(ownerId, venueId);
        validateVenue(venue);
        for (CourtView c : courts(venueId)) {
            try {
                validateCourt(setupOf(c), venue.openMinute(), venue.closeMinute());
            } catch (InvalidVenueSetupException e) {
                throw new InvalidVenueSetupException(c.name() + ": " + e.getMessage());
            }
        }
        jdbc.sql("""
                        UPDATE venues SET name = :name, area = :area, address = :address, lat = :lat, lng = :lng,
                                          open_minute = :open, close_minute = :close,
                                          refunds_per_month = :refundsPerMonth
                        WHERE id = :venueId
                        """)
                .param("venueId", venueId)
                .params(venueFields(venue))
                .update();
        return find(venueId);
    }

    /**
     * Adds a court to the owner's venue, after the existing ones.
     *
     * @throws VenueNotFoundException     if the venue does not exist or belongs to another owner
     * @throws InvalidVenueSetupException if the court does not fit the venue or its name is taken
     */
    @Transactional
    public VenueView addCourt(long ownerId, long venueId, CourtSetup court) {
        access.lockOwnedVenue(ownerId, venueId);
        Hours hours = hours(venueId);
        validateCourt(court, hours.openMinute(), hours.closeMinute());
        insertCourt(venueId, court);
        return find(venueId);
    }

    /**
     * Replaces one court's settings and all of its price rules. Existing bookings keep the price stored on them.
     *
     * @throws CourtNotFoundException     if the court does not exist or is at another owner's venue
     * @throws InvalidVenueSetupException if the court does not fit the venue or its name is taken
     */
    @Transactional
    public VenueView updateCourt(long ownerId, long courtId, CourtSetup court) {
        long venueId = access.lockOwnedCourt(ownerId, courtId);
        Hours hours = hours(venueId);
        validateCourt(court, hours.openMinute(), hours.closeMinute());
        try {
            jdbc.sql("""
                            UPDATE courts SET name = :name, slot_minutes = :slot, advance_amount = :advance,
                                              refund_full_hours_before = :refundHours, refund_policy = :refundPolicy,
                                              active = :active
                            WHERE id = :courtId
                            """)
                    .param("courtId", courtId)
                    .params(courtFields(court))
                    .update();
        } catch (DuplicateKeyException e) {
            throw nameTaken(court);
        }
        jdbc.sql("DELETE FROM court_sports WHERE court_id = :courtId").param("courtId", courtId).update();
        jdbc.sql("DELETE FROM price_rules WHERE court_id = :courtId").param("courtId", courtId).update();
        insertSportsAndRules(courtId, court);
        return find(venueId);
    }

    @Transactional(readOnly = true)
    public List<VenueView> listForOwner(long ownerId) {
        List<Long> ids = jdbc.sql("SELECT id FROM venues WHERE owner_id = :ownerId ORDER BY id")
                .param("ownerId", ownerId)
                .query(Long.class)
                .list();
        return ids.stream().map(this::find).toList();
    }

    // --- validation ---

    static void validateVenue(VenueSetup s) {
        if (s.openMinute() < 0 || s.openMinute() > 1439) {
            throw new InvalidVenueSetupException("Opening time must be between 00:00 and 23:59.");
        }
        if (s.closeMinute() <= s.openMinute() || s.closeMinute() > MAX_MINUTE) {
            throw new InvalidVenueSetupException("Closing time must be after opening time.");
        }
        if (s.closeMinute() - s.openMinute() > 1440) {
            throw new InvalidVenueSetupException("A venue can be open for at most 24 hours a day.");
        }
        if (s.refundsPerMonth() < 0 || s.refundsPerMonth() > 31) {
            throw new InvalidVenueSetupException("Refunds per month must be between 0 and 31.");
        }
    }

    void validateCourt(CourtSetup s, int openMinute, int closeMinute) {
        if (s.name() == null || s.name().isBlank() || s.name().strip().length() > 60) {
            throw new InvalidVenueSetupException("Give the court a name of up to 60 characters.");
        }
        List<String> sports = normalizedSports(s.sports());
        if (sports.isEmpty()) {
            throw new InvalidVenueSetupException("Pick at least one sport for " + s.name().strip() + ".");
        }
        Set<String> known = Set.copyOf(jdbc.sql("SELECT code FROM sports").query(String.class).list());
        for (String sport : sports) {
            if (!known.contains(sport)) {
                throw new InvalidVenueSetupException("Unknown sport: " + sport + ".");
            }
        }
        int openFor = closeMinute - openMinute;
        if (s.slotMinutes() <= 0 || s.slotMinutes() > openFor) {
            throw new InvalidVenueSetupException("Slot length must be between 1 minute and the opening hours.");
        }
        if (s.advanceAmount() < 0) {
            throw new InvalidVenueSetupException("Advance amount cannot be negative.");
        }
        if (s.refundFullHoursBefore() < 0 || s.refundFullHoursBefore() > 24 * 30) {
            throw new InvalidVenueSetupException("Refund notice must be between 0 hours and 30 days.");
        }
        validateRules(s.priceRules(), openMinute, closeMinute);
    }

    private static void validateRules(List<PriceRule> rules, int openMinute, int closeMinute) {
        for (PriceRule r : rules) {
            if (r.dayOfWeek() != null && (r.dayOfWeek() < 1 || r.dayOfWeek() > 7)) {
                throw new InvalidVenueSetupException("Day of week must be 1 (Monday) to 7 (Sunday).");
            }
            if (r.startMinute() < 0 || r.endMinute() > MAX_MINUTE || r.endMinute() <= r.startMinute()) {
                throw new InvalidVenueSetupException(
                        "Price rule " + range(r) + " must end after it starts, within the operating day.");
            }
            if (r.rate() < 0) {
                throw new InvalidVenueSetupException("Price rule " + range(r) + " has a negative rate.");
            }
        }

        // Group by specificity: general rules under key 0, day rules under their day.
        Map<Integer, List<PriceRule>> groups = rules.stream()
                .collect(Collectors.groupingBy(r -> Objects.requireNonNullElse(r.dayOfWeek(), 0)));
        for (List<PriceRule> group : groups.values()) {
            List<PriceRule> sorted = group.stream().sorted(Comparator.comparingInt(PriceRule::startMinute)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).startMinute() < sorted.get(i - 1).endMinute()) {
                    throw new InvalidVenueSetupException("Price rules " + range(sorted.get(i - 1)) + " and "
                            + range(sorted.get(i)) + " overlap" + dayLabel(sorted.get(i)) + ".");
                }
            }
        }

        int covered = openMinute;
        for (PriceRule r : groups.getOrDefault(0, List.of()).stream()
                .sorted(Comparator.comparingInt(PriceRule::startMinute)).toList()) {
            if (r.startMinute() > covered) {
                break;
            }
            covered = Math.max(covered, r.endMinute());
        }
        if (covered < closeMinute) {
            throw new InvalidVenueSetupException("No general price is set from " + clock(covered)
                    + ". General price rules must cover all opening hours, "
                    + clock(openMinute) + " to " + clock(closeMinute) + ".");
        }
    }

    /** Lower-cased, trimmed, without duplicates, in the order given. */
    static List<String> normalizedSports(List<String> sports) {
        if (sports == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : sports) {
            if (s != null && !s.isBlank()) {
                out.add(s.strip().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }

    /** Operating-day minute as a wall-clock time, e.g. 1500 -> "01:00 (next day)". */
    static String clock(int minute) {
        String time = "%02d:%02d".formatted((minute / 60) % 24, minute % 60);
        return minute >= 1440 ? time + " (next day)" : time;
    }

    private static String range(PriceRule r) {
        return clock(r.startMinute()) + "-" + clock(r.endMinute());
    }

    private static String dayLabel(PriceRule r) {
        return r.dayOfWeek() == null ? "" : " on " + DayOfWeek.of(r.dayOfWeek());
    }

    private static InvalidVenueSetupException nameTaken(CourtSetup c) {
        return new InvalidVenueSetupException("Another court here is already called " + c.name().strip() + ".");
    }

    // --- persistence ---

    record Hours(int openMinute, int closeMinute) {
    }

    private Hours hours(long venueId) {
        return jdbc.sql("SELECT open_minute, close_minute FROM venues WHERE id = :id")
                .param("id", venueId)
                .query(Hours.class)
                .single();
    }

    private static Map<String, Object> venueFields(VenueSetup s) {
        Map<String, Object> f = new HashMap<>();
        f.put("name", s.name().strip());
        f.put("area", s.area().strip());
        f.put("address", s.address().strip());
        f.put("lat", s.lat());
        f.put("lng", s.lng());
        f.put("open", s.openMinute());
        f.put("close", s.closeMinute());
        f.put("refundsPerMonth", s.refundsPerMonth());
        return f;
    }

    private static Map<String, Object> courtFields(CourtSetup s) {
        Map<String, Object> f = new HashMap<>();
        f.put("name", s.name().strip());
        f.put("slot", s.slotMinutes());
        f.put("advance", s.advanceAmount());
        f.put("refundHours", s.refundFullHoursBefore());
        f.put("refundPolicy", s.refundPolicy() == null || s.refundPolicy().isBlank() ? null : s.refundPolicy().strip());
        f.put("active", s.active());
        return f;
    }

    private void insertCourt(long venueId, CourtSetup c) {
        long courtId;
        try {
            courtId = jdbc.sql("""
                            INSERT INTO courts (venue_id, name, slot_minutes, advance_amount, refund_full_hours_before,
                                                refund_policy, active, sort_order)
                            VALUES (:venueId, :name, :slot, :advance, :refundHours, :refundPolicy, :active,
                                    (SELECT coalesce(max(sort_order), -1) + 1 FROM courts WHERE venue_id = :venueId))
                            RETURNING id
                            """)
                    .param("venueId", venueId)
                    .params(courtFields(c))
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw nameTaken(c);
        }
        insertSportsAndRules(courtId, c);
    }

    private void insertSportsAndRules(long courtId, CourtSetup c) {
        for (String sport : normalizedSports(c.sports())) {
            jdbc.sql("INSERT INTO court_sports (court_id, sport) VALUES (:courtId, :sport)")
                    .param("courtId", courtId)
                    .param("sport", sport)
                    .update();
        }
        for (PriceRule r : c.priceRules()) {
            jdbc.sql("""
                            INSERT INTO price_rules (court_id, day_of_week, start_minute, end_minute, rate)
                            VALUES (:courtId, CAST(:day AS smallint), :start, :end, :rate)
                            """)
                    .param("courtId", courtId)
                    .param("day", r.dayOfWeek())
                    .param("start", r.startMinute())
                    .param("end", r.endMinute())
                    .param("rate", r.rate())
                    .update();
        }
    }

    private static CourtSetup setupOf(CourtView c) {
        return new CourtSetup(c.name(), c.sports(), c.slotMinutes(), c.advanceAmount(), c.refundFullHoursBefore(),
                c.refundPolicy(), c.active(), c.priceRules());
    }

    /** The venue with all its courts (active or not), in display order. */
    public VenueView find(long venueId) {
        List<CourtView> courts = courts(venueId);
        return jdbc.sql("SELECT * FROM venues WHERE id = :venueId")
                .param("venueId", venueId)
                .query((rs, n) -> new VenueView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("area"),
                        rs.getString("address"),
                        rs.getBigDecimal("lat"),
                        rs.getBigDecimal("lng"),
                        rs.getInt("open_minute"),
                        rs.getInt("close_minute"),
                        rs.getInt("refunds_per_month"),
                        rs.getBoolean("active"),
                        rs.getBoolean("approved"),
                        rs.getString("razorpay_account_id"),
                        rs.getString("razorpay_account_status"),
                        rs.getString("upi_id"),
                        rs.getString("upi_name"),
                        linkedAccounts.acceptsOnlineAdvance(rs.getString("razorpay_account_status"),
                                rs.getString("upi_id")),
                        courts))
                .optional()
                .orElseThrow(() -> new VenueNotFoundException(venueId));
    }

    List<CourtView> courts(long venueId) {
        record Row(long id, long venueId, String name, int slotMinutes, long advanceAmount, int refundFullHoursBefore,
                   String refundPolicy, boolean active, int sortOrder) {
        }
        List<Row> rows = jdbc.sql("SELECT * FROM courts WHERE venue_id = :venueId ORDER BY sort_order, id")
                .param("venueId", venueId)
                .query(Row.class)
                .list();
        return rows.stream().map(r -> new CourtView(r.id(), r.venueId(), r.name(), sportsOf(r.id()), r.slotMinutes(),
                r.advanceAmount(), r.refundFullHoursBefore(), r.refundPolicy(), r.active(), r.sortOrder(),
                rulesOf(r.id()))).toList();
    }

    private List<String> sportsOf(long courtId) {
        return jdbc.sql("""
                        SELECT cs.sport FROM court_sports cs JOIN sports s ON s.code = cs.sport
                        WHERE cs.court_id = :courtId ORDER BY s.sort_order
                        """)
                .param("courtId", courtId)
                .query(String.class)
                .list();
    }

    private List<PriceRule> rulesOf(long courtId) {
        return jdbc.sql("""
                        SELECT day_of_week, start_minute, end_minute, rate FROM price_rules
                        WHERE court_id = :courtId
                        ORDER BY day_of_week NULLS FIRST, start_minute
                        """)
                .param("courtId", courtId)
                .query(PriceRule.class)
                .list();
    }
}
