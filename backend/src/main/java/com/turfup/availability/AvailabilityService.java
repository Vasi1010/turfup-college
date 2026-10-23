package com.turfup.availability;

import com.turfup.booking.HoldExpirySql;
import com.turfup.pricing.DayPricing;
import com.turfup.pricing.PricingService;
import com.turfup.settings.AppSettings;
import com.turfup.venue.CourtHours;
import com.turfup.venue.CourtHoursRepository;
import com.turfup.venue.CourtHoursRepository.PublicCourtHours;
import com.turfup.venue.CourtNotFoundException;
import com.turfup.venue.OperatingSlot;
import com.turfup.venue.VenueNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Lists a court's slots for one operating day. A slot is BOOKED or HELD when it overlaps a row that the
 * bookings exclusion constraint treats as active (CONFIRMED, or HELD and not expired per
 * {@link HoldExpirySql}); otherwise it is AVAILABLE. Slots that have already started, or that have no
 * price rule, are not bookable and are left out.
 *
 * <p>"Any free court" combines the courts of one sport at a venue: a slot is AVAILABLE if any of them has it
 * free, priced at the cheapest free one.
 */
@Service
public class AvailabilityService {

    /** The bookable slots of one court on one operating day. */
    public record CourtSlots(long courtId, LocalDate date, int slotMinutes, List<SlotView> slots) {
    }

    /** One "any court" slot: {@code price} is the cheapest free court's (or the cheapest court's, if none is free). */
    public record AnyCourtSlot(OffsetDateTime start, OffsetDateTime end, long price, SlotStatus status, int freeCourts) {
    }

    public record AnyCourtSlots(long venueId, String sport, LocalDate date, int slotMinutes, List<AnyCourtSlot> slots) {
    }

    private final JdbcClient jdbc;
    private final CourtHoursRepository courts;
    private final PricingService pricing;
    private final AppSettings settings;

    public AvailabilityService(JdbcClient jdbc, CourtHoursRepository courts, PricingService pricing,
                               AppSettings settings) {
        this.jdbc = jdbc;
        this.courts = courts;
        this.pricing = pricing;
        this.settings = settings;
    }

    record ActiveBooking(OffsetDateTime startAt, OffsetDateTime endAt, String status) {

        boolean overlaps(OperatingSlot slot) {
            return startAt.isBefore(slot.endAt()) && slot.startAt().isBefore(endAt);
        }
    }

    /**
     * @param operatingDate the venue's operating day; slots after midnight belong to the previous date
     * @throws CourtNotFoundException if the court (or its venue) does not exist, is inactive or is not approved
     */
    @Transactional(readOnly = true)
    public CourtSlots slotsFor(long courtId, LocalDate operatingDate) {
        CourtHours hours = courts.findPublic(courtId).orElseThrow(() -> new CourtNotFoundException(courtId));
        return new CourtSlots(courtId, operatingDate, hours.slotMinutes(), slots(courtId, hours, operatingDate, dbNow()));
    }

    /**
     * "Any free court" for one sport at a venue.
     *
     * @throws VenueNotFoundException   if no public court of the venue offers the sport
     * @throws IllegalArgumentException if the sport's courts do not share a slot length (pick a court instead)
     */
    @Transactional(readOnly = true)
    public AnyCourtSlots anyCourtSlots(long venueId, String sport, LocalDate operatingDate) {
        List<PublicCourtHours> candidates = courts.findPublicForSport(venueId, sport);
        if (candidates.isEmpty()) {
            throw new VenueNotFoundException(venueId);
        }
        int slotMinutes = candidates.getFirst().hours().slotMinutes();
        if (candidates.stream().anyMatch(c -> c.hours().slotMinutes() != slotMinutes)) {
            throw new IllegalArgumentException("These courts have different slot lengths. Please pick a court.");
        }

        OffsetDateTime now = dbNow();
        Map<OffsetDateTime, List<SlotView>> byStart = new LinkedHashMap<>();
        for (PublicCourtHours c : candidates) {
            for (SlotView s : slots(c.courtId(), c.hours(), operatingDate, now)) {
                byStart.computeIfAbsent(s.start(), k -> new ArrayList<>()).add(s);
            }
        }
        List<AnyCourtSlot> merged = new ArrayList<>();
        byStart.values().stream()
                .sorted((a, b) -> a.getFirst().start().compareTo(b.getFirst().start()))
                .forEach(views -> merged.add(merge(views)));
        return new AnyCourtSlots(venueId, sport, operatingDate, slotMinutes, merged);
    }

    private static AnyCourtSlot merge(List<SlotView> views) {
        List<SlotView> free = views.stream().filter(v -> v.status() == SlotStatus.AVAILABLE).toList();
        SlotView first = views.getFirst();
        if (!free.isEmpty()) {
            long price = free.stream().mapToLong(SlotView::price).min().orElseThrow();
            return new AnyCourtSlot(first.start(), first.end(), price, SlotStatus.AVAILABLE, free.size());
        }
        long price = views.stream().mapToLong(SlotView::price).min().orElseThrow();
        boolean held = views.stream().anyMatch(v -> v.status() == SlotStatus.HELD);
        return new AnyCourtSlot(first.start(), first.end(), price, held ? SlotStatus.HELD : SlotStatus.BOOKED, 0);
    }

    private OffsetDateTime dbNow() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single();
    }

    private List<SlotView> slots(long courtId, CourtHours hours, LocalDate operatingDate, OffsetDateTime now) {
        List<OperatingSlot> slots = hours.slotsOn(operatingDate);
        if (slots.isEmpty()) {
            return List.of();
        }
        DayPricing prices = pricing.forDay(courtId, operatingDate.getDayOfWeek());
        List<ActiveBooking> active = jdbc.sql("""
                        SELECT start_at, end_at, status FROM bookings
                        WHERE court_id = :courtId
                          AND status IN ('HELD', 'CONFIRMED')
                          AND NOT (status = 'HELD' AND %s)
                          AND tstzrange(start_at, end_at) && tstzrange(:from, :to)
                        """.formatted(HoldExpirySql.IS_EXPIRED))
                .param("courtId", courtId)
                .param("graceMinutes", settings.graceMinutes())
                .param("from", slots.getFirst().startAt())
                .param("to", slots.getLast().endAt())
                .query(ActiveBooking.class)
                .list();

        List<SlotView> views = new ArrayList<>();
        for (OperatingSlot slot : slots) {
            if (!slot.startAt().isAfter(now)) {
                continue;
            }
            OptionalLong price = prices.find(slot.startMinute(), slot.endMinute());
            if (price.isEmpty()) {
                continue;
            }
            views.add(new SlotView(slot.startAt(), slot.endAt(), price.getAsLong(), statusOf(slot, active)));
        }
        return views;
    }

    private static SlotStatus statusOf(OperatingSlot slot, List<ActiveBooking> active) {
        SlotStatus status = SlotStatus.AVAILABLE;
        for (ActiveBooking b : active) {
            if (b.overlaps(slot)) {
                if (b.status().equals("CONFIRMED")) {
                    return SlotStatus.BOOKED;
                }
                status = SlotStatus.HELD;
            }
        }
        return status;
    }
}
