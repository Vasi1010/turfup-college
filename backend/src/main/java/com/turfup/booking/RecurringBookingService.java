package com.turfup.booking;

import com.turfup.auth.PlayerNotFoundException;
import com.turfup.booking.BookingService.LockedCourt;
import com.turfup.payment.PaymentMethod;
import com.turfup.payment.PaymentService;
import com.turfup.payment.RefundReason;
import com.turfup.pricing.DayPricing;
import com.turfup.pricing.PricingService;
import com.turfup.venue.CourtHours;
import com.turfup.venue.CourtHours.Booker;
import com.turfup.venue.CourtNotFoundException;
import com.turfup.venue.InvalidSlotException;
import com.turfup.venue.VenueAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalLong;

/**
 * Standing slots ("every Tuesday 8-9 PM on court 2 for Rahul"), set up once by the owner on one court. Their
 * bookings are generated four weeks ahead as CONFIRMED phone bookings with no advance, each linked by
 * {@code recurring_id}.
 *
 * <p>Generation takes the same court lock as every other booking path. An occurrence that cannot be booked
 * (slot taken, outside opening hours, no price) is skipped and recorded in {@code recurring_skips} for the
 * owner's Today screen instead of failing; later runs retry it. Each occurrence is generated at most once
 * (unique index), so one the owner or player cancelled is never recreated.
 */
@Service
public class RecurringBookingService {

    /** Weeks of bookings kept generated ahead. */
    static final int HORIZON_DAYS = 28;

    /** {@code courtId} is only read when changing a standing slot (moving it to another court of the venue). */
    public record RecurringRequest(Long playerId, Integer dayOfWeek, Integer startMinute, Integer endMinute,
                                   LocalDate validFrom, LocalDate validTo, Long courtId) {
    }

    /** A standing slot as the owner sees it. {@code upcoming}: generated future bookings still confirmed. */
    public record RecurringView(long id, long courtId, String courtName, long playerId, String playerName,
                                String playerPhone,
                                int dayOfWeek, int startMinute, int endMinute, LocalDate validFrom, LocalDate validTo,
                                long upcoming, long skipped) {
    }

    public record Generated(int created, int skipped) {
    }

    public record Saved(RecurringView recurring, Generated generated) {
    }

    public record Ended(long recurringId, int cancelled) {
    }

    record Recurring(long id, long courtId, long playerId, int dayOfWeek, int startMinute, int endMinute,
                     LocalDate validFrom, LocalDate validTo, OffsetDateTime endedAt) {
    }

    private final JdbcClient jdbc;
    private final BookingService bookings;
    private final PricingService pricing;
    private final PaymentService payments;
    private final VenueAccess access;

