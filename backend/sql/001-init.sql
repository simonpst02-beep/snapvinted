BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS profiles (
    id text PRIMARY KEY,
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO profiles (id, name)
VALUES ('moi', 'Moi')
ON CONFLICT (id) DO NOTHING;


CREATE TABLE IF NOT EXISTS tracked_items (
    id text PRIMARY KEY,

    profile_id text NOT NULL DEFAULT 'moi'
        REFERENCES profiles(id)
        ON DELETE RESTRICT,

    ebay_item_id text,

    title text NOT NULL,
    image_url text,
    icon text,
    source_url text,

    purchase_estimated numeric(12,2),
    resale_estimated numeric(12,2),
    quantity integer NOT NULL DEFAULT 1
        CHECK (quantity > 0),

    resale_min numeric(12,2),
    resale_max numeric(12,2),
    resale_is_used boolean,

    margin_estimated numeric(12,2),

    status text NOT NULL DEFAULT 'repere'
        CHECK (
            status IN (
                'repere',
                'achete',
                'repar',
                'vente',
                'vendu'
            )
        ),

    purchase_actual numeric(12,2),
    parts_cost numeric(12,2),
    sale_actual numeric(12,2),

    parts_ordered text,
    delivery_date date,
    notes text NOT NULL DEFAULT '',

    status_dates jsonb NOT NULL DEFAULT '{}'::jsonb,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS tracked_items_profile_idx
    ON tracked_items(profile_id);

CREATE INDEX IF NOT EXISTS tracked_items_status_idx
    ON tracked_items(status);

CREATE INDEX IF NOT EXISTS tracked_items_ebay_item_idx
    ON tracked_items(ebay_item_id);


CREATE TABLE IF NOT EXISTS alerts (
    id text PRIMARY KEY,

    query text NOT NULL DEFAULT '',
    category text NOT NULL DEFAULT '',
    category_name text NOT NULL DEFAULT '',

    minimum_margin numeric(12,2) NOT NULL DEFAULT 40,
    maximum_purchase numeric(12,2),

    france_only boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,

    created_at timestamptz NOT NULL DEFAULT now(),
    last_checked_at timestamptz,
    last_match_count integer
);


CREATE TABLE IF NOT EXISTS stock_parts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    name text NOT NULL,
    quantity integer NOT NULL DEFAULT 1
        CHECK (quantity >= 0),

    unit_price numeric(12,2) NOT NULL DEFAULT 0
        CHECK (unit_price >= 0),

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);


CREATE TABLE IF NOT EXISTS watched_sellers (
    username text PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);


CREATE TABLE IF NOT EXISTS recent_searches (
    id bigserial PRIMARY KEY,
    query text NOT NULL,
    searched_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS recent_searches_date_idx
    ON recent_searches(searched_at DESC);


CREATE TABLE IF NOT EXISTS price_history (
    id bigserial PRIMARY KEY,

    search_key text NOT NULL,
    price numeric(12,2) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS price_history_key_date_idx
    ON price_history(search_key, recorded_at DESC);


CREATE TABLE IF NOT EXISTS settings (
    key text PRIMARY KEY,
    value jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);


CREATE TABLE IF NOT EXISTS search_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    query text,
    category text,

    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,

    result_count integer NOT NULL DEFAULT 0,
    ebay_requests integer NOT NULL DEFAULT 0,

    status text NOT NULL DEFAULT 'running'
        CHECK (status IN ('running','completed','failed')),

    error_message text
);


CREATE TABLE IF NOT EXISTS listing_candidates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    search_run_id uuid
        REFERENCES search_runs(id)
        ON DELETE CASCADE,

    ebay_item_id text NOT NULL,
    title text NOT NULL,
    url text,
    image_url text,

    seller text,

    purchase_price numeric(12,2),
    shipping_price numeric(12,2),
    resale_estimate numeric(12,2),
    repair_estimate numeric(12,2),
    ebay_fee_estimate numeric(12,2),

    net_margin numeric(12,2),
    margin_percent numeric(10,2),
    maximum_purchase numeric(12,2),

    buying_type text,
    french_listing boolean,

    raw_data jsonb,

    detected_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS listing_candidates_ebay_idx
    ON listing_candidates(ebay_item_id);

CREATE INDEX IF NOT EXISTS listing_candidates_margin_idx
    ON listing_candidates(net_margin DESC);


CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;


DROP TRIGGER IF EXISTS tracked_items_updated_at
ON tracked_items;

CREATE TRIGGER tracked_items_updated_at
BEFORE UPDATE ON tracked_items
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();


DROP TRIGGER IF EXISTS stock_parts_updated_at
ON stock_parts;

CREATE TRIGGER stock_parts_updated_at
BEFORE UPDATE ON stock_parts
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();


DROP TRIGGER IF EXISTS settings_updated_at
ON settings;

CREATE TRIGGER settings_updated_at
BEFORE UPDATE ON settings
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

COMMIT;
