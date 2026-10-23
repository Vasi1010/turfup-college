# Tournament module

_Status: **frozen on 22 Oct 2026** as migration `V15__tournaments.sql`, so the scorer endpoints (B3) could be built
before the tournament. A committed migration is never edited: any change from here, including one the organiser's
answers need (section 6), is a new migration. This file and `frontend/src/lib/tournamentTypes.ts` are the API
contract._

Live scoring for the cricket tournament on 7–8 Nov (`docs/work-plan.md`, track B). The scorer taps one button per
ball; each tap is one row in `deliveries`; every number anyone sees is computed from those rows.

| File | What |
|---|---|
| `backend/src/main/resources/db/migration/V15__tournaments.sql` | The schema. Checked: applies on top of V1–V14 on PostgreSQL 16, and its constraints reject 14 kinds of bad data (see "Verified" below) |
| `frontend/src/lib/tournamentTypes.ts` | Every request and response shape |
| `frontend/src/mocks/tournament.ts` | Mock responses that match those types (the build type-checks them) |
| `frontend/src/mocks/tournament.test.ts` | Checks the mocks add up: totals, bowling figures, points table with NRR, leaderboards |

---

## 1. Decisions

Frozen with V15. To change one, agree it first, then add a migration (and update the engine, the API and the mocks
in the same PR).

| # | Proposal | Why |
|---|---|---|
| D1 | Tournament players are **`cricketers`**, separate from app users | Most will never have an account, and "player" already means a registered user (`users.role = 'PLAYER'`, `bookings.player_id`) |
| D2 | **ADMIN** creates and edits tournaments, teams and fixtures. The **scorer** is any logged-in account assigned to a match (`matches.scorer_user_id`); ADMIN can always score | No new role, and a scorer can only touch their own match |
| D3 | **Who's on strike** is suggested by the server (`live.strikerId`) and confirmed by the scorer; every ball carries `strikerId` and `nonStrikerId` | Dismissal rules differ by type and organisers play them loosely. The rows stay right even when the suggestion is wrong |
| D4 | The module sits behind **`turfup.tournaments.enabled`** (default `false`); its endpoints answer 404 when off | CLAUDE.md: features are switched by config. Pavilion's server can switch it off after the event |
| D5 | **Undo** is `DELETE /api/deliveries/{id}` for the latest ball, not `DELETE .../deliveries/last` | The offline queue may send an undo twice; with an id the second is a no-op instead of deleting another ball |
| D6 | **Not stored:** over and ball numbers, the target, any total | They follow from the balls (`seq`, `is_legal`), so they can't disagree with them |
| D7 | **Groups belong to teams** (`teams.group_name`); a knockout fixture can exist before its teams are known, with labels such as "Winner SF 1" | The table shows a team with no matches yet, and the final can be on the schedule from day one |
| D8 | **No playing XI per match** in this version: all out is at `players_per_side − 1` wickets (or `players_per_side` with "last man stands"); "yet to bat" lists the rest of the squad | Simpler for 7 Nov. Track it later if squads are much bigger than the side |
| D9 | **A tied knockout** is decided by a super over that is not scored ball by ball: the admin records `result = TIE` with the `winnerId` and a note | Rare, and not worth building the engine for |
| D10 | **The points table** counts finished matches only; **leaderboards** include the live match | What spectators expect |

---

## 2. Schema

See `V15__tournaments.sql` for the full definition and comments. In short:

- **`tournaments`**: the organiser's rules (overs, bowler limit, balls per over, players per side, wide and no-ball
  runs, points, tiebreaker) and `local_rules` (JSON: `noLbw`, `freeHit`, `lastManStands`).
- **`teams`**, **`cricketers`**, **`squad_members`**: a cricketer plays for one team per tournament (enforced by a
  unique key), and the captain must be in the squad (composite foreign key).
- **`matches`**: fixture, toss, status, result. A finished match must have a result, a win must have a winner, the toss
  and winner must be one of the two teams, and a match can't go live before both teams are known. `version` goes up
  by one on every scoring change, so a polling page can skip redrawing an unchanged match.
- **`innings`**: the openers and the opening bowler (the live page shows them before the first ball; after it,
  every ball names its batters and bowler); at most one live innings per match (partial unique index); why it ended
  (`OVERS`, `ALL_OUT`, `TARGET`, `CLOSED`).
