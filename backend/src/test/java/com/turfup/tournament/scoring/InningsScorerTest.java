package com.turfup.tournament.scoring;

import com.turfup.tournament.scoring.InningsState.BatterStatus;
import com.turfup.tournament.scoring.InningsState.BattingLine;
import com.turfup.tournament.scoring.InningsState.BowlingLine;
import com.turfup.tournament.scoring.InningsState.Live;
import com.turfup.tournament.scoring.Play.Who;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.turfup.tournament.scoring.Play.RULES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The scoring rules of docs/work-plan.md, section 4.5, one by one. Rules: see {@link Play#RULES}. */
class InningsScorerTest {

    private static BattingLine bat(InningsState s, long id) {
        return s.batting().stream().filter(l -> l.cricketerId() == id).findFirst().orElseThrow();
    }

    private static BowlingLine bowl(InningsState s, long id) {
        return s.bowling().stream().filter(l -> l.cricketerId() == id).findFirst().orElseThrow();
    }

    private static void refused(Play play, String message) {
        assertThatThrownBy(play::state).isInstanceOf(InvalidDeliveryException.class).hasMessageContaining(message);
    }

    @Nested
    class Strike {

        @Test
        void oddRunsSwapTheBatters() {
            Play p = Play.innings(RULES);
            assertThat(p.runs(1).state().live().strikerId()).isEqualTo(2);
            assertThat(p.runs(3).state().live().strikerId()).isEqualTo(1);
            assertThat(p.bye(1).state().live().strikerId()).isEqualTo(2);
            assertThat(p.legBye(3).state().live().strikerId()).isEqualTo(1);
        }

        @Test
        void evenRunsAndBoundariesKeepTheStriker() {
            Play p = Play.innings(RULES).runs(2).runs(4).runs(6);
            assertThat(p.state().live().strikerId()).isEqualTo(1);
            assertThat(bat(p.state(), 1)).extracting(BattingLine::runs, BattingLine::fours, BattingLine::sixes)
                    .containsExactly(12, 1, 1);
        }

        @Test
        void endOfOverSwapsTheBattersAndNeedsANewBowler() {
            InningsState s = Play.innings(RULES).dots(6).state();
            Live live = s.live();
            assertThat(live.strikerId()).isEqualTo(2);
            assertThat(live.nonStrikerId()).isEqualTo(1);
            assertThat(live.newOver()).isTrue();
            assertThat(live.bowlerId()).isNull();
            assertThat(live.lastOverBowlerId()).isEqualTo(11);
            assertThat(live.eligibleBowlerIds()).doesNotContain(11L).contains(12L);
        }

        @Test
        void aSingleOffTheLastBallKeepsTheStrikerForTheNextOver() {
            // He runs to the other end, then the over ends: back where he started, and he faces again.
            assertThat(Play.innings(RULES).dots(5).runs(1).state().live().strikerId()).isEqualTo(1);
        }

        @Test
        void theScorerMaySwapWhoIsOnStrike() {
            Play p = Play.innings(RULES).swapEnds().runs(0);
            assertThat(p.balls().get(0).strikerId()).isEqualTo(2);
            assertThat(bat(p.state(), 2).balls()).isEqualTo(1);
            assertThat(bat(p.state(), 1).balls()).isZero();
        }
    }

    @Nested
    class Bowlers {

        @Test
        void theNextOverMustBeBowledBySomeoneElse() {
            Play p = Play.innings(RULES).dots(6);
            refused(p.addRaw(nextFrom(p, 11)), "two overs in a row");
        }

        @Test
        void aBowlerAtTheLimitCannotStartAnotherOver() {
            ScoringRules threeOvers = new ScoringRules(3, 1, 6, 4, 1, 1, false, false);
            Play p = Play.innings(threeOvers).dots(6).bowler(12).dots(6);
            assertThat(p.state().live().eligibleBowlerIds()).doesNotContain(11L, 12L).contains(13L);
            refused(p.addRaw(nextFrom(p, 11)), "bowled all 1 of their overs");
        }

        @Test
        void anOverIsBowledByOneBowler() {
            Play p = Play.innings(RULES).dots(3);
            refused(p.addRaw(nextFrom(p, 12)), "finish this over");
        }

        @Test
        void maidensCountOnlyTheBowlersRuns() {
            // A bye is not the bowler's: still a maiden. A wide is: not a maiden.
            InningsState s = Play.innings(RULES).dots(5).bye(1).bowler(12).wide(0).dots(6).state();
            assertThat(bowl(s, 11).maidens()).isEqualTo(1);
            assertThat(bowl(s, 12).maidens()).isZero();
        }
    }

    @Nested
    class Extras {

        @Test
        void widesAndNoBallsAreNotLegalBallsAndTheOverContinues() {
            InningsState s = Play.innings(RULES).dots(5).wide(0).noBall(0).state();
            assertThat(s.legalBalls()).isEqualTo(5);
            assertThat(s.live().newOver()).isFalse();
            assertThat(s.runs()).isEqualTo(2);
            assertThat(s.extras()).isEqualTo(new InningsState.Extras(1, 1, 0, 0));
            assertThat(bowl(s, 11).runs()).isEqualTo(2);
            // The wide and no-ball show as ball 6, still to be bowled again.
            assertThat(s.balls().subList(5, 7)).allSatisfy(b -> assertThat(b.ballInOver()).isEqualTo(6));
        }

        @Test
        void theOrganiserSetsWhatAWideAndANoBallAreWorth() {
            ScoringRules two = new ScoringRules(2, 1, 6, 4, 2, 2, false, false);
            InningsState s = Play.innings(two).wide(0).noBall(0).state();
            assertThat(s.runs()).isEqualTo(4);
            assertThat(s.live().strikerId()).isEqualTo(1); // penalty runs are not run
        }

        @Test
        void runsOffANoBallAreTheBattersAndByesOffItAreExtras() {
            Play p = Play.innings(RULES).noBall(4);
            InningsState s = p.state();
            assertThat(bat(s, 1).runs()).isEqualTo(4);
            assertThat(bat(s, 1).balls()).isEqualTo(1); // a no-ball is faced
            assertThat(s.extras().noBalls()).isEqualTo(1);
            assertThat(bowl(s, 11).runs()).isEqualTo(5);

            s = p.add(0, ExtraType.NB, 1 + 1, null, null, null).state(); // a bye off a no-ball
            assertThat(s.extras().noBalls()).isEqualTo(3);
            assertThat(bat(s, 1).runs()).isEqualTo(4);
            assertThat(s.live().strikerId()).isEqualTo(2); // they ran one
        }

        @Test
        void aWideWithByes() {
            InningsState s = Play.innings(RULES).wide(4).state();
            assertThat(s.runs()).isEqualTo(5);
            assertThat(s.extras().wides()).isEqualTo(5);
            assertThat(s.live().strikerId()).isEqualTo(1); // four byes: no running
            assertThat(bat(s, 1).balls()).isZero(); // a wide is not faced

            assertThat(Play.innings(RULES).wide(1).state().live().strikerId()).isEqualTo(2);
        }

        @Test
        void impossibleRunsAreRefused() {
            refused(Play.innings(RULES).addRaw(firstBall(2, ExtraType.WD, 1)), "byes, not runs off the bat");
            refused(Play.innings(RULES).addRaw(firstBall(0, ExtraType.WD, 0)), "A wide is worth 1 run");
            refused(Play.innings(RULES).addRaw(firstBall(2, ExtraType.NB, 2)), "either off the bat or byes");
            refused(Play.innings(RULES).addRaw(firstBall(0, ExtraType.B, 0)), "at least 1 run");
            refused(Play.innings(RULES).addRaw(firstBall(0, null, 1)), "need the kind of extra");
            refused(Play.innings(RULES).addRaw(firstBall(8, null, 0)), "between 0 and 7");
        }
    }

    @Nested
    class Wickets {

        @Test
        void theNewBatterComesInAtTheStrikersEnd() {
            InningsState s = Play.innings(RULES).runs(0).out(WicketType.BOWLED, null).state();
            assertThat(s.live().strikerId()).isNull();
            assertThat(s.live().nonStrikerId()).isEqualTo(2);
            assertThat(s.live().needsNewBatter()).isTrue();
            assertThat(s.live().availableBatterIds()).containsExactly(3L, 4L, 5L, 6L);
            assertThat(bowl(s, 11).wickets()).isEqualTo(1);
        }

        @Test
        void exceptOffTheLastBallOfTheOver() {
            InningsState s = Play.innings(RULES).dots(5).out(WicketType.CAUGHT, 13L).state();
            assertThat(s.live().strikerId()).isEqualTo(2);
            assertThat(s.live().nonStrikerId()).isNull();
        }

        @Test
        void theNewBatterIsRecordedOnTheNextBall() {
            InningsState s = Play.innings(RULES).out(WicketType.BOWLED, null).newBatter(4).runs(1).state();
            assertThat(s.batting()).extracting(BattingLine::cricketerId).containsExactly(1L, 2L, 4L);
            assertThat(bat(s, 4).runs()).isEqualTo(1);
        }

        @Test
        void runOutOfEitherBatterAfterRunsCompleted() {
            // One run completed, then the non-striker (2), now at the striker's end, is run out going for a second.
            InningsState s = Play.innings(RULES).runOut(1, false).state();
            assertThat(s.runs()).isEqualTo(1);
            assertThat(bat(s, 1).runs()).isEqualTo(1);
            assertThat(bat(s, 2).status()).isEqualTo(BatterStatus.OUT);
            assertThat(bat(s, 2).dismissal().bowlerId()).isNull(); // not the bowler's wicket
            assertThat(bowl(s, 11).wickets()).isZero();
            assertThat(s.wickets()).isEqualTo(1);
            assertThat(s.live().strikerId()).isNull(); // the new batter takes 2's end and faces
            assertThat(s.live().nonStrikerId()).isEqualTo(1);

            InningsState striker = Play.innings(RULES).runOut(0, true).state();
            assertThat(bat(striker, 1).status()).isEqualTo(BatterStatus.OUT);
            assertThat(striker.live().strikerId()).isNull();
        }

        @Test
        void fallOfWicketsAndDismissals() {
            InningsState s = Play.innings(RULES).runs(4).out(WicketType.CAUGHT, 13L).runs(2)
                    .out(WicketType.STUMPED, 12L).state();
            assertThat(s.fallOfWickets()).extracting(InningsState.FallOfWicket::runs, InningsState.FallOfWicket::overs,
                            InningsState.FallOfWicket::cricketerId)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(4, "0.2", 1L),
                            org.assertj.core.groups.Tuple.tuple(6, "0.4", 3L));
            assertThat(bat(s, 1).dismissal()).isEqualTo(new InningsState.Dismissal(WicketType.CAUGHT, 11L, 13L));
        }

        @Test
        void waysOfGettingOutThatCannotHappen() {
            Play caughtOffNoBall = Play.innings(RULES);
            refused(caughtOffNoBall.addRaw(caughtOffNoBall.next(0, ExtraType.NB, 1, WicketType.CAUGHT, Who.STRIKER, 13L)),
                    "Off a no-ball the batter can only be run out");
            Play nonStrikerBowled = Play.innings(RULES);
            refused(nonStrikerBowled.addRaw(nonStrikerBowled.next(0, null, 0, WicketType.BOWLED, Who.NON_STRIKER, null)),
                    "non-striker can only be run out");
            Play caughtOffWide = Play.innings(RULES);
            refused(caughtOffWide.addRaw(caughtOffWide.next(0, ExtraType.WD, 1, WicketType.CAUGHT, Who.STRIKER, 13L)),
                    "Off a wide");
            Play runsOnACatch = Play.innings(RULES);
            refused(runsOnACatch.addRaw(runsOnACatch.next(1, null, 0, WicketType.CAUGHT, Who.STRIKER, 13L)),
                    "No runs count");
            Play fielderOnBowled = Play.innings(RULES);
            refused(fielderOnBowled.addRaw(fielderOnBowled.next(0, null, 0, WicketType.BOWLED, Who.STRIKER, 13L)),
                    "names a fielder");
            Play retired = Play.innings(RULES);
            refused(retired.addRaw(retired.next(0, null, 0, WicketType.RETIRED_HURT, Who.STRIKER, null)),
                    "Retirements");
        }

        @Test
        void stumpedOffAWideCounts() {
            Play p = Play.innings(RULES).add(0, ExtraType.WD, 1, WicketType.STUMPED, Who.STRIKER, 12L);
            InningsState s = p.state();
            assertThat(s.runs()).isEqualTo(1);
            assertThat(s.wickets()).isEqualTo(1);
            assertThat(s.legalBalls()).isZero();
            assertThat(bowl(s, 11).wickets()).isEqualTo(1);
        }

        @Test
        void aBatterWhoIsOutCannotComeBack() {
            Play p = Play.innings(RULES).out(WicketType.BOWLED, null).newBatter(1);
            assertThatThrownBy(() -> p.runs(0)).isInstanceOf(InvalidDeliveryException.class)
                    .hasMessageContaining("not have batted yet");
        }

        @Test
        void theBattersMustBeTheTwoNotOut() {
            Play p = Play.innings(RULES).runs(0);
            refused(p.addRaw(new Delivery(UUID.randomUUID(), 2, 1, 3, 11, 0, null, 0, null, null, null)),
                    "the two not out");
        }
    }

    @Nested
    class Ending {

        @Test
        void whenTheOversAreDone() {
            Play p = Play.innings(RULES).dots(6).dots(6);
            InningsState s = p.state();
            assertThat(s.endReason()).isEqualTo(InningsEndReason.OVERS);
            assertThat(s.live()).isNull();
            assertThat(bat(s, 1).status()).isEqualTo(BatterStatus.NOT_OUT);
            refused(p.addRaw(p.next(0, null, 0, null, null, null)), "The innings is over");
        }

        @Test
        void whenTheSideIsAllOut() {
            // 4 a side: all out at 3 wickets.
            InningsState s = Play.innings(RULES).out(WicketType.BOWLED, null).out(WicketType.BOWLED, null)
                    .out(WicketType.BOWLED, null).state();
            assertThat(s.endReason()).isEqualTo(InningsEndReason.ALL_OUT);
            assertThat(s.wicketsInHand()).isZero();
        }

        @Test
        void aSquadSmallerThanTheSideIsAllOutSooner() {
            InningsState s = Play.withSquad(RULES, List.of(1L, 2L, 3L)).out(WicketType.BOWLED, null).state();
            assertThat(s.wicketsInHand()).isEqualTo(1);
            s = Play.withSquad(RULES, List.of(1L, 2L, 3L)).out(WicketType.BOWLED, null).out(WicketType.BOWLED, null)
                    .state();
            assertThat(s.endReason()).isEqualTo(InningsEndReason.ALL_OUT);
        }

        @Test
        void theMomentTheTargetIsPassed() {
            InningsState s = Play.chase(RULES, 5).runs(4).runs(1).state();
            assertThat(s.endReason()).isEqualTo(InningsEndReason.TARGET);
            assertThat(s.runsNeeded()).isZero();
            assertThat(s.ballsLeft()).isEqualTo(10);
        }

        @Test
        void aChaseShowsRunsNeededAndBallsLeft() {
            InningsState s = Play.chase(RULES, 20).runs(4).wide(0).runs(2).state();
            assertThat(s.runsNeeded()).isEqualTo(13);
            assertThat(s.ballsLeft()).isEqualTo(10);
            assertThat(s.target()).isEqualTo(20);
        }
    }

    @Nested
    class LocalRules {

        @Test
        void noLbw() {
            ScoringRules noLbw = new ScoringRules(2, 1, 6, 4, 1, 1, true, false);
            Play p = Play.innings(noLbw);
            refused(p.addRaw(p.next(0, null, 0, WicketType.LBW, Who.STRIKER, null)), "LBW is not allowed");
            Play allowed = Play.innings(RULES).out(WicketType.LBW, null);
            assertThat(allowed.state().wickets()).isEqualTo(1);
        }

        @Test
        void aFreeHitAfterANoBallLastsUntilALegalBall() {
            ScoringRules freeHit = new ScoringRules(2, 1, 6, 4, 1, 1, false, true);
            Play p = Play.innings(freeHit).noBall(0);
            assertThat(p.state().live().freeHit()).isTrue();
            p.wide(0); // a wide on a free hit: still a free hit
            assertThat(p.state().live().freeHit()).isTrue();

            Play bowled = Play.innings(freeHit).noBall(0);
            refused(bowled.addRaw(bowled.next(0, null, 0, WicketType.BOWLED, Who.STRIKER, null)), "free hit");
            Play runOut = Play.innings(freeHit).noBall(0).runOut(0, true);
            assertThat(runOut.state().wickets()).isEqualTo(1);

            InningsState after = p.runs(0).state();
            assertThat(after.live().freeHit()).isFalse();
            assertThat(after.balls().get(2).freeHit()).isTrue(); // the legal ball was the free hit
        }
    }

    @Test
    void undoIsScoringWithoutTheLastBall() {
        // Across the end of an over and across a wicket: every prefix scores the same as before the next ball.
        Play p = Play.innings(RULES);
        List<InningsState> before = new ArrayList<>();
        Runnable snap = () -> before.add(p.state());
        snap.run();
        p.dots(5);
        snap.run();
        p.runs(1); // ends the over
        snap.run();
        p.out(WicketType.BOWLED, null); // first ball of the next over
        snap.run();
        p.newBatter(5).runs(4);
        snap.run();
        assertThat(p.stateWithout(1)).isEqualTo(before.get(3)); // undo the four: the wicket is the last ball again
        assertThat(p.stateWithout(2)).isEqualTo(before.get(2)); // undo the wicket: the new over has no ball yet
        assertThat(p.stateWithout(3)).isEqualTo(before.get(1)); // undo the over's last ball
        assertThat(p.stateWithout(8)).isEqualTo(before.get(0)); // nothing bowled
    }

    @Test
    void ballLabels() {
        InningsState s = Play.innings(new ScoringRules(3, null, 6, 4, 1, 1, false, false))
                .runs(0).runs(4).wide(0).wide(4).noBall(4).add(0, ExtraType.NB, 3, null, null, null)
                .bye(2).legBye(1).runOut(1, true).state();
        assertThat(s.balls()).extracting(InningsState.BallSummary::label)
                .containsExactly("0", "4", "1wd", "5wd", "nb4", "nb+2", "2b", "1lb", "1W");
        assertThat(s.balls().get(1).boundary()).isTrue();
        assertThat(s.balls().get(3).boundary()).isFalse(); // four byes off a wide are not a boundary hit
    }

    @Test
    void ballsMustComeInOrder() {
        refused(Play.innings(RULES).addRaw(new Delivery(UUID.randomUUID(), 2, 1, 2, 11, 0, null, 0, null, null, null)),
                "the next ball is number 1");
    }

    /** The ball after p's last one, bowled by {@code bowlerId}. */
    private static Delivery nextFrom(Play p, long bowlerId) {
        return p.bowler(bowlerId).next(0, null, 0, null, null, null);
    }

    /** The first ball of an innings with these runs. */
    private static Delivery firstBall(int runsBat, ExtraType extra, int extraRuns) {
        return new Delivery(UUID.randomUUID(), 1, 1, 2, 11, runsBat, extra, extraRuns, null, null, null);
    }
}
