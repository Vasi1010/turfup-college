package com.turfup.tournament;

import com.jayway.jsonpath.JsonPath;
import com.turfup.PostgresTestBase;
import com.turfup.auth.Role;
import com.turfup.demo.DemoDataSeeder;
import com.turfup.demo.DemoTournament;
import com.turfup.tournament.ScoringService.Actor;
import com.turfup.tournament.ScoringService.DeliveryRequest;
import com.turfup.tournament.ScoringService.StartInningsRequest;
import com.turfup.tournament.ScoringService.TossRequest;
import com.turfup.tournament.TournamentAdminService.AddTeam;
import com.turfup.tournament.TournamentAdminService.CreateMatch;
import com.turfup.tournament.TournamentAdminService.SideRequest;
import com.turfup.tournament.scoring.WicketType;
import com.turfup.venue.VenueApprovalService;
import com.turfup.venue.VenueSetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Setting up a tournament (B5a) and its public pages (B5). Tournament: 2 overs of 6, 3 a side (all out at 2
 * wickets), one over per bowler, NRR tiebreaker.
 */
@TestPropertySource(properties = "turfup.tournaments.enabled=true")
@SuppressWarnings("unchecked")
class TournamentApisTest extends PostgresTestBase {

    static final String RULES = """
            {"oversPerInnings": 2, "maxOversPerBowler": 1, "ballsPerOver": 6, "playersPerSide": 3, "wideRuns": 1,
             "noBallRuns": 1, "pointsWin": 2, "pointsTie": 1, "pointsNoResult": 1, "tiebreaker": "NRR",
             "localRules": {"noLbw": true, "freeHit": false, "lastManStands": false}}
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    TournamentAdminService admin;
    @Autowired
    ScoringService scoring;
    @Autowired
    TournamentQueryService query;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    VenueSetupService venueSetup;
    @Autowired
    VenueApprovalService approvals;
    @Autowired
    ApplicationContext context;

    long adminId;
    Actor asAdmin;

    @BeforeEach
    void setUp() {
        adminId = createUser("9000000001", "ADMIN");
        asAdmin = new Actor(adminId, true);
    }

    private String call(MockHttpServletRequestBuilder req, Long user, Role role, ResultMatcher expect) throws Exception {
        if (user != null) {
            req.header(HttpHeaders.AUTHORIZATION, bearer(user, role));
        }
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(expect).andReturn().getResponse()
                .getContentAsString();
    }

    private String asAdmin(MockHttpServletRequestBuilder req, ResultMatcher expect) throws Exception {
        return call(req, adminId, Role.ADMIN, expect);
    }

    private long createTournament() throws Exception {
        String body = """
                {"name": "Cup", "venueName": "Big Turf", "startDate": "2026-11-07", "endDate": "2026-11-08",
                 "rules": %s}
                """.formatted(RULES);
        return num(asAdmin(post("/api/tournaments").content(body), status().isCreated()), "$.id");
    }

    private long team(long tournament, String name, String shortName, String squad) {
        return admin.addTeam(tournament, new AddTeam(name, shortName, null, squad)).id();
    }

    private long fixture(long tournament, int no, long a, long b) {
        return admin.createMatch(tournament, new CreateMatch(no, "GROUP", new SideRequest(a, null),
                new SideRequest(b, null), null, null)).id();
    }

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    // ----- playing through the scoring service, like the scorer screen -----

    private MatchView ball(MatchView m, int runs, WicketType wicket) {
        MatchView.LiveState live = m.live();
        Long striker = live.strikerId();
        Long nonStriker = live.nonStrikerId();
        if (striker == null) {
            striker = live.availableBatterIds().get(0);
        } else if (nonStriker == null) {
            nonStriker = live.availableBatterIds().get(0);
        }
        long bowler = live.bowlerId() != null ? live.bowlerId() : live.eligibleBowlerIds().get(0);
        return scoring.addDelivery(live.inningsId(), asAdmin, new DeliveryRequest(UUID.randomUUID(), live.nextSeq(),
                striker, nonStriker, bowler, runs, null, 0, wicket, wicket == null ? null : striker, null));
    }

    private MatchView runs(MatchView m, int... runs) {
        for (int r : runs) {
            m = ball(m, r, null);
        }
        return m;
    }

    private MatchView start(long matchId, MatchView m, int innings) {
        long batting = innings == 1
                ? (m.toss().decision().equals("BAT") ? m.toss().winnerId() : other(m, m.toss().winnerId()))
                : m.innings().get(0).bowlingTeamId();
        List<Long> bat = squad(m.tournamentId(), batting);
        List<Long> bowl = squad(m.tournamentId(), other(m, batting));
        return scoring.startInnings(matchId, asAdmin, new StartInningsRequest(bat.get(0), bat.get(1), bowl.get(0)));
    }

    private long other(MatchView m, long teamId) {
        return teamId == m.teamA().teamId() ? m.teamB().teamId() : m.teamA().teamId();
    }

    private List<Long> squad(long tournament, long teamId) {
        return jdbc.sql("SELECT cricketer_id FROM squad_members WHERE team_id = :t ORDER BY created_at, cricketer_id")
                .param("t", teamId).query(Long.class).list();
    }

    // ----- B5a: setting up -----

    @Test
    void onlyAnAdminCreatesATournamentAndItsRulesAreChecked() throws Exception {
        long player = createPlayer(2);
        String body = """
                {"name": "Cup", "venueName": "Big Turf", "startDate": "2026-11-07", "endDate": "2026-11-08",
                 "rules": %s}
                """.formatted(RULES);
        call(post("/api/tournaments").content(body), player, Role.PLAYER, status().isForbidden());
        String detail = asAdmin(post("/api/tournaments").content(body), status().isCreated());
        assertThat(num(detail, "$.rules.maxOversPerBowler")).isEqualTo(1);
        assertThat((Boolean) read(detail, "$.rules.localRules.noLbw")).isTrue();
        assertThat((String) read(detail, "$.status")).isEqualTo("UPCOMING");

        String tooManyBowlerOvers = body.replace("\"maxOversPerBowler\": 1", "\"maxOversPerBowler\": 3");
        String problem = asAdmin(post("/api/tournaments").content(tooManyBowlerOvers), status().isBadRequest());
        assertThat((String) read(problem, "$.detail")).contains("over limit");
        asAdmin(post("/api/tournaments").content(body.replace("\"2026-11-08\"", "\"2026-11-01\"")),
                status().isBadRequest());
    }

    @Test
    void aSquadIsPastedOneLinePerPlayer() throws Exception {
        long t = createTournament();
        String team = asAdmin(post("/api/tournaments/{id}/teams", t).content("""
                {"name": "Altamash XI", "shortName": "alt", "groupName": "A",
                 "squad": "7 Arbaaz Shaikh (c)\\nFaizan Qureshi\\n\\n10. Imran Pathan\\n  Sameer   Khan  "}
                """), status().isCreated());
        assertThat((String) read(team, "$.shortName")).isEqualTo("ALT");
        assertThat((List<String>) read(team, "$.squad[*].name"))
                .containsExactly("Arbaaz Shaikh", "Faizan Qureshi", "Imran Pathan", "Sameer Khan");
        assertThat((List<Integer>) read(team, "$.squad[*].jerseyNo")).containsExactly(7, null, 10, null);
        assertThat(num(team, "$.captainId")).isEqualTo(num(team, "$.squad[0].id"));

        String again = """
                {"name": "Other", "shortName": "ALT", "squad": "A B\\nC D"}
                """;
        assertThat((String) read(asAdmin(post("/api/tournaments/{id}/teams", t).content(again),
                status().isBadRequest()), "$.detail")).contains("already in this tournament");
        asAdmin(post("/api/tournaments/{id}/teams", t).content("""
                {"name": "Solo", "shortName": "SOL", "squad": "Only One"}
                """), status().isBadRequest());
        asAdmin(post("/api/tournaments/{id}/teams", t).content("""
                {"name": "Twins", "shortName": "TWN", "squad": "Ravi Kale\\nravi kale"}
                """), status().isBadRequest());
    }

    @Test
    void fixturesWithTeamsOrLabelsAndAScorerFoundByPhone() throws Exception {
        long t = createTournament();
        long a = team(t, "Alpha", "ALP", "A One\nA Two\nA Three");
        long b = team(t, "Bravo", "BRV", "B One\nB Two\nB Three");
        long scorer = createUser("9123456789", "PLAYER");

        String match = asAdmin(post("/api/tournaments/{id}/matches", t).content("""
                {"matchNo": 1, "stage": "GROUP", "teamA": {"teamId": %d}, "teamB": {"teamId": %d},
                 "scheduledAt": "2026-11-07T07:00:00+05:30", "scorerPhone": "91234 56789"}
                """.formatted(a, b)), status().isCreated());
        assertThat((String) read(match, "$.teamA.name")).isEqualTo("Alpha");
        long matchId = num(match, "$.id");
        // The scorer found by phone may score this match.
        call(post("/api/matches/{id}/toss", matchId).content("{\"winnerId\": %d, \"decision\": \"BAT\"}".formatted(a)),
                scorer, Role.PLAYER, status().isOk());

        String scorers = asAdmin(get("/api/admin/tournaments/{id}/scorers", t), status().isOk());
        assertThat((List<String>) read(scorers, "$[*].phone")).containsExactly("9123456789");
        call(get("/api/admin/tournaments/{id}/scorers", t), scorer, Role.PLAYER, status().isForbidden());

        String fin = asAdmin(post("/api/tournaments/{id}/matches", t).content("""
                {"matchNo": 2, "stage": "FINAL", "teamA": {"label": "1st in the table"},
                 "teamB": {"label": "2nd in the table"}}
                """), status().isCreated());
        assertThat((String) read(fin, "$.teamA.label")).isEqualTo("1st in the table");
        assertThat((Object) read(fin, "$.teamA.teamId")).isNull();

        asAdmin(post("/api/tournaments/{id}/matches", t).content("""
                {"matchNo": 3, "teamA": {"teamId": %d}, "teamB": {"teamId": %d}, "scorerPhone": "9000000099"}
                """.formatted(a, b)), status().isBadRequest()); // nobody has that phone
        asAdmin(post("/api/tournaments/{id}/matches", t).content("""
                {"matchNo": 1, "teamA": {"teamId": %d}, "teamB": {"teamId": %d}}
                """.formatted(a, b)), status().isBadRequest()); // match 1 exists
        long other = createTournament();
        long stranger = team(other, "Elsewhere", "ELS", "E One\nE Two");
        asAdmin(post("/api/tournaments/{id}/matches", t).content("""
                {"matchNo": 4, "teamA": {"teamId": %d}, "teamB": {"teamId": %d}}
                """.formatted(a, stranger)), status().isBadRequest());
    }

    @Test
    void theFinalGetsItsTeamsOnceKnownAndResultsCanBeRecordedOrCorrected() throws Exception {
        long t = createTournament();
        long a = team(t, "Alpha", "ALP", "A One\nA Two\nA Three");
        long b = team(t, "Bravo", "BRV", "B One\nB Two\nB Three");
        long fin = admin.createMatch(t, new CreateMatch(9, "FINAL", new SideRequest(null, "Winner SF 1"),
                new SideRequest(null, "Winner SF 2"), null, null)).id();

        String match = asAdmin(patch("/api/matches/{id}", fin).content("""
                {"teamA": {"teamId": %d}, "teamB": {"teamId": %d}, "oversPerInnings": 1}
                """.formatted(a, b)), status().isOk());
        assertThat((String) read(match, "$.teamB.name")).isEqualTo("Bravo");
        assertThat(num(match, "$.oversPerInnings")).isEqualTo(1);

        // Rain: the first innings is closed early and the admin records the result.
        MatchView m = scoring.toss(fin, asAdmin, new TossRequest(a, "BAT"));
        m = runs(start(fin, m, 1), 4);
        scoring.endInnings(m.live().inningsId(), asAdmin);
        String problem = asAdmin(patch("/api/matches/{id}", fin).content("""
                {"result": "WIN", "winnerId": 999}
                """), status().isBadRequest());
        assertThat((String) read(problem, "$.detail")).contains("one of the two teams");
        match = asAdmin(patch("/api/matches/{id}", fin).content("""
                {"result": "WIN", "winnerId": %d, "resultNote": "Rain; decided on the organiser's rules"}
                """.formatted(a)), status().isOk());
        assertThat((String) read(match, "$.status")).isEqualTo("DONE");
        assertThat((String) read(match, "$.resultText")).isEqualTo("Alpha won");
        assertThat((String) read(match, "$.resultNote")).isEqualTo("Rain; decided on the organiser's rules");

        match = asAdmin(patch("/api/matches/{id}", fin).content("""
                {"result": "TIE", "winnerId": %d}
                """.formatted(b)), status().isOk()); // corrected: a tie, Bravo won the super over
        assertThat((String) read(match, "$.result")).isEqualTo("TIE");
        assertThat(num(match, "$.winnerId")).isEqualTo(b);
        asAdmin(patch("/api/matches/{id}", fin).content("""
                {"teamA": {"teamId": %d}}
                """.formatted(b)), status().isConflict()); // too late for the teams
        asAdmin(patch("/api/matches/{id}", fin).content("{\"status\": \"LIVE\"}"), status().isBadRequest());
        call(patch("/api/matches/{id}", fin).content("{\"resultNote\": null}"), createPlayer(5), Role.PLAYER,
                status().isForbidden());
    }

    // ----- B5: the public pages -----

    @Test
    void pointsTableLeaderboardsAndFixtures() throws Exception {
        long t = createTournament();
        long a = team(t, "Alpha", "ALP", "A One\nA Two\nA Three\nA Four");
        long b = team(t, "Bravo", "BRV", "B One\nB Two\nB Three\nB Four");
        long c = team(t, "Charlie", "CHA", "C One\nC Two\nC Three\nC Four");
        long m1 = fixture(t, 1, a, b);
        long m2 = fixture(t, 2, b, c);
        long m3 = fixture(t, 3, a, c);

        // M1: Alpha 11 in 2 overs; Bravo all out for 0 in 2 balls (counts as 2 overs). Alpha won by 11 runs.
        MatchView m = scoring.toss(m1, asAdmin, new TossRequest(a, "BAT"));
        m = runs(start(m1, m, 1), 6, 4, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0);
        m = start(m1, m, 2);
        m = ball(ball(m, 0, WicketType.BOWLED), 0, WicketType.BOWLED);
        assertThat(m.resultText()).isEqualTo("Alpha won by 11 runs");

        // M2: Bravo 8 in 2 overs; Charlie 10 off 2 balls. Charlie won by 2 wickets with 10 balls left.
        m = scoring.toss(m2, asAdmin, new TossRequest(b, "BAT"));
        m = runs(start(m2, m, 1), 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        m = runs(start(m2, m, 2), 6, 4);
        assertThat(m.resultText()).isEqualTo("Charlie won by 2 wickets (10 balls left)");

        // The fixture list while M3 is still to play.
        String detail = call(get("/api/tournaments/{id}", t), null, null, status().isOk());
        assertThat((String) read(detail, "$.status")).isEqualTo("LIVE");
        assertThat((List<String>) read(detail, "$.matches[*].status")).containsExactly("DONE", "DONE", "UPCOMING");
        assertThat((List<Integer>) read(detail, "$.matches[0].innings[*].runs")).containsExactly(11, 0);
        assertThat((String) read(detail, "$.matches[0].innings[1].endReason")).isEqualTo("ALL_OUT");

        // M3 is rained off.
        asAdmin(patch("/api/matches/{id}", m3).content("{\"status\": \"ABANDONED\"}"), status().isOk());

        String table = call(get("/api/tournaments/{id}/table", t), null, null, status().isOk());
        assertThat((List<String>) read(table, "$.groups[0].rows[*].shortName")).containsExactly("CHA", "ALP", "BRV");
        assertThat((List<Integer>) read(table, "$.groups[0].rows[*].points")).containsExactly(3, 3, 0);
        // Charlie: 10 in 2 balls (30 an over) - 8 in 2 overs (4 an over) = 26
        assertThat(new BigDecimal(read(table, "$.groups[0].rows[0].nrr").toString())).isEqualByComparingTo("26.000");
        // Alpha: 11 in 2 overs - 0 in 2 overs (bowled out: full overs) = 5.5
        assertThat(new BigDecimal(read(table, "$.groups[0].rows[1].nrr").toString())).isEqualByComparingTo("5.500");
        // Bravo: (0 + 8) in 4 overs - (11 + 10) in 2 overs 2 balls = 2 - 9 = -7
        assertThat(new BigDecimal(read(table, "$.groups[0].rows[2].nrr").toString())).isEqualByComparingTo("-7.000");
        assertThat((String) read(table, "$.groups[0].rows[2].oversFaced")).isEqualTo("4.0");
        assertThat(num(table, "$.groups[0].rows[0].noResult")).isEqualTo(1);

        String leaders = call(get("/api/tournaments/{id}/leaderboards", t), null, null, status().isOk());
        // C One 10 off 2 balls beats A One's 10 off 11 on strike rate; B One 8.
        assertThat((List<String>) read(leaders, "$.topRunScorers[*].name")).startsWith("C One", "A One", "B One");
        assertThat((String) read(leaders, "$.topRunScorers[0].highest")).isEqualTo("10*");
        assertThat((String) read(leaders, "$.topWicketTakers[0].name")).isEqualTo("A One");
        assertThat((String) read(leaders, "$.topWicketTakers[0].best")).isEqualTo("2/0");

        String done = call(get("/api/tournaments/{id}", t), null, null, status().isOk());
        assertThat((String) read(done, "$.status")).isEqualTo("DONE");
        assertThat((List<String>) read(call(get("/api/tournaments"), null, null, status().isOk()), "$[*].name"))
                .containsExactly("Cup");
    }

    @Test
    void theDemoTournamentIsSeededOnceWithTwoMatchesPlayed() throws Exception {
        new DemoDataSeeder(jdbc, passwords, venueSetup, approvals, context).seed();
        DemoTournament demo = new DemoTournament(jdbc, passwords, admin, scoring);
        demo.seed();
        demo.seed(); // a second run changes nothing

        List<TournamentQueryService.Summary> all = query.list();
        assertThat(all).extracting(TournamentQueryService.Summary::name).containsExactly(DemoTournament.NAME);
        TournamentView.Detail cup = query.detail(all.get(0).id());
        assertThat(cup.teams()).hasSize(4).allSatisfy(t -> assertThat(t.squad()).hasSize(9));
        assertThat(cup.matches()).extracting(TournamentView.Fixture::status)
                .containsExactly("DONE", "DONE", "UPCOMING", "UPCOMING", "UPCOMING", "UPCOMING", "UPCOMING");
        assertThat(cup.matches().get(0).resultText()).isNotBlank();
        assertThat(cup.status()).isEqualTo("LIVE");
        assertThat(query.table(cup.id()).groups().get(0).rows())
                .extracting(TournamentView.PointsRow::played).containsExactlyInAnyOrder(1, 1, 1, 1);
        assertThat(query.leaderboards(cup.id()).topRunScorers()).isNotEmpty();

        // The demo scorer scores every match.
        long scorer = jdbc.sql("SELECT id FROM users WHERE phone = :p").param("p", DemoTournament.SCORER_PHONE)
                .query(Long.class).single();
        String mine = call(get("/api/scorer/matches"), scorer, Role.PLAYER, status().isOk());
        assertThat((List<Object>) read(mine, "$")).hasSize(7);
    }

    @Test
    void groupsHaveTheirOwnTables() throws Exception {
        long t = createTournament();
        admin.addTeam(t, new AddTeam("Delta", "DEL", "B", "D One\nD Two"));
        admin.addTeam(t, new AddTeam("Alpha", "ALP", "A", "A One\nA Two"));
        admin.addTeam(t, new AddTeam("Echo", "ECH", "B", "E One\nE Two"));
        String table = call(get("/api/tournaments/{id}/table", t), null, null, status().isOk());
        assertThat((List<String>) read(table, "$.groups[*].name")).containsExactly("A", "B");
        assertThat((List<String>) read(table, "$.groups[1].rows[*].teamName")).containsExactly("Delta", "Echo");
        call(get("/api/tournaments/{id}", 999_999), null, null, status().isNotFound());
    }
}
