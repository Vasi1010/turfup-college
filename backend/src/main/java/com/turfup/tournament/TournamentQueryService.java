package com.turfup.tournament;

import com.turfup.tournament.TournamentView.Cricketer;
import com.turfup.tournament.TournamentView.Detail;
import com.turfup.tournament.TournamentView.Fixture;
import com.turfup.tournament.TournamentView.Group;
import com.turfup.tournament.TournamentView.InningsScore;
import com.turfup.tournament.TournamentView.Leaders;
import com.turfup.tournament.TournamentView.LocalRules;
import com.turfup.tournament.TournamentView.PointsRow;
import com.turfup.tournament.TournamentView.RunScorer;
import com.turfup.tournament.TournamentView.Rules;
import com.turfup.tournament.TournamentView.Side;
import com.turfup.tournament.TournamentView.Table;
import com.turfup.tournament.TournamentView.Team;
import com.turfup.tournament.TournamentView.WicketTaker;
import com.turfup.tournament.scoring.Standings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The public tournament pages (task B5): the tournament with its fixtures and results, the points table with NRR,
 * and the leaderboards. Every figure is computed from the balls on each read (a tournament has a handful of matches).
 */
@Service
@ConditionalOnProperty(name = "turfup.tournaments.enabled", havingValue = "true")
public class TournamentQueryService {

    /** A tournament in a list. */
    public record Summary(long id, String name, String venueName, LocalDate startDate, LocalDate endDate) {
    }

    private record Head(long id, String name, String venueName, LocalDate startDate, LocalDate endDate, Rules rules) {
    }

    private final JdbcClient jdbc;
    private final MatchLoader loader;

    public TournamentQueryService(JdbcClient jdbc, MatchLoader loader) {
        this.jdbc = jdbc;
        this.loader = loader;
    }

    /** All tournaments, the latest first. */
    @Transactional(readOnly = true)
    public List<Summary> list() {
        return jdbc.sql("""
                        SELECT id, name, venue_name, start_date, end_date FROM tournaments
                        ORDER BY start_date DESC, id DESC
                        """)
                .query(Summary.class)
                .list();
    }

    @Transactional(readOnly = true)
    public Detail detail(long tournamentId) {
        Head h = head(tournamentId);
        List<MatchView> matches = matches(tournamentId);
        return new Detail(h.id(), h.name(), h.venueName(), h.startDate(), h.endDate(), status(matches), h.rules(),
                teams(tournamentId), matches.stream().map(TournamentQueryService::fixture).toList());
    }

