package com.turfup.venue;

/** The owner's venue or court settings (hours, slots, price rules, sports) are inconsistent; nothing was saved. */
public class InvalidVenueSetupException extends RuntimeException {

    public InvalidVenueSetupException(String message) {
        super(message);
    }
}
