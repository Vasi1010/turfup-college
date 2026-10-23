#!/usr/bin/env node
// Load check for tournament day (work plan, phase 8): many phones watching a live match while the scorer scores.
// Each simulated phone polls the API the way the real pages do: the match page (/m/{id}) every 5 s, or the
// tournament page (/t/{id}: fixtures plus the table or the leaderboards) every 15 s. Phones start at random moments,
// as real ones do. With --score the demo scorer scores a ball every few seconds, so the scorecard keeps changing and
// the scorer's own taps are timed under the load.
//
//   node scripts/load-check.mjs                                   # 30 phones for 3 minutes on localhost:8080
//   node scripts/load-check.mjs --viewers 20,50,100 --minutes 2 --score
//   node scripts/load-check.mjs --base https://<domain> --viewers 50 --score --phone <scorer> --password <pw>
//
// Options:
//   --base URL         where /api lives (default http://localhost:8080; a frontend origin works too, via its proxy)
//   --viewers N[,N]    phones per stage; several numbers run stages one after another (default 30)
//   --minutes M        length of each stage (default 3)
//   --tournament ID    default: the first tournament listed
//   --match ID         default: a LIVE match, else the first upcoming match with both teams known
//   --score            log in as the scorer and score the match while the phones watch (it really scores it)
//   --phone, --password  the scorer's login (default the demo scorer 7777777777 / scorer-demo-123)
//   --ball-seconds S   seconds between balls with --score (default 8)
//   --max-p95 MS       pass mark for the 95th-percentile response time (default 1000)
//
// Needs Node 18+ and nothing else. The backend must run with TURFUP_TOURNAMENTS_ENABLED=true and have a tournament
// (the demo profile seeds the TurfUp Cricket Cup). Exits 1 if any stage fails: an error, or p95 over --max-p95.
// --score writes real deliveries: on a real deployment, point it at a tournament made for the test.

const args = parseArgs(process.argv.slice(2))
const BASE = (args.base ?? 'http://localhost:8080').replace(/\/$/, '')
const STAGES = String(args.viewers ?? '30').split(',').map(Number)
const STAGE_MS = Number(args.minutes ?? 3) * 60_000
const BALL_MS = Number(args['ball-seconds'] ?? 8) * 1000
const MAX_P95 = Number(args['max-p95'] ?? 1000)
const MATCH_POLL_MS = 5_000 // frontend MatchPage POLL_MS
const TOURNAMENT_POLL_MS = 15_000 // frontend TournamentPage REFRESH_MS
const MATCH_PAGE_SHARE = 0.7 // most people at the ground watch the live match
const TIMEOUT_MS = 10_000
const REPORT_MS = 15_000

if (STAGES.some((n) => !Number.isInteger(n) || n < 1) || !(STAGE_MS > 0) || !(BALL_MS > 0)) {
  console.error('Bad options. See the top of scripts/load-check.mjs.')
  process.exit(2)
}

/** Response times and failures, per endpoint, for the stage running now. */
let stats = newStats()

function newStats() {
  return { byLabel: new Map(), window: [], windowErrors: 0 }
}

function record(label, ms, ok, bytes, why) {
  let s = stats.byLabel.get(label)
  if (!s) stats.byLabel.set(label, (s = { times: [], errors: new Map(), bytes: 0 }))
  if (ok) {
    s.times.push(ms)
    s.bytes += bytes
    stats.window.push(ms)
  } else {
    s.errors.set(why, (s.errors.get(why) ?? 0) + 1)
    stats.windowErrors++
  }
}

async function call(label, path, { method = 'GET', body, token } = {}) {
  const started = performance.now()
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    const text = await res.text()
    const ms = performance.now() - started
    if (!res.ok) {
      record(label, ms, false, 0, `HTTP ${res.status}`)
      const err = new Error(`${method} ${path}: HTTP ${res.status} ${text.slice(0, 200)}`)
      err.status = res.status
      throw err
    }
    record(label, ms, true, text.length)
    return text ? JSON.parse(text) : null
  } catch (e) {
    if (e.status === undefined) {
      record(label, performance.now() - started, false, 0, e.name === 'TimeoutError' ? 'timeout' : e.code ?? e.name)
    }
    throw e
  }
}

