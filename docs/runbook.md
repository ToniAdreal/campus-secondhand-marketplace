# Operator runbook — Prometheus alerts

One short section per alert in [docs/alert-rules.yml](alert-rules.yml):
what it means, the first three checks, and what it is **not**.

Honest scope: this repo provides the alert rules and this runbook. It
does **not** deploy or bundle Prometheus or Alertmanager — the rules
only fire in an operator's own Prometheus that scrapes
`/actuator/prometheus` (scrape target in
[docs/smoke-test.md](smoke-test.md)) and loads the rules file. The
payment alerts concern the **mock** PSP: no real money moves in this
project, so a payment alert is a code/integration signal, never a
financial incident.

## AuthLoginFailureRateHigh

More than half of login attempts over the last 10 minutes were rejected
for an unknown identifier or a wrong password (`auth.login.failure`
growing faster than `auth.login.success`).

First three checks:

1. Look at the login-history / audit surface and the application logs
   (correlate by `X-Request-ID`) — are the failures spread across many
   accounts (an attack or a broken client) or concentrated on a few?
2. Check whether a release just changed the login path, password
   hashing, or case normalisation (logins are case-insensitive by
   design; a regression there fails every mixed-case user at once).
3. Confirm the per-IP auth rate limit and the per-account lockout are
   still in front of the endpoint (are 429s / lockouts also rising?).

What it is NOT: it is not proof of a breach — failed logins are the
identical-401 pair by design and reveal nothing about which accounts
exist. Locked-account attempts do not feed this counter at all.

## AuthLockoutSurge

`auth.lockout.triggered` fired at least 3 times in 15 minutes. The
counter fires once per lock transition (not per locked attempt), so
several distinct accounts crossed the failed-login threshold.

First three checks:

1. Identify the locked accounts from the audit log and check whether
   their owners also show successful logins from familiar devices —
   targeted accounts look different from a spray.
