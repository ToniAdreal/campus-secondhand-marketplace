-- V3: listing category taxonomy + composite list-view index.
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
-- category_id on item is nullable: listings created before this migration are
-- simply uncategorized rather than force-fitted into a taxonomy.
-- The composite index (category_id, status, created_at) is the access path for
-- the category-filtered list view: equality on category_id + status, newest-first
-- ordering on created_at.

CREATE TABLE category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(60) NOT NULL,
  slug VARCHAR(60) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_category_name UNIQUE (name),
  CONSTRAINT uq_category_slug UNIQUE (slug)
);

INSERT INTO category (name, slug) VALUES
  ('Electronics', 'electronics'),
  ('Books & Study', 'books-study'),
  ('Furniture', 'furniture'),
  ('Clothing', 'clothing'),
  ('Sports & Outdoors', 'sports-outdoors'),
  ('Tickets & Services', 'tickets-services');

ALTER TABLE item ADD COLUMN category_id BIGINT;

ALTER TABLE item ADD CONSTRAINT fk_item_category
  FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL;

CREATE INDEX idx_item_category_status_created ON item(category_id, status, created_at);