    /** Finished group matches only; a group per {@code teams.group_name} (one group, named null, without groups). */
    @Transactional(readOnly = true)
    public Table table(long tournamentId) {
        Head h = head(tournamentId);
        Rules r = h.rules();
        List<Team> teams = teams(tournamentId); // by name
        List<MatchView> matches = matches(tournamentId);
        Map<String, List<Team>> groups = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        teams.forEach(t -> groups.computeIfAbsent(t.groupName(), g -> new ArrayList<>()).add(t));

        Standings.PointsRules points = new Standings.PointsRules(r.pointsWin(), r.pointsTie(), r.pointsNoResult(),
                Standings.Tiebreaker.valueOf(r.tiebreaker()), r.ballsPerOver());
        List<Group> result = new ArrayList<>();
        for (Map.Entry<String, List<Team>> g : groups.entrySet()) {
            Map<Long, Team> byId = g.getValue().stream().collect(Collectors.toMap(Team::id, t -> t));
            Set<Long> ids = byId.keySet();
            List<Standings.FinishedMatch> finished = matches.stream()
                    .filter(m -> m.stage().equals("GROUP") && (m.status().equals("DONE") || m.status().equals("ABANDONED")))
                    .filter(m -> ids.contains(m.teamA().teamId()) && ids.contains(m.teamB().teamId()))
                    .map(m -> finished(m, r.ballsPerOver()))
                    .toList();
            List<Standings.Row> rows = Standings.compute(g.getValue().stream().map(Team::id).toList(), finished, points);
            List<PointsRow> table = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Standings.Row row = rows.get(i);
                Team t = byId.get(row.teamId());
                table.add(new PointsRow(i + 1, t.id(), t.name(), t.shortName(), row.played(), row.won(), row.lost(),
                        row.tied(), row.noResult(), row.points(), row.runsFor(), row.ballsFaced(),
                        overs(row.ballsFaced(), r.ballsPerOver()), row.runsAgainst(), row.ballsBowled(),
                        overs(row.ballsBowled(), r.ballsPerOver()), row.nrr()));
            }
            result.add(new Group(g.getKey(), table));
        }
        return new Table(result);
    }

    /** The top 10 run scorers and wicket takers over every innings so far, live ones included. */
    @Transactional(readOnly = true)
    public Leaders leaderboards(long tournamentId) {
        Head h = head(tournamentId);
        int bpo = h.rules().ballsPerOver();
        Map<Long, String> shortNames = new HashMap<>();
        teams(tournamentId).forEach(t -> shortNames.put(t.id(), t.shortName()));

        final class Bat {
            long id;
            String name;
            long teamId;
            int innings;
            int notOuts;
            int runs;
            int balls;
            int fours;
            int sixes;
            int highest = -1;
            boolean highestNotOut;
        }
        final class Bowl {
            long id;
            String name;
            long teamId;
            int innings;
            int legalBalls;
            int runs;
            int wickets;
            int bestWickets = -1;
            int bestRuns;
        }
        Map<Long, Bat> bats = new LinkedHashMap<>();
        Map<Long, Bowl> bowls = new LinkedHashMap<>();
        for (MatchView m : matches(tournamentId)) {
            for (MatchView.InningsCard card : m.innings()) {
                for (MatchView.BattingLine l : card.batting()) {
                    Bat a = bats.computeIfAbsent(l.cricketerId(), k -> new Bat());
                    a.id = l.cricketerId();
                    a.name = l.name();
                    a.teamId = card.battingTeamId();
                    a.innings++;
                    boolean notOut = !l.status().equals("OUT");
                    if (notOut) {
                        a.notOuts++;
                    }
                    a.runs += l.runs();
                    a.balls += l.balls();
                    a.fours += l.fours();
                    a.sixes += l.sixes();
                    if (l.runs() > a.highest || (l.runs() == a.highest && notOut)) {
                        a.highest = l.runs();
                        a.highestNotOut = notOut;
                    }
                }
                for (MatchView.BowlingLine l : card.bowling()) {
                    Bowl a = bowls.computeIfAbsent(l.cricketerId(), k -> new Bowl());
                    a.id = l.cricketerId();
                    a.name = l.name();
                    a.teamId = card.bowlingTeamId();
                    a.innings++;
                    a.legalBalls += l.legalBalls();
                    a.runs += l.runs();
                    a.wickets += l.wickets();
                    if (l.wickets() > a.bestWickets || (l.wickets() == a.bestWickets && l.runs() < a.bestRuns)) {
                        a.bestWickets = l.wickets();
                        a.bestRuns = l.runs();
                    }
                }
            }
        }
        List<RunScorer> runScorers = bats.values().stream()
                .map(a -> new RunScorer(a.id, a.name, a.teamId, shortNames.get(a.teamId), a.innings, a.notOuts, a.runs,
                        a.balls, a.fours, a.sixes, a.highest + (a.highestNotOut ? "*" : ""),
                        rate(a.runs * 100L, a.balls, 1)))
                .sorted(Comparator.comparingInt(RunScorer::runs).reversed()
                        .thenComparing(RunScorer::strikeRate, Comparator.reverseOrder())
                        .thenComparing(RunScorer::name))
                .limit(10)
                .toList();
        List<WicketTaker> wicketTakers = bowls.values().stream()
                .map(a -> new WicketTaker(a.id, a.name, a.teamId, shortNames.get(a.teamId), a.innings, a.legalBalls,
                        overs(a.legalBalls, bpo), a.runs, a.wickets, rate((long) a.runs * bpo, a.legalBalls, 2),
                        a.bestWickets + "/" + a.bestRuns))
                .sorted(Comparator.comparingInt(WicketTaker::wickets).reversed()
                        .thenComparing(WicketTaker::economy)
                        .thenComparing(WicketTaker::name))
                .limit(10)
                .toList();
        return new Leaders(runScorers, wicketTakers);
    }

    private Head head(long tournamentId) {
        return jdbc.sql("""
                        SELECT id, name, venue_name, start_date, end_date, overs_per_innings, max_overs_per_bowler,
                               balls_per_over, players_per_side, wide_runs, no_ball_runs, points_win, points_tie,
                               points_no_result, tiebreaker,
                               COALESCE((local_rules ->> 'noLbw')::boolean, false) AS no_lbw,
                               COALESCE((local_rules ->> 'freeHit')::boolean, false) AS free_hit,
                               COALESCE((local_rules ->> 'lastManStands')::boolean, false) AS last_man_stands
                        FROM tournaments WHERE id = :id
                        """)
                .param("id", tournamentId)
                .query((rs, n) -> new Head(rs.getLong("id"), rs.getString("name"), rs.getString("venue_name"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        new Rules(rs.getInt("overs_per_innings"), (Integer) rs.getObject("max_overs_per_bowler"),
                                rs.getInt("balls_per_over"), rs.getInt("players_per_side"), rs.getInt("wide_runs"),
                                rs.getInt("no_ball_runs"), rs.getInt("points_win"), rs.getInt("points_tie"),
                                rs.getInt("points_no_result"), rs.getString("tiebreaker"),
                                new LocalRules(rs.getBoolean("no_lbw"), rs.getBoolean("free_hit"),
                                        rs.getBoolean("last_man_stands")))))
                .optional()
                .orElseThrow(() -> new MatchNotFoundException("tournament", tournamentId));
    }

    /** The tournament's teams by name, each with its squad in squad order. */
    List<Team> teams(long tournamentId) {
        record Row(long id, String name, String shortName, String groupName, Long captainId) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT id, name, short_name, group_name, captain_id FROM teams
                        WHERE tournament_id = :id ORDER BY name
                        """)
                .param("id", tournamentId)
                .query(Row.class)
                .list();
        Map<Long, List<Cricketer>> squads = new HashMap<>();
        jdbc.sql("""
                        SELECT sm.team_id, c.id, c.name, sm.jersey_no
                        FROM squad_members sm JOIN cricketers c ON c.id = sm.cricketer_id
                        WHERE sm.tournament_id = :id
                        ORDER BY sm.team_id, sm.created_at, c.id
                        """)
                .param("id", tournamentId)
                .query((rs, n) -> {
                    Cricketer c = new Cricketer(rs.getLong("id"), rs.getString("name"),
                            (Integer) rs.getObject("jersey_no"));
                    squads.computeIfAbsent(rs.getLong("team_id"), k -> new ArrayList<>()).add(c);
                    return c;
                })
                .list();
        return rows.stream().map(r -> new Team(r.id(), r.name(), r.shortName(), r.groupName(), r.captainId(),
                List.copyOf(squads.getOrDefault(r.id(), List.of())))).toList();
    }

    private List<MatchView> matches(long tournamentId) {
        return jdbc.sql("SELECT id FROM matches WHERE tournament_id = :id ORDER BY match_no")
                .param("id", tournamentId)
                .query(Long.class)
                .list()
                .stream()
                .map(id -> MatchViews.of(loader.load(id)))
                .toList();
    }

    private static String status(List<MatchView> matches) {
        boolean anyLive = matches.stream().anyMatch(m -> m.status().equals("LIVE"));
        long finished = matches.stream().filter(m -> m.status().equals("DONE") || m.status().equals("ABANDONED")).count();
        if (anyLive || (finished > 0 && finished < matches.size())) {
            return "LIVE";
        }
        return finished > 0 && finished == matches.size() ? "DONE" : "UPCOMING";
    }

    private static Fixture fixture(MatchView m) {
        return new Fixture(m.id(), m.matchNo(), m.stage(), m.scheduledAt(), m.status(),
                new Side(m.teamA().teamId(), m.teamA().label()), new Side(m.teamB().teamId(), m.teamB().label()),
                m.innings().stream().map(c -> new InningsScore(c.number(), c.battingTeamId(), c.runs(), c.wickets(),
                        c.legalBalls(), c.overs(), c.endReason())).toList(),
                m.result(), m.winnerId(), m.resultText());
    }

    private static Standings.FinishedMatch finished(MatchView m, int ballsPerOver) {
        Standings.Result result = m.status().equals("ABANDONED") || "NO_RESULT".equals(m.result())
                ? Standings.Result.NO_RESULT
                : "TIE".equals(m.result()) ? Standings.Result.TIE : Standings.Result.WIN;
        int maxBalls = m.oversPerInnings() * ballsPerOver; // the match's own overs if it was shortened
        return new Standings.FinishedMatch(m.teamA().teamId(), m.teamB().teamId(), result,
                result == Standings.Result.WIN ? m.winnerId() : null, maxBalls,
                m.innings().stream().map(c -> new Standings.InningsTotal(c.battingTeamId(), c.runs(), c.legalBalls(),
                        Objects.equals(c.endReason(), "ALL_OUT"))).toList());
    }

    private static String overs(int legalBalls, int ballsPerOver) {
        return legalBalls / ballsPerOver + "." + legalBalls % ballsPerOver;
    }

    private static BigDecimal rate(long numerator, int denominator, int scale) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }
}
