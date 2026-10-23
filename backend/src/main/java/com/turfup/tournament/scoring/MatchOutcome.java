package com.turfup.tournament.scoring;

import java.util.Optional;

/**
 * The result of a match decided on the field. A tie is a tie here; a super over, if the organiser plays one, is
 * recorded by the admin.
 *
 * @param winningInnings 1 when the side batting first won, 2 when the chase succeeded; null for a tie
 * @param runMargin      won by this many runs (side batting first)
 * @param wicketMargin   won by this many wickets (chasing side)
 * @param ballsLeft      legal balls left when the chase succeeded
 */
public record MatchOutcome(Result result, Integer winningInnings, int runMargin, int wicketMargin, int ballsLeft) {

    public enum Result { WIN, TIE }

    /**
     * Empty while the match is still going, or when an innings was closed early (rain): then the admin records the
     * result.
     */
    public static Optional<MatchOutcome> decide(InningsState first, InningsState second) {
        if (first == null || second == null || !first.ended()) {
            return Optional.empty();
        }
        if (first.endReason() == InningsEndReason.CLOSED || second.endReason() == InningsEndReason.CLOSED) {
            return Optional.empty();
        }
        if (second.endReason() == InningsEndReason.TARGET) {
            return Optional.of(new MatchOutcome(Result.WIN, 2, 0, second.wicketsInHand(), second.ballsLeft()));
        }
        if (!second.ended()) {
            return Optional.empty();
        }
        if (second.runs() == first.runs()) {
            return Optional.of(new MatchOutcome(Result.TIE, null, 0, 0, 0));
        }
        return Optional.of(new MatchOutcome(Result.WIN, 1, first.runs() - second.runs(), 0, 0));
    }
}
