package com.turfup.booking;

import com.turfup.auth.TokenService;
import com.turfup.booking.RecurringBookingService.Ended;
import com.turfup.booking.RecurringBookingService.RecurringRequest;
import com.turfup.booking.RecurringBookingService.RecurringView;
import com.turfup.booking.RecurringBookingService.Saved;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Owner: standing slots (recurring bookings). */
@RestController
@RequestMapping("/api/owner")
class OwnerRecurringController {

    private final RecurringBookingService recurring;

    OwnerRecurringController(RecurringBookingService recurring) {
        this.recurring = recurring;
    }

    /** Every standing slot at the venue, on all its courts. */
    @GetMapping("/venues/{venueId}/recurring")
    List<RecurringView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long venueId) {
        return recurring.list(TokenService.userId(jwt), venueId);
    }

    /** Times are operating-day minutes; {@code validFrom} defaults to today, {@code validTo} to open-ended. */
    @PostMapping("/courts/{courtId}/recurring")
    @ResponseStatus(HttpStatus.CREATED)
    Saved create(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId, @RequestBody RecurringRequest req) {
        return recurring.create(TokenService.userId(jwt), courtId, req);
    }

    /** Replaces the pattern (optionally on another court of the venue); the response has the new standing slot's id. */
    @PutMapping("/recurring/{recurringId}")
    Saved update(@AuthenticationPrincipal Jwt jwt, @PathVariable long recurringId, @RequestBody RecurringRequest req) {
        return recurring.update(TokenService.userId(jwt), recurringId, req);
    }

    @PostMapping("/recurring/{recurringId}/end")
    Ended end(@AuthenticationPrincipal Jwt jwt, @PathVariable long recurringId) {
        return recurring.end(TokenService.userId(jwt), recurringId);
    }
}
