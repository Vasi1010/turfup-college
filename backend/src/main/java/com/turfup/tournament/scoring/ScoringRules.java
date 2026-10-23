package com.turfup.tournament.scoring;

/**
 * The organiser's rules that scoring depends on: the tournament's settings, with a shortened match's own overs.
 *
 * <p>"Last man stands" is not supported yet: every ball needs two batters, so an innings is all out at
 * {@code playersPerSide - 1} wickets.
 *
 * @param maxOversPerBowler null: no limit
 * @param wideRuns          penalty runs for a wide
 * @param noBallRuns        penalty runs for a no-ball
 * @param noLbw             local rule: no LBW dismissals
 * @param freeHit           local rule: the ball after a no-ball is a free hit
 */
public record ScoringRules(int oversPerInnings, Integer maxOversPerBowler, int ballsPerOver, int playersPerSide,
                           int wideRuns, int noBallRuns, boolean noLbw, boolean freeHit) {

    public ScoringRules {
        if (oversPerInnings < 1 || oversPerInnings > 50) {
            throw new IllegalArgumentException("Overs per innings must be between 1 and 50.");
        }
        if (maxOversPerBowler != null && (maxOversPerBowler < 1 || maxOversPerBowler > oversPerInnings)) {
            throw new IllegalArgumentException("A bowler's over limit must be between 1 and the overs per innings.");
        }
        if (ballsPerOver < 4 || ballsPerOver > 8) {
            throw new IllegalArgumentException("Balls per over must be between 4 and 8.");
        }
        if (playersPerSide < 2 || playersPerSide > 11) {
            throw new IllegalArgumentException("Players per side must be between 2 and 11.");
        }
        if (wideRuns < 0 || wideRuns > 2 || noBallRuns < 0 || noBallRuns > 2) {
            throw new IllegalArgumentException("Wide and no-ball runs must be between 0 and 2.");
        }
    }

    /** The same rules for a match shortened to {@code overs}; the bowler's limit shrinks with it if needed. */
    public ScoringRules withOvers(int overs) {
        Integer limit = maxOversPerBowler == null ? null : Math.min(maxOversPerBowler, overs);
        return new ScoringRules(overs, limit, ballsPerOver, playersPerSide, wideRuns, noBallRuns, noLbw, freeHit);
    }

    public int maxLegalBalls() {
        return oversPerInnings * ballsPerOver;
    }

    /** Wickets that end an innings. */
    public int allOutWickets() {
        return playersPerSide - 1;
    }

    /** Legal balls as overs, e.g. 45 balls of 6 is "7.3". */
    public String overs(int legalBalls) {
        return legalBalls / ballsPerOver + "." + legalBalls % ballsPerOver;
    }
}
