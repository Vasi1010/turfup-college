package com.turfup.booking;

/** The player already holds another slot; only one active hold per player is allowed. */
public class ActiveHoldExistsException extends RuntimeException {

    public ActiveHoldExistsException(long playerId) {
        super("Player " + playerId + " already has a slot on hold. "
                + "Complete or cancel that booking before holding another slot.");
    }
}