/** Untimed setup calls (finding the match, logging in), before any stage starts. */
async function setup(path, options) {
  const saved = stats
  stats = newStats()
  try {
    return await call('setup', path, options)
  } finally {
    stats = saved
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const pick = (list) => list[Math.floor(Math.random() * list.length)]

// ---- The phones ----

/** One phone until `until`: polls like the page it is on and ignores failures (the real pages retry too). */
async function phone(tournamentId, matchId, until) {
  const onMatch = Math.random() < MATCH_PAGE_SHARE
  const every = onMatch ? MATCH_POLL_MS : TOURNAMENT_POLL_MS
  const tab = Math.random() < 0.5 ? 'table' : 'leaderboards'
  await sleep(Math.random() * every)
  while (Date.now() < until) {
    const tick = Date.now()
    if (onMatch) {
      await call('match', `/api/matches/${matchId}`).catch(() => {})
    } else {
      await Promise.all([
        call('tournament', `/api/tournaments/${tournamentId}`).catch(() => {}),
        call(tab, `/api/tournaments/${tournamentId}/${tab}`).catch(() => {}),
      ])
    }
    // React Query polls at a fixed rate; a refetch still running when the next is due is not repeated.
    await sleep(Math.max(0, Math.min(tick + every, until) - Date.now()))
  }
}

// ---- The scorer ----

const scorer = { note: '', printed: false }

/** Scores the match ball by ball until it ends or the run is over; plausible turf cricket, like DemoTournament. */
async function score(matchId, token, until) {
  let m = await call('scorer', `/api/matches/${matchId}`, { token })
  while (Date.now() < until) {
    if (m.status === 'DONE' || m.status === 'ABANDONED') {
      scorer.note = `match ${m.status.toLowerCase()}: ${m.resultText ?? m.result ?? ''}`.trim()
      return
    }
    try {
      if (!m.toss) {
        m = await call('scorer', `/api/matches/${matchId}/toss`, {
          method: 'POST', token, body: { winnerId: m.teamA.teamId, decision: 'BAT' },
        })
      } else if (!m.live && m.innings.length >= 2) {
        scorer.note = `both innings over but the match is ${m.status}; scoring stopped`
        return
      } else if (!m.live) {
        m = await call('scorer', `/api/matches/${matchId}/innings`, { method: 'POST', token, body: openers(m) })
      } else {
        m = await call('ball', `/api/innings/${m.live.inningsId}/deliveries`, { method: 'POST', token, body: ball(m) })
        await sleep(BALL_MS)
      }
    } catch (e) {
      if (e.status === 401 || e.status === 403) {
        scorer.note = `scoring stopped: this login may not score match ${matchId} (${e.message})`
        return
      }
      // A failed tap: read the match again and carry on, as the scorer screen would.
      await sleep(2000)
      m = await call('scorer', `/api/matches/${matchId}`, { token }).catch(() => m)
    }
  }
}

function openers(m) {
  const first = m.innings[0]
  const batting = first ? first.bowlingTeamId
    : m.toss.decision === 'BAT' ? m.toss.winnerId : other(m, m.toss.winnerId)
  const bat = squad(m, batting)
  const bowl = squad(m, other(m, batting))
  return { strikerId: bat[0].cricketerId, nonStrikerId: bat[1].cricketerId, bowlerId: bowl.at(-1).cricketerId }
}

function ball(m) {
  const live = m.live
  const strikerId = live.strikerId ?? live.availableBatterIds[0]
  const nonStrikerId = live.nonStrikerId ?? live.availableBatterIds[live.strikerId == null ? 1 : 0]
  const bowlerId = live.bowlerId ?? pick(live.eligibleBowlerIds)
  const base = { id: crypto.randomUUID(), seq: live.nextSeq, strikerId, nonStrikerId, bowlerId }
  const r = Math.random()
  if (r < 0.04) return { ...base, runsBat: 0, extraType: 'WD', extraRuns: m.rules.wideRuns }
  if (r < 0.06) return { ...base, runsBat: Math.round(Math.random()), extraType: 'NB', extraRuns: m.rules.noBallRuns }
  if (r < 0.11 && !live.freeHit) {
    return { ...base, runsBat: 0, extraRuns: 0, wicketType: 'BOWLED', playerOutId: strikerId }
  }
  return { ...base, runsBat: pick([0, 0, 0, 1, 1, 1, 2, 2, 4, 4, 6]), extraRuns: 0 }
}

const other = (m, teamId) => (m.teamA.teamId === teamId ? m.teamB.teamId : m.teamA.teamId)
const squad = (m, teamId) => (m.teamA.teamId === teamId ? m.teamA.squad : m.teamB.squad)

// ---- Reporting ----

function percentile(sorted, p) {
  if (sorted.length === 0) return NaN
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]
}

const fmt = (ms) => (Number.isNaN(ms) ? '-' : `${Math.round(ms)}`)

function progress(started, viewers) {
  const w = stats.window.sort((a, b) => a - b)
  const secs = Math.round((Date.now() - started) / 1000)
  console.log(`  ${String(secs).padStart(4)}s  ${viewers} phones  ${(w.length / (REPORT_MS / 1000)).toFixed(1)} req/s`
    + `  p50 ${fmt(percentile(w, 50))} ms  p95 ${fmt(percentile(w, 95))} ms`
    + (stats.windowErrors ? `  ${stats.windowErrors} FAILED` : ''))
  stats.window = []
  stats.windowErrors = 0
}

/** Prints the stage's table and returns whether it passed. */
function summary(viewers) {
  const rows = [['endpoint', 'ok', 'failed', 'p50', 'p95', 'p99', 'max', 'avg KB']]
  let pass = true
  for (const [label, s] of stats.byLabel) {
    const t = s.times.sort((a, b) => a - b)
    const failed = [...s.errors.values()].reduce((a, b) => a + b, 0)
    const p95 = percentile(t, 95)
    if (failed > 0 || p95 > MAX_P95) pass = false
    rows.push([label, t.length, failed, fmt(percentile(t, 50)), fmt(p95), fmt(percentile(t, 99)), fmt(t.at(-1)),
      t.length ? (s.bytes / t.length / 1024).toFixed(1) : '-'])
  }
  const widths = rows[0].map((_, i) => Math.max(...rows.map((r) => String(r[i]).length)))
  console.log(`\n  ${viewers} phones (times in ms):`)
  for (const r of rows) console.log('  ' + r.map((c, i) => String(c)[i ? 'padStart' : 'padEnd'](widths[i])).join('  '))
  for (const [label, s] of stats.byLabel) {
    for (const [why, n] of s.errors) console.log(`  ${label}: ${n} x ${why}`)
  }
  console.log(`  ${pass ? 'PASS' : 'FAIL'} (no failures, p95 at most ${MAX_P95} ms)\n`)
  return pass
}

// ---- Main ----

async function main() {
  console.log(`Load check against ${BASE}`)
  // A sleeping backend (Render's free plan) can take a minute to wake; wait for it before timing anything.
  for (let i = 0; ; i++) {
    try {
      const res = await fetch(BASE + '/api/tournaments', { signal: AbortSignal.timeout(90_000) })
      if (res.ok) break
      if (res.status === 404 || res.status === 403) {
        throw new Error('no /api/tournaments: is the backend running with TURFUP_TOURNAMENTS_ENABLED=true?')
      }
      throw new Error(`HTTP ${res.status}`)
    } catch (e) {
      if (i >= 2 || e.message.startsWith('no /api')) throw e
      console.log(`  waiting for the backend (${e.message})…`)
    }
  }

  const tournamentId = args.tournament ? Number(args.tournament) : (await setup('/api/tournaments'))[0]?.id
  if (!tournamentId) throw new Error('No tournament found. Run the backend with the demo profile, or pass --tournament.')
  const t = await setup(`/api/tournaments/${tournamentId}`)
  const known = (f) => f.teamA.teamId != null && f.teamB.teamId != null
  const match = args.match ? t.matches.find((f) => f.id === Number(args.match))
    : t.matches.find((f) => f.status === 'LIVE') ?? t.matches.find((f) => f.status === 'UPCOMING' && known(f))
  if (!match) throw new Error(`No live or upcoming match in "${t.name}". Pass --match.`)
  console.log(`  "${t.name}" (tournament ${tournamentId}), match ${match.matchNo} (id ${match.id}, ${match.status})`)

  let token
  if (args.score) {
    const login = await setup('/api/auth/login', {
      method: 'POST', body: { phone: args.phone ?? '7777777777', password: args.password ?? 'scorer-demo-123' },
    })
    token = login.token
    console.log(`  scoring as ${login.name}, a ball every ${BALL_MS / 1000} s`)
  }

  let allPassed = true
  for (const viewers of STAGES) {
    stats = newStats()
    const started = Date.now()
    const until = started + STAGE_MS
    console.log(`\nStage: ${viewers} phones for ${STAGE_MS / 60_000} min`)
    const timer = setInterval(() => progress(started, viewers), REPORT_MS)
    const running = Array.from({ length: viewers }, () => phone(tournamentId, match.id, until))
    if (token && !scorer.note) running.push(score(match.id, token, until).catch((e) => {
      scorer.note = `scoring stopped: ${e.message}`
    }))
    await Promise.all(running)
    clearInterval(timer)
    if (scorer.note && !scorer.printed) {
      console.log(`  scorer: ${scorer.note}`)
      scorer.printed = true
    }
    allPassed = summary(viewers) && allPassed
  }
  console.log(allPassed ? 'All stages passed.' : 'Some stages FAILED.')
  process.exit(allPassed ? 0 : 1)
}

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue
    const key = argv[i].slice(2)
    const next = argv[i + 1]
    if (next === undefined || next.startsWith('--')) out[key] = true
    else out[key] = argv[++i]
  }
  return out
}

main().catch((e) => {
  console.error(`Load check could not run: ${e.message}`)
  process.exit(2)
})
