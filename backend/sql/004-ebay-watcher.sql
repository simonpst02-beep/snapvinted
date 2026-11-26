BEGIN;

ALTER TABLE listing_candidates
ADD COLUMN IF NOT EXISTS alert_id text
REFERENCES alerts(id)
ON DELETE SET NULL;

ALTER TABLE listing_candidates
ADD COLUMN IF NOT EXISTS last_seen_at timestamptz NOT NULL DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS listing_candidates_alert_ebay_uidx
ON listing_candidates(alert_id, ebay_item_id)
WHERE alert_id IS NOT NULL;


CREATE TABLE IF NOT EXISTS ebay_resale_cache (
    search_key text PRIMARY KEY,
    resale_estimate numeric(12,2) NOT NULL,
    resale_min numeric(12,2),
    resale_max numeric(12,2),
    sample_count integer NOT NULL DEFAULT 0,
    is_used boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now()
);

COMMIT;