- **`deliveries`**: one row per ball. `id` is a UUID made on the scorer's phone, so a retried upload is a no-op.
  `is_legal` is a generated column. Undo sets `deleted_at`; the next ball reuses the `seq` (partial unique index on
  live rows). CHECK constraints refuse impossible balls: runs off the bat on a wide, a catch off a no-ball, a
  non-striker bowled, the bowler batting.
- **`innings_totals`** (view): runs, wickets, legal balls and extras of every innings, computed from its balls. The
  other figures follow the same pattern (section 3).

Lock order, the same as the booking paths: the match row, then the innings, then deliveries. One scorer per
match, so contention is rare.

**Verified:** V15 applies after V1–V14 on PostgreSQL 16. A test innings (a four, a wide with 4 byes, a no-ball
with a run, a catch, then a run-out undone and replaced by a six) gives 17 runs, 1 wicket, 3 legal balls and 6 extras,
as expected. These are all rejected by the database: a cricketer in two teams, a squad member from another
tournament's team, a captain outside the squad, a fixture with another tournament's team, a live match without teams,
a win without a winner, a second live innings, the same opener twice, an opening bowler who is also an opener, a
duplicate `seq`, bat runs on a wide, a catch off a no-ball, the
non-striker bowled, and a bowler who is also batting.

---

## 3. How every figure is computed

Over the live (`deleted_at IS NULL`) deliveries of an innings, in `seq` order:

