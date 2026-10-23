package com.turfup.tournament;

/** No such match, innings or ball (or the tournament module is switched off). */
public class MatchNotFoundException extends RuntimeException {

    public MatchNotFoundException(String what, Object id) {
        super("There is no " + what + " " + id + ".");
    }
}
