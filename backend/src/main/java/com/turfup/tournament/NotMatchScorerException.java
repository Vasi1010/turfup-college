package com.turfup.tournament;

/** Only the match's assigned scorer, or an admin, may score it. */
public class NotMatchScorerException extends RuntimeException {

    public NotMatchScorerException() {
        super("You are not the scorer of this match.");
    }
}
