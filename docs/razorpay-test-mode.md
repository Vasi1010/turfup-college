# Razorpay in test mode

How to run TurfUp with `turfup.payments.mode = razorpay` against Razorpay's test mode, where no real money
moves. The college version never needs this: it runs in `simulated` mode.

## How it works

- **Route:** each venue's advances (on any of its courts) are transferred to its owner's Razorpay *linked account* when the payment is
  captured. TurfUp never holds the money. An admin enters the account id (`acc_...`) on the Approvals screen.
  TurfUp fetches its status from Razorpay, and refreshes it every hour.
- **Approval gate:** in razorpay mode a venue can be approved only while its linked account is `activated`. If
  the account later stops being active (suspended, for example), online advances pause. Waived regulars,
  zero-advance courts, walk-ins and phone bookings keep working.
- **Paying:** "Pay advance" creates a Razorpay order and opens Razorpay Checkout in the browser. The booking
  confirms only when Razorpay's signed `payment.captured` webhook arrives. The browser never confirms it.
  A payment captured after the grace window, or for a slot taken in the meantime, is refunded automatically.
- **Held transfers:** each advance's transfer is created with `on_hold: true` and `on_hold_until` set to the
  end of the booked slot. Razorpay settles it to the owner the next business day after that. Until then the
  money sits unsettled in the owner's linked account, so any refund made before the slot ends can always
  reverse it: player cancellations, owner cancellations (rain, maintenance) and late payments. A slot ending
  less than 5 minutes after the player pays is not held. The hold time is stored on the advance payment
  (`payments.transfer_hold_until`).
- **Refunds** (late payments, player and owner cancellations) go back through Razorpay and reverse the Route
  transfer, so the money comes out of the owner's account. They are recorded PENDING and marked done by the
  `refund.processed` or `refund.failed` webhook.
- **Refunds needing attention:** a refund made after the slot ends (for example, an owner cancelling a booking
  that has already been played, which is possible until it becomes COMPLETED) reverses a transfer that may have
  settled already, so the owner's account might not cover it. If Razorpay rejects a refund, it stays PENDING,
  its reason is recorded (`payments.gateway_error`) and logged at ERROR, and reconciliation retries it every 5
  minutes. Rejected refunds and FAILED ones are listed at the top of the admin's Approvals page. Refund the
  player from the Razorpay Dashboard, or have the owner top up the account and let the retry go through.
  After a manual refund, press **Refunded manually**. The refund then counts as done, and TurfUp stops
  retrying it, so the player is never refunded twice.
- **Reconciliation** (every 5 minutes) handles missed webhooks: it confirms or refunds captured payments,
  sends refunds that never reached Razorpay, and re-checks refunds that are still pending. An order nobody paid
  for within an hour is marked FAILED.

## Setup

1. In the Razorpay Dashboard, switch to **Test Mode**.
2. **API keys:** Settings → API Keys → generate a test key. The key id starts with `rzp_test_`.
3. **Route:** make sure Route is enabled on the account (Dashboard → Route). Create a linked account for the
   test owner and note its id (`acc_...`).
4. **Webhook:** Settings → Webhooks → add `https://<your-host>/api/payments/razorpay/webhook`, choose a
   secret, and subscribe to `payment.captured`, `refund.created`, `refund.processed` and `refund.failed`.
   Razorpay needs a public HTTPS URL. To test locally, tunnel to the backend, for example
   `cloudflared tunnel --url http://localhost:8080`, and use the URL it prints.
5. Start the backend in razorpay mode. It refuses to start if any of the three values below is missing:

   ```sh
   TURFUP_PAYMENTS_MODE=razorpay \
   RAZORPAY_KEY_ID=rzp_test_... \
   RAZORPAY_KEY_SECRET=... \
   RAZORPAY_WEBHOOK_SECRET=... \
   JWT_SECRET=<32+ chars> SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
   ```

   With `demo`, the seeded venue stays unapproved in razorpay mode, because it has no linked account yet.
   In production, set the same variables in `.env.prod`.
6. Log in as the admin (9999999999 / admin-demo-123). On Approvals, paste the linked account id into the
   venue's card, press **Link**, then **Approve** once the status shows Active.
7. As a player, hold a slot and press **Pay**. In Checkout, use one of Razorpay's
   [test cards or UPI IDs](https://razorpay.com/docs/payments/payments/test-card-details/). For example, UPI
   `success@razorpay` succeeds. The page shows "Confirming your booking…" until the webhook arrives.

## Checking it

- **Backend log:** each webhook logs `Webhook payment.captured ... CONFIRMED` (or `REFUNDED`). A webhook with
  a bad signature gets 401 and changes nothing.
- **Missed webhooks:** stop the tunnel, pay, and wait about 5 minutes. Reconciliation logs
  `Reconciled missed capture ...` and the booking confirms.
- **Refunds:** cancel a confirmed booking 24+ hours ahead (as the player), or cancel it as the owner. Then
  check Razorpay Dashboard → Refunds and the linked account's transfer reversal.
- **Held transfers:** after paying, open the payment's transfer in the Razorpay Dashboard. It should show
  "on hold" until the slot ends. Cancel (as the player, or as the owner) before then and check that the
  reversal goes through while the transfer is still held. Razorpay's docs don't spell this
  case out, so confirm it once in test mode before going live.
- **Failed and rejected refunds** are logged at ERROR (`... FAILED; settle it manually` or `Razorpay rejected
  refund ...`) and listed under "Refunds needing attention" on the admin's Approvals page.

Automated coverage: `RazorpayPaymentTest` runs the whole flow against a fake gateway on a real Postgres
(Testcontainers). `RefundSafetyTest` covers held transfers and refunds that need attention.
`HttpRazorpayGatewayTest` checks the HTTP calls against a mock server.
