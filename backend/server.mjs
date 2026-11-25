import Fastify from "fastify";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import pg from "pg";
import dotenv from "dotenv";
import { randomUUID } from "node:crypto";
import registerBackupRoutes from "./backup-routes.mjs";

dotenv.config({ path: "../.env.database" });

const app = Fastify({
  logger: true,
  bodyLimit: 1024 * 1024
});

await app.register(cors, { origin: true });
await app.register(helmet);

const pool = new pg.Pool({
  host: process.env.DB_HOST,
  port: Number(process.env.DB_PORT || 5432),
  database: process.env.DB_NAME,
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD
});

await registerBackupRoutes(app, pool);

const n = v =>
  v === "" || v === null || v === undefined ? null : Number(v);

const suivi = r => ({
  id: r.id,
  profileId: r.profile_id,
  itemId: r.ebay_item_id || "",
  t: r.title,
  img: r.image_url || "",
  ico: r.icon || "",
  url: r.source_url || "",
  achatPrev: n(r.purchase_estimated) ?? 0,
  reventePrev: n(r.resale_estimated) ?? 0,
  qty: Number(r.quantity || 1),
  min: n(r.resale_min),
  max: n(r.resale_max),
  occ: r.resale_is_used,
  margePrev: n(r.margin_estimated) ?? 0,
  statut: r.status,
  achat: r.purchase_actual == null ? "" : n(r.purchase_actual),
  pieces: r.parts_cost == null ? "" : n(r.parts_cost),
  vente: r.sale_actual == null ? "" : n(r.sale_actual),
  commande: r.parts_ordered || "",
  livraison: r.delivery_date
    ? String(r.delivery_date).slice(0, 10)
    : "",
  notes: r.notes || "",
  date: new Date(r.created_at).getTime(),
  dates: r.status_dates || {}
});

const alerte = r => ({
  id: r.id,
  q: r.query,
  cat: r.category,
  catName: r.category_name,
  mmin: n(r.minimum_margin) ?? 40,
  pmax: r.maximum_purchase == null ? "" : n(r.maximum_purchase),
  fr: r.france_only,
  enabled: r.enabled
});

const stock = r => ({
  id: r.id,
  nom: r.name,
  qte: Number(r.quantity),
  prix: n(r.unit_price) ?? 0
});


/* ================= HEALTH ================= */

app.get("/", async () => ({
  service: "SpyEbay API",
  version: "1.1.0",
  status: "online"
}));

app.get("/health", async (req, reply) => {
  try {
    const r = await pool.query(
      "SELECT current_database() AS database, now() AS time"
    );

    return {
      api: "ok",
      database: "ok",
      postgres: r.rows[0]
    };
  } catch (error) {
    app.log.error(error);

    reply.code(503);

    return {
      api: "ok",
      database: "error",
      message: error.message
    };
  }
});


/* ================= PROFILS ================= */

app.get("/api/profiles", async () => {
  const r = await pool.query(
    "SELECT id, name FROM profiles ORDER BY created_at"
  );

  return r.rows.map(x => ({
    id: x.id,
    nom: x.name
  }));
});

app.post("/api/profiles", async (req, reply) => {
  const nom = String(req.body?.nom || "").trim();

  if (!nom) {
    return reply.code(400).send({ error: "nom requis" });
  }

  const id = String(req.body?.id || randomUUID());

  const r = await pool.query(`
    INSERT INTO profiles(id, name)
    VALUES ($1,$2)
    ON CONFLICT(id)
    DO UPDATE SET name=EXCLUDED.name
    RETURNING id,name
  `, [id, nom]);

  return {
    id: r.rows[0].id,
    nom: r.rows[0].name
  };
});

app.delete("/api/profiles/:id", async (req, reply) => {
  const id = req.params.id;

  if (id === "moi") {
    return reply.code(400).send({
      error: "Le profil Moi ne peut pas être supprimé"
    });
  }

  const c = await pool.connect();

  try {
    await c.query("BEGIN");

    await c.query(
      "UPDATE tracked_items SET profile_id='moi' WHERE profile_id=$1",
      [id]
    );

    await c.query(
      "DELETE FROM profiles WHERE id=$1",
      [id]
    );

    await c.query("COMMIT");

    return { ok: true };
  } catch (e) {
    await c.query("ROLLBACK");
    throw e;
  } finally {
    c.release();
  }
});


/* ================= SUIVI ================= */

