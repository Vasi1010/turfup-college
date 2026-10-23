package com.turfup.venue;

/** The requested time is not a bookable slot on this court. */
public class InvalidSlotException extends RuntimeException {

    public enum Reason {
        PAST("This slot has already started. Please pick a later time."),
        EARLIER_DAY("Bookings can be entered for today or later, not for earlier days."),
        OUTSIDE_HOURS("The venue is closed at this time."),
        MISALIGNED("Please pick one of the listed slots.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public InvalidSlotException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