    public RecurringBookingService(JdbcClient jdbc, BookingService bookings, PricingService pricing,
                                   PaymentService payments, VenueAccess access) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.pricing = pricing;
        this.payments = payments;
        this.access = access;
    }

    // --- owner actions ---

    /** The venue's standing slots (all courts) that have not been ended. */
    @Transactional(readOnly = true)
    public List<RecurringView> list(long ownerId, long venueId) {
        access.requireOwnedVenue(ownerId, venueId);
        return jdbc.sql(VIEW_SQL + """
                         WHERE c.venue_id = :venueId AND r.ended_at IS NULL
                        ORDER BY r.day_of_week, r.start_minute, c.sort_order, c.id
                        """)
                .param("venueId", venueId)
                .query(RecurringView.class)
                .list();
    }

    /**
     * Creates a standing slot on one of the owner's courts and generates its next four weeks of bookings.
     *
     * @throws CourtNotFoundException    if the court is not at one of the owner's venues
     * @throws PlayerNotFoundException   if the player does not exist
     * @throws InvalidRecurringException if the pattern does not fit the court
     */
    @Transactional
    public Saved create(long ownerId, long courtId, RecurringRequest req) {
        access.venueOfOwnedCourt(ownerId, courtId);
        LockedCourt court = bookings.lockCourtForJob(courtId);
        long id = insert(courtId, court, req);
        Generated generated = generateLocked(load(id), court);
        return new Saved(view(id), generated);
    }

    /**
     * Changes a standing slot: ends the old one (cancelling its future bookings) and creates the new pattern,
     * on the same court or, with {@code courtId}, another court of the same venue. Returns the new standing slot,
     * which has a new id. Moving courts locks both courts in id order.
     *
     * @throws CourtNotFoundException if {@code courtId} is not a court of the same venue
     */
    @Transactional
    public Saved update(long ownerId, long recurringId, RecurringRequest req) {
        Recurring old = loadOwned(ownerId, recurringId);
        long courtId = req.courtId() != null ? req.courtId() : old.courtId();
        if (courtId != old.courtId()) {
            long oldVenue = access.venueOfOwnedCourt(ownerId, old.courtId());
            if (access.venueOfOwnedCourt(ownerId, courtId) != oldVenue) {
                throw new CourtNotFoundException(courtId);
            }
        }
        LockedCourt first = bookings.lockCourtForJob(Math.min(courtId, old.courtId()));
        LockedCourt second = courtId == old.courtId() ? first : bookings.lockCourtForJob(Math.max(courtId, old.courtId()));
        LockedCourt court = courtId <= old.courtId() ? first : second;
        endLocked(old);
        RecurringRequest merged = new RecurringRequest(
                req.playerId() != null ? req.playerId() : old.playerId(), req.dayOfWeek(), req.startMinute(),
                req.endMinute(), req.validFrom(), req.validTo(), courtId);
        long id = insert(courtId, court, merged);
        Generated generated = generateLocked(load(id), court);
        return new Saved(view(id), generated);
    }

    /**
     * Ends a standing slot: no more bookings are generated, and its future bookings are cancelled by the venue
     * (reason RECURRING_CHANGED). They had no advance; a balance already collected is refunded. Owner
     * cancellations never count toward a player's monthly refund allowance.
     */
    @Transactional
    public Ended end(long ownerId, long recurringId) {
        Recurring r = loadOwned(ownerId, recurringId);
        bookings.lockCourtForJob(r.courtId());
        return new Ended(recurringId, endLocked(r));
    }

    // --- generation (daily job, and right after create/update) ---

    /** Ids of standing slots still running, in court order (the order the job locks courts in). */
    @Transactional(readOnly = true)
    public List<Long> activeIds() {
        return jdbc.sql("SELECT id FROM recurring_bookings WHERE ended_at IS NULL ORDER BY court_id, id")
                .query(Long.class)
                .list();
    }

    /** Generates the missing bookings of one standing slot in the next four weeks. One transaction, one court lock. */
    @Transactional
    public Generated generate(long recurringId) {
        Recurring r = load(recurringId);
        if (r.endedAt() != null) {
            return new Generated(0, 0);
        }
        return generateLocked(r, bookings.lockCourtForJob(r.courtId()));
    }

    private Generated generateLocked(Recurring r, LockedCourt court) {
        CourtHours hours = court.hours();
        OffsetDateTime now = court.dbNow();
        LocalDate today = hours.currentOperatingDate(now);
        LocalDate from = r.validFrom().isAfter(today) ? r.validFrom() : today;
        LocalDate horizon = today.plusDays(HORIZON_DAYS - 1);
        LocalDate to = r.validTo() != null && r.validTo().isBefore(horizon) ? r.validTo() : horizon;
        DayPricing prices = pricing.forDay(r.courtId(), DayOfWeek.of(r.dayOfWeek()));

        int created = 0;
        int skipped = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() != r.dayOfWeek()) {
                continue;
            }
            OffsetDateTime start = d.atStartOfDay(CourtHours.IST).plusMinutes(r.startMinute()).toOffsetDateTime();
            OffsetDateTime end = d.atStartOfDay(CourtHours.IST).plusMinutes(r.endMinute()).toOffsetDateTime();
            if (!start.isAfter(now) || occurrenceExists(r.id(), start)) {
                continue;
            }

            String reason = null;
            OptionalLong price = OptionalLong.empty();
            try {
                hours.validateSlot(start, end, now, Booker.OWNER);
                price = prices.find(r.startMinute(), r.endMinute());
                if (price.isEmpty()) {
                    reason = "NO_PRICE";
                }
            } catch (InvalidSlotException e) {
                reason = "OUTSIDE_HOURS";
            }
            if (reason == null) {
                bookings.expireStaleHolds(r.courtId(), start, end, null);
                if (bookings.slotTaken(r.courtId(), start, end)) {
                    reason = "SLOT_TAKEN";
                }
            }

            if (reason != null) {
                recordSkip(r.id(), start, end, reason);
                skipped++;
                continue;
            }
            bookings.insert(r.courtId(), r.playerId(), null, null, start, end, BookingStatus.CONFIRMED,
                    BookingSource.PHONE, price.getAsLong(), 0, null, r.id());
            jdbc.sql("DELETE FROM recurring_skips WHERE recurring_id = :id AND start_at = :start")
                    .param("id", r.id()).param("start", start).update();
            created++;
        }
        return new Generated(created, skipped);
    }

    // --- helpers ---

    private long insert(long courtId, LockedCourt court, RecurringRequest req) {
        validate(court, req);
        LocalDate today = court.hours().currentOperatingDate(court.dbNow());
        LocalDate validFrom = req.validFrom() != null ? req.validFrom() : today;
        return jdbc.sql("""
                        INSERT INTO recurring_bookings (court_id, player_id, day_of_week, start_minute, end_minute,
                                                        valid_from, valid_to)
                        VALUES (:courtId, :playerId, :day, :start, :end, :from, :to)
                        RETURNING id
                        """)
                .param("courtId", courtId)
                .param("playerId", req.playerId())
                .param("day", req.dayOfWeek())
                .param("start", req.startMinute())
                .param("end", req.endMinute())
                .param("from", validFrom)
                .param("to", req.validTo())
                .query(Long.class)
                .single();
    }

    private void validate(LockedCourt court, RecurringRequest req) {
        if (req.playerId() == null) {
            throw new InvalidRecurringException("Pick the player this slot is for.");
        }
        jdbc.sql("SELECT 1 FROM users WHERE id = :id AND role = 'PLAYER'")
                .param("id", req.playerId())
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new PlayerNotFoundException(req.playerId()));
        if (req.dayOfWeek() == null || req.dayOfWeek() < 1 || req.dayOfWeek() > 7) {
            throw new InvalidRecurringException("Pick a day of the week.");
        }
        if (req.startMinute() == null || req.endMinute() == null || req.endMinute() <= req.startMinute()) {
            throw new InvalidRecurringException("The slot must end after it starts.");
        }
        CourtHours h = court.hours();
        if (req.startMinute() < h.openMinute() || req.endMinute() > h.closeMinute()) {
            throw new InvalidRecurringException("The slot must be within the venue's opening hours.");
        }
        if ((req.startMinute() - h.openMinute()) % h.slotMinutes() != 0
                || (req.endMinute() - req.startMinute()) % h.slotMinutes() != 0) {
            throw new InvalidRecurringException("Pick one of the court's slots.");
        }
        if (req.validFrom() != null && req.validTo() != null && req.validTo().isBefore(req.validFrom())) {
            throw new InvalidRecurringException("The end date must be on or after the start date.");
        }
    }

    private int endLocked(Recurring r) {
        jdbc.sql("UPDATE recurring_bookings SET ended_at = now() WHERE id = :id AND ended_at IS NULL")
                .param("id", r.id()).update();
        List<Long> future = jdbc.sql("""
                        SELECT id FROM bookings
                        WHERE recurring_id = :id AND status = 'CONFIRMED' AND start_at > now()
                        FOR UPDATE
                        """)
                .param("id", r.id())
                .query(Long.class)
                .list();
        for (long bookingId : future) {
            record Balance(long id, long amount, String method) {
            }
            jdbc.sql("""
                            SELECT id, amount, method FROM payments
                            WHERE booking_id = :id AND kind = 'BALANCE' AND status = 'SUCCEEDED'
                            """)
                    .param("id", bookingId)
                    .query(Balance.class)
                    .optional()
                    .ifPresent(b -> payments.recordRefund(bookingId, b.amount(), PaymentMethod.valueOf(b.method()), null,
                            RefundReason.OWNER_CANCELLATION, b.id()));
        }
        if (!future.isEmpty()) {
            jdbc.sql("""
                            UPDATE bookings SET status = 'CANCELLED', cancelled_by = 'OWNER',
                                                cancel_reason = 'RECURRING_CHANGED'
                            WHERE id IN (:ids)
                            """)
                    .param("ids", future)
                    .update();
        }
        jdbc.sql("DELETE FROM recurring_skips WHERE recurring_id = :id AND start_at > now()")
                .param("id", r.id()).update();
        return future.size();
    }

    private boolean occurrenceExists(long recurringId, OffsetDateTime start) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM bookings WHERE recurring_id = :id AND start_at = :start)")
                .param("id", recurringId)
                .param("start", start)
                .query(Boolean.class)
                .single();
    }

    private void recordSkip(long recurringId, OffsetDateTime start, OffsetDateTime end, String reason) {
        jdbc.sql("""
                        INSERT INTO recurring_skips (recurring_id, start_at, end_at, reason)
                        VALUES (:id, :start, :end, :reason)
                        ON CONFLICT (recurring_id, start_at) DO UPDATE SET reason = EXCLUDED.reason
                        """)
                .param("id", recurringId)
                .param("start", start)
                .param("end", end)
                .param("reason", reason)
                .update();
    }

    private Recurring load(long id) {
        return jdbc.sql("SELECT * FROM recurring_bookings WHERE id = :id")
                .param("id", id)
                .query(Recurring.class)
                .optional()
                .orElseThrow(() -> new RecurringNotFoundException(id));
    }

    /** Loads a standing slot that is the owner's and still running. */
    private Recurring loadOwned(long ownerId, long id) {
        return jdbc.sql("""
                        SELECT r.* FROM recurring_bookings r JOIN courts c ON c.id = r.court_id
                        JOIN venues v ON v.id = c.venue_id
                        WHERE r.id = :id AND v.owner_id = :ownerId AND r.ended_at IS NULL
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .query(Recurring.class)
                .optional()
                .orElseThrow(() -> new RecurringNotFoundException(id));
    }

    private RecurringView view(long id) {
        return jdbc.sql(VIEW_SQL + " WHERE r.id = :id").param("id", id).query(RecurringView.class).single();
    }

    private static final String VIEW_SQL = """
            SELECT r.id, r.court_id, c.name AS court_name, r.player_id, u.name AS player_name, u.phone AS player_phone,
                   r.day_of_week, r.start_minute, r.end_minute, r.valid_from, r.valid_to,
                   (SELECT count(*) FROM bookings b
                    WHERE b.recurring_id = r.id AND b.status = 'CONFIRMED' AND b.start_at > now()) AS upcoming,
                   (SELECT count(*) FROM recurring_skips s
                    WHERE s.recurring_id = r.id AND s.start_at > now()) AS skipped
            FROM recurring_bookings r JOIN users u ON u.id = r.player_id JOIN courts c ON c.id = r.court_id
            """;
}
