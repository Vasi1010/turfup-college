package com.turfup.booking;

/** The requested time overlaps an active (HELD or CONFIRMED) booking on the same court. */
public class SlotUnavailableException extends RuntimeException {

    public SlotUnavailableException() {
        super("This slot is no longer available on this court. Please pick another time.");
    }
}
