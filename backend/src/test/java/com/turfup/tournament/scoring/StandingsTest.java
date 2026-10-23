package com.turfup.tournament.scoring;

import com.turfup.tournament.scoring.Standings.FinishedMatch;
import com.turfup.tournament.scoring.Standings.InningsTotal;
import com.turfup.tournament.scoring.Standings.PointsRules;
import com.turfup.tournament.scoring.Standings.Result;
import com.turfup.tournament.scoring.Standings.Row;
import com.turfup.tournament.scoring.Standings.Tiebreaker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 8-over matches (48 balls); a win is 2 points, a tie or no result 1. Teams A=1, B=2, C=3, D=4. */
class StandingsTest {

    static final long A = 1, B = 2, C = 3, D = 4;
    static final PointsRules NRR = new PointsRules(2, 1, 1, Tiebreaker.NRR, 6);

    private static FinishedMatch win(long winner, long a, long b, InningsTotal first, InningsTotal second) {
        return new FinishedMatch(a, b, Result.WIN, winner, 48, List.of(first, second));
    }

    private static InningsTotal inn(long team, int runs, int balls) {
        return new InningsTotal(team, runs, balls, false);
    }

    private static InningsTotal allOut(long team, int runs, int balls) {
        return new InningsTotal(team, runs, balls, true);
    }

    private static Row row(List<Row> rows, long team) {
        return rows.stream().filter(r -> r.teamId() == team).findFirst().orElseThrow();
    }

    @Test
    void pointsAndNetRunRateWithASideBowledOutCountedAtItsFullOvers() {
        // A 80 (8 ov) beat B 70 all out in 5 overs, which counts as 8 overs.
        // C 61/2 (6 ov) beat B 60 (8 ov).
        List<Row> rows = Standings.compute(List.of(A, B, C), List.of(
                win(A, A, B, inn(A, 80, 48), allOut(B, 70, 30)),
                win(C, B, C, inn(B, 60, 48), inn(C, 61, 36))), NRR);

        assertThat(rows).extracting(Row::teamId).containsExactly(C, A, B);
        // A: 80/8 - 70/8 = 1.25
        assertThat(row(rows, A).nrr()).isEqualByComparingTo("1.250");
        // C: 61/6 - 60/8 = 10.1667 - 7.5 = 2.667
        assertThat(row(rows, C).nrr()).isEqualByComparingTo("2.667");
        // B: (70 + 60)/(8 + 8) - (80 + 61)/(8 + 6) = 8.125 - 10.0714 = -1.946
        assertThat(row(rows, B).nrr()).isEqualByComparingTo("-1.946");
        assertThat(row(rows, B)).extracting(Row::played, Row::won, Row::lost, Row::points, Row::ballsFaced)
                .containsExactly(2, 0, 2, 0, 96);
    }

    @Test
    void aTieAndANoResultShareThePoints() {
        List<Row> rows = Standings.compute(List.of(A, B), List.of(
                new FinishedMatch(A, B, Result.TIE, null, 48, List.of(inn(A, 70, 48), inn(B, 70, 48))),
                new FinishedMatch(A, B, Result.NO_RESULT, null, 48, List.of(inn(A, 30, 18)))), NRR);
        assertThat(row(rows, A)).extracting(Row::played, Row::tied, Row::noResult, Row::points, Row::runsFor)
                .containsExactly(2, 1, 1, 2, 70); // the no result's runs don't count for NRR
        assertThat(row(rows, A).nrr()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void headToHeadDecidesBeforeNetRunRateWhenThatIsTheTiebreaker() {
        // A and C both have 2 points. A beat C by a run, but C's NRR is far better.
        List<FinishedMatch> matches = List.of(
                win(A, A, C, inn(A, 50, 48), inn(C, 49, 48)),
                win(C, C, D, inn(C, 150, 48), inn(D, 50, 48)),
                win(B, B, A, inn(B, 60, 48), inn(A, 59, 48)),
                win(B, B, D, inn(B, 70, 48), allOut(D, 30, 24)));
        List<Long> teams = List.of(A, B, C, D);

        assertThat(Standings.compute(teams, matches, NRR)).extracting(Row::teamId).containsExactly(B, C, A, D);
        PointsRules h2h = new PointsRules(2, 1, 1, Tiebreaker.HEAD_TO_HEAD, 6);
        assertThat(Standings.compute(teams, matches, h2h)).extracting(Row::teamId).containsExactly(B, A, C, D);
    }

    @Test
    void teamsWithNothingToSeparateThemKeepTheGivenOrder() {
        assertThat(Standings.compute(List.of(C, A, B), List.of(), NRR)).extracting(Row::teamId)
                .containsExactly(C, A, B);
    }

    @Test
    void aWinNeedsAWinnerFromTheTwoTeams() {
        assertThatThrownBy(() -> new FinishedMatch(A, B, Result.WIN, C, 48, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
