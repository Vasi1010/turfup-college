package com.turfup.availability;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Public. {@code date} is the operating day; slots after midnight are listed under the previous date. */
@RestController
class SlotsController {

    private final AvailabilityService availability;

    SlotsController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/api/courts/{courtId}/slots")
    AvailabilityService.CourtSlots courtSlots(@PathVariable long courtId,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return availability.slotsFor(courtId, date);
    }

    /** "Any free court" for one sport at a venue. */
    @GetMapping("/api/venues/{venueId}/slots")
    AvailabilityService.AnyCourtSlots anyCourtSlots(@PathVariable long venueId, @RequestParam String sport,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return availability.anyCourtSlots(venueId, sport, date);
    }
}