app.get("/api/tracked", async req => {
  const profile = req.query?.profile;

  const r = profile && profile !== "tous"
    ? await pool.query(
        "SELECT * FROM tracked_items WHERE profile_id=$1 ORDER BY created_at DESC",
        [profile]
      )
    : await pool.query(
        "SELECT * FROM tracked_items ORDER BY created_at DESC"
      );

  return r.rows.map(suivi);
});

app.post("/api/tracked", async (req, reply) => {
  const x = req.body || {};

  if (!x.id || !x.t) {
    return reply.code(400).send({
      error: "id et t sont requis"
    });
  }

  const params = [
    String(x.id),
    String(x.profileId || "moi"),
    x.itemId || null,
    String(x.t),
    x.img || null,
    x.ico || null,
    x.url || null,
    n(x.achatPrev),
    n(x.reventePrev),
    Math.max(1, Number(x.qty || 1)),
    n(x.min),
    n(x.max),
    x.occ == null ? null : Boolean(x.occ),
    n(x.margePrev),
    x.statut || "repere",
    n(x.achat),
    n(x.pieces),
    n(x.vente),
    x.commande || null,
    x.livraison || null,
    x.notes || "",
    JSON.stringify(x.dates || {})
  ];

  const r = await pool.query(`
    INSERT INTO tracked_items(
      id,
      profile_id,
      ebay_item_id,
      title,
      image_url,
      icon,
      source_url,
      purchase_estimated,
      resale_estimated,
      quantity,
      resale_min,
      resale_max,
      resale_is_used,
      margin_estimated,
      status,
      purchase_actual,
      parts_cost,
      sale_actual,
      parts_ordered,
      delivery_date,
      notes,
      status_dates
    )
    VALUES(
      $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,
      $12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22
    )
    ON CONFLICT(id) DO UPDATE SET
      profile_id=EXCLUDED.profile_id,
      ebay_item_id=EXCLUDED.ebay_item_id,
      title=EXCLUDED.title,
      image_url=EXCLUDED.image_url,
      icon=EXCLUDED.icon,
      source_url=EXCLUDED.source_url,
      purchase_estimated=EXCLUDED.purchase_estimated,
      resale_estimated=EXCLUDED.resale_estimated,
      quantity=EXCLUDED.quantity,
      resale_min=EXCLUDED.resale_min,
      resale_max=EXCLUDED.resale_max,
      resale_is_used=EXCLUDED.resale_is_used,
      margin_estimated=EXCLUDED.margin_estimated,
      status=EXCLUDED.status,
      purchase_actual=EXCLUDED.purchase_actual,
      parts_cost=EXCLUDED.parts_cost,
      sale_actual=EXCLUDED.sale_actual,
      parts_ordered=EXCLUDED.parts_ordered,
      delivery_date=EXCLUDED.delivery_date,
      notes=EXCLUDED.notes,
      status_dates=EXCLUDED.status_dates
    RETURNING *
  `, params);

  return suivi(r.rows[0]);
});

app.patch("/api/tracked/:id", async (req, reply) => {
  const map = {
    profileId: ["profile_id", v => String(v || "moi")],
    itemId: ["ebay_item_id", v => v || null],
    t: ["title", v => String(v)],
    img: ["image_url", v => v || null],
    ico: ["icon", v => v || null],
    url: ["source_url", v => v || null],
    achatPrev: ["purchase_estimated", n],
    reventePrev: ["resale_estimated", n],
    qty: ["quantity", v => Math.max(1, Number(v || 1))],
    min: ["resale_min", n],
    max: ["resale_max", n],
    occ: ["resale_is_used", v => v == null ? null : Boolean(v)],
    margePrev: ["margin_estimated", n],
    statut: ["status", String],
    achat: ["purchase_actual", n],
    pieces: ["parts_cost", n],
    vente: ["sale_actual", n],
    commande: ["parts_ordered", v => v || null],
    livraison: ["delivery_date", v => v || null],
    notes: ["notes", v => String(v || "")],
    dates: ["status_dates", v => JSON.stringify(v || {})]
  };

  const sets = [];
  const vals = [];

  for (const [k, v] of Object.entries(req.body || {})) {
    if (!map[k]) continue;

    const [col, conv] = map[k];

    vals.push(conv(v));
    sets.push(`${col}=$${vals.length}`);
  }

  if (!sets.length) {
    return reply.code(400).send({
      error: "aucun champ modifiable"
    });
  }

  vals.push(req.params.id);

  const r = await pool.query(`
    UPDATE tracked_items
    SET ${sets.join(",")}
    WHERE id=$${vals.length}
    RETURNING *
  `, vals);

  if (!r.rowCount) {
    return reply.code(404).send({
      error: "appareil introuvable"
    });
  }

  return suivi(r.rows[0]);
});

