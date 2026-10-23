# End-to-end walkthrough

Drives the real app in a phone-sized Chromium through every main flow:
player hold → pay → cancel (with and without refund) → release; owner walk-in, phone entry, balance,
no-show (on one court column of a multi-court venue), cancel with a reason, waiver; "any free court" badminton bookings spreading over two courts; a new owner setting up a venue with its first court; admin approval.

`tournament.spec.ts` covers live cricket on the demo tournament: the public tournament page (results, points table, leaderboards, a scorecard), then the demo scorer scores a match while a logged-out visitor follows the live page; the signal drops mid-over, the scorer keeps scoring, and when it returns the balls send themselves and both phones show the same score; an over ends and the next bowler is picked. A second test has the admin set up a tournament from the screens: rules, two teams from pasted squads, a match with a scorer and a final whose teams aren't known yet; the match is moved, then abandoned, and the public points table gives each side a point. It runs after the first because a later tournament takes over the Explore link. The e2e backend runs with the tournament module on (`TURFUP_TOURNAMENTS_ENABLED`).

```
cd frontend && npm run e2e        # or: npx playwright test   (SHOTS=<dir> keeps screenshots)
```

It runs on its own stack and never touches the demo database:

| Piece    | Port | Started by |
|----------|------|------------|
| Postgres (`postgres-e2e`, in memory, recreated each run) | 5433 | `e2e/start-backend.mjs` via docker compose `--profile e2e` |
| Backend (demo profile, so it gets the same seed data)    | 8081 | `e2e/start-backend.mjs` |
| Vite, proxying `/api` to :8081                           | 5174 | Playwright `webServer` |

Playwright starts all three and stops them afterwards (`e2e/teardown.mjs` stops the database). Docker must be
running. The demo stack on :5432/:8080/:5173 can keep running alongside.
