package com.turfup.tournament;

/**
 * The match moved on from what the scorer screen showed (a ball out of turn, an undo of a ball that isn't the latest,
 * an innings that already ended). Carries the current match so the screen can catch up; the problem response has it
 * as {@code match}.
 */
public class ScoreConflictException extends RuntimeException {

    private final MatchView match;

    public ScoreConflictException(String message, MatchView match) {
        super(message);
        this.match = match;
    }

    public MatchView getMatch() {
        return match;
    }
}
