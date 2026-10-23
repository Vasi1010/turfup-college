package com.turfup.booking;

/** A standing slot's pattern does not fit the court (day, hours, alignment, dates). */
public class InvalidRecurringException extends RuntimeException {

    public InvalidRecurringException(String message) {
        super(message);
    }
}
