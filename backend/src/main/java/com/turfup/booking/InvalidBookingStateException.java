package com.turfup.booking;

/** The action does not apply to the booking's current status. */
public class InvalidBookingStateException extends RuntimeException {

    public InvalidBookingStateException(String message) {
        super(message);
    }
}