2. Check whether one client/IP range dominates the failures (the
   rate-limit filter's 429s should be rising in the same window).
3. Verify the lockout threshold/duration properties were not changed
   by a recent deploy (a threshold of 1 would manufacture this alert).

What it is NOT: a single user mistyping a password cannot fire this —
one account locks once per lock window. It is also not a sign the
lockout is broken; the lockout working as designed during an attack
looks exactly like this.

## AuthRefreshTheftDetected

`auth.refresh.theft_detected` incremented even once: a refresh token
that had already been replaced was replayed after the rotation grace
window, and theft detection revoked that user's whole token family.

First three checks:

1. Identify the affected user from the WARN log line written at the
   same choke point that increments the counter, and revoke/confirm
   revocation of all their sessions.
2. Check the user's login history for a new device/IP around the
   replay time (the new-device alert and history page exist for this).
3. Rule out the benign shape: two tabs refreshing concurrently are
   absorbed by the grace window, so a post-window replay is not
   explained by ordinary multi-tab use.

What it is NOT: it does not by itself prove the account was taken
over — a token copied into a second browser profile by the owner can
look identical. Treat it as a session-revocation event to review, not
a confirmed compromise.

## PaymentDeclineRateHigh

Mock-PSP capture declines (`payments.declined`) are a large share of
payment attempts relative to fresh payments (`orders.paid`). A decline
is an attempt outcome, not an order transition: the order stays
PENDING and the buyer may retry.

First three checks:

1. Check whether declines cluster on one listing, buyer, or amount —
   the mock PSP's decline behaviour is deterministic, so a cluster
   points at the triggering input, not randomness.
2. Compare with the pay-latency timer (`orders.pay.duration`,
   `outcome=failure` samples rise with declines) and look for a recent
   change to the payment seam (`PaymentService`).
3. Confirm `orders.paid` is still moving — declines plus zero paid
   means capture is fully broken; declines with paid still flowing is
   partial.

What it is NOT: no real money is involved (mock PSP), and a decline is
not a lost order — the listing stays RESERVED and the order PENDING
until it is paid, cancelled, or expired.

## WebhookMismatchRateHigh

Mock-PSP webhook deliveries are failing to reconcile
(`payments.webhook.mismatched` growing relative to
`payments.webhook.matched`): a delivery named an unknown reference, an
order in the wrong state, or an unknown event type.

First three checks:

1. Open the ADMIN webhook-event viewer and read the mismatching
   deliveries — unknown reference vs wrong state are different bugs
   (sender-side id mix-up vs a state-machine gap).
2. Check `payments.webhook.replayed` in the same window: replays are
   answered 200 and counted separately, so a replay storm alone must
   not explain a mismatch rise.
3. Verify the webhook signature configuration was not rotated without
   updating the sender (signature failures are 401 and counted
   nowhere here — if mismatches are flat but deliveries stopped, look
   there instead).

What it is NOT: a mismatch is not a lost payment — the synchronous pay
path is what transitions orders; webhooks are the reconciliation
signal. It is also not caused by duplicate deliveries (see check 2).

## Http5xxRateHigh

More than 5% of HTTP responses over the last 10 minutes are 5xx, per
Spring Boot's `http.server.requests` series on the same Prometheus
export.

First three checks:

1. Break the series down by `uri` and `exception` tags in Prometheus
   to find which endpoint is failing, then read those requests' logs
   via their `X-Request-ID`.
2. Check readiness (`/actuator/health/readiness`) and the database —
   a DB outage surfaces here as 5xx on every JPA-backed route while
   liveness stays UP.
3. Check whether a deploy landed inside the window and whether the
   failing URI matches the changed surface.

What it is NOT: 4xx client errors (validation 400, auth 401/403,
conflict 409) do not count — this alert is server faults only. It also
says nothing about latency; the domain timers
(`auth.login.duration`, `orders.pay.duration`, …) are the latency
surface.

## Backup and restore drill (backlog #225)

The compose stack's MySQL data (`mysql-data` volume) and listing photos
(`uploads-data` volume) have exactly one way back from a lost volume:
`scripts/backup.sh` + `scripts/restore-drill.sh`. There is no managed
backup service, no off-site copy, and no point-in-time recovery — the
procedure below is a manual local drill for a portfolio stack, and a
drill that passes once proves only that day's restore.

### Back up

Run from the repo root, with the stack already up:

```bash
docker compose up -d
bash scripts/backup.sh [BACKUP_DIR]   # default: backups/<timestamp>
```

It writes `marketdb.sql` (mysqldump, `--single-transaction`, so a
running stack dumps a consistent InnoDB snapshot), `uploads.tar.gz`
(the uploads volume), and `manifest.txt` (timestamp, source project,
row counts for `app_user` / `item` / `orders`, sha256 checksums). It
never asks for a password: the dump runs inside the mysql container,
where the compose file already sets the database credentials.

A backup directory contains a database dump that includes password
hashes. Treat it like a credential: never commit it (`backups/` is
gitignored), never upload it anywhere.

### Restore drill

```bash
bash scripts/restore-drill.sh <BACKUP_DIR> [SCRATCH_PROJECT]
```

It restores into a scratch compose project (default
`marketplace-restore-drill`) — never over the running stack — with the
host ports remapped (mysql 13306, backend 18080, frontend 180), boots
it, and checks it: actuator `/health` UP, the nginx `/api` proxy
answering the 401 envelope, and row counts for `app_user` / `item` /
`orders` matching the source (live source counts when the source stack
is up, otherwise the counts in `manifest.txt` from backup time). Any
mismatch exits non-zero. The scratch stack is torn down (`down -v`) on
exit unless `KEEP_SCRATCH=1`. The script refuses to run when the
scratch project equals the source project, and every compose invocation
in it uses the scratch project — no drill step can touch the running
stack's containers or volumes.

A drill against an empty stack (all counts 0) passes with a warning:
0 == 0 proves the plumbing, not a restore of real data. Seed the
source stack first (register a user, create a listing — the CI drill
job does exactly this) for a drill that means something.

### Cadence and scope

Run the drill before any migration-heavy release, and after any change
to the backup/restore scripts themselves. The CI `backup-restore-drill`
job runs the same two scripts on every push: seed, back up, restore
into the scratch project, compare, tear both stacks down. What a
passing drill does **not** prove: that any older backup is restorable
(only the backup just taken was restored), that a backup taken on a
different machine restores here, or that data survives anything other
than the volume-loss shape the drill rehearses.
