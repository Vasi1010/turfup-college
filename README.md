# TurfUp

A sports booking platform for Aurangabad, built as a **Java + DBMS course project**. Players find football and
cricket turfs and badminton, tennis and pickleball courts by sport and area, see the open slots, hold one for
5 minutes, pay an advance and play. Owners run their courts from a dashboard: prices, walk-ins, phone bookings,
regular customers and weekly standing slots. An admin approves new venues.

The point of the project is that **two people can never book the same court at the same time**, even when 50
requests arrive at once, and that the database itself guarantees it.

- **Live app:** https://turfup-nine.vercel.app (demo data; the free server sleeps, so the first load can take
  about a minute)
- **Viva guide** (every Java and DBMS concept with the real code): https://claude.ai/artifact/TztrRCbCievLJrQfYQMANJ
- **Database notes:** [`docs/dbms.md`](docs/dbms.md): ER diagram, normalization to BCNF, constraints, the
  exclusion constraint and the deadlock it caused, transactions and isolation, the trigger and the stored procedure

## What this version includes

This is the **college version**: the core booking system with payments simulated. The same codebase also has
business features (UPI and Razorpay payments, a cricket tournament module); they are switched off here by
configuration, not removed:

| Setting | This version |
|---|---|
| `turfup.payments.mode` | `simulated`: "Pay advance" confirms the booking at once, through the same service methods a real payment uses |
| `turfup.notifications.enabled` | `false` |
| `turfup.subscriptions.enabled` | `false` |
| `turfup.tournaments.enabled` | `false` |

## Stack

- **Backend:** Java 21, Spring Boot 3, Spring Security (JWT), Spring Data JPA and SQL through `JdbcClient`
- **Database:** PostgreSQL 16; the schema is built only by Flyway migrations
  ([`backend/src/main/resources/db/migration`](backend/src/main/resources/db/migration))
- **Frontend:** React, TypeScript, Vite, Tailwind
- **Tests:** JUnit 5 + Testcontainers on a real PostgreSQL (never an in-memory database), Vitest, Playwright

## DBMS concepts at a glance

| Concept | Where |
|---|---|
| ER model, normalization (1NF to BCNF) | [`docs/dbms.md`](docs/dbms.md) |
| No double booking: `EXCLUDE USING gist (court_id WITH =, tstzrange(start_at, end_at) WITH &&)` | [`V1__init.sql`](backend/src/main/resources/db/migration/V1__init.sql), [`V12__venues_and_courts.sql`](backend/src/main/resources/db/migration/V12__venues_and_courts.sql) |
| Transactions, row locks (`FOR NO KEY UPDATE`), READ COMMITTED | [`BookingService.java`](backend/src/main/java/com/turfup/booking/BookingService.java), [`CancellationService.java`](backend/src/main/java/com/turfup/booking/CancellationService.java) |
| Trigger: counting no-shows | [`V2__no_show_trigger_and_payment_indexes.sql`](backend/src/main/resources/db/migration/V2__no_show_trigger_and_payment_indexes.sql) |
| Stored procedure: releasing expired holds | [`V6__release_expired_holds.sql`](backend/src/main/resources/db/migration/V6__release_expired_holds.sql), [`HoldExpiryJob.java`](backend/src/main/java/com/turfup/booking/HoldExpiryJob.java) |
| 50 concurrent requests for one slot, exactly one succeeds | [`BookingHoldConcurrencyTest.java`](backend/src/test/java/com/turfup/booking/BookingHoldConcurrencyTest.java) |

## Run it locally

You need Git, Java 21, Node.js 22 and Docker Desktop.

```bash
cp .env.example .env              # put any 32+ character string after JWT_SECRET=
docker compose up -d              # the local PostgreSQL

# Terminal 1: the backend, with demo data
cd backend
JWT_SECRET=<same string> SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
# PowerShell: $env:JWT_SECRET="<string>"; $env:SPRING_PROFILES_ACTIVE="demo"; .\mvnw spring-boot:run

# Terminal 2: the app
cd frontend
npm install
npm run dev                       # open the printed localhost link
```

Demo logins (password in brackets):

- Admin: 9999999999 (`admin-demo-123`)
- Owners: 8888888888 Kick Arena, 8888888881 Street Sports Arena, 8888888882 The Pavilion Arena (`owner-demo-123`)
- Players: register with any phone number

Never use the `demo` profile on a real deployment.

## Tests

```bash
cd backend && ./mvnw test         # Docker must be running (Testcontainers)
cd frontend && npm run lint && npm test && npm run build
cd frontend && npm run e2e        # Playwright walkthrough on its own throwaway database
```

## Project layout

```
backend/    Spring Boot app: auth, venue, pricing, availability, booking, payment
frontend/   React app
docs/       dbms.md (viva notes, ER diagram), design notes
```

## About this repository

This repository is a snapshot of the TurfUp working repository, published for the course. Development happens
there; this copy is refreshed from it. A few comments mention `CLAUDE.md`, `docs/work-plan.md` or
`docs/go-live.md`: those are internal project notes and are not included here.
