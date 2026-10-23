package com.turfup.demo;

import com.turfup.tournament.MatchView;
import com.turfup.tournament.ScoringService;
import com.turfup.tournament.ScoringService.Actor;
import com.turfup.tournament.ScoringService.DeliveryRequest;
import com.turfup.tournament.ScoringService.StartInningsRequest;
import com.turfup.tournament.ScoringService.TossRequest;
import com.turfup.tournament.TournamentAdminService;
import com.turfup.tournament.TournamentAdminService.AddTeam;
import com.turfup.tournament.TournamentAdminService.CreateMatch;
import com.turfup.tournament.TournamentAdminService.CreateTournament;
import com.turfup.tournament.TournamentAdminService.SideRequest;
import com.turfup.tournament.TournamentView;
import com.turfup.tournament.scoring.ExtraType;
import com.turfup.tournament.scoring.WicketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A demo cricket tournament for showing live scoring (demo profile, with turfup.tournaments.enabled): four teams,
 * a round of six group matches and a final at Kick Arena, around today. The first two matches are played ball by ball
 * through the scoring service (seeded, so every reseed plays the same), so the points table, leaderboards and
 * scorecards have data; the rest wait for a scorer. The demo scorer (7777777777 / scorer-demo-123) scores every
 * match; the demo admin can too. Seeded once: a database that already has it is left alone.
 */
@Component
@Profile("demo")
@ConditionalOnProperty(name = "turfup.tournaments.enabled", havingValue = "true")
public class DemoTournament {

    public static final String NAME = "TurfUp Cricket Cup";
    public static final String SCORER_PHONE = "7777777777";
    public static final String SCORER_PASSWORD = "scorer-demo-123";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final List<List<String>> TEAMS = List.of(
            List.of("Cidco Strikers", "CID", "Rahul Patil (c)\nAditya Kulkarni\nOmkar Pawar\nSachin More\nGanesh Wagh\n"
                    + "Akshay Deshpande\nMahesh Rao\nPratik Jadhav\nNikhil Gaikwad"),
            List.of("Garkheda Gladiators", "GAR", "Vikram Shinde (c)\nRohit Bhosale\nSagar Kale\nAmol Thorat\n"
                    + "Kunal Sonawane\nYash Chavan\nTushar Mane\nHarshal Patil\nSiddharth Wankhede"),
            List.of("Altamash XI", "ALT", "Arbaaz Shaikh (c)\nFaizan Qureshi\nImran Pathan\nSameer Khan\nZaid Siddiqui\n"
                    + "Owais Ansari\nRehan Baig\nTalha Inamdar\nJunaid Momin"),
            List.of("Osmanpura Royals", "OSM", "Abhishek Joshi (c)\nSaurabh Kadam\nRitesh Deshmukh\nAjinkya Salunke\n"
                    + "Prasad Gore\nMayur Dhumal\nSwapnil Pawar\nAniket Bhalerao\nRavi Kshirsagar"));

    private static final Logger log = LoggerFactory.getLogger(DemoTournament.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TournamentAdminService admin;
    private final ScoringService scoring;

    public DemoTournament(JdbcClient jdbc, PasswordEncoder passwords, TournamentAdminService admin,
                          ScoringService scoring) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.admin = admin;
        this.scoring = scoring;
    }

