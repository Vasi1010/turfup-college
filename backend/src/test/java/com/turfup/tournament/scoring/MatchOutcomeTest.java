package com.turfup.tournament.scoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.turfup.tournament.scoring.Play.RULES;
import static org.assertj.core.api.Assertions.assertThat;

class MatchOutcomeTest {

    /** A first innings of 10 runs in the full 2 overs. */
    private static InningsState firstInnings() {
        return Play.innings(RULES).runs(4).runs(4).runs(2).dots(9).state();
    }

    @Test
    void theChaseSucceedsByWicketsWithBallsLeft() {
        InningsState first = firstInnings();
        InningsState second = Play.chase(RULES, first.runs() + 1).out(WicketType.BOWLED, null).runs(6).runs(4).runs(1).state();
        assertThat(MatchOutcome.decide(first, second)).contains(new MatchOutcome(MatchOutcome.Result.WIN, 2, 0, 2, 8));
    }

    @Test
    void theSideBattingFirstWinsByRuns() {
        InningsState first = firstInnings();
        InningsState second = Play.chase(RULES, first.runs() + 1).runs(4).dots(11).state();
        assertThat(MatchOutcome.decide(first, second)).contains(new MatchOutcome(MatchOutcome.Result.WIN, 1, 6, 0, 0));
    }

    @Test
    void levelScoresAreATie() {
        InningsState first = firstInnings();
        InningsState second = Play.chase(RULES, first.runs() + 1).runs(6).runs(4).dots(10).state();
        assertThat(MatchOutcome.decide(first, second)).contains(new MatchOutcome(MatchOutcome.Result.TIE, null, 0, 0, 0));
    }

    @Test
    void undecidedWhileTheChaseIsOn() {
        InningsState first = firstInnings();
        InningsState second = Play.chase(RULES, first.runs() + 1).runs(4).state();
        assertThat(MatchOutcome.decide(first, second)).isEmpty();
        assertThat(MatchOutcome.decide(first, null)).isEmpty();
    }

    @Test
    void aClosedInningsIsLeftToTheAdmin() {
        InningsState first = firstInnings();
        InningsState closed = new InningsState(5, 0, 3, 3, "0.3", new InningsState.Extras(0, 0, 0, 0), 11, 6, 9,
                InningsEndReason.CLOSED, List.of(), List.of(), List.of(), List.of(), null);
        assertThat(MatchOutcome.decide(first, closed)).isEmpty();
    }
}
