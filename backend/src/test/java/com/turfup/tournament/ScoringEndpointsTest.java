package com.turfup.tournament;

import com.jayway.jsonpath.JsonPath;
import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scorer endpoints end to end. A 2-over tournament, 4 a side (all out at 3 wickets), one over per bowler; Alpha
 * and Bravo with 5 cricketers each; the match's scorer is a player account.
 */
@TestPropertySource(properties = "turfup.tournaments.enabled=true")
class ScoringEndpointsTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    long admin;
    long scorer;
    long stranger;
    long alpha;
    long bravo;
    long matchId;
    final List<Long> a = new ArrayList<>();
    final List<Long> b = new ArrayList<>();
    UUID lastBall;

    @BeforeEach
    void setUp() {
        admin = createUser("9000000001", "ADMIN");
        scorer = createPlayer(2);
        stranger = createPlayer(3);
        long tournament = jdbc.sql("""
                        INSERT INTO tournaments (name, venue_name, start_date, end_date, overs_per_innings,
                                                 max_overs_per_bowler, players_per_side, created_by)
                        VALUES ('Cup', 'Big Turf', '2026-11-07', '2026-11-08', 2, 1, 4, :admin) RETURNING id
                        """).param("admin", admin).query(Long.class).single();
        alpha = team(tournament, "Alpha", "ALP", a, "Arif Shaikh", "Bilal Khan", "Chetan Patil", "Dev More", "Eshan Rao");
        bravo = team(tournament, "Bravo", "BRV", b, "Farhan Ali", "Gaurav Jadhav", "Harsh Pawar", "Irfan Baig",
                "Jay Joshi");
        matchId = jdbc.sql("""
                        INSERT INTO matches (tournament_id, match_no, team_a_id, team_b_id, scorer_user_id)
                        VALUES (:t, 1, :a, :b, :scorer) RETURNING id
                        """).param("t", tournament).param("a", alpha).param("b", bravo).param("scorer", scorer)
                .query(Long.class).single();
    }

    private long team(long tournament, String name, String shortName, List<Long> ids, String... players) {
        long team = jdbc.sql("INSERT INTO teams (tournament_id, name, short_name) VALUES (:t, :n, :s) RETURNING id")
                .param("t", tournament).param("n", name).param("s", shortName).query(Long.class).single();
        for (String p : players) {
            long c = jdbc.sql("INSERT INTO cricketers (name) VALUES (:n) RETURNING id").param("n", p)
                    .query(Long.class).single();
            jdbc.sql("INSERT INTO squad_members (team_id, tournament_id, cricketer_id) VALUES (:team, :t, :c)")
                    .param("team", team).param("t", tournament).param("c", c).update();
            ids.add(c);
        }
        return team;
    }

    // ----- calls -----

    private String call(MockHttpServletRequestBuilder req, Long user, Role role, ResultMatcher expect) throws Exception {
        if (user != null) {
            req.header(HttpHeaders.AUTHORIZATION, bearer(user, role));
        }
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(expect).andReturn().getResponse()
                .getContentAsString();
    }

    private String asScorer(MockHttpServletRequestBuilder req, ResultMatcher expect) throws Exception {
        return call(req, scorer, Role.PLAYER, expect);
    }

    private String toss(long winner, String decision) throws Exception {
        return asScorer(post("/api/matches/{id}/toss", matchId)
                .content("{\"winnerId\": %d, \"decision\": \"%s\"}".formatted(winner, decision)), status().isOk());
    }

    private String start(long striker, long nonStriker, long bowler, ResultMatcher expect) throws Exception {
        return asScorer(post("/api/matches/{id}/innings", matchId).content(
                "{\"strikerId\": %d, \"nonStrikerId\": %d, \"bowlerId\": %d}".formatted(striker, nonStriker, bowler)),
                expect);
    }

    /** A ball built from the live state in {@code match}, like the scorer screen: suggested ends, first choices. */
    private String ball(String match, int runsBat, String extraType, int extraRuns, String wicket, boolean strikerOut,
                        Long fielder, ResultMatcher expect) throws Exception {
        long innings = num(match, "$.live.inningsId");
        Long striker = optNum(match, "$.live.strikerId");
        Long nonStriker = optNum(match, "$.live.nonStrikerId");
        if (striker == null) {
            striker = num(match, "$.live.availableBatterIds[0]");
        } else if (nonStriker == null) {
            nonStriker = num(match, "$.live.availableBatterIds[0]");
        }
        Long bowler = optNum(match, "$.live.bowlerId");
        if (bowler == null) {
            bowler = num(match, "$.live.eligibleBowlerIds[0]");
        }
        lastBall = UUID.randomUUID();
        String out = wicket == null ? "null" : String.valueOf(strikerOut ? striker : nonStriker);
        String body = """
                {"id": "%s", "seq": %d, "strikerId": %d, "nonStrikerId": %d, "bowlerId": %d, "runsBat": %d,
                 "extraType": %s, "extraRuns": %d, "wicketType": %s, "playerOutId": %s, "fielderId": %s}
                """.formatted(lastBall, num(match, "$.live.nextSeq"), striker, nonStriker, bowler, runsBat,
                extraType == null ? "null" : "\"" + extraType + "\"", extraRuns,
                wicket == null ? "null" : "\"" + wicket + "\"", out, fielder == null ? "null" : fielder);
        return asScorer(post("/api/innings/{id}/deliveries", innings).content(body), expect);
    }

    private String runs(String match, int... runs) throws Exception {
        for (int r : runs) {
            match = ball(match, r, null, 0, null, true, null, status().isOk());
        }
        return match;
    }

    private String undo(UUID ball, ResultMatcher expect) throws Exception {
        return asScorer(delete("/api/deliveries/{id}", ball), expect);
    }

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private static Long optNum(String json, String path) {
        Number n = JsonPath.read(json, path);
        return n == null ? null : n.longValue();
    }

    private static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    // ----- tests -----

    @Test
    void anyoneMayReadAMatchButOnlyItsScorerOrAnAdminMayScore() throws Exception {
        String match = call(get("/api/matches/{id}", matchId), null, null, status().isOk());
        assertThat((String) read(match, "$.teamA.name")).isEqualTo("Alpha");
        assertThat((String) read(match, "$.status")).isEqualTo("UPCOMING");

        String body = "{\"winnerId\": %d, \"decision\": \"BAT\"}".formatted(alpha);
        call(post("/api/matches/{id}/toss", matchId).content(body), null, null, status().isUnauthorized());
        call(post("/api/matches/{id}/toss", matchId).content(body), stranger, Role.PLAYER, status().isForbidden());
        call(post("/api/matches/{id}/toss", matchId).content(body), admin, Role.ADMIN, status().isOk());
        assertThat((String) read(toss(bravo, "BOWL"), "$.toss.decision")).isEqualTo("BOWL"); // corrected
        call(get("/api/matches/{id}", 999_999), null, null, status().isNotFound());
    }

    @Test
    void aWholeMatchFromTossToResult() throws Exception {
        toss(alpha, "BAT");
        String match = start(a.get(0), a.get(1), b.get(0), status().isOk());
        assertThat((String) read(match, "$.status")).isEqualTo("LIVE");
        assertThat(num(match, "$.live.strikerId")).isEqualTo(a.get(0));
        assertThat(num(match, "$.live.bowlerId")).isEqualTo(b.get(0));

        match = runs(match, 4, 1, 0, 0, 0, 0, 6, 0, 0, 0, 0, 0); // two overs: 11 runs
        assertThat(num(match, "$.innings[0].runs")).isEqualTo(11);
        assertThat((String) read(match, "$.innings[0].status")).isEqualTo("DONE");
        assertThat((String) read(match, "$.innings[0].endReason")).isEqualTo("OVERS");
        assertThat((Object) read(match, "$.live")).isNull();

        match = start(b.get(0), b.get(1), a.get(0), status().isOk());
        assertThat(num(match, "$.innings[1].target")).isEqualTo(12);
        assertThat(num(match, "$.live.runsNeeded")).isEqualTo(12);
        assertThat(num(match, "$.live.ballsLeft")).isEqualTo(12);

        match = runs(match, 6, 6);
        assertThat((String) read(match, "$.status")).isEqualTo("DONE");
        assertThat((String) read(match, "$.result")).isEqualTo("WIN");
        assertThat(num(match, "$.winnerId")).isEqualTo(bravo);
        assertThat((String) read(match, "$.resultText")).isEqualTo("Bravo won by 3 wickets (10 balls left)");
        assertThat((String) read(match, "$.innings[1].endReason")).isEqualTo("TARGET");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theScorecardTheLivePageShows() throws Exception {
        toss(bravo, "BOWL"); // Alpha bats
        String match = start(a.get(0), a.get(1), b.get(0), status().isOk());
        match = runs(match, 4);
        match = ball(match, 0, "WD", 1, null, true, null, status().isOk());
        match = ball(match, 0, null, 0, "CAUGHT", true, b.get(2), status().isOk());
        assertThat((Boolean) read(match, "$.live.needsNewBatter")).isTrue();
        assertThat((Object) read(match, "$.live.strikerId")).isNull();
        match = runs(match, 1); // the new batter, Chetan Patil, takes a single

        assertThat((String) read(match, "$.innings[0].overs")).isEqualTo("0.3");
        assertThat(num(match, "$.innings[0].runs")).isEqualTo(6);
        assertThat((String) read(match, "$.innings[0].batting[0].dismissal")).isEqualTo("c Pawar b Ali");
        assertThat(num(match, "$.innings[0].batting[0].balls")).isEqualTo(2); // the wide is not faced
        assertThat((String) read(match, "$.innings[0].batting[2].name")).isEqualTo("Chetan Patil");
        assertThat((List<String>) read(match, "$.innings[0].yetToBat[*].name")).containsExactly("Dev More", "Eshan Rao");
        assertThat((Boolean) read(match, "$.innings[0].batting[1].onStrike")).isTrue(); // Bilal Khan, after the single
        assertThat(((Number) read(match, "$.innings[0].bowling[0].economy")).doubleValue()).isEqualTo(12.0);
        assertThat(num(match, "$.innings[0].bowling[0].wides")).isEqualTo(1);
        assertThat((List<String>) read(match, "$.live.thisOver[*].label")).containsExactly("4", "1wd", "W", "1");
        assertThat((String) read(match, "$.innings[0].fallOfWickets[0].overs")).isEqualTo("0.2");
    }

    @Test
    void aBallSentTwiceIsSavedOnce() throws Exception {
        toss(alpha, "BAT");
        String match = runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 4);
        long version = num(match, "$.version");
        String body = """
                {"id": "%s", "seq": 1, "strikerId": %d, "nonStrikerId": %d, "bowlerId": %d, "runsBat": 4,
                 "extraType": null, "extraRuns": 0, "wicketType": null, "playerOutId": null, "fielderId": null}
                """.formatted(lastBall, a.get(0), a.get(1), b.get(0));
        long inningsId = num(match, "$.innings[0].id");
        String again = asScorer(post("/api/innings/{id}/deliveries", inningsId).content(body), status().isOk());
        assertThat(num(again, "$.innings[0].runs")).isEqualTo(4);
        assertThat(num(again, "$.version")).isEqualTo(version);
    }

    @Test
    void aBallOutOfTurnGetsTheCurrentMatchBack() throws Exception {
        toss(alpha, "BAT");
        String match = runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 1);
        String stale = """
                {"id": "%s", "seq": 1, "strikerId": %d, "nonStrikerId": %d, "bowlerId": %d, "runsBat": 0,
                 "extraType": null, "extraRuns": 0, "wicketType": null, "playerOutId": null, "fielderId": null}
                """.formatted(UUID.randomUUID(), a.get(0), a.get(1), b.get(0));
        String problem = asScorer(post("/api/innings/{id}/deliveries", num(match, "$.innings[0].id")).content(stale),
                status().isConflict());
        assertThat((String) read(problem, "$.detail")).contains("the next ball is number 2");
        assertThat(num(problem, "$.match.live.nextSeq")).isEqualTo(2);
    }

    @Test
    void aBallTheRulesDoNotAllowIsRefused() throws Exception {
        toss(alpha, "BAT");
        String match = runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 0, 0, 0, 0, 0, 0);
        String body = """
                {"id": "%s", "seq": 7, "strikerId": %d, "nonStrikerId": %d, "bowlerId": %d, "runsBat": 0,
                 "extraType": null, "extraRuns": 0, "wicketType": null, "playerOutId": null, "fielderId": null}
                """.formatted(UUID.randomUUID(), a.get(1), a.get(0), b.get(0));
        String problem = asScorer(post("/api/innings/{id}/deliveries", num(match, "$.innings[0].id")).content(body),
                status().isBadRequest());
        assertThat((String) read(problem, "$.detail")).contains("two overs in a row");
        assertThat((String) read(problem, "$.code")).isEqualTo("INVALID_DELIVERY");
    }

    @Test
    void undoTheLatestBallOnlyAndOnlyOnce() throws Exception {
        toss(alpha, "BAT");
        String match = runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 4);
        UUID first = lastBall;
        match = runs(match, 6);
        undo(first, status().isConflict()); // not the latest

        match = undo(lastBall, status().isOk());
        assertThat(num(match, "$.innings[0].runs")).isEqualTo(4);
        long version = num(match, "$.version");
        match = undo(lastBall, status().isOk()); // sent again: nothing more happens
        assertThat(num(match, "$.innings[0].runs")).isEqualTo(4);
        assertThat(num(match, "$.version")).isEqualTo(version);
        assertThat(num(match, "$.live.nextSeq")).isEqualTo(2);
    }

    @Test
    void undoingTheWinningBallReopensTheMatch() throws Exception {
        toss(alpha, "BAT");
        String match = runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        match = start(b.get(0), b.get(1), a.get(0), status().isOk());
        match = runs(match, 2);
        assertThat((String) read(match, "$.status")).isEqualTo("DONE");

        match = undo(lastBall, status().isOk());
        assertThat((String) read(match, "$.status")).isEqualTo("LIVE");
        assertThat((Object) read(match, "$.result")).isNull();
        assertThat((String) read(match, "$.innings[1].status")).isEqualTo("LIVE");
        assertThat(num(match, "$.live.runsNeeded")).isEqualTo(2);
    }

    @Test
    void theFirstInningsCannotBeUndoneOnceTheSecondHasStarted() throws Exception {
        toss(alpha, "BAT");
        runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        UUID lastOfFirst = lastBall;
        start(b.get(0), b.get(1), a.get(0), status().isOk());
        String problem = undo(lastOfFirst, status().isConflict());
        assertThat((String) read(problem, "$.detail")).contains("next innings has started");
    }

    @Test
    void anInningsClosedEarlyTakesNoMoreBalls() throws Exception {
        toss(alpha, "BAT");
        String match = runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 4);
        long inningsId = num(match, "$.innings[0].id");
        String closed = asScorer(post("/api/innings/{id}/end", inningsId).content("{\"reason\": \"CLOSED\"}"),
                status().isOk());
        assertThat((String) read(closed, "$.innings[0].endReason")).isEqualTo("CLOSED");
        assertThat((Object) read(closed, "$.live")).isNull();
        String problem = ball(match, 1, null, 0, null, true, null, status().isConflict());
        assertThat((String) read(problem, "$.detail")).contains("innings is over");
    }

    @Test
    void inningsStartOnlyInTheRightOrderWithTheRightPlayers() throws Exception {
        start(a.get(0), a.get(1), b.get(0), status().isConflict()); // no toss yet
        toss(alpha, "BAT");
        start(b.get(0), b.get(1), a.get(0), status().isBadRequest()); // Bravo's openers, but Alpha bats
        start(a.get(0), a.get(1), b.get(0), status().isOk());
        start(b.get(0), b.get(1), a.get(0), status().isConflict()); // the first innings is still on
        asScorer(post("/api/matches/{id}/toss", matchId)
                .content("{\"winnerId\": %d, \"decision\": \"BOWL\"}".formatted(bravo)), status().isConflict());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theScorerSeesTheirMatchesAndBothSquads() throws Exception {
        String mine = call(get("/api/scorer/matches"), scorer, Role.PLAYER, status().isOk());
        assertThat((List<String>) read(mine, "$[*].teamA")).containsExactly("Alpha");
        assertThat((List<String>) read(mine, "$[*].tournamentName")).containsExactly("Cup");
        assertThat((List<Object>) read(call(get("/api/scorer/matches"), stranger, Role.PLAYER, status().isOk()), "$"))
                .isEmpty();
        assertThat((List<Object>) read(call(get("/api/scorer/matches"), admin, Role.ADMIN, status().isOk()), "$"))
                .hasSize(1);
        call(get("/api/scorer/matches"), null, null, status().isUnauthorized());

        String match = call(get("/api/matches/{id}", matchId), null, null, status().isOk());
        assertThat((List<String>) read(match, "$.teamB.squad[*].name"))
                .containsExactly("Farhan Ali", "Gaurav Jadhav", "Harsh Pawar", "Irfan Baig", "Jay Joshi");
    }

    @Test
    void theBallThatEndedAnInningsCanStillBeUndone() throws Exception {
        toss(alpha, "BAT");
        String match = runs(start(a.get(0), a.get(1), b.get(0), status().isOk()), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4);
        assertThat((Object) read(match, "$.live")).isNull();
        assertThat((String) read(match, "$.innings[0].lastDeliveryId")).isEqualTo(lastBall.toString());
        match = undo(UUID.fromString(read(match, "$.innings[0].lastDeliveryId")), status().isOk());
        assertThat((String) read(match, "$.innings[0].status")).isEqualTo("LIVE");
        assertThat(num(match, "$.live.ballsLeft")).isEqualTo(1);
    }

    @Test
    void everyChangeBumpsTheVersion() throws Exception {
        long v0 = num(call(get("/api/matches/{id}", matchId), null, null, status().isOk()), "$.version");
        long v1 = num(toss(alpha, "BAT"), "$.version");
        long v2 = num(start(a.get(0), a.get(1), b.get(0), status().isOk()), "$.version");
        String match = call(get("/api/matches/{id}", matchId), null, null, status().isOk());
        long v3 = num(runs(match, 1), "$.version");
        long v4 = num(undo(lastBall, status().isOk()), "$.version");
        assertThat(List.of(v0, v1, v2, v3, v4)).containsExactly(0L, 1L, 2L, 3L, 4L);
    }
}