app.delete("/api/tracked/:id", async (req, reply) => {
  const r = await pool.query(
    "DELETE FROM tracked_items WHERE id=$1",
    [req.params.id]
  );

  return {
    ok: true,
    deleted: r.rowCount
  };
});


/* ================= ALERTES ================= */

app.get("/api/alerts", async () => {
  const r = await pool.query(
    "SELECT * FROM alerts ORDER BY created_at DESC"
  );

  return r.rows.map(alerte);
});

app.post("/api/alerts", async (req, reply) => {
  const x = req.body || {};

  if (!x.q && !x.cat) {
    return reply.code(400).send({
      error: "q ou cat requis"
    });
  }

  const id = String(x.id || randomUUID());

  const r = await pool.query(`
    INSERT INTO alerts(
      id,
      query,
      category,
      category_name,
      minimum_margin,
      maximum_purchase,
      france_only,
      enabled
    )
    VALUES($1,$2,$3,$4,$5,$6,$7,$8)

    ON CONFLICT(id) DO UPDATE SET
      query=EXCLUDED.query,
      category=EXCLUDED.category,
      category_name=EXCLUDED.category_name,
      minimum_margin=EXCLUDED.minimum_margin,
      maximum_purchase=EXCLUDED.maximum_purchase,
      france_only=EXCLUDED.france_only,
      enabled=EXCLUDED.enabled

    RETURNING *
  `, [
    id,
    x.q || "",
    x.cat || "",
    x.catName || "",
    n(x.mmin) ?? 40,
    n(x.pmax),
    Boolean(x.fr),
    x.enabled !== false
  ]);

  return alerte(r.rows[0]);
});

app.delete("/api/alerts/:id", async req => {
  const r = await pool.query(
    "DELETE FROM alerts WHERE id=$1",
    [req.params.id]
  );

  return {
    ok: true,
    deleted: r.rowCount
  };
});


/* ================= STOCK ================= */

app.get("/api/stock", async () => {
  const r = await pool.query(
    "SELECT * FROM stock_parts ORDER BY created_at"
  );

  return r.rows.map(stock);
});

app.post("/api/stock", async (req, reply) => {
  const x = req.body || {};
  const nom = String(x.nom || "").trim();

  if (!nom) {
    return reply.code(400).send({
      error: "nom requis"
    });
  }

  const r = await pool.query(`
    INSERT INTO stock_parts(name, quantity, unit_price)
    VALUES($1,$2,$3)
    RETURNING *
  `, [
    nom,
    Math.max(0, Number(x.qte ?? 1)),
    Math.max(0, Number(x.prix ?? 0))
  ]);

  return stock(r.rows[0]);
});

app.patch("/api/stock/:id", async (req, reply) => {
  const map = {
    nom: ["name", v => String(v)],
    qte: ["quantity", v => Math.max(0, Number(v))],
    prix: ["unit_price", v => Math.max(0, Number(v))]
  };

  const sets = [];
  const vals = [];

  for (const [k, v] of Object.entries(req.body || {})) {
    if (!map[k]) continue;

    const [col, conv] = map[k];

    vals.push(conv(v));
    sets.push(`${col}=$${vals.length}`);
  }

  if (!sets.length) {
    return reply.code(400).send({
      error: "aucun champ modifiable"
    });
  }

  vals.push(req.params.id);

  const r = await pool.query(`
    UPDATE stock_parts
    SET ${sets.join(",")}
    WHERE id=$${vals.length}
    RETURNING *
  `, vals);

  if (!r.rowCount) {
    return reply.code(404).send({
      error: "pièce introuvable"
    });
  }

  return stock(r.rows[0]);
});

app.delete("/api/stock/:id", async req => {
  const r = await pool.query(
    "DELETE FROM stock_parts WHERE id=$1",
    [req.params.id]
  );

  return {
    ok: true,
    deleted: r.rowCount
  };
});


/* ================= START ================= */

await app.listen({
  host: "0.0.0.0",
  port: 8103
});
