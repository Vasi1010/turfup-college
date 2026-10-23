package com.turfup.booking;

/** No running standing slot with this id at the owner's venues. */
public class RecurringNotFoundException extends RuntimeException {

    public RecurringNotFoundException(long id) {
        super("Recurring booking " + id + " not found");
    }
}
