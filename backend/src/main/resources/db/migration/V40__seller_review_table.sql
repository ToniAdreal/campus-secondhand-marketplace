-- V40: seller reviews table — one post-completion review per order
-- (backlog #175). Runs on both MySQL 8 (prod/CI) and H2 in
-- MySQL-compatibility mode (local dev).
--
-- One row per reviewed trade: order_id carries a plain UNIQUE — a
-- review is append-only and is never edited or deleted in v1, so
-- there is no status dimension and no guard column is needed; the
-- unique key IS the "one review per trade" rule, enforced at the
-- database level under races. reviewer is the order's buyer (buyer_id,
-- FK mirroring orders V4); seller_id is denormalised from the listing
-- at write time (a listing's seller never changes, so the copy cannot
-- drift) so a seller's review list and rating aggregate are plain
-- indexed reads.
--
-- rating is 1..5 (validated at the API boundary; the column stays a
-- plain INT so the aggregate AVG is a database computation). body is
-- optional free text, length-capped at the boundary to match the
-- VARCHAR(500) column. No average is stored anywhere: a stored
-- average that drifts from the rows is exactly the bug the aggregate
-- query avoids — the profile read computes AVG + COUNT per request.

CREATE TABLE seller_review (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  buyer_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  rating INT NOT NULL,
  body VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_review_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT fk_review_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
  CONSTRAINT uq_review_order UNIQUE (order_id)
);

CREATE INDEX idx_review_seller_created ON seller_review(seller_id, created_at);
CREATE INDEX idx_review_buyer ON seller_review(buyer_id);
