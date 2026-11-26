BEGIN;

-- Dates de modification manquantes
ALTER TABLE profiles
ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE alerts
ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();


-- Mise à jour automatique de updated_at
DROP TRIGGER IF EXISTS profiles_updated_at ON profiles;

CREATE TRIGGER profiles_updated_at
BEFORE UPDATE ON profiles
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();


DROP TRIGGER IF EXISTS alerts_updated_at ON alerts;

CREATE TRIGGER alerts_updated_at
BEFORE UPDATE ON alerts
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();


-- Historique des suppressions pour la synchro incrémentale
CREATE TABLE IF NOT EXISTS sync_tombstones (
    entity_type text NOT NULL
        CHECK (entity_type IN ('profile','tracked','alert','stock')),

    entity_id text NOT NULL,

    deleted_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (entity_type, entity_id)
);

CREATE INDEX IF NOT EXISTS sync_tombstones_date_idx
ON sync_tombstones(deleted_at);


-- Enregistre une suppression
CREATE OR REPLACE FUNCTION sync_mark_deleted()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    kind text;
BEGIN
    kind := CASE TG_TABLE_NAME
        WHEN 'profiles' THEN 'profile'
        WHEN 'tracked_items' THEN 'tracked'
        WHEN 'alerts' THEN 'alert'
        WHEN 'stock_parts' THEN 'stock'
    END;

    INSERT INTO sync_tombstones(entity_type, entity_id, deleted_at)
    VALUES(kind, OLD.id::text, now())
    ON CONFLICT(entity_type, entity_id)
    DO UPDATE SET deleted_at = EXCLUDED.deleted_at;

    RETURN OLD;
END;
$$;


-- Si l'objet réapparaît, sa suppression précédente n'est plus valable
CREATE OR REPLACE FUNCTION sync_clear_tombstone()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    kind text;
BEGIN
    kind := CASE TG_TABLE_NAME
        WHEN 'profiles' THEN 'profile'
        WHEN 'tracked_items' THEN 'tracked'
        WHEN 'alerts' THEN 'alert'
        WHEN 'stock_parts' THEN 'stock'
    END;

    DELETE FROM sync_tombstones
    WHERE entity_type = kind
      AND entity_id = NEW.id::text;

    RETURN NEW;
END;
$$;


-- Profils
DROP TRIGGER IF EXISTS profiles_sync_delete ON profiles;
CREATE TRIGGER profiles_sync_delete
AFTER DELETE ON profiles
FOR EACH ROW
EXECUTE FUNCTION sync_mark_deleted();

DROP TRIGGER IF EXISTS profiles_sync_restore ON profiles;
CREATE TRIGGER profiles_sync_restore
AFTER INSERT OR UPDATE ON profiles
FOR EACH ROW
EXECUTE FUNCTION sync_clear_tombstone();


-- Suivi
DROP TRIGGER IF EXISTS tracked_sync_delete ON tracked_items;
CREATE TRIGGER tracked_sync_delete
AFTER DELETE ON tracked_items
FOR EACH ROW
EXECUTE FUNCTION sync_mark_deleted();

DROP TRIGGER IF EXISTS tracked_sync_restore ON tracked_items;
CREATE TRIGGER tracked_sync_restore
AFTER INSERT OR UPDATE ON tracked_items
FOR EACH ROW
EXECUTE FUNCTION sync_clear_tombstone();


-- Alertes
DROP TRIGGER IF EXISTS alerts_sync_delete ON alerts;
CREATE TRIGGER alerts_sync_delete
AFTER DELETE ON alerts
FOR EACH ROW
EXECUTE FUNCTION sync_mark_deleted();

DROP TRIGGER IF EXISTS alerts_sync_restore ON alerts;
CREATE TRIGGER alerts_sync_restore
AFTER INSERT OR UPDATE ON alerts
FOR EACH ROW
EXECUTE FUNCTION sync_clear_tombstone();


-- Stock
DROP TRIGGER IF EXISTS stock_sync_delete ON stock_parts;
CREATE TRIGGER stock_sync_delete
AFTER DELETE ON stock_parts
FOR EACH ROW
EXECUTE FUNCTION sync_mark_deleted();

DROP TRIGGER IF EXISTS stock_sync_restore ON stock_parts;
CREATE TRIGGER stock_sync_restore
AFTER INSERT OR UPDATE ON stock_parts
FOR EACH ROW
EXECUTE FUNCTION sync_clear_tombstone();

COMMIT;
