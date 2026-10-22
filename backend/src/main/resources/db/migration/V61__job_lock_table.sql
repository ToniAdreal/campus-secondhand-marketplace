-- V61: single-instance scheduler leases (backlog #215).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- Before #215 the six mutating @Scheduled jobs (pending-order expiry,
-- offer expiry, saved-search matching, data retention, listing expiry,
-- orphan-upload sweep) had no cross-instance coordination at all: the
-- compose stack runs one backend today, but a second instance -- a
-- rolling-deploy overlap is enough -- double-ran every job, and each
-- job's safety was an accident of its own queries.
--
-- One row per job, created on first acquisition and deleted by its
-- holder on release:
--   name         the job's stable lock name; the PRIMARY KEY is the
--                mutual-exclusion primitive -- two racing inserts of
--                the same name resolve to exactly one winner.
--   locked_by    the holding instance's id (host + per-JVM random
--                suffix); release deletes by (name, locked_by), so an
--                instance can never release another holder's lease.
--   locked_until the lease deadline. A row left behind by a crashed
--                holder is stealable only at or past this instant, via
--                one conditional UPDATE that resolves competing
--                stealers to a single winner -- no permanently wedged
--                job, and no library dependency for three columns.
CREATE TABLE job_lock (
  name VARCHAR(64) PRIMARY KEY,
  locked_by VARCHAR(128) NOT NULL,
  locked_until TIMESTAMP NOT NULL
);
