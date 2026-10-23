package com.turfup.tournament.scoring;

import com.turfup.tournament.scoring.InningsState.BallSummary;
import com.turfup.tournament.scoring.InningsState.BatterStatus;
import com.turfup.tournament.scoring.InningsState.BattingLine;
import com.turfup.tournament.scoring.InningsState.BowlingLine;
import com.turfup.tournament.scoring.InningsState.Dismissal;
import com.turfup.tournament.scoring.InningsState.Extras;
import com.turfup.tournament.scoring.InningsState.FallOfWicket;
import com.turfup.tournament.scoring.InningsState.Live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The scoring engine: an innings computed from its balls. A pure function of the rules, the setup and the balls in
 * {@code seq} order, so undo is simply scoring the list without its last ball.
 *
 * <p>Every ball is checked against the ones before it, and the first one that can't follow them is refused with a
 * message for the scorer. Adding a ball is therefore: score the recorded balls plus the new one.
 *
 * <p>Ends: an odd number of runs completed (off the bat, byes, leg byes, or run on a wide or no-ball; penalties don't
 * count) swaps the batters, and so does the end of an over. A batter who is out leaves their end empty: after a catch
 * (and any other dismissal of the striker) the new batter faces, unless the over just ended; after a run out the new
 * batter takes the end of the batter who was out. These are suggestions: the scorer names both batters on every
 * ball and may swap them.
 */
public final class InningsScorer {

    private InningsScorer() {
    }

    /**
     * @param balls the innings' balls in {@code seq} order, undone balls left out
     * @throws InvalidDeliveryException at the first ball that can't follow the ones before it
     */
    public static InningsState score(ScoringRules rules, InningsSetup setup, List<Delivery> balls) {
        Replay replay = new Replay(rules, setup);
        for (Delivery ball : balls) {
            replay.apply(ball);
        }
        return replay.state();
    }

    private static final class Bat {
        final long id;
        Dismissal dismissal;
        int runs;
        int balls;
        int fours;
        int sixes;

        Bat(long id) {
            this.id = id;
        }
    }

    private static final class Bowl {
        final long id;
        int oversStarted;
        int legalBalls;
        int maidens;
        int runs;
        int wickets;
        int wides;
        int noBalls;

        Bowl(long id) {
            this.id = id;
        }
    }

    private static final class Replay {
        private final ScoringRules rules;
        private final InningsSetup setup;
        private final int allOut;
        private final Map<Long, Bat> batting = new LinkedHashMap<>();
        private final Map<Long, Bowl> bowling = new LinkedHashMap<>();
        private final List<FallOfWicket> fallOfWickets = new ArrayList<>();
        private final List<BallSummary> balls = new ArrayList<>();

        /** The batters at each end; null when a new batter is needed there. */
        private Long striker;
        private Long nonStriker;
        /** Bowler of the over in progress; null between overs. */
        private Long overBowler;
        private Long lastOverBowler;
        /** Deliveries in the over in progress, wides and no-balls included. */
        private int ballsThisOver;
        private int overBowlerRuns;
        private int runs;
        private int wickets;
        private int legal;
        private int wides;
        private int noBalls;
        private int byes;
        private int legByes;
        private boolean freeHit;
        private InningsEndReason ended;

        Replay(ScoringRules rules, InningsSetup setup) {
            this.rules = rules;
            this.setup = setup;
            // A squad smaller than the side runs out of batters sooner.
            this.allOut = Math.min(rules.allOutWickets(), setup.battingSquad().size() - 1);
            this.striker = setup.strikerId();
            this.nonStriker = setup.nonStrikerId();
            batting.put(striker, new Bat(striker));
            batting.put(nonStriker, new Bat(nonStriker));
        }

        void apply(Delivery d) {
            if (ended != null) {
                throw new InvalidDeliveryException("The innings is over.");
            }
            if (d.seq() != balls.size() + 1) {
                throw new InvalidDeliveryException(
                        "This ball is out of order: the next ball is number " + (balls.size() + 1) + ".");
            }
            checkRuns(d);
            checkBatters(d);
            checkBowler(d);
            checkWicket(d);
            record(d);
        }

