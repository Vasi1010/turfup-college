package com.turfup.booking;

/** No such booking, or it belongs to another player or to another owner's venue. */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(long bookingId) {
        super("Booking " + bookingId + " not found");
    }
}