    /** Call after DemoDataSeeder#seed() (it creates the admin). */
    public void seed() {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM tournaments WHERE name = :name)")
                .param("name", NAME)
                .query(Boolean.class)
                .single();
        if (exists) {
            return;
        }
        long adminId = jdbc.sql("SELECT id FROM users WHERE phone = :phone")
                .param("phone", DemoDataSeeder.ADMIN_PHONE)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        INSERT INTO users (name, phone, password_hash, role)
                        VALUES ('Demo Scorer', :phone, :hash, 'PLAYER')
                        ON CONFLICT (phone) DO NOTHING
                        """)
                .param("phone", SCORER_PHONE)
                .param("hash", passwords.encode(SCORER_PASSWORD))
                .update();

        LocalDate today = LocalDate.now(IST);
        TournamentView.Detail cup = admin.createTournament(adminId, new CreateTournament(NAME, "Kick Arena, Cidco",
                today.minusDays(1), today.plusDays(1), new TournamentView.Rules(6, 2, 6, 8, 1, 1, 2, 1, 1, "NRR",
                        new TournamentView.LocalRules(true, true, false))));
        long[] teams = TEAMS.stream()
                .mapToLong(t -> admin.addTeam(cup.id(), new AddTeam(t.get(0), t.get(1), null, t.get(2))).id())
                .toArray();
        long cid = teams[0], gar = teams[1], alt = teams[2], osm = teams[3];
        long[][] fixtures = {{cid, gar}, {alt, osm}, {cid, alt}, {gar, osm}, {cid, osm}, {gar, alt}};
        int[] dayOffset = {-1, -1, 0, 0, 1, 1};
        int[] hour = {7, 9, 7, 9, 7, 9};
        long[] matchIds = new long[fixtures.length];
        for (int i = 0; i < fixtures.length; i++) {
            matchIds[i] = admin.createMatch(cup.id(), new CreateMatch(i + 1, "GROUP",
                    new SideRequest(fixtures[i][0], null), new SideRequest(fixtures[i][1], null),
                    at(today.plusDays(dayOffset[i]), hour[i]), SCORER_PHONE)).id();
        }
        admin.createMatch(cup.id(), new CreateMatch(7, "FINAL", new SideRequest(null, "1st in the table"),
                new SideRequest(null, "2nd in the table"), at(today.plusDays(1), 11), SCORER_PHONE));

        Actor asAdmin = new Actor(adminId, true);
        Random random = new Random(20261107);
        play(matchIds[0], asAdmin, random);
        play(matchIds[1], asAdmin, random);
        log.info("Demo tournament: {} with 4 teams and 7 matches, 2 played; scorer {} / {}", NAME, SCORER_PHONE,
                SCORER_PASSWORD);
    }

    private static OffsetDateTime at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(IST).toOffsetDateTime();
    }

    /** Plays a whole match through the scoring service: plausible turf cricket, every ball checked by the engine. */
    private void play(long matchId, Actor actor, Random random) {
        MatchView m = scoring.match(matchId);
        long winner = random.nextBoolean() ? m.teamA().teamId() : m.teamB().teamId();
        String decision = random.nextBoolean() ? "BAT" : "BOWL";
        m = scoring.toss(matchId, actor, new TossRequest(winner, decision));
        for (int innings = 1; innings <= 2; innings++) {
            long batting = innings == 1
                    ? (decision.equals("BAT") ? winner : other(m, winner))
                    : m.innings().get(0).bowlingTeamId();
            List<MatchView.Named> bat = squad(m, batting);
            List<MatchView.Named> bowl = squad(m, other(m, batting));
            m = scoring.startInnings(matchId, actor, new StartInningsRequest(bat.get(0).cricketerId(),
                    bat.get(1).cricketerId(), bowl.get(bowl.size() - 1).cricketerId()));
            while (m.live() != null) {
                m = scoring.addDelivery(m.live().inningsId(), actor, ball(m, bowl, random));
            }
            if (!m.status().equals("LIVE")) {
                break; // decided
            }
        }
    }

    private static DeliveryRequest ball(MatchView m, List<MatchView.Named> bowlers, Random random) {
        MatchView.LiveState live = m.live();
        long striker = live.strikerId() != null ? live.strikerId() : live.availableBatterIds().get(0);
        long nonStriker = live.nonStrikerId() != null ? live.nonStrikerId() : live.availableBatterIds().get(0);
        if (live.strikerId() == null && live.nonStrikerId() == null) {
            nonStriker = live.availableBatterIds().get(1);
        }
        long bowler = live.bowlerId() != null ? live.bowlerId()
                : live.eligibleBowlerIds().get(random.nextInt(live.eligibleBowlerIds().size()));
        double r = random.nextDouble();
        UUID id = new UUID(m.id() * 1_000_000L + live.inningsNumber() * 1_000L + live.nextSeq(), 20261107L);
        if (r < 0.04) {
            return new DeliveryRequest(id, live.nextSeq(), striker, nonStriker, bowler, 0, ExtraType.WD,
                    m.rules().wideRuns(), null, null, null);
        }
        if (r < 0.06) {
            return new DeliveryRequest(id, live.nextSeq(), striker, nonStriker, bowler, random.nextInt(2),
                    ExtraType.NB, m.rules().noBallRuns(), null, null, null);
        }
        if (r < 0.12 && !live.freeHit()) {
            double how = random.nextDouble();
            long fielder = bowlers.get(random.nextInt(bowlers.size())).cricketerId();
            if (how < 0.5) {
                return new DeliveryRequest(id, live.nextSeq(), striker, nonStriker, bowler, 0, null, 0,
                        WicketType.CAUGHT, striker, fielder);
            }
            if (how < 0.85) {
                return new DeliveryRequest(id, live.nextSeq(), striker, nonStriker, bowler, 0, null, 0,
                        WicketType.BOWLED, striker, null);
            }
            return new DeliveryRequest(id, live.nextSeq(), striker, nonStriker, bowler, 1, null, 0,
                    WicketType.RUN_OUT, striker, fielder);
        }
        int[] runs = {0, 0, 0, 1, 1, 1, 2, 2, 4, 4, 6};
        return new DeliveryRequest(id, live.nextSeq(), striker, nonStriker, bowler, runs[random.nextInt(runs.length)],
                null, 0, null, null, null);
    }

    private static long other(MatchView m, long teamId) {
        return teamId == m.teamA().teamId() ? m.teamB().teamId() : m.teamA().teamId();
    }

    private static List<MatchView.Named> squad(MatchView m, long teamId) {
        return m.teamA().teamId() == teamId ? m.teamA().squad() : m.teamB().squad();
    }
}
