# Core module (ClaimPilot)

An insurance policy and claims engine built around the parts of insurance software that are easy to get wrong:
rating that can be explained line by line, policies whose history can be queried as of any date, claims that pay
out exactly once, and renewal batches that can be rerun safely.

Java 21, Spring Boot, PostgreSQL (JDBC, Flyway), Kafka, S3-compatible object storage, Spring Batch. All data in
the demo is synthetic; every e-mail address ends in `example.test`. HTTP surface: `/v1/**` (OpenAPI in Swagger UI
when the `local` profile is active).

## What it does

- **Products and rating.** Managers publish versioned products (health and motor) as JSON: coverages, riders, a
  rating table and claim rules. A quote stores the product version it was rated against plus a step-by-step premium
  breakdown (`base`, `age`, `members`, `region`, riders, `tax`), so a quote can always be re-explained later.
- **Bind and pay.** Binding a quote charges a (fake) payment gateway and issues the policy in one request, guarded by
  an `Idempotency-Key`. A retried bind returns the stored response with `Idempotent-Replayed: true` and never
  charges twice. Receipts and a policy schedule are rendered as PDFs.
- **Bitemporal policies.** Every endorsement appends a new version with a valid-time range and a recorded-at time.
  `GET /v1/policies/{id}?validAt=…&asOf=…` answers both "what cover applies on this date" and "what did we believe
  on that date". Endorsements charge or refund the pro-rata premium difference and use `If-Match` for optimistic
  concurrency (412 on a stale version).
- **Claims.** FNOL returns presigned S3 upload URLs for the documents the claim needs. When the last document is
  uploaded the claim is triaged by pure rules (waiting period, report delay, straight-through limit, coverage limit
  and deductible, prior claims, document count, fraud score). Claims that pass everything settle straight through;
  the rest go to an assessor, and amounts over an assessor's authority need a manager's sign-off as well. Payouts are
  keyed by claim, so a settlement can't be paid twice.
- **Fraud signals.** Party details on a claim (phone, bank account, device, garage, hospital) are stored only as
  HMACs. A union-find graph links holders who share a personal identifier; a shared garage or hospital is only a
  weak, weighted signal. The score and its reasons feed triage.
- **Renewals.** A partitioned Spring Batch job per business date lapses unpaid policies past grace, renews autopay
  policies at the current product version's rate, marks the rest payment-due, and sends reminders. Running the same
  date twice returns the first run (`replayed: true`).
- **Events and email.** State changes go into a transactional outbox, are relayed to Kafka, and a consumer sends
  emails (issued, changed, claim paid, renewed, reminders) with a processed-event table to skip duplicates.
- **Access.** Bearer JWT: the platform's token, issued at portal login (see [ARCHITECTURE](../ARCHITECTURE.md#identity-and-authorisation)).
  Customers see only their own policies and claims (404 otherwise). Agents, assessors and managers are assigned
  through `/v1/staff`, or implied by a portal role when no assignment exists (ADMIN is a manager, HR an assessor,
  AGENT an agent); an explicit assignment always wins. Quotes are rate limited per subject.

## Layout

Code lives in `backend/src/main/java/com/insurai/platform/core`. Each module (`product`, `quote`, `policy`, `payment`, `claim`, `document`, `fraud`, `renewal`, `notification`,
`outbox`, `idempotency`, `staff`) is split into `domain`, `application` and `adapter` packages. ArchUnit tests keep
the domain free of Spring and JDBC, and stop modules reaching into each other's adapters.

## Trying it

`scripts/demo.sh` walks the whole flow against the running stack and asserts each step: quote, bind with an
idempotent replay, bitemporal endorsement, a straight-through claim with a presigned upload, the e-mails in
Mailpit, a renewal run and its replay, and the metrics. `scripts/bench.sh` repeats the timing runs below.

## Numbers

Measured on the **standalone ClaimPilot application before the merge** (`bench.sh`, one shared development box, one app instance, fake payment gateway). They have not been re-measured on the combined application, which now hosts two more modules in the same JVM; `scripts/bench.sh` is the ported script to repeat them:

| What | Result |
| --- | --- |
| Coverage-as-of reads (`GET /v1/policies/{id}?validAt=`), 300 sequential requests | p50 3.03 ms, p95 4.20 ms, p99 6.10 ms (client side, includes curl) |
| Bind 200 policies sequentially (token + quote + bind each) | 11.1 s |
| One renewal run over 200 due policies | 200 renewed in 294 ms |

These are small runs on a machine shared with other work. Treat them as a sanity check, not capacity figures. No
straight-through rate was measured: with synthetic claims it would only reflect the chosen test mix.

## Limitations

Known and not fixed (carried over from the original project):

- The quote rate limiter is in memory, so each instance has its own budget.
- The endorsement refund is issued after the endorsement commits. If the refund call fails, the endorsement stands
  and the refund has to be retried by hand; there is no retry job.
- The fake payment gateway keeps its idempotency memo in memory; a real gateway would hold that state.
- The fraud graph is per instance and rebuilt on a timer from the database, not updated from events, so a brand-new
  shared party can be missed until the next rebuild.
- Replaying an FNOL returns the original upload URLs, which may have expired; clients should request fresh ones from
  `/documents/{id}/upload-url`.
- Replaying an approval that escalated to a manager returns 200 rather than the original 202.
- `POST /v1/renewals/run` runs the job on the request thread. Fine for a demo; a real deployment would launch it
  asynchronously and poll.
- Renewal re-rates with the inputs captured at bind or last endorsement; ages aren't advanced a year.
- Emails are sent at least once. The duplicate check and the send share a transaction, but a crash after the SMTP
  send and before commit sends the mail again.
- Event payloads carry ids rather than contact details, and the notification consumer looks the contact up when it
  sends. An email therefore goes to the holder's current address, not the one at the time of the event.

*Platform-level limitations, including the open `spring-webmvc` CVEs, are in the [README](../../README.md#known-limitations).*