        private void checkRuns(Delivery d) {
            if (d.runsBat() < 0 || d.runsBat() > 7) {
                throw new InvalidDeliveryException("Runs off the bat must be between 0 and 7.");
            }
            if (d.extraType() == null) {
                if (d.extraRuns() != 0) {
                    throw new InvalidDeliveryException("Extra runs need the kind of extra: wide, no-ball, bye or leg bye.");
                }
                return;
            }
            int run = d.extraRuns() - d.extraType().penalty(rules);
            switch (d.extraType()) {
                case WD -> {
                    if (d.runsBat() != 0) {
                        throw new InvalidDeliveryException("Runs off a wide are byes, not runs off the bat.");
                    }
                    if (run < 0) {
                        throw new InvalidDeliveryException("A wide is worth " + rules.wideRuns() + " run"
                                + (rules.wideRuns() == 1 ? "" : "s") + " before any runs taken.");
                    }
                }
                case NB -> {
                    if (run < 0) {
                        throw new InvalidDeliveryException("A no-ball is worth " + rules.noBallRuns() + " run"
                                + (rules.noBallRuns() == 1 ? "" : "s") + " before any runs taken.");
                    }
                    if (d.runsBat() > 0 && run > 0) {
                        throw new InvalidDeliveryException(
                                "On a no-ball the runs are either off the bat or byes, not both.");
                    }
                }
                case B, LB -> {
                    if (d.runsBat() != 0) {
                        throw new InvalidDeliveryException("Byes and leg byes are not runs off the bat.");
                    }
                    if (run < 1) {
                        throw new InvalidDeliveryException("A bye or leg bye scores at least 1 run.");
                    }
                }
            }
            if (run > 7) {
                throw new InvalidDeliveryException("At most 7 runs can be taken off one ball.");
            }
        }

        private void checkBatters(Delivery d) {
            if (d.strikerId() == d.nonStrikerId()) {
                throw new InvalidDeliveryException("The striker and non-striker must be different players.");
            }
            Set<Long> named = Set.of(d.strikerId(), d.nonStrikerId());
            for (Long current : new Long[] {striker, nonStriker}) {
                if (current != null && !named.contains(current)) {
                    throw new InvalidDeliveryException(
                            "The batters must be the two not out: only who's on strike can change.");
                }
            }
            for (long id : named) {
                boolean current = Objects.equals(id, striker) || Objects.equals(id, nonStriker);
                if (!current && (!setup.battingSquad().contains(id) || batting.containsKey(id))) {
                    throw new InvalidDeliveryException(
                            "The new batter must be in the batting team's squad and not have batted yet.");
                }
            }
        }

        private void checkBowler(Delivery d) {
            if (!setup.bowlingSquad().contains(d.bowlerId())) {
                throw new InvalidDeliveryException("The bowler must be in the bowling team's squad.");
            }
            if (ballsThisOver > 0) {
                if (d.bowlerId() != overBowler) {
                    throw new InvalidDeliveryException("An over is bowled by one bowler: finish this over first.");
                }
                return;
            }
            if (Objects.equals(d.bowlerId(), lastOverBowler)) {
                throw new InvalidDeliveryException("A bowler can't bowl two overs in a row.");
            }
            Integer limit = rules.maxOversPerBowler();
            if (limit != null && oversStarted(d.bowlerId()) >= limit) {
                throw new InvalidDeliveryException("This bowler has bowled all " + limit + " of their overs.");
            }
        }

