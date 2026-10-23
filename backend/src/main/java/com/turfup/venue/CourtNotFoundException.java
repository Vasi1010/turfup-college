package com.turfup.venue;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No such court (or not one this caller may book or manage). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CourtNotFoundException extends RuntimeException {

    public CourtNotFoundException(long courtId) {
        super("Court " + courtId + " not found");
    }
}
