const num = v =>
  v === null || v === undefined || v === ""
    ? null
    : Number(v);

const tracked = r => ({
  id: r.id,
  profileId: r.profile_id,
  itemId: r.ebay_item_id || "",
  t: r.title,
  img: r.image_url || "",
  ico: r.icon || "",
  url: r.source_url || "",
  achatPrev: num(r.purchase_estimated) ?? 0,
  reventePrev: num(r.resale_estimated) ?? 0,
  qty: Number(r.quantity || 1),
  min: num(r.resale_min),
  max: num(r.resale_max),
  occ: r.resale_is_used,
  margePrev: num(r.margin_estimated) ?? 0,
  statut: r.status,
  achat: r.purchase_actual == null ? "" : num(r.purchase_actual),
  pieces: r.parts_cost == null ? "" : num(r.parts_cost),
  vente: r.sale_actual == null ? "" : num(r.sale_actual),
  commande: r.parts_ordered || "",
  livraison: r.delivery_date
    ? String(r.delivery_date).slice(0, 10)
    : "",
  notes: r.notes || "",
  date: new Date(r.created_at).getTime(),
  dates: r.status_dates || {},
  updatedAt: new Date(r.updated_at).toISOString()
});

export default async function registerSyncRoutes(app, pool) {

  app.get("/api/sync", async (req, reply) => {

    const raw = String(req.query?.since || "");
    const since = raw ? new Date(raw) : new Date(0);

    if (Number.isNaN(since.getTime())) {
      return reply.code(400).send({
        error: "Paramètre since invalide"
      });
    }

    const c = await pool.connect();

    try {
      await c.query(
        "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"
      );

      const sr = await c.query(
        "SELECT now() AS server_time"
      );

      const serverTime = sr.rows[0].server_time;

      const profiles = await c.query(`
        SELECT *
        FROM profiles
        WHERE updated_at > $1
          AND updated_at <= $2
        ORDER BY updated_at
      `, [since, serverTime]);

      const suivi = await c.query(`
        SELECT *
        FROM tracked_items
        WHERE updated_at > $1
          AND updated_at <= $2
        ORDER BY updated_at
      `, [since, serverTime]);

      const alerts = await c.query(`
        SELECT *
        FROM alerts
        WHERE updated_at > $1
          AND updated_at <= $2
        ORDER BY updated_at
      `, [since, serverTime]);

      const stock = await c.query(`
        SELECT *
        FROM stock_parts
        WHERE updated_at > $1
          AND updated_at <= $2
        ORDER BY updated_at
      `, [since, serverTime]);

      const deleted = await c.query(`
        SELECT entity_type, entity_id, deleted_at
        FROM sync_tombstones
        WHERE deleted_at > $1
          AND deleted_at <= $2
        ORDER BY deleted_at
      `, [since, serverTime]);

      await c.query("COMMIT");

      return {
        serverTime: new Date(serverTime).toISOString(),

        profiles: profiles.rows.map(r => ({
          id: r.id,
          nom: r.name,
          updatedAt: new Date(r.updated_at).toISOString()
        })),

        tracked: suivi.rows.map(tracked),

        alerts: alerts.rows.map(r => ({
          id: r.id,
          q: r.query,
          cat: r.category,
          catName: r.category_name,
          mmin: num(r.minimum_margin) ?? 40,
          pmax: r.maximum_purchase == null
            ? ""
            : num(r.maximum_purchase),
          fr: r.france_only,
          enabled: r.enabled,
          updatedAt: new Date(r.updated_at).toISOString()
        })),

        stock: stock.rows.map(r => ({
          id: r.id,
          nom: r.name,
          qte: Number(r.quantity),
          prix: num(r.unit_price) ?? 0,
          updatedAt: new Date(r.updated_at).toISOString()
        })),

        deleted: deleted.rows.map(r => ({
          type: r.entity_type,
          id: r.entity_id,
          deletedAt: new Date(r.deleted_at).toISOString()
        }))
      };

    } catch (e) {
      try {
        await c.query("ROLLBACK");
      } catch {}

      throw e;

    } finally {
      c.release();
    }
  });
}