        private void checkWicket(Delivery d) {
            WicketType type = d.wicketType();
            if ((type == null) != (d.playerOutId() == null)) {
                throw new InvalidDeliveryException(type == null
                        ? "Say how the batter got out."
                        : "Say which batter is out.");
            }
            if (d.fielderId() != null) {
                if (type == null || !type.hasFielder()) {
                    throw new InvalidDeliveryException("Only a catch, a run out or a stumping names a fielder.");
                }
                if (!setup.bowlingSquad().contains(d.fielderId())) {
                    throw new InvalidDeliveryException("The fielder must be in the bowling team's squad.");
                }
            }
            if (type == null) {
                return;
            }
            if (type == WicketType.RETIRED_OUT || type == WicketType.RETIRED_HURT) {
                throw new InvalidDeliveryException("Retirements can't be scored yet.");
            }
            long out = d.playerOutId();
            if (out != d.strikerId() && out != d.nonStrikerId()) {
                throw new InvalidDeliveryException("Only one of the two batters can be out.");
            }
            if (out == d.nonStrikerId() && type != WicketType.RUN_OUT) {
                throw new InvalidDeliveryException("The non-striker can only be run out.");
            }
            if (type == WicketType.LBW && rules.noLbw()) {
                throw new InvalidDeliveryException("LBW is not allowed in this tournament.");
            }
            if (freeHit && type != WicketType.RUN_OUT) {
                throw new InvalidDeliveryException("On a free hit the batter can only be run out.");
            }
            if (d.extraType() == ExtraType.NB && type != WicketType.RUN_OUT) {
                throw new InvalidDeliveryException("Off a no-ball the batter can only be run out.");
            }
            if (d.extraType() == ExtraType.WD && type != WicketType.RUN_OUT && type != WicketType.STUMPED
                    && type != WicketType.HIT_WICKET) {
                throw new InvalidDeliveryException("Off a wide the batter can only be run out, stumped or hit wicket.");
            }
            if (!type.allowsRuns() && d.runsCompleted(rules) != 0) {
                throw new InvalidDeliveryException("No runs count when a batter is out this way; only a run out "
                        + "can come after runs were taken.");
            }
        }

        private void record(Delivery d) {
            for (long id : new long[] {d.strikerId(), d.nonStrikerId()}) {
                batting.computeIfAbsent(id, Bat::new);
            }
            striker = d.strikerId();
            nonStriker = d.nonStrikerId();
            if (ballsThisOver == 0) {
                overBowler = d.bowlerId();
                overBowlerRuns = 0;
                bowl(d.bowlerId()).oversStarted++;
            }
            Bowl bowler = bowl(d.bowlerId());
            Bat bat = batting.get(striker);
            boolean wasFreeHit = freeHit;
            int over = legal / rules.ballsPerOver();
            int ballInOver = legal % rules.ballsPerOver() + 1;

            if (d.extraType() != ExtraType.WD) {
                bat.balls++;
            }
            bat.runs += d.runsBat();
            if (d.runsBat() == 4) {
                bat.fours++;
            } else if (d.runsBat() == 6) {
                bat.sixes++;
            }
            bowler.runs += d.bowlerRuns();
            overBowlerRuns += d.bowlerRuns();
            if (d.extraType() != null) {
                switch (d.extraType()) {
                    case WD -> {
                        bowler.wides++;
                        wides += d.extraRuns();
                    }
                    case NB -> {
                        bowler.noBalls++;
                        noBalls += d.extraRuns();
                    }
                    case B -> byes += d.extraRuns();
                    case LB -> legByes += d.extraRuns();
                }
            }
            runs += d.totalRuns();
            if (d.legal()) {
                legal++;
                bowler.legalBalls++;
            }
            if (d.runsCompleted(rules) % 2 == 1) {
                swapEnds();
            }
            if (d.wicketType() != null) {
                WicketType type = d.wicketType();
                Bat out = batting.get(d.playerOutId());
                out.dismissal = new Dismissal(type, type.creditedToBowler() ? d.bowlerId() : null, d.fielderId());
                if (type.creditedToBowler()) {
                    bowler.wickets++;
                }
                if (type.countsAsWicket()) {
                    wickets++;
                    fallOfWickets.add(new FallOfWicket(wickets, runs, legal, rules.overs(legal), out.id));
                }
                if (Objects.equals(striker, d.playerOutId())) {
                    striker = null;
                } else {
                    nonStriker = null;
                }
            }
            if (rules.freeHit() && d.extraType() == ExtraType.NB) {
                freeHit = true;
            } else if (d.legal()) {
                freeHit = false;
            }
            balls.add(new BallSummary(d.id(), d.seq(), over, ballInOver, label(d, rules), d.totalRuns(), d.legal(),
                    d.wicketType() != null, d.extraType() != ExtraType.WD && (d.runsBat() == 4 || d.runsBat() == 6),
                    wasFreeHit));

            ballsThisOver++;
            if (d.legal() && legal % rules.ballsPerOver() == 0) {
                if (overBowlerRuns == 0) {
                    bowler.maidens++;
                }
                swapEnds();
                lastOverBowler = overBowler;
                overBowler = null;
                ballsThisOver = 0;
            }

            if (setup.target() != null && runs >= setup.target()) {
                ended = InningsEndReason.TARGET;
            } else if (wickets >= allOut) {
                ended = InningsEndReason.ALL_OUT;
            } else if (legal >= rules.maxLegalBalls()) {
                ended = InningsEndReason.OVERS;
            }
        }

