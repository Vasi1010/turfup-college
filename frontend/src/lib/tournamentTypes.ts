/**
 * Tournament API shapes (task B1 draft, see docs/tournament.md). Every number in a response is computed on the server
 * from the ball-by-ball rows; the client never adds anything up. Times are ISO instants, dates are "YYYY-MM-DD".
 * Overs are shown as "7.3" (7 overs and 3 balls) and also given as legal balls, for sorting and maths.
 */

export type TournamentStatus = 'UPCOMING' | 'LIVE' | 'DONE'
export type MatchStage = 'GROUP' | 'QUARTER_FINAL' | 'SEMI_FINAL' | 'FINAL'
export type MatchStatus = 'UPCOMING' | 'LIVE' | 'DONE' | 'ABANDONED'
export type MatchResult = 'WIN' | 'TIE' | 'NO_RESULT'
export type TossDecision = 'BAT' | 'BOWL'
export type InningsEndReason = 'OVERS' | 'ALL_OUT' | 'TARGET' | 'CLOSED'
/** WD wide, NB no-ball, B bye, LB leg bye. */
export type ExtraType = 'WD' | 'NB' | 'B' | 'LB'
export type WicketType =
  | 'BOWLED'
  | 'CAUGHT'
  | 'LBW'
  | 'RUN_OUT'
  | 'STUMPED'
  | 'HIT_WICKET'
  | 'RETIRED_OUT'
  | 'RETIRED_HURT'

/** The organiser's rules. */
export interface TournamentRules {
  oversPerInnings: number
  /** null: no limit. */
  maxOversPerBowler: number | null
  ballsPerOver: number
  /** All out at playersPerSide - 1 wickets (playersPerSide with lastManStands). */
  playersPerSide: number
  wideRuns: number
  noBallRuns: number
  pointsWin: number
  pointsTie: number
  pointsNoResult: number
  tiebreaker: 'NRR' | 'HEAD_TO_HEAD'
  localRules: LocalRules
}

export interface LocalRules {
  noLbw?: boolean
  freeHit?: boolean
  lastManStands?: boolean
}

export interface Cricketer {
  id: number
  name: string
  jerseyNo: number | null
}

export interface Team {
  id: number
  name: string
  shortName: string
  groupName: string | null
  captainId: number | null
  squad: Cricketer[]
}

/** A team in a fixture: the team once known, otherwise a label such as "Winner SF 1". */
export interface FixtureSide {
  teamId: number | null
  label: string
}

export interface InningsScore {
  number: 1 | 2
  battingTeamId: number
  runs: number
  wickets: number
  legalBalls: number
  overs: string
  /** null while the innings is LIVE. */
  endReason: InningsEndReason | null
}

/** A match in the fixture list. */
export interface Fixture {
  id: number
  matchNo: number
  stage: MatchStage
  scheduledAt: string | null
  status: MatchStatus
  teamA: FixtureSide
  teamB: FixtureSide
  /** Played innings, in order. */
  innings: InningsScore[]
  result: MatchResult | null
  winnerId: number | null
  /** e.g. "Cidco Strikers won by 12 runs"; null until DONE or ABANDONED. */
  resultText: string | null
}

/** GET /api/tournaments: every tournament, the latest first. */
export interface TournamentSummary {
  id: number
  name: string
  venueName: string
  startDate: string
  endDate: string
}

/**
 * GET /api/tournaments/{id}. status: UPCOMING until a match starts, LIVE while matches are being played, DONE once
 * every match is finished or abandoned.
 */
export interface TournamentDetail {
  id: number
  name: string
  venueName: string
  startDate: string
  endDate: string
  status: TournamentStatus
  rules: TournamentRules
  teams: Team[]
  matches: Fixture[]
}

export interface PointsRow {
  position: number
  teamId: number
  teamName: string
  shortName: string
  played: number
  won: number
  lost: number
  tied: number
  noResult: number
  points: number
  runsFor: number
  /** All out counts as the full overs. */
  ballsFaced: number
  oversFaced: string
  runsAgainst: number
  ballsBowled: number
  oversBowled: string
  /** Net run rate to 3 decimals; 0 before the first result. */
  nrr: number
}

/** GET /api/tournaments/{id}/table: DONE matches only; one group, named null, when there are no groups. */
export interface PointsTable {
  groups: { name: string | null; rows: PointsRow[] }[]
}

