package com.turfup.tournament.scoring;

/** A ball that can't be recorded as given: the message says why, in words a scorer understands. */
public class InvalidDeliveryException extends RuntimeException {

    public InvalidDeliveryException(String message) {
        super(message);
    }
}
