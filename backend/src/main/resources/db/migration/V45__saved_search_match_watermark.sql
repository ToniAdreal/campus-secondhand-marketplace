-- V45: saved-search background matching (backlog #182).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode
-- (local dev) — plain ADD COLUMN statements are portable (see V34).
--
-- saved_search.last_notified_item_id is the matcher's watermark: the
-- highest listing id the background matcher has SEEN for this search
-- (the implementation note in backlog #182 picks the highest-seen-id
-- watermark over a timestamp one — listing ids are monotonic, so "a
-- listing created after the watermark" is exactly "id > watermark",
-- with no clock-skew or same-instant tie cases). NULL means the row
-- predates the matcher: its first run only establishes the baseline
-- and never notifies for listings that already existed. Searches
-- created through the API are baselined at creation time by
-- SavedSearchService, so a listing created after the search is never
-- missed by that first-run rule.
--
-- notification.saved_search_id / matched_listing_count carry the
-- SAVED_SEARCH_MATCH payload: which saved search matched and how many
-- NEW listings matched in that run — one batched row per saved search
-- per run, never one row per listing. Both are plain ids/counts with
-- no foreign keys, mirroring the rest of the notification table (V30).

ALTER TABLE saved_search ADD COLUMN last_notified_item_id BIGINT;

ALTER TABLE notification ADD COLUMN saved_search_id BIGINT;

ALTER TABLE notification ADD COLUMN matched_listing_count INT;