export interface RunScorer {
  cricketerId: number
  name: string
  teamId: number
  teamShortName: string
  innings: number
  notOuts: number
  runs: number
  balls: number
  fours: number
  sixes: number
  /** "34*" when not out. */
  highest: string
  strikeRate: number
}

export interface WicketTaker {
  cricketerId: number
  name: string
  teamId: number
  teamShortName: string
  innings: number
  legalBalls: number
  overs: string
  runs: number
  wickets: number
  economy: number
  /** Best figures in one innings, e.g. "3/12". */
  best: string
}

/** GET /api/tournaments/{id}/leaderboards: top 10 each, live matches included. */
export interface Leaderboards {
  topRunScorers: RunScorer[]
  topWicketTakers: WicketTaker[]
}

export type BatterStatus = 'BATTING' | 'NOT_OUT' | 'OUT' | 'RETIRED_HURT'

export interface BattingLine {
  cricketerId: number
  name: string
  status: BatterStatus
  /** e.g. "c Imran b Sameer", "run out (Faizan)"; null unless OUT or RETIRED_HURT. */
  dismissal: string | null
  runs: number
  /** Legal balls and no-balls faced (wides are not). */
  balls: number
  fours: number
  sixes: number
  strikeRate: number
  onStrike: boolean
}

export interface BowlingLine {
  cricketerId: number
  name: string
  legalBalls: number
  overs: string
  maidens: number
  /** Off the bat plus wides and no-balls; byes and leg byes are not the bowler's. */
  runs: number
  wickets: number
  wides: number
  noBalls: number
  economy: number
  bowling: boolean
}

export interface Extras {
  wides: number
  noBalls: number
  byes: number
  legByes: number
  total: number
}

export interface FallOfWicket {
  wicket: number
  runs: number
  overs: string
  cricketerId: number
  name: string
}

/**
 * One ball as the live page shows it. label: "0", "4", "W", "1wd" (wide, 1 run), "5wd" (wide + 4 byes),
 * "nb4" (no-ball, 4 off the bat), "2b", "1lb", "1W" (run out after a run).
 */
export interface BallSummary {
  deliveryId: string
  label: string
  runs: number
  legal: boolean
  wicket: boolean
  boundary: boolean
  /** It was a free hit (the ball after a no-ball, with the free-hit local rule). */
  freeHit: boolean
}

export interface InningsCard extends InningsScore {
  id: number
  bowlingTeamId: number
  status: 'LIVE' | 'DONE'
  extras: Extras
  /** Runs to win; null in the first innings. */
  target: number | null
  /** The latest ball, for undo (also once the innings is over); null before the first ball. */
  lastDeliveryId: string | null
  batting: BattingLine[]
  yetToBat: { cricketerId: number; name: string }[]
  bowling: BowlingLine[]
  fallOfWickets: FallOfWicket[]
}

/**
 * The state of the innings being played, for the live page and the scorer screen. The ends are the server's
 * suggestion; the scorer may swap them (every ball names both batters).
 */
export interface LiveState {
  inningsId: number
  inningsNumber: 1 | 2
  /** null when a new batter is needed at the striker's end. */
  strikerId: number | null
  /** null when a new batter is needed at the non-striker's end. */
  nonStrikerId: number | null
  /** This over's bowler (before the first ball, the opening bowler); null when a new over needs one picked. */
  bowlerId: number | null
  lastOverBowlerId: number | null
  /** The next ball's seq, for POST .../deliveries. */
  nextSeq: number
  /** The current over so far, wides and no-balls included. */
  thisOver: BallSummary[]
  /** The last 12 balls, oldest first. */
  recentBalls: BallSummary[]
  currentRunRate: number
  /** Second innings only, otherwise null. */
  runsNeeded: number | null
  ballsLeft: number
  requiredRunRate: number | null
  /** Who may bowl the next ball: not the last over's bowler, and under the over limit. */
  eligibleBowlerIds: number[]
  /** Who can come in to bat: the squad members who haven't batted. */
  availableBatterIds: number[]
  /** True when the over just ended and a new bowler must be picked. */
  newOver: boolean
  /** True after a wicket until the new batter is picked. */
  needsNewBatter: boolean
  /** The next ball is a free hit. */
  freeHit: boolean
}

