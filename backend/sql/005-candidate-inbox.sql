BEGIN;

ALTER TABLE listing_candidates
ADD COLUMN IF NOT EXISTS state text NOT NULL DEFAULT 'new'
CHECK (state IN (
  'new',
  'seen',
  'ignored',
  'tracked',
  'bought',
  'expired'
));

ALTER TABLE listing_candidates
ADD COLUMN IF NOT EXISTS viewed_at timestamptz;

ALTER TABLE listing_candidates
ADD COLUMN IF NOT EXISTS actioned_at timestamptz;

ALTER TABLE listing_candidates
ADD COLUMN IF NOT EXISTS missing_runs integer NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS listing_candidates_state_idx
ON listing_candidates(state, detected_at DESC);

COMMIT;
