package com.turfup.venue;

import com.turfup.venue.PublicVenueService.AreaCount;
import com.turfup.venue.PublicVenueService.SportCount;
import com.turfup.venue.PublicVenueService.VenueCard;
import com.turfup.venue.PublicVenueService.VenueDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public browse endpoints (no login). Only active, approved venues and their active courts. */
@RestController
@RequestMapping("/api/venues")
class PublicVenueController {

    private final PublicVenueService venues;

    PublicVenueController(PublicVenueService venues) {
        this.venues = venues;
    }

    @GetMapping
    List<VenueCard> list(@RequestParam(required = false) String area, @RequestParam(required = false) String sport) {
        return venues.list(area, sport);
    }

    @GetMapping("/areas")
    List<AreaCount> areas() {
        return venues.areas();
    }

    @GetMapping("/sports")
    List<SportCount> sports() {
        return venues.sports();
    }

    @GetMapping("/{venueId}")
    VenueDetail detail(@PathVariable long venueId) {
        return venues.detail(venueId);
    }
}