/** The rules a match is scored by: the tournament's, with the match's own overs. */
export interface MatchRules {
  oversPerInnings: number
  maxOversPerBowler: number | null
  ballsPerOver: number
  playersPerSide: number
  wideRuns: number
  noBallRuns: number
  noLbw: boolean
  freeHit: boolean
}

/** A side of a match, with its squad for picking players (empty until the team is known). */
export type MatchSide = FixtureSide & {
  name: string | null
  shortName: string | null
  squad: { cricketerId: number; name: string }[]
}

/** GET /api/scorer/matches: the matches you score (an admin: all), being played first. teamA/teamB: name or label. */
export interface ScorerMatch {
  id: number
  tournamentId: number
  tournamentName: string
  matchNo: number
  stage: MatchStage
  scheduledAt: string | null
  status: MatchStatus
  teamA: string
  teamB: string
}

/** GET /api/matches/{id}, and the response of every scorer call. Poll every 5 s while LIVE. */
export interface MatchDetail {
  id: number
  tournamentId: number
  matchNo: number
  stage: MatchStage
  scheduledAt: string | null
  status: MatchStatus
  oversPerInnings: number
  rules: MatchRules
  teamA: MatchSide
  teamB: MatchSide
  toss: { winnerId: number; decision: TossDecision } | null
  innings: InningsCard[]
  live: LiveState | null
  result: MatchResult | null
  winnerId: number | null
  resultText: string | null
  resultNote: string | null
  /** Change marker: the client may skip re-rendering when it hasn't changed. */
  version: number
}

// ----- Requests -----

/** POST /api/tournaments (ADMIN) */
export interface CreateTournamentRequest {
  name: string
  venueName: string
  startDate: string
  endDate: string
  rules: TournamentRules
}

/**
 * POST /api/tournaments/{id}/teams (ADMIN). squad is pasted text, one cricketer per line, with an optional jersey
 * number first: "7 Rahul Patil" or "Rahul Patil". The first line marked "(c)" is the captain.
 */
export interface AddTeamRequest {
  name: string
  shortName: string
  groupName: string | null
  squad: string
}

/** POST /api/tournaments/{id}/matches (ADMIN). Give a team id, or a label while the team isn't known. */
export interface CreateMatchRequest {
  matchNo: number
  stage: MatchStage
  teamA: FixtureSide
  teamB: FixtureSide
  scheduledAt: string | null
  /** The scorer's TurfUp account by phone (they sign up first); null for none yet. */
  scorerPhone: string | null
}

/** PATCH /api/matches/{id} (ADMIN): only the fields given change. */
export interface UpdateMatchRequest {
  scheduledAt?: string | null
  teamA?: FixtureSide
  teamB?: FixtureSide
  oversPerInnings?: number | null
  /** The scorer by phone; null removes the scorer. */
  scorerPhone?: string | null
  /** Ends the match with no result (rain); not for a finished match. */
  status?: 'ABANDONED'
  /** Record a result once no innings is being played (e.g. after rain), or correct a finished match's. */
  result?: MatchResult
  winnerId?: number | null
  resultNote?: string | null
}

/** POST /api/matches/{id}/toss (scorer) */
export interface TossRequest {
  winnerId: number
  decision: TossDecision
}

/** POST /api/matches/{id}/innings (scorer): openers and opening bowler. */
export interface StartInningsRequest {
  strikerId: number
  nonStrikerId: number
  bowlerId: number
}

/**
 * POST /api/innings/{id}/deliveries (scorer). id is a UUID made on the phone, so a retry of the same ball is a no-op;
 * seq must be the live state's nextSeq (409 otherwise; the problem response carries the current MatchDetail as `match`).
 */
export interface AddDeliveryRequest {
  id: string
  seq: number
  strikerId: number
  nonStrikerId: number
  bowlerId: number
  runsBat: number
  extraType: ExtraType | null
  /** Every extra on the ball, penalty included: a wide with 4 byes is 5. */
  extraRuns: number
  wicketType: WicketType | null
  playerOutId: number | null
  fielderId: number | null
}

/** POST /api/innings/{id}/end (scorer): close the innings early, e.g. for rain. It ends by itself otherwise. */
export interface EndInningsRequest {
  reason: 'CLOSED'
}
