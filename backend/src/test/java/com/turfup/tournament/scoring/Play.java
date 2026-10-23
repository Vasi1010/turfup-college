package com.turfup.tournament.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scores an innings ball by ball the way the scorer screen does: each ball takes the engine's suggested batters and
 * bowler unless the test picks them. Batting squad 1-6 (openers 1 and 2), bowling squad 11-16 (opening bowler 11).
 */
final class Play {

    static final List<Long> BATTING = List.of(1L, 2L, 3L, 4L, 5L, 6L);
    static final List<Long> BOWLING = List.of(11L, 12L, 13L, 14L, 15L, 16L);

    /** 2 overs of 6, one over per bowler, 4 a side (all out at 3 wickets), 1 run for a wide or no-ball. */
    static final ScoringRules RULES = new ScoringRules(2, 1, 6, 4, 1, 1, false, false);

    private final ScoringRules rules;
    private final InningsSetup setup;
    private final List<Delivery> balls = new ArrayList<>();
    private Long newBatter;
    private Long bowler;
    private boolean swap;

    private Play(ScoringRules rules, List<Long> battingSquad, Integer target) {
        this.rules = rules;
        this.setup = new InningsSetup(battingSquad, BOWLING, 1, 2, 11, target);
    }

    static Play innings(ScoringRules rules) {
        return new Play(rules, BATTING, null);
    }

    static Play chase(ScoringRules rules, int target) {
        return new Play(rules, BATTING, target);
    }

    static Play withSquad(ScoringRules rules, List<Long> battingSquad) {
        return new Play(rules, battingSquad, null);
    }

    InningsState state() {
        return InningsScorer.score(rules, setup, balls);
    }

    List<Delivery> balls() {
        return List.copyOf(balls);
    }

    InningsState stateWithout(int last) {
        return InningsScorer.score(rules, setup, balls.subList(0, balls.size() - last));
    }

    /** The new batter for the next ball's empty end. */
    Play newBatter(long id) {
        newBatter = id;
        return this;
    }

    /** The bowler for the next ball (a new over, or to try a change mid-over). */
    Play bowler(long id) {
        bowler = id;
        return this;
    }

    /** Swap the suggested ends for the next ball. */
    Play swapEnds() {
        swap = true;
        return this;
    }

    Play dots(int n) {
        for (int i = 0; i < n; i++) {
            runs(0);
        }
        return this;
    }

    Play runs(int r) {
        return add(r, null, 0, null, null, null);
    }

    Play wide(int ran) {
        return add(0, ExtraType.WD, rules.wideRuns() + ran, null, null, null);
    }

    Play noBall(int bat) {
        return add(bat, ExtraType.NB, rules.noBallRuns(), null, null, null);
    }

    Play bye(int r) {
        return add(0, ExtraType.B, r, null, null, null);
    }

    Play legBye(int r) {
        return add(0, ExtraType.LB, r, null, null, null);
    }

    /** The striker out, no runs. */
    Play out(WicketType type, Long fielderId) {
        return add(0, null, 0, type, Who.STRIKER, fielderId);
    }

    Play runOut(int ran, boolean striker) {
        return add(ran, null, 0, WicketType.RUN_OUT, striker ? Who.STRIKER : Who.NON_STRIKER, 15L);
    }

    enum Who { STRIKER, NON_STRIKER }

    /** Adds a ball built from the suggestions; the engine checks it when the state is next computed. */
    Play add(int runsBat, ExtraType extra, int extraRuns, WicketType wicket, Who out, Long fielderId) {
        Delivery d = next(runsBat, extra, extraRuns, wicket, out, fielderId);
        balls.add(d);
        state(); // fail fast, at the ball that is wrong
        return this;
    }

    /** The next ball as add() would build it, without adding it. */
    Delivery next(int runsBat, ExtraType extra, int extraRuns, WicketType wicket, Who out, Long fielderId) {
        InningsState.Live live = state().live();
        if (live == null) {
            // The innings is over: build a ball anyway, so the test can see it refused.
            return new Delivery(UUID.randomUUID(), balls.size() + 1, 1, 2, 11, runsBat, extra, extraRuns, null, null,
                    null);
        }
        Long striker = live.strikerId();
        Long nonStriker = live.nonStrikerId();
        if (striker == null) {
            striker = pickBatter(live);
        } else if (nonStriker == null) {
            nonStriker = pickBatter(live);
        }
        if (swap) {
            Long s = striker;
            striker = nonStriker;
            nonStriker = s;
            swap = false;
        }
        long bowlerId = bowler != null ? bowler : live.bowlerId() != null ? live.bowlerId() : live.eligibleBowlerIds().get(0);
        bowler = null;
        Long playerOut = out == null ? null : out == Who.STRIKER ? striker : nonStriker;
        return new Delivery(UUID.randomUUID(), balls.size() + 1, striker, nonStriker, bowlerId, runsBat, extra,
                extraRuns, wicket, playerOut, fielderId);
    }

    /** Adds a ball exactly as given (for refused balls); doesn't compute the state. */
    Play addRaw(Delivery d) {
        balls.add(d);
        return this;
    }

    private long pickBatter(InningsState.Live live) {
        long id = newBatter != null ? newBatter : live.availableBatterIds().get(0);
        newBatter = null;
        return id;
    }
}