| Figure | Rule |
|---|---|
| Runs | Σ (`runs_bat` + `extra_runs`) |
| Wickets | balls with a `wicket_type` other than `RETIRED_HURT` |
| Legal balls | balls with `extra_type` null, `B` or `LB`. Overs shown as `legal ÷ balls_per_over` "." `legal mod balls_per_over`, e.g. 7.3 |
| Over of a ball | legal balls before it ÷ balls per over (a window count over `seq`); wides and no-balls belong to the over in progress |
| Batter: runs | Σ `runs_bat` as striker |
| Batter: balls faced | balls as striker except wides (no-balls count) |
| Batter: 4s, 6s | `runs_bat` = 4 or 6 |
| Bowler: runs | `runs_bat` + the `extra_runs` of wides and no-balls (byes and leg byes are not the bowler's) |
| Bowler: wickets | bowled, caught, LBW, stumped, hit wicket (not run out, not retired) |
| Maiden | a completed over of the bowler with 0 bowler runs |
| Extras | wides, no-balls, byes and leg byes: Σ `extra_runs` by `extra_type` |
| Target | first innings runs + 1 |
| Strike rate | runs × 100 ÷ balls faced, 1 decimal |
| Economy | bowler runs ÷ (legal balls ÷ balls per over), 2 decimals |
| Run rates | current: runs ÷ overs. Required: runs needed ÷ (balls left ÷ balls per over) |
| Result | chasing side passes the target: won by (all-out wickets − wickets lost) wickets, with balls left; otherwise won by the run margin; equal: tie |
| NRR | (runs for ÷ overs faced) − (runs against ÷ overs bowled), over finished matches. **A side bowled out counts as having faced its full overs** (confirm with the organiser). 3 decimals |
| Table order | points, then NRR, then name (or head-to-head if that's the tiebreaker) |

The scoring engine (B2) is a pure function from the tournament rules and an innings' deliveries to these figures plus
the live state (who's on, eligible bowlers, whether the innings has ended). The public queries (B5) use the same rules
over the database.

---

## 4. API

Types are in `frontend/src/lib/tournamentTypes.ts`. Times are ISO instants, dates `YYYY-MM-DD`.

| Method and path | Who | Request | Response |
|---|---|---|---|
| `GET /api/tournaments` | anyone | | `TournamentSummary[]` |
| `GET /api/tournaments/{id}` | anyone | | `TournamentDetail` |
| `GET /api/tournaments/{id}/table` | anyone | | `PointsTable` |
| `GET /api/tournaments/{id}/leaderboards` | anyone | | `Leaderboards` |
| `GET /api/matches/{id}` | anyone | | `MatchDetail` |
| `POST /api/tournaments` | ADMIN | `CreateTournamentRequest` | `TournamentDetail` |
| `POST /api/tournaments/{id}/teams` | ADMIN | `AddTeamRequest` (squad as pasted lines) | `Team` |
| `POST /api/tournaments/{id}/matches` | ADMIN | `CreateMatchRequest` | `MatchDetail` |
| `PATCH /api/matches/{id}` | ADMIN | `UpdateMatchRequest` | `MatchDetail` |
| `GET /api/scorer/matches` | logged in | | `ScorerMatch[]`: the matches you score (an admin: all) |
| `POST /api/matches/{id}/toss` | scorer | `TossRequest` | `MatchDetail` |
| `POST /api/matches/{id}/innings` | scorer | `StartInningsRequest` | `MatchDetail` |
| `POST /api/innings/{id}/deliveries` | scorer | `AddDeliveryRequest` | `MatchDetail` |
| `DELETE /api/deliveries/{id}` | scorer | | `MatchDetail` |
| `POST /api/innings/{id}/end` | scorer | `EndInningsRequest` | `MatchDetail` |

Every scorer call returns the whole `MatchDetail`, so the scorer screen redraws from the server's figures. It also
carries what the scorer screen needs to build the next ball: the match's `rules` (wide and no-ball runs, no LBW, free
hit), each side's `squad`, and each innings' `lastDeliveryId` (undo, also once the innings is over).

**The scorer screen** (`/score`, then `/score/{matchId}`, task B4) works without a signal (task B6). Each tap goes
into a queue saved on the phone (it survives a reload), which sends the balls in order whenever there is a
connection, each with its own id, so a ball whose answer was lost is never recorded twice. Meanwhile the screen shows
the last confirmed match with the waiting balls applied by the same rules as the server (`frontend/src/lib/liveScore.ts`),
and keeps the last confirmed match on the phone. Undo takes back a ball that hasn't been sent. A ball the server
refuses stops the queue with the reason; the scorer retries or discards what is left. Toss, starting and ending an
innings need a connection. The app itself is not cached for offline use: open the scorer screen with a signal, then
it keeps working if the signal drops.

**Setting up (ADMIN):**
- A squad is pasted one cricketer per line: `7 Rahul Patil`, `10. Sameer Khan` or `Aditya Kulkarni`. `(c)` marks the
  captain. Blank lines are skipped; a name or jersey listed twice is refused.
- A match's scorer is given by phone (`scorerPhone`): they register in the app first, as any player.
- `PATCH /api/matches/{id}` changes only the fields sent; a field sent as `null` clears it. Teams and overs change
  only before play starts (changing the teams clears the toss). `status: "ABANDONED"` ends a match with no result.
  A `result` (with `winnerId` for a win, or a tie decided by a super over) can be recorded once no innings is being
  played, e.g. after the second innings was closed for rain, and corrected after the match is over.

**Errors** are problem responses (RFC 9457), like the rest of the API (`ApiExceptionHandler`): the message to show is
in `detail`.
- 400: invalid input (a wicket type not allowed on that ball, a bowler over the limit, a batter who is already out).
- 401 / 403: not logged in, or not this match's scorer (ADMIN always may).
- 404: unknown id, or the module is switched off.
- 409: the match moved on. A ball whose `seq` isn't the live state's `nextSeq`, or an undo of a ball that isn't the
  latest. The problem carries the current `MatchDetail` in a `match` property, so the scorer screen catches up.
- A delivery whose `id` is already saved, or an undo of a ball already undone, returns 200 with the current
  `MatchDetail`, and nothing changes (retry-safe).

**Live updates:** pages poll `GET /api/matches/{id}` every 5 s while the match is `LIVE` and skip redrawing when
`version` hasn't changed.

---

## 5. Mocks (for building the pages now)

`frontend/src/mocks/tournament.ts`: a 4-team, 8-over tournament (8 a side, 2 overs per bowler, no LBW, free hit).
Matches 1–3 are done, match 4 is live in the chase (16 needed off 16 balls), 5–6 and the final are upcoming.

- Generated by simulating every ball and computing each figure the way section 3 says, so scorecards, the points
  table and the leaderboards agree. Example: Cidco's NRR is 68 ÷ 6.1667 − 67 ÷ 8 = **2.652**.
- `mockGet(path)` returns the mock for a public GET path, so a page can switch to the real API in one place later.
- `tournament.test.ts` fails if a hand edit makes the mocks disagree with each other.
- Change the contract and the mocks in the same PR (`docs/work-plan.md`, section 5).

---

## 6. Questions for the organiser (B9)

Each one changes a tournament setting, not the code:

1. Overs per innings, balls per over, players per side, and the bowler's over limit.
2. Runs for a wide and for a no-ball (1 each by default), and whether a no-ball gives a free hit.
3. LBW allowed? "Last man stands" (the last batter carries on alone)?
4. Points for a win, tie and no result, and the tiebreaker (NRR or head-to-head).
5. Does a side bowled out count as having faced its full overs for NRR? (The standard rule, and our default.)
6. Format: one group or several, then semis or straight to a final? How is a tied knockout decided?
