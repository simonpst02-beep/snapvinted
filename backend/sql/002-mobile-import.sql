BEGIN;

CREATE TABLE IF NOT EXISTS mobile_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source text NOT NULL DEFAULT 'android',
    app_version integer,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS mobile_snapshots_date_idx
ON mobile_snapshots(created_at DESC);

COMMIT;
