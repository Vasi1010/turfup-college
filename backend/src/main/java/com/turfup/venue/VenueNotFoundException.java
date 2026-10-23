package com.turfup.venue;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No such venue (or not one this caller may see: not public, or another owner's). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class VenueNotFoundException extends RuntimeException {

    public VenueNotFoundException(long venueId) {
        super("Venue " + venueId + " not found");
    }
}