        private void swapEnds() {
            Long s = striker;
            striker = nonStriker;
            nonStriker = s;
        }

        private Bowl bowl(long id) {
            return bowling.computeIfAbsent(id, Bowl::new);
        }

        private int oversStarted(long id) {
            Bowl b = bowling.get(id);
            return b == null ? 0 : b.oversStarted;
        }

        InningsState state() {
            List<BattingLine> battingLines = batting.values().stream().map(b -> new BattingLine(b.id,
                    b.dismissal != null ? BatterStatus.OUT : ended != null ? BatterStatus.NOT_OUT : BatterStatus.BATTING,
                    b.dismissal, b.runs, b.balls, b.fours, b.sixes)).toList();
            List<BowlingLine> bowlingLines = bowling.values().stream().map(b -> new BowlingLine(b.id, b.legalBalls,
                    rules.overs(b.legalBalls), b.maidens, b.runs, b.wickets, b.wides, b.noBalls)).toList();
            Integer target = setup.target();
            Integer runsNeeded = target == null ? null : Math.max(0, target - runs);
            return new InningsState(runs, wickets, allOut - wickets, legal, rules.overs(legal),
                    new Extras(wides, noBalls, byes, legByes), target, runsNeeded, rules.maxLegalBalls() - legal, ended,
                    battingLines, bowlingLines, List.copyOf(fallOfWickets), List.copyOf(balls),
                    ended != null ? null : live());
        }

        private Live live() {
            boolean newOver = ballsThisOver == 0;
            Long bowler = overBowler != null ? overBowler : balls.isEmpty() ? (Long) setup.bowlerId() : null;
            Integer limit = rules.maxOversPerBowler();
            List<Long> eligible = newOver
                    ? setup.bowlingSquad().stream()
                    .filter(id -> !id.equals(lastOverBowler) && (limit == null || oversStarted(id) < limit))
                    .toList()
                    : List.of(overBowler);
            List<Long> available = setup.battingSquad().stream().filter(id -> !batting.containsKey(id)).toList();
            return new Live(striker, nonStriker, bowler, lastOverBowler, newOver, freeHit, balls.size() + 1, eligible,
                    available);
        }
    }

    /** "0", "4", "W", "1W", "1wd", "5wd", "nb", "nb4", "nb+2" (2 byes off a no-ball), "2b", "1lb". */
    static String label(Delivery d, ScoringRules rules) {
        String w = d.wicketType() != null ? "W" : "";
        if (d.extraType() == null) {
            return w.isEmpty() ? String.valueOf(d.runsBat()) : d.runsBat() > 0 ? d.runsBat() + w : w;
        }
        return switch (d.extraType()) {
            case WD -> d.extraRuns() + "wd" + w;
            case NB -> {
                int byes = d.extraRuns() - rules.noBallRuns();
                yield "nb" + (d.runsBat() > 0 ? String.valueOf(d.runsBat()) : byes > 0 ? "+" + byes : "") + w;
            }
            case B -> d.extraRuns() + "b" + w;
            case LB -> d.extraRuns() + "lb" + w;
        };
    }
}
