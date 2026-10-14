-- V33: saved searches table — saved browse filter sets (backlog #164).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per saved search. The canonical browse filter set (#96) is stored
-- as discrete columns — keyword, category_id, min/max price in cents, sort —
-- never a serialized query string, so the per-row match count can be computed
-- by re-running ItemService's own filter validation and browse projection
-- over exactly the values the browse page would use.
--
-- user_id is a real foreign key (accounts are disabled, never deleted, so
-- no cascade). category_id is deliberately a plain id with NO foreign key:
-- it is a filter value, not a reference — a category an ADMIN later deletes
-- simply matches nothing, exactly as the same categoryId in a browse URL
-- would; an FK would make category deletion fail on rows that are only
-- saved filters. All filter columns are nullable: null means "no filter"
-- for that dimension, mirroring the browse parameters.
-- Reads are keyed (user_id, created_at): the only read path is the caller's
-- own saved-search list, newest save first.

CREATE TABLE saved_search (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  keyword VARCHAR(255),
  category_id BIGINT,
  min_price_cents BIGINT,
  max_price_cents BIGINT,
  sort VARCHAR(16) NOT NULL DEFAULT 'newest',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_saved_search_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE INDEX idx_saved_search_user_created ON saved_search(user_id, created_at);
